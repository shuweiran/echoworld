package com.roleplay.engine.service.world;

import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SessionRegistry;
import com.roleplay.engine.service.StructureMapService;
import com.roleplay.engine.simulation.SimulationService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 一般模式自治世界的权威集成层。
 *
 * <p>主控只能向 {@link WorldCommandBus} 提议白名单命令；本服务复核角色状态、容量和命令
 * 前置条件后才执行。群演以轻量投影存在，不创建 Agent/长期记忆/轨道；只有晋升命令通过后
 * 才进入 {@link SimulationService}。地图在后台生成并校验，READY 后仍需显式发布。</p>
 */
@Service
public class WorldRuntimeService implements AutoCloseable {

    public static final String SIMULATION_SESSION = "simulation";

    private static final List<String> EXTRA_ARCHETYPES = List.of(
            "赶路的旅人", "卖花姑娘", "巡夜人", "送信学徒", "摊贩", "修鞋匠",
            "抱书学生", "搬运工", "遛狗老人", "咖啡店员", "街头画师", "车站乘客");
    private static final List<String> EXTRA_LINES = List.of(
            "今天这里比往常热闹。", "我只是路过，很快就走。", "刚才好像听见了什么。",
            "天色不早了。", "前面的路似乎不太好走。", "别在这里停留太久。",
            "我还得赶下一班车。", "这附近最近总有陌生人。", "生意有些冷清。", "风向变了。");

    private final InputMailbox mailbox;
    private final SessionRegistry sessions;
    private final SimulationService simulation;
    private final SSEController sse;
    private final StructureMapService structureMaps;
    private final WorldCommandPlanner planner;
    private final WorldCommandBus commandBus = new WorldCommandBus();
    private final RoleLifecycleManager roles = new RoleLifecycleManager();
    private final RoleLifecycleRuleEngine lifecycleRules;
    private final MapLifecycleService mapJobs;
    private final ExecutorService inputExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> mailboxSessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> inputInFlight = new ConcurrentHashMap<>();
    /** 会话世代令牌：关闭即移除；同 sessionId 新建得到新对象，旧在途 planner 无法串入。 */
    private final ConcurrentHashMap<String, Object> sessionTokens = new ConcurrentHashMap<>();
    /** 同一会话规划版本：旧 LLM 结果晚到时不得覆盖更新场景。 */
    private final ConcurrentHashMap<String, Long> planRevisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RouterService> sessionRouters = new ConcurrentHashMap<>();
    private final Set<String> promotionCommandsInFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> enrichmentReadyRoles = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, java.util.LinkedHashSet<String>> interactionEventIds =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ExtraProjection>> extras =
            new ConcurrentHashMap<>();
    /** 晋升后的生命周期 roleId → SimulationWorld 真实角色名，供安全降载/退场定位。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> promotedRoleNames =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ExtraProjection>> promotedProjections =
            new ConcurrentHashMap<>();
    /** 通用叙事场景人口预算；不依赖 2D 坐标或地图存在。 */
    private final ConcurrentHashMap<String, ScenePopulationProfile> populationProfiles = new ConcurrentHashMap<>();
    /** 会话级动态剧本：总目标稳定，主控只能补丁式改写未发生的阶段与下一拍。 */
    private final DynamicStoryManager stories = new DynamicStoryManager();
    private final ArrayDeque<Map<String, Object>> recentResults = new ArrayDeque<>();
    private final int targetAmbientCount;
    private final int maxAmbientCount;
    private final boolean ambientCrowdEnabled;
    private final int ambientAdjustStep;
    private final int inputMaxRetries;
    private final int ambientPromotionScore;
    private final int temporaryPromotionScore;
    private final SessionRegistry.RemovalListener sessionRemovalListener = this::removeSessionGeneration;
    private final Object worldLifecycleLock = new Object();
    private volatile boolean closed;

    @Autowired
    public WorldRuntimeService(
            InputMailbox mailbox,
            SessionRegistry sessions,
            SimulationService simulation,
            SSEController sse,
            StructureMapService structureMaps,
            WorldCommandPlanner planner,
            @Value("${roleplay.world.ambient.enabled:true}") boolean ambientCrowdEnabled,
            @Value("${roleplay.world.ambient.target-count:24}") int targetAmbientCount,
            @Value("${roleplay.world.ambient.max-count:80}") int maxAmbientCount,
            @Value("${roleplay.world.ambient.adjust-step:6}") int ambientAdjustStep,
            @Value("${roleplay.world.map-job.capacity:32}") int mapJobCapacity,
            @Value("${roleplay.world.map-job.workers:2}") int mapJobWorkers,
            @Value("${roleplay.world.map-job.ready-ttl-ms:1800000}") long mapReadyTtlMs,
            @Value("${roleplay.world.input-mailbox.max-retries:3}") int inputMaxRetries,
            @Value("${roleplay.world.lifecycle.ambient-ttl-ms:300000}") long ambientTtlMs,
            @Value("${roleplay.world.lifecycle.passive-after-ms:600000}") long passiveAfterMs,
            @Value("${roleplay.world.lifecycle.dormant-after-ms:1800000}") long dormantAfterMs,
            @Value("${roleplay.world.lifecycle.archive-after-ms:7200000}") long archiveAfterMs,
            @Value("${roleplay.world.lifecycle.ambient-promotion-interactions:2}") int ambientPromotionInteractions,
            @Value("${roleplay.world.lifecycle.temporary-promotion-interactions:6}") int temporaryPromotionInteractions) {
        this.mailbox = mailbox;
        this.sessions = sessions;
        this.simulation = simulation;
        this.sse = sse;
        this.structureMaps = structureMaps;
        this.planner = planner;
        this.ambientCrowdEnabled = ambientCrowdEnabled;
        this.maxAmbientCount = Math.max(1, maxAmbientCount);
        this.targetAmbientCount = Math.min(this.maxAmbientCount, Math.max(0, targetAmbientCount));
        this.ambientAdjustStep = Math.max(1, Math.min(this.maxAmbientCount, ambientAdjustStep));
        this.inputMaxRetries = Math.max(0, inputMaxRetries);
        this.ambientPromotionScore = ambientPromotionInteractions;
        this.temporaryPromotionScore = temporaryPromotionInteractions;
        this.lifecycleRules = new RoleLifecycleRuleEngine(new RoleLifecyclePolicy(
                Duration.ofMillis(ambientTtlMs), Duration.ofMillis(passiveAfterMs),
                Duration.ofMillis(dormantAfterMs), Duration.ofMillis(archiveAfterMs),
                ambientPromotionInteractions, temporaryPromotionInteractions));
        this.mapJobs = new MapLifecycleService(this::generateStructureMap, this::validateGeneratedMaps,
                Math.max(1, mapJobCapacity), Math.max(1, mapJobWorkers),
                Duration.ofMillis(Math.max(1_000L, mapReadyTtlMs)));
        this.sessions.addRemovalListener(sessionRemovalListener);
    }

