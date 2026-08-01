package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.TaskCancelledException;
import com.roleplay.engine.interrupt.TaskType;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.*;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import com.roleplay.engine.simulation.track.EavesdropSummarizer;
import com.roleplay.engine.simulation.track.SpatialTrackResolver;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);

    private static final long GROUP_IDLE_TIMEOUT_MS = 30_000;
    private static final long CONVERSATION_COOLDOWN_MS = 5_000;
    /** Phase 1 Track fusion: 两两距离 < 5 格 → 可对话 (requirement doc). */
    private static final double CONVERSATION_DISTANCE_THRESHOLD = 5.0;

    private final Map<ConversationMode, ConversationStrategy> strategies = new EnumMap<>(ConversationMode.class);
    private final Map<String, ConversationGroup> activeGroups = new ConcurrentHashMap<>();
    private final Map<String, Long> recentPairCooldowns = new ConcurrentHashMap<>();
    private final Map<String, TopicManager> groupTopicManagers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private SimulationWorld world;
    private LLMClient llmClient;
    /** Phase 3: optional Track Director (轨道决策). Null → legacy spatial-only path. */
    private TrackDirectorService trackDirector;
    /** Phase 3: optional supplier of World Director goals for track conflict detection. */
    private java.util.function.Supplier<Map<String, String>> goalSupplier;
    /** D1: 中断管理器（模拟停止 / 事件驱动取消）。可空（测试直构场景）。 */
    private InterruptManager interruptManager;
    /** D1: Agent 任务生命周期管理（Task ID + CancellationToken）。可空。 */
    private AgentTaskManager agentTaskManager;
    /** D1: 停止标志 —— 模拟停止后群组循环不再开始新一轮生成。 */
    private volatile boolean stopped = false;

    /** 演讲→广播监听器（演讲与广播合并地基）：PUBLIC_SPEAKING 轮次产出后回调，
     *  由 SimulationService 注册，把 AI 演讲接入 AnnouncementService 统一管线。 */
    private java.util.function.Consumer<SpeechTurn> speechBroadcastListener;

    /** 方案B 共存开关：init 注入的 AnnouncementService（可空）。executeRound 在
     *  split 模式下抑制方案A 回调，避免与 SpeechStrategy 内联广播重复推送。 */
    private AnnouncementService announcementService;

    /** 一次演讲产出（供广播管线使用：谁、说了什么、所属群组）。 */
    public record SpeechTurn(String groupId, String speaker, String text) {}

    public void setSpeechBroadcastListener(java.util.function.Consumer<SpeechTurn> listener) {
        this.speechBroadcastListener = listener;
    }

    public ConversationManager() {}

    /**
     * Phase 3 wiring: inject the Track Director. When set, group track assignments
     * are decided by TrackDirectorService (InteractionDetector score + secret-task
     * override + goal conflicts); when null, the legacy Phase 1/2 spatial-only
     * resolution is used. TrackStrategy consumes the result either way.
     */
    public void setTrackDirector(TrackDirectorService trackDirector) {
        this.trackDirector = trackDirector;
    }

    /** Phase 3 wiring: supplier for current World Director goals (agent → goal). */
    public void setGoalSupplier(java.util.function.Supplier<Map<String, String>> goalSupplier) {
        this.goalSupplier = goalSupplier;
    }

    /** D1: 注入中断管理器（由 SimulationService 组装）。 */
    public void setInterruptManager(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
    }

    /** D1: 注入任务生命周期管理器。 */
    public void setAgentTaskManager(AgentTaskManager agentTaskManager) {
        this.agentTaskManager = agentTaskManager;
    }

    /** D1: 停止全部群组生成（模拟停止时调用，配合 InterruptManager.cancelAll）。 */
    public void stopAll() {
        stopped = true;
        log.info("ConversationManager stopped ({} active groups)", activeGroups.size());
    }

    /** D1: 恢复生成（模拟重新 start 时调用）。 */
    public void resetStopped() {
        stopped = false;
    }

    public boolean isStopped() { return stopped; }

    public void init(SimulationWorld world, LLMClient llmClient,
                     java.util.function.Function<String, Agent> agentLookup,
                     java.util.function.Supplier<String> narrationSupplier) {
        init(world, llmClient, agentLookup, narrationSupplier, null, null, null);
    }

    /**
     * 方案B（分步落地）init：额外注入 AnnouncementService + HearingSystem/全量状态提供者，
     * 传给 SpeechStrategy 使其 processResults 可内联发区域广播（speech-mode=split 时）。
     * 旧四参 init 保留（方案A 路径 / 单元测试零依赖）。
     */
    public void init(SimulationWorld world, LLMClient llmClient,
                     java.util.function.Function<String, Agent> agentLookup,
                     java.util.function.Supplier<String> narrationSupplier,
                     AnnouncementService announcementService,
                     java.util.function.Supplier<HearingSystem> hearingSystem,
                     java.util.function.Supplier<Collection<AgentState>> allStates) {
        this.world = world;
        this.llmClient = llmClient;
        this.announcementService = announcementService;

        java.util.function.Function<String, String> narFn = s -> narrationSupplier.get();

        strategies.put(ConversationMode.DYAD,
                new DyadStrategy(agentLookup, narFn));
        // Phase 2 Track fusion: TrackStrategy unifies GROUP_DISCUSSION and DEBATE.
        // GroupStrategy/DebateStrategy are retained (requirement doc §13) —
        // GroupStrategy is TrackStrategy's no-track-info fallback, DebateStrategy
        // remains for reference only.
        TrackStrategy trackStrategy = new TrackStrategy(
                agentLookup, narFn, new EavesdropSummarizer(llmClient),
                this::getOrCreateTopicManager);
        strategies.put(ConversationMode.GROUP_DISCUSSION, trackStrategy);
        strategies.put(ConversationMode.DEBATE, trackStrategy);
        strategies.put(ConversationMode.PUBLIC_SPEAKING,
                new SpeechStrategy(agentLookup, narFn, getOrCreateTopicManager("speech"),
                        announcementService, hearingSystem, allStates));
    }

    public TopicManager getOrCreateTopicManager(String groupId) {
        return groupTopicManagers.computeIfAbsent(groupId, k -> new TopicManager());
    }

    private static final String PLAYER_AGENT_NAME = "me";

    public void tick(long now) {
        List<AgentState> allStates = new ArrayList<>(world.getAllStates().values());
        if (allStates.size() < 2) return;

        List<HearingSystem.HearingResult> hearing =
                world.getHearingSystem().computeAudibility(allStates);

        Map<String, AgentState> available = new LinkedHashMap<>();
        for (AgentState s : allStates) {
            // Keep "me" out of auto-initiated conversations (proximity-based)
            // Only converse when user sends a message manually
            if (s.isPlayerControlled()) continue;
            if (!isBusy(s)) available.put(s.getAgentName(), s);
        }

        // If any player-controlled agent has a pending message, start a conversation with nearest agent
        for (AgentState player : allStates) {
            if (!player.isPlayerControlled()) continue;
            if (player.getCurrentMessage() == null || player.getCurrentMessage().isEmpty()
                    || player.getCurrentMessage().startsWith("(主控")) continue;
            AgentState nearest = null;
            double minDist = Double.MAX_VALUE;
            for (AgentState a : available.values()) {
                if (a.isPlayerControlled()) continue;
                double d = player.distanceTo(a);
                if (d < minDist) { minDist = d; nearest = a; }
            }
            if (nearest != null) {
                String gid = player.getAgentName() + "+" + nearest.getAgentName();
                if (!activeGroups.containsKey(gid)) {
                    startGroup(gid, ConversationMode.DYAD, List.of(player, nearest));
                }
            }
        }

        ModeClassifier classifier = new ModeClassifier();
        List<ModeClassifier.GroupCandidate> candidates = classifier.classify(hearing, available);

        for (ModeClassifier.GroupCandidate cand : candidates) {
            String gid = cand.groupId();
            if (activeGroups.containsKey(gid)) continue;

            String pairKey = makePairKey(cand.members());
            if (recentPairCooldowns.containsKey(pairKey)
                    && now - recentPairCooldowns.get(pairKey) < CONVERSATION_COOLDOWN_MS) {
                continue;
            }

            startGroup(gid, cand.mode(), cand.members());
        }

        List<String> toRemove = new ArrayList<>();
        for (ConversationGroup group : activeGroups.values()) {
            if (!group.isActive() || group.idleMs() > GROUP_IDLE_TIMEOUT_MS) {
                toRemove.add(group.getGroupId());
            }
        }
        for (String id : toRemove) {
            dissolveGroup(id);
        }
    }

    private boolean isBusy(AgentState s) {
        if (s.isInConversation()) return true;
        for (ConversationGroup g : activeGroups.values()) {
            if (g.containsAgent(s.getAgentName()) && g.isActive()) return true;
        }
        return false;
    }

    private void startGroup(String groupId, ConversationMode mode, List<AgentState> members) {
        ConversationStrategy strat = strategies.get(mode);
        if (strat == null) { strat = strategies.get(ConversationMode.DYAD); }
        final ConversationStrategy strategy = strat;
        final ConversationMode finalMode = mode;

        ConversationGroup group = new ConversationGroup(groupId, mode, members);

        // Phase 1 Track fusion: compute spatial track assignments (MERGED/WEAK/ISOLATED)
        // at group creation and store them on the group. Phase 1 only stores —
        // Phase 2 (TrackStrategy) will read these to drive context visibility.
        try {
            Map<String, TrackAssignment> assignments;
            if (trackDirector != null) {
                // Phase 3: Track Director decides who-knows-what (score + secrets + goals).
                Map<String, String> goals = goalSupplier != null ? goalSupplier.get() : Map.of();
                assignments = trackDirector.assign(members, goals);
            } else {
                // Legacy Phase 1/2 path: pure spatial resolution (unchanged behavior).
                SpatialTrackResolver trackResolver = new SpatialTrackResolver(CONVERSATION_DISTANCE_THRESHOLD);
                assignments = trackResolver.resolve(members);
            }
            group.setTrackAssignments(assignments);
            log.info("Group {} track assignments: {}", groupId,
                    assignments.values().stream()
                            .map(a -> a.agentId() + "=" + a.type()).toList());
        } catch (Exception e) {
            log.warn("Track assignment failed for group {}, continuing without: {}", groupId, e.getMessage());
        }

        for (AgentState s : members) {
            s.setInConversation(true);
            s.setVx(0);
            s.setVy(0);
            group.freeze(s.getAgentName());
        }

        String firstSpeaker = members.get(0).getAgentName();
        group.setCurrentSpeaker(firstSpeaker);

        activeGroups.put(groupId, group);

        TopicManager tm = getOrCreateTopicManager(groupId);
        String topicDesc = mode == ConversationMode.DEBATE ? "观点分歧辩论" :
                           mode == ConversationMode.PUBLIC_SPEAKING ? "主题演讲" :
                           mode == ConversationMode.GROUP_DISCUSSION ? "多人闲聊" : "偶遇聊天";
        tm.initiateTopic(groupId, topicDesc, firstSpeaker,
                members.stream().map(AgentState::getAgentName).toList());

        log.info("Group started: {} | mode={} | members={} | topic={}", groupId, mode,
                members.stream().map(AgentState::getAgentName).toList());

        CompletableFuture.runAsync(() -> {
            while (group.isActive() && strategy.shouldContinue(group) && !stopped) {
                try {
                    executeRound(group, strategy);
                    long cooldown = finalMode == ConversationMode.GROUP_DISCUSSION ? 3000 : 2000;
                    Thread.sleep(cooldown);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Group {} round failed: {}", groupId, e.getMessage());
                    break;
                }
            }
            dissolveGroup(groupId);
        }, executor);
    }

    private void executeRound(ConversationGroup group, ConversationStrategy strategy) {
        executeRound(group, strategy, null);
    }

    /**
     * GAP-3: 可捕获上下文的轮次执行。contextCapture 非空时，每轮 prepareContext 后记录
     * 各成员本轮上下文（name → context），供剧本杀讨论验证 WEAK 隔离（A3-2）。
     */
    private void executeRound(ConversationGroup group, ConversationStrategy strategy,
                              Map<String, String> contextCapture) {
        // D1: 已停止 → 不再启动新一轮生成，直接解散
        if (stopped) {
            group.setActive(false);
            return;
        }
        Map<String, Map<String, String>> agentContexts = new ConcurrentHashMap<>();
        strategy.prepareContext(group, agentContexts);
        if (contextCapture != null) {
            contextCapture.clear();
            for (var e : agentContexts.entrySet()) {
                Map<String, String> info = e.getValue();
                if (info != null && info.get("context") != null) {
                    contextCapture.put(e.getKey(), info.get("context"));
                }
            }
        }

        Map<String, String> responses = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(agentContexts.size());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        for (var entry : agentContexts.entrySet()) {
            String name = entry.getKey();
            String context = entry.getValue().get("context");

            // Skip LLM for player-controlled agents - use their existing message
            AgentState playerState = world.getState(name);
            if (playerState != null && playerState.isPlayerControlled()) {
                String existingMsg = playerState.getCurrentMessage();
                if (existingMsg != null && !existingMsg.isEmpty() && !existingMsg.startsWith("(\u4e3b\u63a7")) {
                    responses.put(name, existingMsg);
                } else {
                    responses.put(name, "...");
                }
                latch.countDown();
                continue;
            }

            // D1: 注册中断任务（Task ID + CancellationToken），轨道上下文用命名空间隔离
            // 前缀 sim:{groupId}:{mode}，避免与 RouterService 的轨道 id 互相误伤
            com.roleplay.engine.interrupt.AgentTask interruptTask = null;
            if (agentTaskManager != null && !stopped) {
                String simTrackId = "sim:" + group.getGroupId() + ":"
                        + simTrackModeOf(group, name);
                interruptTask = agentTaskManager.createTask(name, TaskType.DIALOGUE,
                        Map.of("groupId", group.getGroupId(), "trackId", simTrackId));
                agentTaskManager.startTask(interruptTask);
            }
            final com.roleplay.engine.interrupt.AgentTask taskRef = interruptTask;
            final CancellationToken token = interruptTask != null
                    ? interruptTask.getCancelToken() : null;

            Future<?> f = executor.submit(() -> {
                try {
                    if (token != null) token.checkpoint();
                    Agent agent = world.getAgent(name);
                    String resp = agent != null
                            ? (token != null ? agent.generateWithContext(context, token)
                                             : agent.generateWithContext(context))
                            : null;
                    if (resp != null) responses.put(name, resp);
                    if (taskRef != null) agentTaskManager.completeTask(taskRef);
                } catch (TaskCancelledException e) {
                    // D1: 取消 → 本回合该成员发言作废（不入 responses），软停止保存未完成内容；
                    // 不解散群组：下轮按新轨道/新状态继续（硬停止场景由 stopped 标志接管）
                    cancelled.set(true);
                    if (taskRef != null) taskRef.saveUnfinished(e.getPartial());
                    log.info("Group {} agent {} cancelled: {}", group.getGroupId(), name, e.getReason());
                } catch (Exception e) {
                    log.warn("Agent {} generation failed: {}", name, e.getMessage());
                    if (taskRef != null) agentTaskManager.failTask(taskRef, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            if (interruptTask != null && interruptManager != null) {
                interruptManager.attachFuture(interruptTask.getId(), f);
            }
        }

        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            log.info("Group {} interrupted", group.getGroupId());
        }

        // D1: 发生取消 → 跳过 processResults（省一次摘要 LLM 调用），已取消成员的发言不提交
        if (cancelled.get()) {
            return;
        }

        strategy.processResults(group, responses, llmClient);

        // 演讲与广播合并地基：演讲模式（PUBLIC_SPEAKING）产出 → 统一广播管线。
        // 由 SimulationService 的监听器判定听众（merged=HearingSystem 声学判定 / auto=wouldOthersListen）
        // 后走 AnnouncementService.enqueueAutoSpeech（演讲=区域广播 / 无听众=按兜底配置决定全局公告）。
        // 方案B（speech-mode=split）时此处抑制——内联广播已由 SpeechStrategy.processResults
        // 直接入队，三条路径互斥不重复推送（同一实例可经 POST /api/announcements/mode 切换）。
        if (group.getMode() == ConversationMode.PUBLIC_SPEAKING
                && speechBroadcastListener != null
                && (announcementService == null
                    || !"split".equals(announcementService.getSpeechMode()))) {
            String speaker = group.getCurrentSpeaker();
            String speech = responses.get(speaker);
            if (speech != null && !speech.isBlank()) {
                try {
                    speechBroadcastListener.accept(new SpeechTurn(group.getGroupId(), speaker, speech));
                } catch (Exception e) {
                    log.warn("Speech broadcast listener failed for {}: {}", speaker, e.getMessage());
                }
            }
        }

        Map<String, Object> convEntry = new LinkedHashMap<>();
        convEntry.put("group", group.getGroupId());
        convEntry.put("mode", group.getMode().name());
        convEntry.put("tick", world.getTickCount());
        convEntry.put("round", group.getRoundCount());
        for (var entry : responses.entrySet()) {
            String val = entry.getValue();
            if (val != null && val.length() > 80) val = val.substring(0, 80);
            convEntry.put(entry.getKey(), val);
        }
        world.addConversationEntry(convEntry);

        if (!responses.isEmpty()) {
            log.info("Group {} round {} | {} responses",
                    group.getGroupId(), group.getRoundCount(), responses.size());
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  GAP-3: 剧本杀讨论（显式成员 + 显式轨道，不依赖 2D 空间听觉）
    // ═══════════════════════════════════════════════════════════

    /** 剧本杀讨论结果：完整发言记录 + 最后一轮各成员上下文（供 WEAK 隔离验证）。 */
    public record ScriptDiscussionResult(
            List<Map<String, String>> transcript,
            Map<String, String> lastContexts) {}

    /**
     * 建组阶段（同步）：创建固定成员讨论组并注册到 activeGroups。
     * 轨道分配由调用方显式给出（剧本杀：持秘密角色 WEAK / 未持 MERGED），
     * 不经过空间听觉与 ModeClassifier。
     *
     * @return 讨论组（供 {@link #runScriptDiscussionRounds} 驱动）
     */
    public ConversationGroup createScriptDiscussionGroup(
            String groupId, List<AgentState> members,
            Map<String, TrackAssignment> trackAssignments) {
        ConversationGroup group = new ConversationGroup(
                groupId, ConversationMode.GROUP_DISCUSSION, members);
        group.setTrackAssignments(trackAssignments == null ? Map.of() : trackAssignments);

        for (AgentState s : members) {
            s.setInConversation(true);
            s.setVx(0);
            s.setVy(0);
            group.freeze(s.getAgentName());
        }
        String firstSpeaker = members.get(0).getAgentName();
        group.setCurrentSpeaker(firstSpeaker);
        activeGroups.put(groupId, group);

        TopicManager tm = getOrCreateTopicManager(groupId);
        tm.initiateTopic(groupId, "剧本杀案情讨论", firstSpeaker,
                members.stream().map(AgentState::getAgentName).toList());
        log.info("Script discussion group created: {} | members={}", groupId,
                members.stream().map(AgentState::getAgentName).toList());
        return group;
    }

    /**
     * 轮次阶段（同步驱动）：复用 TrackStrategy 的 prepareContext/processResults；
     * WEAK=摘要隔离由 EavesdropSummarizer 承担（只给摘要，不给明文）。
     * 结束后解散群组并返回发言记录 + 最后一轮上下文。
     */
    public ScriptDiscussionResult runScriptDiscussionRounds(ConversationGroup group, int maxRounds) {
        if (group == null) return new ScriptDiscussionResult(List.of(), Map.of());
        ConversationStrategy strategy = strategies.get(ConversationMode.GROUP_DISCUSSION);
        if (strategy == null) strategy = strategies.get(ConversationMode.DYAD);
        if (strategy == null) {
            dissolveGroup(group.getGroupId());
            return new ScriptDiscussionResult(List.of(), Map.of());
        }

        Map<String, String> lastContexts = new LinkedHashMap<>();
        int rounds = Math.max(1, maxRounds);
        for (int r = 0; r < rounds && group.isActive() && strategy.shouldContinue(group) && !stopped; r++) {
            try {
                executeRound(group, strategy, lastContexts);
            } catch (Exception e) {
                log.warn("Script discussion group {} round {} failed: {}",
                        group.getGroupId(), r + 1, e.getMessage());
                break;
            }
        }
        List<Map<String, String>> transcript = new ArrayList<>(group.getMessageHistory());
        int roundCount = group.getRoundCount();
        dissolveGroup(group.getGroupId());
        log.info("Script discussion finished: {} | rounds={} turns={}",
                group.getGroupId(), roundCount, transcript.size());
        return new ScriptDiscussionResult(transcript, lastContexts);
    }

    private void dissolveGroup(String groupId) {
        ConversationGroup group = activeGroups.remove(groupId);
        if (group == null) return;

        for (AgentState s : group.getParticipantList()) {
            s.setInConversation(false);
            s.setVx(0);
            s.setVy(0);
        }

        String pairKey = makePairKey(group.getParticipantList());
        recentPairCooldowns.put(pairKey, System.currentTimeMillis());

        TopicManager tm = groupTopicManagers.get(groupId);
        if (tm != null) tm.closeCurrentTopic();

        log.info("Group dissolved: {} | mode={} | turns={}", groupId,
                group.getMode(), group.getTurnCount());
    }

    public Collection<ConversationGroup> getActiveGroups() {
        return activeGroups.values();
    }

    public int getActiveGroupCount() {
        return activeGroups.size();
    }

    private String makePairKey(List<AgentState> members) {
        List<String> sorted = members.stream().map(AgentState::getAgentName).sorted().toList();
        return String.join("_", sorted);
    }

    /** D1: 群组任务轨道上下文（sim:{groupId}:{mode}，与 RouterService 轨道 id 命名空间隔离）。 */
    private String simTrackModeOf(ConversationGroup group, String name) {
        TrackAssignment ta = group.getTrackAssignment(name);
        return ta != null ? ta.type().name() : group.getMode().name();
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeGroups", activeGroups.size());
        List<Map<String, Object>> groupList = new ArrayList<>();
        for (ConversationGroup g : activeGroups.values()) {
            Map<String, Object> gs = new LinkedHashMap<>();
            gs.put("id", g.getGroupId());
            gs.put("mode", g.getMode().name());
            gs.put("participants", g.getParticipantList().stream().map(AgentState::getAgentName).toList());
            gs.put("rounds", g.getRoundCount());
            gs.put("turns", g.getTurnCount());
            gs.put("idleMs", g.idleMs());
            TopicManager tm = groupTopicManagers.get(g.getGroupId());
            if (tm != null && tm.hasActiveTopic()) {
                gs.put("topic", tm.getTopicContext());
            }
            groupList.add(gs);
        }
        status.put("groups", groupList);
        return status;
    }
}