    /** 轻量测试/嵌入入口，使用生产默认生命周期阈值。 */
    WorldRuntimeService(InputMailbox mailbox, SessionRegistry sessions, SimulationService simulation,
                        SSEController sse, StructureMapService structureMaps, WorldCommandPlanner planner,
                        boolean ambientCrowdEnabled, int targetAmbientCount, int maxAmbientCount,
                        int mapJobCapacity, int mapJobWorkers, long mapReadyTtlMs) {
        this(mailbox, sessions, simulation, sse, structureMaps, planner,
                ambientCrowdEnabled, targetAmbientCount, maxAmbientCount,
                6, mapJobCapacity, mapJobWorkers, mapReadyTtlMs, 3,
                300_000, 600_000, 1_800_000, 7_200_000, 2, 6);
    }

    public InputMailbox.OfferResult enqueueInput(InputMailbox.MailboxInput input) {
        RouterService router = bindLiveRouter(input.sessionId());
        synchronized (worldLifecycleLock) {
            requireBoundRouter(input.sessionId(), router);
            sessionTokens.computeIfAbsent(input.sessionId(), ignored -> new Object());
            InputMailbox.OfferResult result = mailbox.offer(input);
            if (result.accepted()) mailboxSessions.add(input.sessionId());
            return result;
        }
    }

    public boolean propose(WorldCommand command) {
        if (command == null) return false;
        if (mutatesSingletonWorld(command.type()) && !SIMULATION_SESSION.equals(command.sessionId())) return false;
        if (!SIMULATION_SESSION.equals(command.sessionId())) {
            try {
                RouterService router = bindLiveRouter(command.sessionId());
                synchronized (worldLifecycleLock) {
                    requireBoundRouter(command.sessionId(), router);
                    if (isRoleMutation(command.type()) && command.preconditions().isEmpty()) return false;
                    return commandBus.offer(command);
                }
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (isRoleMutation(command.type()) && command.preconditions().isEmpty()) return false;
        return commandBus.offer(command);
    }

    public RoleLifecycleSnapshot spawnExtra(String sessionId, String roleId, String name,
                                            String line, String persona, Double x, Double y) {
        String sid = required(sessionId, "sessionId");
        RouterService expectedRouter = null;
        if (!SIMULATION_SESSION.equals(sid)) {
            expectedRouter = bindLiveRouter(sid);
        }
        synchronized (worldLifecycleLock) {
            if (expectedRouter != null && sessionRouters.get(sid) != expectedRouter) {
                throw new IllegalArgumentException("session closed during world mutation");
            }
            return spawnExtraLocked(sid, roleId, name, line, persona, x, y);
        }
    }

    private RoleLifecycleSnapshot spawnExtraLocked(String sessionId, String roleId, String name,
                                                    String line, String persona, Double x, Double y) {
        String sid = required(sessionId, "sessionId");
        ConcurrentHashMap<String, ExtraProjection> sessionExtras =
                extras.computeIfAbsent(sid, ignored -> new ConcurrentHashMap<>());
        String id = roleId == null || roleId.isBlank() ? "extra_" + UUID.randomUUID() : roleId.trim();
        int hash = Math.abs(id.hashCode());
        String safeName = name == null || name.isBlank()
                ? EXTRA_ARCHETYPES.get(hash % EXTRA_ARCHETYPES.size()) + "·" + Integer.toString(hash, 36)
                : safeRoleName(name, EXTRA_ARCHETYPES.get(hash % EXTRA_ARCHETYPES.size()));
        String safeLine = boundedText(textOr(line, EXTRA_LINES.get(hash % EXTRA_LINES.size())), 160);
        double px = x == null ? 30 + (hash % 920) : clamp(x, 20, 980);
        double py = y == null ? 30 + ((hash / 97) % 520) : clamp(y, 20, 580);
        ExtraProjection projection = new ExtraProjection(id, safeName, safeLine,
                boundedText(textOr(persona, safeName + "，只在场景中短暂停留。"), 600),
                px, py, Instant.now());
        ExtraProjection previous;
        synchronized (sessionExtras) {
            previous = sessionExtras.get(id);
            if (previous == null) {
                if (sessionExtras.size() >= maxAmbientCount) {
                    throw new IllegalStateException("ambient role capacity reached");
                }
                if (roleExistsInWorld(sid, safeName)
                        || projectionNameExists(sid, safeName)) {
                    throw new IllegalStateException("role name already exists in world: " + safeName);
                }
                sessionExtras.put(id, projection);
            }
        }
        if (previous != null) return roles.get(sid, id).orElseGet(() ->
                roles.register(sid, id, RoleTier.AMBIENT, previous.createdAt()));
        RoleLifecycleSnapshot snapshot = roles.register(sid, id, RoleTier.AMBIENT, projection.createdAt());
        broadcast(sid, "world_role_spawned", projection.toAgentMap(snapshot));
        return snapshot;
    }

    /** 兼容内部调用：一次显式有效互动计 1 分。API 点击默认走 ATTENTION，不调用这里。 */
    public RoleLifecycleSnapshot interact(String sessionId, String roleId) {
        return interact(sessionId, roleId, 1);
    }

    public RoleLifecycleSnapshot interact(String sessionId, String roleId, RoleInteractionKind kind) {
        RoleInteractionKind safeKind = kind == null ? RoleInteractionKind.ATTENTION : kind;
        return interact(sessionId, roleId, safeKind.score());
    }

    private RoleLifecycleSnapshot interact(String sessionId, String roleId, int score) {
        String sid = required(sessionId, "sessionId");
        if (!SIMULATION_SESSION.equals(sid)) {
            RouterService router = bindLiveRouter(sid);
            synchronized (worldLifecycleLock) { requireBoundRouter(sid, router); }
        }
        synchronized (worldLifecycleLock) {
            return interactLocked(sid, roleId, score);
        }
    }

    public RoleLifecycleSnapshot interactOnce(String sessionId, String roleId,
                                               RoleInteractionKind kind, String eventId) {
        String sid = required(sessionId, "sessionId");
        String eid = required(eventId, "eventId");
        RouterService expected = SIMULATION_SESSION.equals(sid) ? null : bindLiveRouter(sid);
        synchronized (worldLifecycleLock) {
            if (expected != null) requireBoundRouter(sid, expected);
            java.util.LinkedHashSet<String> ids = interactionEventIds.computeIfAbsent(
                    sid, ignored -> new java.util.LinkedHashSet<>());
            synchronized (ids) {
                String key = roleId + "\u0000" + eid;
                if (!ids.add(key)) return roles.get(sid, roleId)
                        .orElseThrow(() -> new IllegalArgumentException("role not found"));
                while (ids.size() > 512) ids.remove(ids.iterator().next());
            }
            return interactLocked(sid, roleId, kind == null ? 0 : kind.score());
        }
    }

    private RoleLifecycleSnapshot interactLocked(String sid, String roleId, int score) {
        Instant now = Instant.now();
        RoleLifecycleSnapshot updated = roles.recordInteraction(
                        sid, required(roleId, "roleId"), score, now)
                .orElseThrow(() -> new IllegalArgumentException("role not found"));
        if (score > 0 && updated.tier() == RoleTier.AMBIENT
                && updated.interactionCount() >= ambientPromotionScore) {
            prepareRoleEnrichment(updated);
        } else if (score > 0) {
            lifecycleRules.evaluate(updated, now).ifPresent(commandBus::offer);
        }
        return roles.get(sid, roleId).orElse(updated);
    }

    public RoleLifecycleSnapshot interactByName(String name) {
        String safeName = required(name, "name");
        synchronizeExistingAgents();
        String roleId = promotedRoleNames.getOrDefault(SIMULATION_SESSION, new ConcurrentHashMap<>())
                .entrySet().stream().filter(entry -> entry.getValue().equals(safeName))
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("managed role not found"));
        return interact(SIMULATION_SESSION, roleId);
    }

    public MapJob submitMap(String sessionId, MapGenerationRequest request, boolean autoPublish) {
        if (autoPublish) throw new IllegalArgumentException("auto_publish is disabled; publish READY maps explicitly");
        String sid = required(sessionId, "sessionId");
        if (!SIMULATION_SESSION.equals(sid) && !sessionRouters.containsKey(sid)) {
            bindLiveRouter(sid);
        }
        MapJob job = mapJobs.submit(sid, request);
        broadcast(sessionId, "world_map_job", mapJobMap(job));
        return job;
    }

    public MapJob publishMap(String sessionId, String jobId) {
        MapJob published = mapJobs.publish(sessionId, jobId);
        broadcast(sessionId, "world_map_published", mapJobMap(published));
        return published;
    }

    public Map<String, Object> state(String sessionId) {
        String sid = required(sessionId, "sessionId");
        List<Map<String, Object>> ambientAgents = new ArrayList<>();
        ConcurrentHashMap<String, ExtraProjection> sessionExtras = extras.get(sid);
        if (sessionExtras != null) {
            for (ExtraProjection extra : sessionExtras.values()) {
                roles.get(sid, extra.roleId()).ifPresent(role -> {
                    if (role.status() != RoleLifecycleStatus.EXITED
                            && role.status() != RoleLifecycleStatus.ARCHIVED
                            && !roleIsActive(sid, extra.name())) {
                        ambientAgents.add(extra.toAgentMap(role));
                    }
                });
            }
        }
        ambientAgents.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("agentName"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", sid);
        out.put("ambient_agents", ambientAgents);
        out.put("roles", roles.snapshots(sid));
        out.put("map_jobs", mapJobs.list(sid).stream().map(WorldRuntimeService::mapJobMap).toList());
        out.put("command_queue", commandBus.size(sid));
        out.put("mailbox", mailbox.sessionMetrics(sid).orElse(null));
        ScenePopulationProfile population = populationProfiles.get(sid);
        out.put("scene_population", population == null ? null : Map.of(
                "category", population.category().name(), "scene_label", population.sceneLabel(),
                "target_count", population.targetCount(), "confidence", population.confidence(),
                "assessed_at", population.assessedAt().toString()));
        out.put("story_script", stories.snapshot(sid, population == null ? "当前场景" : population.sceneLabel()).publicMap());
        synchronized (recentResults) {
            out.put("recent_results", recentResults.stream()
                    .filter(result -> sid.equals(String.valueOf(result.get("session_id"))))
                    .toList());
        }
        return out;
    }

    @Scheduled(fixedDelayString = "${roleplay.world.input-mailbox.dispatch-ms:100}")
    public void dispatchInputs() {
        if (closed) return;
        for (String sessionId : List.copyOf(mailboxSessions)) {
            Object sessionToken = sessionTokens.get(sessionId);
            if (sessionToken == null || inputInFlight.putIfAbsent(sessionId, sessionToken) != null) continue;
            List<InputMailbox.MailboxInput> batch = mailbox.drain(sessionId, 1);
            if (batch.isEmpty()) {
                inputInFlight.remove(sessionId, sessionToken);
                if (mailbox.pendingCount(sessionId) == 0) mailboxSessions.remove(sessionId);
                continue;
            }
            InputMailbox.MailboxInput input = batch.getFirst();
            try {
                inputExecutor.submit(() -> dispatchOne(input, sessionToken));
            } catch (RejectedExecutionException e) {
                inputInFlight.remove(sessionId, sessionToken);
                requeueAfterFailure(input, sessionToken, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${roleplay.world.command.dispatch-ms:250}")
    public void dispatchCommands() {
        if (closed) return;
        synchronized (worldLifecycleLock) {
            for (String sessionId : commandBus.sessions()) {
                for (WorldCommand command : commandBus.drain(sessionId, 16)) execute(command);
            }
        }
    }

    @Scheduled(fixedDelayString = "${roleplay.world.lifecycle.scan-ms:5000}")
    public void scanLifecycle() {
        if (closed) return;
        synchronized (worldLifecycleLock) {
            synchronizeExistingAgentsLocked();
            for (String sid : unionRuntimeSessions()) {
                List<RoleLifecycleSnapshot> snapshots = roles.snapshots(sid);
                for (RoleLifecycleSnapshot role : snapshots) {
                    if (promotionCommandsInFlight.contains(roleKey(role.sessionId(), role.roleId()))) continue;
                    if (role.tier() == RoleTier.AMBIENT && role.status() == RoleLifecycleStatus.ACTIVE
                            && role.interactionCount() >= ambientPromotionScore && !role.hasPendingWork()) {
                        prepareRoleEnrichment(role);
                        continue;
                    }
                    lifecycleRules.evaluate(role, Instant.now()).ifPresent(commandBus::offer);
                }
            }
            ensureAmbientCrowd();
        }
        mapJobs.expireDueJobs();
    }

    private void dispatchOne(InputMailbox.MailboxInput input, Object sessionToken) {
        try {
            if (sessionTokens.get(input.sessionId()) != sessionToken) return;
            RouterService router = sessions.get(input.sessionId());
            if (sessionTokens.get(input.sessionId()) != sessionToken) return;
            String speaker = stringAttribute(input.attributes(), "speaker");
            String playerId = stringAttribute(input.attributes(), "player_id");
            List<String> conversationMembers = stringListAttribute(input.attributes(), "conversation_members", 12);
            RouterService.RoundResult result = conversationMembers.isEmpty()
                    ? router.runRound(input.content(), null, speaker, playerId)
                    : router.runRoundTargeted(input.content(), null, speaker, playerId, conversationMembers);
            List<String> focusedRoleIds = stringListAttribute(input.attributes(), "focused_role_ids", 12);
            String focusedRoleId = stringAttribute(input.attributes(), "focused_role_id");
            if (focusedRoleIds.isEmpty() && focusedRoleId != null && !focusedRoleId.isBlank()) {
                focusedRoleIds = List.of(focusedRoleId);
            }
            boolean successfulRound = isSuccessfulRound(result.status);
            if (successfulRound) {
                for (String roleId : focusedRoleIds) {
                    try {
                        interactOnce(input.sessionId(), roleId, RoleInteractionKind.DIALOGUE,
                                input.inputId() + ":" + roleId);
                    } catch (IllegalArgumentException ignored) {
                        /* 过期焦点不让已完成回合失败重试 */
                    }
                }
                synchronized (worldLifecycleLock) {
                    if (sessionTokens.get(input.sessionId()) == sessionToken
                            && sessionRouters.get(input.sessionId()) == router) {
                        applyStoryUpdateLocked(input.sessionId(), publicSceneContext(result, router), input.content(), null);
                    }
                }
            }
            recordResult(Map.of("kind", "input", "input_id", input.inputId(), "session_id", input.sessionId(),
                    "status", result.status, "at", Instant.now().toString()));
            broadcast(input.sessionId(), "world_input_processed",
                    Map.of("input_id", input.inputId(), "status", result.status));
            if (planner != null && successfulRound) {
                long planRevision;
                synchronized (worldLifecycleLock) {
                    if (sessionTokens.get(input.sessionId()) != sessionToken
                            || sessionRouters.get(input.sessionId()) != router) return;
                    planRevision = planRevisions.merge(input.sessionId(), 1L, Long::sum);
                }
                inputExecutor.submit(() -> {
                    int ambientCount = extras.getOrDefault(input.sessionId(), new ConcurrentHashMap<>()).size();
                    planner.setStoryContext(input.sessionId(), stories.snapshot(input.sessionId(), "当前场景").directorContext());
                    WorldCommandPlanner.PlanResult planned = planner.planDetailed(
                            input.sessionId(), input.content(), publicSceneContext(result, router), ambientCount,
                            roles.snapshots(input.sessionId()));
                    synchronized (worldLifecycleLock) {
                        if (sessionTokens.get(input.sessionId()) == sessionToken
                                && sessionRouters.get(input.sessionId()) == router
                                && Long.valueOf(planRevision).equals(planRevisions.get(input.sessionId()))) {
                            if (planned.storyPatch() != null) {
                                applyStoryUpdateLocked(input.sessionId(), publicSceneContext(result, router),
                                        input.content(), planned.storyPatch());
                            }
                            applyPopulationSuggestion(input.sessionId(), planned.population());
                            planned.commands().forEach(commandBus::offer);
                        }
                    }
                });
            }
        } catch (Exception e) {
            requeueAfterFailure(input, sessionToken, e);
        } finally {
            inputInFlight.remove(input.sessionId(), sessionToken);
            if (mailbox.pendingCount(input.sessionId()) > 0) mailboxSessions.add(input.sessionId());
        }
    }

    private void execute(WorldCommand command) {
        String status = "accepted";
        String detail = "";
        try {
            if (mutatesSingletonWorld(command.type())) requireSimulationSession(command.sessionId());
            if (isRoleMutation(command.type()) && command.preconditions().isEmpty()) {
                throw new IllegalStateException("role mutation requires state preconditions");
            }
            if (!preconditionsMatch(command)) throw new IllegalStateException("precondition failed");
            Map<String, Object> p = command.payload();
            String roleId = string(p, "roleId", string(p, "role_id", ""));
            switch (command.type()) {
                case SPAWN_EXTRA -> spawnExtraLocked(command.sessionId(), roleId,
                        string(p, "name", ""), string(p, "line", ""), string(p, "persona", ""),
                        number(p.get("x")), number(p.get("y")));
                case PROMOTE_ROLE -> promote(command.sessionId(), roleId,
                        enumValue(RoleTier.class, string(p, "targetTier", "TEMPORARY"), RoleTier.TEMPORARY));
                case SUSPEND_ROLE -> suspend(command.sessionId(), roleId,
                        enumValue(RoleLifecycleStatus.class, string(p, "targetStatus", "DORMANT"),
                                RoleLifecycleStatus.DORMANT));
                case RESUME_ROLE -> resume(command.sessionId(), roleId);
                case RETIRE_ROLE -> retire(command.sessionId(), roleId,
                        enumValue(RoleLifecycleStatus.class, string(p, "targetStatus", "ARCHIVED"),
                                RoleLifecycleStatus.ARCHIVED));
                case ASSIGN_TRACK -> {
                    String agent = string(p, "agent", roleId);
                    if (!simulation.hasAgent(agent)) throw new IllegalArgumentException("agent not found");
                    simulation.setTrackGoal(agent,
                            string(p, "goal", "加入当前相关对话，轨道偏好：" + string(p, "track", "MERGED")));
                }
                case GENERATE_MAP -> {
                    MapGenerationRequest request = new MapGenerationRequest(
                            string(p, "idempotencyKey", command.id()), p);
                    MapJob job = submitMap(command.sessionId(), request, false);
                    detail = job.jobId();
                }
                case PUBLISH_MAP -> {
                    MapJob job = publishMap(command.sessionId(), string(p, "jobId", ""));
                    detail = job.jobId();
                }
            }
        } catch (Exception e) {
            status = "rejected";
            detail = safeError(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "command");
        result.put("command_id", command.id());
        result.put("type", command.type().name());
        result.put("session_id", command.sessionId());
        result.put("status", status);
        result.put("detail", detail);
        result.put("at", Instant.now().toString());
        recordResult(result);
        broadcast(command.sessionId(), "world_command_result", result);
        if (command.type() == WorldCommandType.PROMOTE_ROLE) {
            String roleId = string(command.payload(), "roleId", string(command.payload(), "role_id", ""));
            promotionCommandsInFlight.remove(roleKey(command.sessionId(), roleId));
        }
    }

    /** 每一步都推进事实；LLM 补丁只能编排尚未发生的后续拍点。 */
    private void applyStoryUpdateLocked(String sessionId, String sceneContext, String playerStep, StoryPatch patch) {
        ScenePopulationProfile population = populationProfiles.get(sessionId);
        String scene = population == null || population.sceneLabel().isBlank() ? "当前场景" : population.sceneLabel();
        DynamicStoryState story = stories.advance(sessionId, scene, playerStep, patch);
        RouterService router = sessionRouters.get(sessionId);
        if (router != null) router.setGoals(List.of(story.totalGoal(), story.stageGoal()));
        recordResult(Map.of("kind", "story", "session_id", sessionId,
                "revision", story.revision(), "status", "updated", "at", Instant.now().toString()));
        broadcast(sessionId, "world_story_updated", story.publicMap());
    }

    private void promote(String sessionId, String roleId, RoleTier targetTier) {
        RoleLifecycleSnapshot current = roles.get(sessionId, roleId)
                .orElseThrow(() -> new IllegalArgumentException("role not found"));
        if (current.status() != RoleLifecycleStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE role can be promoted");
        }
        if (targetTier.ordinal() != current.tier().ordinal() + 1) {
            throw new IllegalStateException("role tier can only advance one level");
        }
        int requiredScore = current.tier() == RoleTier.AMBIENT
                ? ambientPromotionScore : temporaryPromotionScore;
        if (current.interactionCount() < requiredScore) {
            throw new IllegalStateException("effective interaction score is below promotion threshold");
        }
        if (current.tier() == RoleTier.AMBIENT
                && (current.hasPendingWork()
                || !enrichmentReadyRoles.contains(roleKey(sessionId, roleId)))) {
            throw new IllegalStateException("ambient role card enrichment is not ready");
        }
        ExtraProjection extra = extras.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(roleId);
        if (extra == null) {
            extra = promotedProjections.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(roleId);
        }
        if (extra == null) throw new IllegalArgumentException("role projection not found");
        boolean alreadyOwned = promotedRoleNames.getOrDefault(sessionId, new ConcurrentHashMap<>())
                .containsKey(roleId);
        boolean present = roleIsActive(sessionId, extra.name());
        if (!alreadyOwned && present) {
            throw new IllegalStateException("role name belongs to an existing agent");
        }
        if (!present) {
            if (SIMULATION_SESSION.equals(sessionId)) {
                Map<String, Object> added = simulation.addSocialAgent(extra.name(), extra.persona());
                if (!"ok".equals(added.get("status"))) {
                    throw new IllegalStateException(String.valueOf(added.get("message")));
                }
            } else {
                routerForSession(sessionId).addWorldAgent(extra.name(), new Persona(extra.name(), extra.persona()));
            }
        }
        roles.applyAccepted(sessionId, roleId, targetTier, RoleLifecycleStatus.ACTIVE);
        enrichmentReadyRoles.remove(roleKey(sessionId, roleId));
        promotedRoleNames.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                .put(roleId, extra.name());
        promotedProjections.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                .put(roleId, extra);
        ConcurrentHashMap<String, ExtraProjection> ambient = extras.get(sessionId);
        if (ambient != null) ambient.remove(roleId);
        broadcast(sessionId, "world_role_promoted",
                Map.of("role_id", roleId, "name", extra.name(), "tier", targetTier.name()));
    }

    /** LLM 补卡永远在全局生命周期锁外执行；pending 期间规则不会晋升或回收该角色。 */
    private void prepareRoleEnrichment(RoleLifecycleSnapshot role) {
        if (role.hasPendingWork()) return;
        ExtraProjection projection = extras.getOrDefault(role.sessionId(), new ConcurrentHashMap<>())
                .get(role.roleId());
        if (projection == null) return;
        roles.setPendingWork(role.sessionId(), role.roleId(), true);
        String scene = populationProfiles.containsKey(role.sessionId())
                ? populationProfiles.get(role.sessionId()).sceneLabel() : "当前场景";
        if (planner == null) {
            completeRoleEnrichment(role.sessionId(), role.roleId(), projection,
                    GeneratedRoleCard.fallback(projection.name(), projection.line(), projection.persona()));
            return;
        }
        try {
            inputExecutor.submit(() -> {
                GeneratedRoleCard card = planner.enrichRole(
                        projection.name(), projection.line(), projection.persona(), scene);
                completeRoleEnrichment(role.sessionId(), role.roleId(), projection, card);
            });
        } catch (RejectedExecutionException e) {
            completeRoleEnrichment(role.sessionId(), role.roleId(), projection,
                    GeneratedRoleCard.fallback(projection.name(), projection.line(), projection.persona()));
        }
    }

    private void completeRoleEnrichment(String sessionId, String roleId, ExtraProjection original,
                                        GeneratedRoleCard card) {
        synchronized (worldLifecycleLock) {
            RoleLifecycleSnapshot current = roles.get(sessionId, roleId).orElse(null);
            ExtraProjection stillPresent = extras.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(roleId);
            if (current == null || stillPresent == null || current.tier() != RoleTier.AMBIENT
                    || current.status() != RoleLifecycleStatus.ACTIVE || !current.hasPendingWork()) return;
            GeneratedRoleCard safeCard = card == null
                    ? GeneratedRoleCard.fallback(original.name(), original.line(), original.persona()) : card;
            ExtraProjection enriched = new ExtraProjection(original.roleId(), original.name(), original.line(),
                    safeCard.toPersona(original.name(), original.persona()), original.x(), original.y(),
                    original.createdAt());
            extras.get(sessionId).put(roleId, enriched);
            RoleLifecycleSnapshot ready = roles.setPendingWork(sessionId, roleId, false).orElse(current);
            String key = roleKey(sessionId, roleId);
            enrichmentReadyRoles.add(key);
            lifecycleRules.evaluate(ready, Instant.now()).ifPresent(command -> {
                promotionCommandsInFlight.add(key);
                if (!commandBus.offer(command)) promotionCommandsInFlight.remove(key);
            });
        }
    }

    private void suspend(String sessionId, String roleId, RoleLifecycleStatus target) {
        RoleLifecycleSnapshot current = roles.get(sessionId, roleId)
                .orElseThrow(() -> new IllegalArgumentException("role not found"));
        if (target != RoleLifecycleStatus.PASSIVE && target != RoleLifecycleStatus.DORMANT) {
            throw new IllegalArgumentException("suspend target must be PASSIVE or DORMANT");
        }
        if (current.status() == RoleLifecycleStatus.ARCHIVED || current.status() == RoleLifecycleStatus.EXITED) {
            throw new IllegalStateException("retired role cannot be suspended");
        }
        if (target == RoleLifecycleStatus.PASSIVE && current.status() != RoleLifecycleStatus.ACTIVE) {
            throw new IllegalStateException("PASSIVE requires ACTIVE source");
        }
        if (target == RoleLifecycleStatus.DORMANT
                && current.status() != RoleLifecycleStatus.ACTIVE
                && current.status() != RoleLifecycleStatus.PASSIVE) {
            throw new IllegalStateException("DORMANT requires ACTIVE or PASSIVE source");
        }
        String name = promotedRoleNames.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(roleId);
        if (!SIMULATION_SESSION.equals(sessionId) && name != null && roleExistsInWorld(sessionId, name)) {
            routerForSession(sessionId).suspendWorldAgent(name);
        } else if (target == RoleLifecycleStatus.DORMANT && name != null && simulation.hasAgent(name)) {
            Map<String, Object> suspended = simulation.suspendSocialAgent(name);
            if (!"ok".equals(suspended.get("status"))) {
                throw new IllegalStateException(String.valueOf(suspended.get("message")));
            }
        } else if (target == RoleLifecycleStatus.PASSIVE && name != null && simulation.hasAgent(name)) {
            simulation.setAgentPassive(name, true);
            simulation.setTrackGoal(name, "保持背景活动，除非玩家接近、点名或剧情需要，不主动加入对话");
        }
        if (SIMULATION_SESSION.equals(sessionId) && target == RoleLifecycleStatus.DORMANT && name != null) {
            simulation.setAgentPassive(name, false);
        }
        roles.applyAccepted(sessionId, roleId, null, target);
        broadcast(sessionId, "world_role_suspended",
                Map.of("role_id", roleId, "tier", current.tier().name(), "status", target.name()));
    }

    private void resume(String sessionId, String roleId) {
        RoleLifecycleSnapshot current = roles.get(sessionId, roleId)
                .orElseThrow(() -> new IllegalArgumentException("role not found"));
        if (current.status() != RoleLifecycleStatus.PASSIVE
                && current.status() != RoleLifecycleStatus.DORMANT
                && current.status() != RoleLifecycleStatus.ARCHIVED) {
            throw new IllegalStateException("only inactive role can resume");
        }
        ExtraProjection projection = promotedProjections
                .getOrDefault(sessionId, new ConcurrentHashMap<>()).get(roleId);
        String name = promotedRoleNames.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(roleId);
        if (name == null) throw new IllegalStateException("role has no managed agent ownership");
        if (!SIMULATION_SESSION.equals(sessionId)) {
            RouterService router = routerForSession(sessionId);
            if (router.hasAgent(name)) {
                throw new IllegalStateException("managed role is already active");
            }
            if (!router.resumeWorldAgent(name)) {
                throw new IllegalStateException("managed suspended agent snapshot not found");
            }
        } else if (simulation.isSuspendedAgent(name)) {
            Map<String, Object> added = simulation.resumeSocialAgent(name);
            if (!"ok".equals(added.get("status"))) {
                throw new IllegalStateException(String.valueOf(added.get("message")));
            }
        } else if (current.status() != RoleLifecycleStatus.PASSIVE) {
            // DORMANT/ARCHIVED 必须从保留槽恢复；同名活动 Agent 不能被冒认为本角色。
            throw new IllegalStateException("managed suspended agent snapshot not found");
        }
        if (SIMULATION_SESSION.equals(sessionId)) simulation.setAgentPassive(name, false);
        roles.applyAccepted(sessionId, roleId, current.tier(), RoleLifecycleStatus.ACTIVE);
        broadcast(sessionId, "world_role_resumed", Map.of("role_id", roleId));
    }

    private void retire(String sessionId, String roleId, RoleLifecycleStatus target) {
        RoleLifecycleSnapshot current = roles.get(sessionId, roleId)
                .orElseThrow(() -> new IllegalArgumentException("role not found"));
        if (target != RoleLifecycleStatus.ARCHIVED && target != RoleLifecycleStatus.EXITED) {
            throw new IllegalArgumentException("retire target must be ARCHIVED or EXITED");
        }
        if (current.status() == RoleLifecycleStatus.ARCHIVED || current.status() == RoleLifecycleStatus.EXITED) {
            throw new IllegalStateException("role is already retired");
        }
        if (current.tier() == RoleTier.CORE) {
            throw new IllegalStateException("core role cannot be archived or exited");
        }
        if (target == RoleLifecycleStatus.ARCHIVED
                && !promotedProjections.getOrDefault(sessionId, new ConcurrentHashMap<>()).containsKey(roleId)) {
            throw new IllegalStateException("only a promoted role can be archived and restored");
        }
        ConcurrentHashMap<String, String> promoted = promotedRoleNames.get(sessionId);
        String promotedName = promoted == null ? null : promoted.get(roleId);
        if (promotedName != null) {
            if (!SIMULATION_SESSION.equals(sessionId)) {
                if (target == RoleLifecycleStatus.ARCHIVED) {
                    routerForSession(sessionId).suspendWorldAgent(promotedName);
                } else {
                    routerForSession(sessionId).removeWorldAgent(promotedName);
                }
            } else {
                simulation.setAgentPassive(promotedName, false);
            }
            Map<String, Object> removed = null;
            if (SIMULATION_SESSION.equals(sessionId)
                    && target == RoleLifecycleStatus.ARCHIVED && simulation.hasAgent(promotedName)) {
                removed = simulation.suspendSocialAgent(promotedName);
            } else if (SIMULATION_SESSION.equals(sessionId) && target == RoleLifecycleStatus.EXITED
                    && (simulation.hasAgent(promotedName) || simulation.isSuspendedAgent(promotedName))) {
                removed = simulation.removeSocialAgent(promotedName);
            }
            if (removed != null && !"ok".equals(removed.get("status"))) {
                throw new IllegalStateException(String.valueOf(removed.get("message")));
            }
        }
        roles.applyAccepted(sessionId, roleId, null, target);
        ConcurrentHashMap<String, ExtraProjection> sessionExtras = extras.get(sessionId);
        if (sessionExtras != null) sessionExtras.remove(roleId);
        if (target == RoleLifecycleStatus.EXITED) {
            if (promoted != null) promoted.remove(roleId);
            ConcurrentHashMap<String, ExtraProjection> projections = promotedProjections.get(sessionId);
            if (projections != null) projections.remove(roleId);
        }
        broadcast(sessionId, "world_role_retired",
                Map.of("role_id", roleId, "status", target.name()));
        if (target == RoleLifecycleStatus.EXITED) roles.remove(sessionId, roleId);
    }

    private boolean preconditionsMatch(WorldCommand command) {
        for (WorldPrecondition precondition : command.preconditions()) {
            String field = precondition.field();
            if (field == null || !"EQ".equalsIgnoreCase(precondition.operator())) return false;
            String prefix = "role.";
            if (!field.startsWith(prefix)) return false;
            int suffixAt = field.lastIndexOf('.');
            if (suffixAt <= prefix.length()) return false;
            String roleId = field.substring(prefix.length(), suffixAt);
            RoleLifecycleSnapshot role = roles.get(command.sessionId(), roleId).orElse(null);
            if (role == null) return false;
            String actual = switch (field.substring(suffixAt + 1)) {
                case "tier" -> role.tier().name();
                case "lifecycleStatus" -> role.status().name();
                default -> null;
            };
            if (actual == null || !actual.equalsIgnoreCase(String.valueOf(precondition.expected()))) return false;
        }
        return true;
    }

    private Map<String, Object> generateStructureMap(String sessionId, MapGenerationRequest request) {
        Map<String, Object> p = request.attributes();
        return structureMaps.generate(new StructureMapService.GenerateRequest(
                string(p, "theme", "动态区域"), string(p, "kind", "custom"),
                longNumber(p.get("seed"), System.currentTimeMillis()),
                intNumber(p.get("width"), 64), intNumber(p.get("height"), 40),
                string(p, "map_mode", "single"), string(p, "style", ""),
                stringList(p.get("locations")), stringList(p.get("clue_locations")), false));
    }

    private MapValidationResult validateGeneratedMaps(Map<String, Object> generated) {
        Object rawMaps = generated.get("maps");
        if (!(rawMaps instanceof Map<?, ?> maps) || maps.isEmpty()) {
            return MapValidationResult.invalid("generated result contains no maps");
        }
        for (Object value : maps.values()) {
            if (!(value instanceof Map<?, ?> raw)) return MapValidationResult.invalid("map entry is not an object");
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((k, v) -> map.put(String.valueOf(k), v));
            com.roleplay.engine.simulation.map.MapValidator.Result result =
                    com.roleplay.engine.simulation.map.MapValidator.validateMap(map);
            if (!result.ok()) return MapValidationResult.invalid(String.join("; ", result.errors()));
        }
        return MapValidationResult.success();
    }

    private void ensureAmbientCrowd() {
        if (!ambientCrowdEnabled) return;
        Set<String> sessionsToAdjust = new java.util.HashSet<>(populationProfiles.keySet());
        Object running = simulation.getState().get("running");
        if (Boolean.TRUE.equals(running)) sessionsToAdjust.add(SIMULATION_SESSION);
        for (String sessionId : sessionsToAdjust) {
            ScenePopulationProfile profile = populationProfiles.get(sessionId);
            int target = profile == null ? targetAmbientCount : profile.targetCount();
            reconcileAmbientPopulation(sessionId, target);
        }
    }

    private void reconcileAmbientPopulation(String sessionId, int requestedTarget) {
        int target = Math.max(0, Math.min(maxAmbientCount, requestedTarget));
        ConcurrentHashMap<String, ExtraProjection> crowd =
                extras.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>());
        int current = crowd.size();
        if (current < target) {
            int additions = Math.min(ambientAdjustStep, target - current);
            for (int i = 0; i < additions; i++) {
                String id = "crowd_" + UUID.randomUUID();
                try {
                    spawnExtraLocked(sessionId, id, null, null, null, null, null);
                } catch (IllegalArgumentException ignored) {
                    break; // 会话在异步判断后已关闭。
                }
            }
        } else if (current > target) {
            List<ExtraProjection> oldest = crowd.values().stream()
                    .filter(extra -> roles.get(sessionId, extra.roleId())
                            .map(role -> role.interactionCount() == 0 && !role.hasPendingWork())
                            .orElse(false))
                    .sorted(java.util.Comparator.comparing(extra -> roles.get(sessionId, extra.roleId())
                            .map(RoleLifecycleSnapshot::lastInteractionAt).orElse(extra.createdAt())))
                    .limit(Math.min(ambientAdjustStep, current - target)).toList();
            for (ExtraProjection extra : oldest) {
                try { retire(sessionId, extra.roleId(), RoleLifecycleStatus.EXITED); }
                catch (IllegalArgumentException | IllegalStateException ignored) { /* 下轮重新核对 */ }
            }
        }
    }

    private void applyPopulationSuggestion(String sessionId,
                                           WorldCommandPlanner.ScenePopulationSuggestion suggestion) {
        if (suggestion == null) return;
        int fallback = populationProfiles.containsKey(sessionId)
                ? populationProfiles.get(sessionId).targetCount() : targetAmbientCount;
        ScenePopulationProfile profile = ScenePopulationProfile.validated(
                suggestion.category(), suggestion.sceneLabel(), suggestion.suggestedTarget(),
                suggestion.confidence(), suggestion.reason(), fallback, maxAmbientCount, Instant.now());
        populationProfiles.put(sessionId, profile);
        broadcast(sessionId, "world_scene_population", Map.of(
                "category", profile.category().name(), "scene_label", profile.sceneLabel(),
                "target_count", profile.targetCount(), "confidence", profile.confidence()));
    }

    /** 把旧 load-characters/动态加入产生的真实 Agent 纳入 CORE 生命周期保护。 */
    public void synchronizeExistingAgents() {
        synchronized (worldLifecycleLock) {
            synchronizeExistingAgentsLocked();
        }
    }

    private void synchronizeExistingAgentsLocked() {
        Map<String, Object> simulationState = simulation.getState();
        if (simulationState == null) return;
        Object rawAgents = simulationState.get("agents");
        if (!(rawAgents instanceof List<?> agents)) return;
        ConcurrentHashMap<String, String> managed = promotedRoleNames
                .computeIfAbsent(SIMULATION_SESSION, ignored -> new ConcurrentHashMap<>());
        for (Object raw : agents) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            Object rawName = map.get("agentName");
            String name = rawName == null ? "" : String.valueOf(rawName).trim();
            if (name.isBlank() || Boolean.TRUE.equals(map.get("playerControlled")) || managed.containsValue(name)) continue;
            String roleId = "core_" + UUID.nameUUIDFromBytes(
                    name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            managed.putIfAbsent(roleId, name);
            roles.register(SIMULATION_SESSION, roleId, RoleTier.CORE, Instant.now());
        }
        // 活跃对话视为一次触发，刷新 CORE 的闲置计时；CORE 不参与互动晋升。
        for (Object raw : agents) {
            if (!(raw instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("inConversation"))) continue;
            String name = String.valueOf(map.get("agentName"));
            managed.entrySet().stream().filter(e -> e.getValue().equals(name)).findFirst()
                    .ifPresent(e -> roles.recordInteraction(SIMULATION_SESSION, e.getKey(), Instant.now()));
        }
    }

    /** 旧 simulation agent API 的名称所有权门：活动/休眠的生命周期角色均为保留名。 */
    public boolean isManagedAgentName(String name) {
        if (name == null || name.isBlank()) return false;
        synchronizeExistingAgents();
        return promotedRoleNames.values().stream().anyMatch(names -> names.containsValue(name));
    }

    /** 用户显式重载/重置单例 2D 世界时同步清空旧世界的生命周期所有权。 */
    public void resetSimulationMetadata() {
        synchronized (worldLifecycleLock) {
            resetSimulationMetadataLocked();
        }
    }

    /** clear/init 与扫描共用同一互斥门，扫描永远看不到半替换世界。 */
    public void replaceSimulationWorld(Runnable replacement) {
        if (replacement == null) throw new IllegalArgumentException("replacement required");
        synchronized (worldLifecycleLock) {
            resetSimulationMetadataLocked();
            replacement.run();
            synchronizeExistingAgentsLocked();
        }
    }

    private void resetSimulationMetadataLocked() {
        commandBus.clearSession(SIMULATION_SESSION);
        roles.removeSession(SIMULATION_SESSION);
        extras.remove(SIMULATION_SESSION);
        promotedRoleNames.remove(SIMULATION_SESSION);
        promotedProjections.remove(SIMULATION_SESSION);
        promotionCommandsInFlight.removeIf(key -> key.startsWith(SIMULATION_SESSION + "\u0000"));
        enrichmentReadyRoles.removeIf(key -> key.startsWith(SIMULATION_SESSION + "\u0000"));
        populationProfiles.remove(SIMULATION_SESSION);
        stories.remove(SIMULATION_SESSION);
        mapJobs.removeSession(SIMULATION_SESSION);
    }

    private Set<String> unionRuntimeSessions() {
        Set<String> result = new java.util.HashSet<>(commandBus.sessions());
        result.addAll(extras.keySet());
        result.addAll(mailboxSessions);
        result.add(SIMULATION_SESSION);
        return result;
    }

    private void broadcast(String sessionId, String eventType, Map<String, Object> data) {
        if (sse == null) return;
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("session_id", sessionId);
        sse.broadcastToSession(sessionId, eventType, payload);
    }

    private void recordResult(Map<String, Object> result) {
        synchronized (recentResults) {
            recentResults.addLast(Map.copyOf(result));
            while (recentResults.size() > 100) recentResults.removeFirst();
        }
    }

    private void requeueAfterFailure(InputMailbox.MailboxInput input, Object sessionToken, Exception error) {
        if (sessionTokens.get(input.sessionId()) != sessionToken) return;
        int retries = intNumber(input.attributes().get("_world_retry"), 0);
        if (retries < inputMaxRetries) {
            Map<String, Object> attributes = new LinkedHashMap<>(input.attributes());
            attributes.put("_world_retry", retries + 1);
            InputMailbox.MailboxInput retry = new InputMailbox.MailboxInput(
                    input.sessionId(), input.inputId(), input.content(), input.priority(),
                    input.createdAt(), attributes);
            if (mailbox.requeue(retry)) {
                mailboxSessions.add(input.sessionId());
                broadcast(input.sessionId(), "world_input_retrying",
                        Map.of("input_id", input.inputId(), "attempt", retries + 1, "error", safeError(error)));
                return;
            }
        }
        recordResult(Map.of("kind", "input", "input_id", input.inputId(), "session_id", input.sessionId(),
                "status", "failed", "error", safeError(error), "at", Instant.now().toString()));
        broadcast(input.sessionId(), "world_input_failed",
                Map.of("input_id", input.inputId(), "error", safeError(error)));
    }

    /** SessionRegistry 的 close/TTL/容量淘汰统一回调。simulation 是共享世界，不由普通会话关闭。 */
    public void removeSession(String sessionId) {
        removeSessionGeneration(sessionId, null);
    }

    /** 仅清理被摘除的 Router 代际；同 ID 已重建时，迟到回调不得清空新会话。 */
    void removeSessionGeneration(String sessionId, RouterService removedRouter) {
        if (sessionId == null || sessionId.isBlank() || SIMULATION_SESSION.equals(sessionId)) return;
        synchronized (worldLifecycleLock) {
            if (removedRouter != null && sessionRouters.get(sessionId) != removedRouter) return;
            sessionTokens.remove(sessionId);
            planRevisions.remove(sessionId);
            sessionRouters.remove(sessionId);
            interactionEventIds.remove(sessionId);
            mailbox.removeSession(sessionId);
            mailboxSessions.remove(sessionId);
            inputInFlight.remove(sessionId);
            commandBus.clearSession(sessionId);
            roles.removeSession(sessionId);
            extras.remove(sessionId);
            promotedRoleNames.remove(sessionId);
            promotedProjections.remove(sessionId);
            promotionCommandsInFlight.removeIf(key -> key.startsWith(sessionId + "\u0000"));
            enrichmentReadyRoles.removeIf(key -> key.startsWith(sessionId + "\u0000"));
            populationProfiles.remove(sessionId);
            stories.remove(sessionId);
            mapJobs.removeSession(sessionId);
            if (planner != null) planner.removeSession(sessionId);
            synchronized (recentResults) {
                recentResults.removeIf(result -> sessionId.equals(String.valueOf(result.get("session_id"))));
            }
        }
    }

    private boolean projectionNameExists(String sessionId, String name) {
        return extras.getOrDefault(sessionId, new ConcurrentHashMap<>()).values().stream()
                .anyMatch(extra -> extra.name().equals(name))
                || promotedRoleNames.getOrDefault(sessionId, new ConcurrentHashMap<>()).values().stream()
                .anyMatch(name::equals);
    }

    private static String roleKey(String sessionId, String roleId) {
        return sessionId + "\u0000" + roleId;
    }

    private boolean roleExistsInWorld(String sessionId, String name) {
        if (SIMULATION_SESSION.equals(sessionId)) {
            return simulation.hasAgent(name) || simulation.isSuspendedAgent(name);
        }
        RouterService router = routerForSession(sessionId);
        return router.hasAgent(name) || router.isWorldSuspendedAgent(name);
    }

    private boolean roleIsActive(String sessionId, String name) {
        if (SIMULATION_SESSION.equals(sessionId)) return simulation.hasAgent(name);
        RouterService router = routerForSession(sessionId);
        return router.hasAgent(name);
    }

    private RouterService routerForSession(String sessionId) {
        RouterService router = sessionRouters.get(sessionId);
        if (router == null) throw new IllegalArgumentException("session not found: " + sessionId);
        return router;
    }

    private RouterService bindLiveRouter(String sessionId) {
        String sid = required(sessionId, "sessionId");
        RouterService first = sessions.get(sid);
        if (first == null) throw new IllegalArgumentException("session not found: " + sid);
        synchronized (worldLifecycleLock) {
            RouterService previous = sessionRouters.get(sid);
            if (previous != null && previous != first) {
                // 同 ID 新代首次触达时先清掉旧代邮箱/角色/命令，再绑定新 Router。
                removeSessionGeneration(sid, previous);
            }
            sessionRouters.put(sid, first);
            try {
                RouterService second = sessions.get(sid);
                if (second != first) throw new IllegalArgumentException("session changed during world mutation");
            } catch (RuntimeException e) {
                sessionRouters.remove(sid, first);
                throw e;
            }
        }
        return first;
    }

    private void requireBoundRouter(String sessionId, RouterService expected) {
        if (sessionRouters.get(sessionId) != expected) {
            throw new IllegalArgumentException("session closed during world mutation");
        }
    }

    private static boolean isRoleMutation(WorldCommandType type) {
        return type == WorldCommandType.PROMOTE_ROLE || type == WorldCommandType.SUSPEND_ROLE
                || type == WorldCommandType.RESUME_ROLE || type == WorldCommandType.RETIRE_ROLE;
    }

    private static boolean mutatesSingletonWorld(WorldCommandType type) {
        return type == WorldCommandType.ASSIGN_TRACK;
    }

    private static void requireSimulationSession(String sessionId) {
        if (!SIMULATION_SESSION.equals(required(sessionId, "sessionId"))) {
            throw new IllegalArgumentException("singleton 2D world only accepts session_id=simulation");
        }
    }

    private static Map<String, Object> mapJobMap(MapJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("job_id", job.jobId());
        map.put("status", job.status().name());
        map.put("publishable", job.publishable());
        map.put("error", job.error() == null ? "" : job.error());
        return map;
    }

    @Override
    @PreDestroy
    public void close() {
        if (closed) return;
        closed = true;
        sessions.removeRemovalListener(sessionRemovalListener);
        sessionTokens.clear();
        planRevisions.clear();
        sessionRouters.clear();
        interactionEventIds.clear();
        mapJobs.close();
        inputExecutor.shutdownNow();
    }

    public record ExtraProjection(String roleId, String name, String line, String persona,
                                  double x, double y, Instant createdAt) {
        Map<String, Object> toAgentMap(RoleLifecycleSnapshot lifecycle) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("roleId", roleId);
            map.put("agentName", name);
            map.put("x", x);
            map.put("y", y);
            map.put("emotion", "NEUTRAL");
            map.put("emotionEmoji", "·");
            map.put("currentMessage", line);
            map.put("inConversation", false);
            map.put("playerControlled", false);
            map.put("ambient", true);
            map.put("roleTier", lifecycle.tier().name());
            map.put("lifecycleStatus", lifecycle.status().name());
            return map;
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String boundedText(String value, int maxLength) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private static String safeRoleName(String value, String fallback) {
        String clean = boundedText(value, 40).replaceAll("[^\\p{L}\\p{N}· _-]", "").trim();
        return clean.isBlank() ? fallback : clean;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static String stringAttribute(Map<String, Object> map, String key) {
        return string(map, key, null);
    }

    private static List<String> stringListAttribute(Map<String, Object> map, String key, int maxItems) {
        Object value = map == null ? null : map.get(key);
        if (!(value instanceof Collection<?> items)) return List.of();
        return items.stream().filter(Objects::nonNull).map(String::valueOf).map(String::trim)
                .filter(item -> !item.isEmpty()).distinct().limit(Math.max(1, maxItems)).toList();
    }

    /** 只给人口判断器公开叙事摘要，不传角色私密输出、链式分析或隐藏记忆。 */
    private static String publicSceneContext(RouterService.RoundResult result, RouterService router) {
        Map<String, Object> integration = result == null || result.integration == null
                ? Map.of() : result.integration;
        String narration = string(integration, "narration", "");
        String progress = string(integration, "scene_progress", "");
        String declaredScene = router == null ? "" : string(router.getState(), "scene", "");
        String joined = (declaredScene + " " + narration + " " + progress)
                .replaceAll("[\\r\\n]+", " ").trim();
        return joined.length() <= 600 ? joined : joined.substring(0, 600);
    }

    private static Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? null : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int intNumber(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static long longNumber(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static boolean isSuccessfulRound(String status) {
        if (status == null || status.isBlank()) return false;
        String lower = status.toLowerCase(Locale.ROOT);
        return !lower.startsWith("error") && !lower.contains("failed") && !lower.contains("失败");
    }
}
