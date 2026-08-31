package com.roleplay.engine.simulation;

import jakarta.annotation.PreDestroy;
import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.broadcast.BroadcastMessage;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.conversation.ConversationGroup;
import com.roleplay.engine.simulation.conversation.ModeClassifier;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import com.roleplay.engine.simulation.director.WorldDirectorService;
import com.roleplay.engine.simulation.movement.MovementConstraint;
import com.roleplay.engine.simulation.movement.MovementTarget;
import com.roleplay.engine.simulation.schedule.SchedulerService;
import com.roleplay.engine.simulation.social.SocialState;
import com.roleplay.engine.simulation.track.InteractionDetector;
import com.roleplay.engine.simulation.track.TrackAssignment;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private static final long MANUAL_TARGET_HOLD_MS = 60_000;
    private static final String PLAYER_AGENT_NAME = "me";

    /** P-0813-F：导演（主控）LLM 轮次间隔 ms（roleplay.pacing.director-interval-ms，默认 15000）。
     *  由 AppConfig 注入；直构（测试）时用 new AppConfig() 默认值。 */
    private final long directorIntervalMs;
    /** P-0813-F：2D 角色移动速度基准 px/s（roleplay.pacing.move-speed-base，默认 45）。 */
    private final double moveSpeedBase;
    /** P-0813-F：移动速度随机幅度（roleplay.pacing.move-speed-random-range，默认 35）。
     *  实际速度 = base + rand×range（原硬编码 50 + rand×60）。 */
    private final double moveSpeedRandomRange;
    /** P-0814-I：玩家角色移动速度基准 px/s（roleplay.pacing.move-speed-player-base，默认 90）——
     *  玩家角色（playerControlled）提速，AI 保持 moveSpeedBase 45-80；配置见 AppConfig.PacingConfig。 */
    private final double moveSpeedPlayerBase;
    /** P-0814-I：玩家角色速度随机幅度（默认 15 → 90-105 px/s）。 */
    private final double moveSpeedPlayerRandomRange;

    /** P-0813-I：主控日程调度服务（混合架构——「时刻→地点→行为」骨架）。
     *  直构（测试）时为 null → 日程全链路禁用（零行为变化）；Spring 注入真实 bean。 */
    private final SchedulerService schedulerService;

    private final SimulationWorld world;
    private final LLMClient llmClient;
    private final DatabaseService databaseService;
    /** D1: 中断管理器 —— 模拟停止时硬停止进行中的生成任务。 */
    private final InterruptManager interruptManager;
    /** D1: 任务生命周期管理器 —— 注入 ConversationManager。 */
    private final AgentTaskManager agentTaskManager;
    /** D1: 世界事件总线 —— 轨道变化事件（TrackDirector → 取消旧轨道生成）。 */
    private final WorldEventBus eventBus;
    /** One stateful conversation runtime per SimulationService/world instance. */
    private final ConversationManager conversationManager;
    /** Phase 3 dual-director architecture: World Director (角色想做什么). */
    private final WorldDirectorService worldDirector;
    /** Phase 3 dual-director architecture: Track Director (谁知道什么). */
    private final TrackDirectorService trackDirector = new TrackDirectorService();
    private final SocialState socialState = new SocialState();
    /** Phase 3 outer orchestrator (需求文档第十四条: Router → Orchestrator → Track/World). */
    private SimulationOrchestrator orchestrator;
    /** Phase 4: 轨道 → 运动约束（纯规则，零 LLM）。 */
    private final MovementConstraint movementConstraint;
    /** Phase 4: 最近一次 orchestrator.tick 的轨道分配，供移动 tick 前的约束层使用。 */
    private volatile Map<String, TrackAssignment> lastTrackAssignments = Map.of();
    /** 演讲与广播合并地基：统一公告管线（AI 演讲产出 → 自动选形态 → 优先级队列 → SSE）。 */
    private final AnnouncementService announcementService;
    /** P-0802-P2（改造方案 Phase 2）：玩家身份解析器 —— player_id → 当前绑定角色名（2D playerControlled 判定解析式）。 */
    private final PlayerIdentityService identityService;
    private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile long lastDirectorTime = 0;
    /** 普通导演轮的自主世界事件冷却；用户指令/硬系统事件可绕过。 */
    private volatile long lastDirectorEventTime = 0;
    private static final long DIRECTOR_EVENT_COOLDOWN_MS = 20_000;
    private final Set<String> pendingUserMessages = ConcurrentHashMap.newKeySet();
    /** 生命周期休眠槽：保留原 Agent/AgentState 引用，不丢记忆、位置、目标或社会关系。 */
    private final ConcurrentHashMap<String, SuspendedAgent> suspendedAgents = new ConcurrentHashMap<>();
    private volatile int lastSaveTick = 0;
    private static final int SNAPSHOT_INTERVAL = 50; // save snapshot every 50 ticks

    public SimulationService(SimulationWorld world, LLMClient llmClient, DatabaseService databaseService,
                             InterruptManager interruptManager, AgentTaskManager agentTaskManager,
                             WorldEventBus eventBus, AnnouncementService announcementService,
                             PlayerIdentityService playerIdentityService) {
        this(world, llmClient, databaseService, interruptManager, agentTaskManager,
                eventBus, announcementService, playerIdentityService, null, null);
    }

    /**
     * P-0813-F：节奏控制注入版构造。appConfig 为 null（直构回退）时
     * 用 {@code new AppConfig()} 默认值（roleplay.pacing.* 默认：导演间隔 15000、
     * 移动速度 45+rand×35、对话冷却 8000、轮次间隔 2000/3000、idle×1.5、inactive×2.0）。
     * 注：无 @Autowired（Spring 使用下方 10 参日程注入版）；本构造供直构/测试委托。
     */
    public SimulationService(SimulationWorld world, LLMClient llmClient, DatabaseService databaseService,
                             InterruptManager interruptManager, AgentTaskManager agentTaskManager,
                             WorldEventBus eventBus, AnnouncementService announcementService,
                             PlayerIdentityService playerIdentityService,
                             com.roleplay.engine.config.AppConfig appConfig) {
        this(world, llmClient, databaseService, interruptManager, agentTaskManager,
                eventBus, announcementService, playerIdentityService, appConfig, null);
    }

    /**
     * P-0813-I：日程注入版构造（Spring 使用 10 参）。schedulerService 为 null（直构回退）
     * → 日程全链路禁用（既有测试零变化）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SimulationService(SimulationWorld world, LLMClient llmClient, DatabaseService databaseService,
                             InterruptManager interruptManager, AgentTaskManager agentTaskManager,
                             WorldEventBus eventBus, AnnouncementService announcementService,
                             PlayerIdentityService playerIdentityService,
                             com.roleplay.engine.config.AppConfig appConfig,
                             SchedulerService schedulerService) {
        this.world = world;
        this.movementConstraint = new MovementConstraint(world);
        this.llmClient = llmClient;
        this.databaseService = databaseService;
        this.interruptManager = interruptManager;
        this.agentTaskManager = agentTaskManager;
        this.eventBus = eventBus;
        this.announcementService = announcementService;
        this.identityService = playerIdentityService;
        this.schedulerService = schedulerService;
        this.conversationManager = new ConversationManager();
        com.roleplay.engine.config.AppConfig cfg = appConfig != null ? appConfig : new com.roleplay.engine.config.AppConfig();
        com.roleplay.engine.config.AppConfig.PacingConfig pacingCfg = cfg.getPacing();
        this.directorIntervalMs = pacingCfg.getDirectorIntervalMs();
        this.moveSpeedBase = pacingCfg.getMoveSpeedBase();
        this.moveSpeedRandomRange = pacingCfg.getMoveSpeedRandomRange();
        this.moveSpeedPlayerBase = pacingCfg.getMoveSpeedPlayerBase();
        this.moveSpeedPlayerRandomRange = pacingCfg.getMoveSpeedPlayerRandomRange();
        this.worldDirector = new WorldDirectorService(llmClient);
        conversationManager.init(world, llmClient,
                name -> world.getAgent(name),
                () -> world.getWorldNarration(),
                // 方案B（分步落地）：传 AnnouncementService + HearingSystem/全量状态给
                // SpeechStrategy，使其 processResults 可内联发区域广播（speech-mode=split 时）
                announcementService,
                world::getHearingSystem,
                () -> world.getAllStates().values());
        // D1: 注入中断系统（2D 对话生成可被模拟停止 / 事件驱动取消）
        conversationManager.setInterruptManager(interruptManager);
        conversationManager.setAgentTaskManager(agentTaskManager);
        // 演讲与广播合并地基：PUBLIC_SPEAKING 轮次产出 → 统一广播管线（自动选演讲/广播形态）
        conversationManager.setSpeechBroadcastListener(this::onSpeechBroadcast);
        conversationManager.setConversationCompletedListener(socialState::recordConversation);
        // P-0813-F：节奏控制接线（仅 2D 模拟世界注入；剧本杀/狼人杀各局自有
        // ConversationManager 实例不接此配置 → 保持原节奏，零影响）。
        conversationManager.setPacing(pacingCfg.isEnabled(),
                pacingCfg.getRoundCooldownMs(), pacingCfg.getGroupRoundCooldownMs(),
                pacingCfg.getConversationCooldownMs(),
                pacingCfg.getIdleRoundMultiplier(), pacingCfg.getInactiveTrackMultiplier());
        // P-0813-H：非玩家轨道发言的气泡停留/展示时长基准（conversation-status 下发前端）
        conversationManager.setSpeechBubbleHoldMs(pacingCfg.getSpeechBubbleHoldMs());
        // P-0814-A：点击驱动对话模式（roleplay.round.playback-driven）——2D 对话组一轮生成完即停，
        // 等 POST /api/simulation/playback_done 信号再下一轮；剧本杀/狼人杀各局 CM 实例不注入=旧行为。
        conversationManager.setPlaybackDriven(cfg.getRound().isPlaybackDriven());
        // P-0814-B：无玩家组「等待播出完毕」超时（roleplay.round.group-await-timeout-ms）——
        // 等待中的无玩家组（AI-AI / 单 AI）超时自动解散，防 awaitPlayback 永久阻塞角色冻结。
        conversationManager.setGroupAwaitTimeoutMs(cfg.getRound().getGroupAwaitTimeoutMs());
        // Phase 3 wiring: Track Director decides group track assignments (with World
        // Director goals for conflict detection); legacy spatial-only path stays as
        // fallback inside ConversationManager when trackDirector is null.
        conversationManager.setTrackDirector(trackDirector);
        conversationManager.setGoalSupplier(worldDirector::getAllGoals);
        // P-0815-A：轨道空间会话距离配置接线（roleplay.track.conversation-distance，px）——
        // 重建 TrackDirectorService 内部 SpatialTrackResolver（默认 70px，修正原 5.0「格」错位）。
        trackDirector.setConversationDistance(cfg.getTrack().getConversationDistance());
        trackDirector.setHearingSystem(world.getHearingSystem());
        conversationManager.setConversationDistance(cfg.getTrack().getConversationDistance());
        this.orchestrator = new SimulationOrchestrator(world, worldDirector, trackDirector, conversationManager, eventBus);
        // Phase 4: 移动 tick 前应用轨道运动约束（使用上一 tick 的轨道分配，延迟一拍）。
        world.addPreTickHook(this::applyMovementConstraints);
        // P-0813-I：日程窗口下发（注册于约束之后 → 有日程表的角色由日程接管移动目标，
        // 无日程表/未接管角色仍走约束+导演原行为）。
        if (schedulerService != null) {
            schedulerService.setOccupiedSupplier(this::occupiedAgentNames);
            world.addPreTickHook(this::applyScheduleWindows);
        }
        world.addTickListener(snapshot -> {
            try {
                // Phase 3: dual-director pipeline (World Director → InteractionDetector →
                // Track Director → ConversationGroup). Runs before conversation tick so
                // freshly created groups pick up the latest goals/track decisions.
                lastTrackAssignments = orchestrator.tick(System.currentTimeMillis());
            } catch (Exception e) {
                log.warn("Orchestrator tick failed: {}", e.getMessage());
            }
            conversationManager.tick(System.currentTimeMillis());
            checkDirectorCycle();
            // Periodic snapshot every SNAPSHOT_INTERVAL ticks
            int tick = world.getTickCount();
            if (tick > 0 && tick - lastSaveTick >= SNAPSHOT_INTERVAL) {
                lastSaveTick = tick;
                try {
                    saveSnapshot();
                } catch (Exception e) {
                    log.warn("Failed to save world snapshot: {}", e.getMessage());
                }
            }
        });
    }

    /** P-0813-I：日程窗口下发 hook（pre-tick，先于 MovementSystem.update；关/未注入 → no-op）。 */
    private void applyScheduleWindows() {
        if (schedulerService == null || !schedulerService.isEnabled()) return;
        try {
            schedulerService.applyToWorld(world, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("Schedule window apply failed: {}", e.getMessage());
        }
    }

    /**
     * P-0813-I：对话占用角色（只读 activeGroups 查询，不改 ConversationManager 内部结构）——
     * 群建立/解散处无需挂钩：每 tick 查询即天然「进入对话 → 日程窗口暂停 / 结束 → 恢复」。
     */
    private Set<String> occupiedAgentNames() {
        Set<String> occupied = new java.util.HashSet<>();
        for (ConversationGroup g : conversationManager.getActiveGroups()) {
            for (AgentState s : g.getParticipantList()) {
                occupied.add(s.getAgentName());
            }
        }
        return occupied;
    }

    /** P-0813-I（测试支持，包可见）：当前日程调度服务（null=未注入禁用）。 */
    SchedulerService getSchedulerService() { return schedulerService; }

    // ── Lifecycle ──────────────────────────────────────────────

    public void initDemo() {
        initDemo(2);
    }

    public void initDemo(int count) {
        clearAll();
        count = Math.max(2, Math.min(count, 8));

        String[][] presets = {
            {"小明", "开朗外向的年轻人，25岁，喜欢运动和户外活动", "说话轻松活泼，爱开玩笑", "在大城市打拼的年轻程序员"},
            {"小红", "温柔细心的女孩，23岁，喜欢阅读和画画，有点害羞", "说话轻声细语，偶尔紧张结巴", "美术学院学生，梦想开画廊"},
            {"阿杰", "沉着冷静的大叔，38岁，热爱钓鱼和茶道，人生阅历丰富", "说话慢条斯理，喜欢讲道理", "退休教师，在公园当志愿者"},
            {"小美", "活泼可爱的少女，19岁，喜欢追星和拍照，精力充沛", "说话快速跳跃，爱用夸张语气词", "大一新生，摄影社成员"},
            {"老陈", "严肃认真的工程师，45岁，喜欢研究机械和历史", "说话简洁有力，偶尔说冷笑话", "在科技公司工作，周末来公园散步"},
            {"阿花", "热情开朗的大妈，52岁，喜欢跳广场舞和种花", "嗓门大，说话直爽热情", "退休工人，公园广场舞领队"},
            {"小智", "聪明好动的男孩，12岁，喜欢打游戏和滑板", "说话天真活泼，充满好奇心", "小学生，放学后喜欢来公园玩"},
            {"小林", "文艺忧郁的青年，28岁，喜欢写诗和弹吉他", "说话文绉绉，偶尔吟诗", "自由撰稿人，经常在公园长椅上写作"},
        };

        for (int i = 0; i < count && i < presets.length; i++) {
            String[] p = presets[i];
            var persona = new com.roleplay.engine.core.Persona(p[0], p[1]);
            persona.setVoice(p[2]);
            persona.setBackground(p[3]);

            Agent agent = new Agent(persona, "npc", llmClient);
            double x = 100 + Math.random() * 800;
            double y = 100 + Math.random() * 400;
            // 大地图社会实验：原 180~260px 会跨越整栋建筑；收敛为近距离交谈尺度。
            double hearRange = 85 + Math.random() * 25;
            double moveSpeed = moveSpeedBase + Math.random() * moveSpeedRandomRange;

            world.registerAgent(agent, x, y, hearRange, moveSpeed);
            socialState.registerAgent(p[0]);
            world.getState(p[0]).setEmotion(Emotion.NEUTRAL);
        }

        ensureSchedulesAndSuppliers();
        log.info("Demo initialized: {} agents", world.getAgentCount());
    }

    /**
     * Initialize simulation with user-defined Personas (bridging from roleplay).
     * Each persona becomes an agent in the 2D world with random starting positions.
     * 兼容旧调用（无 playerName）：按旧规则把名字为 "me" 的 agent 标记为玩家控制。
     */
    public void initWithPersonas(List<Persona> personas, String sceneName) {
        initWithPersonas(personas, sceneName, null);
    }

    /**
     * P0-1（2026-08-02）：玩家控制标记改为显式指定——传 playerName 时只把同名 agent 标记为
     * playerControlled（不再硬编码名字 "me"，避免新建角色名 "me" 被误识别成玩家自己）；
     * 未传时保持旧行为向后兼容。
     */
    public void initWithPersonas(List<Persona> personas, String sceneName, String playerName) {
        initWithPersonas(personas, sceneName, playerName, null);
    }

    /**
     * P-0802-P2（改造方案《玩家角色改名与 AI 识别》Phase 2）：initWithPersonas 四参重载。
     * 判定加 playerId 解析式豁免（方案 §3.3）：player_id 存在且能解析 → 用解析出的当前角色名
     * 标记 playerControlled（角色库改名后即使前端仍传旧名 playerName，也按 player_id 解析新名）；
     * 未命中/缺省 → 走现状 playerName 逻辑，零行为变化。
     */
    public void initWithPersonas(List<Persona> personas, String sceneName, String playerName, String playerId) {
        initWithPersonas(personas, sceneName, playerName, playerId, null, null);
    }

    /**
     * P-0811-G：五参重载 + 自定义障碍（LLM 地图注入）。customObstacles 非空 → 用 LLM 地图障碍
     * 覆盖预置场景；null → 走 sceneName 预置场景（旧行为零变化）。
     */
    public void initWithPersonas(List<Persona> personas, String sceneName, String playerName, String playerId,
                                 List<Obstacle> customObstacles, String mapLabel) {
        clearAll();
        if (personas.isEmpty()) {
            log.warn("Empty persona list, falling back to demo");
            initDemo(2);
            return;
        }

        if (customObstacles != null) {
            world.setCustomObstacles(customObstacles, mapLabel);
            log.info("LLM map obstacles injected: {} (label={})", customObstacles.size(), mapLabel);
        } else if (sceneName != null && !sceneName.isBlank()) {
            world.setScene(sceneName);
        }

        boolean explicitPlayer = playerName != null && !playerName.isBlank();
        // P-0802-P2：player_id 解析出的当前角色名（角色库改名后 = 新名）；未绑定/缺省 → null（回退旧逻辑）
        String resolvedPlayerName = (playerId != null && !playerId.isBlank() && identityService != null)
                ? identityService.resolveCharacterName(playerId).orElse(null) : null;
        // P-0811-G：自定义障碍（LLM 地图）时出生点避开障碍，防角色初始卡进墙内/偏移
        List<Obstacle> spawnObstacles = customObstacles;
        int personaIndex = 0;
        for (Persona p : personas) {
            Agent agent = new Agent(p, "npc", llmClient);
            WorldDefinition definition = world.getWorldDefinition();
            WorldDefinition.SpawnPoint authoredSpawn = definition == null || definition.spawnPoints().isEmpty()
                    ? null : definition.spawnPoints().get(personaIndex % definition.spawnPoints().size());
            boolean fixedAuthoredSpawn = authoredSpawn != null && !authoredSpawn.tags().contains("default");
            double[] spawn = !fixedAuthoredSpawn
                    ? pickSpawnPoint(spawnObstacles)
                    : new double[]{authoredSpawn.position().x(), authoredSpawn.position().z()};
            double x = spawn[0], y = spawn[1];
            // 大地图社会实验：原 180~260px 会跨越整栋建筑；收敛为近距离交谈尺度。
            double hearRange = 85 + Math.random() * 25;
            double moveSpeed = moveSpeedBase + Math.random() * moveSpeedRandomRange;

            world.registerAgent(agent, x, y, hearRange, moveSpeed);
            socialState.registerAgent(p.getName());
            AgentState state = world.getState(p.getName());
            if (state != null) {
                if (authoredSpawn != null) {
                    double elevation = authoredSpawn.position().y();
                    state.getSpatial().setNavLocation(new NavLocation(authoredSpawn.surfaceId(), authoredSpawn.floorId(),
                            new com.roleplay.engine.simulation.spatial.Vec3(x, elevation, y), -1L));
                }
                state.setEmotion(Emotion.NEUTRAL);
                // Mark player-controlled agents: 显式 playerName（P0-1）、playerId 解析名（P-0802-P2）或旧规则名字 "me"
                boolean isPlayerControlled = explicitPlayer
                        ? playerName.equals(p.getName()) || (resolvedPlayerName != null && resolvedPlayerName.equals(p.getName()))
                        : PLAYER_AGENT_NAME.equals(p.getName());
                if (isPlayerControlled) {
                    state.setPlayerControlled(true);
                    // P-0814-I：玩家角色分速——playerControlled 用高速度（默认 90-105 px/s），
                    // AI 维持 moveSpeedBase+rand×range（45-80）。玩家 WASD/点击移动更跟手，AI 密度不受影响。
                    state.setMoveSpeed(moveSpeedPlayerBase + Math.random() * moveSpeedPlayerRandomRange);
                }
            }
            personaIndex++;
        }

        ensureSchedulesAndSuppliers();
        log.info("Loaded {} personas into simulation, scene={}, player={}, playerId={}", personas.size(), sceneName, playerName, playerId);
    }

    /**
     * P-0813-I：开局/场景启动 —— 为主世界每个角色生成日程表（规则+随机，零 LLM）
     * 并为 Agent 注册行为窗口文案提供者（LLM 对话系统提示注入【当前行为窗口】段）。
     * 日程未注入/关闭 → 全 no-op（回退原行为）。
     */
    private void ensureSchedulesAndSuppliers() {
        if (schedulerService == null || !schedulerService.isEnabled()) return;
        schedulerService.generateFor(world.getAllStates().values(), name -> {
            Agent a = world.getAgent(name);
            return a != null ? a.getPersona().getPersonaDesc() : null;
        });
        for (AgentState s : world.getAllStates().values()) {
            Agent a = world.getAgent(s.getAgentName());
            if (a != null) {
                a.setScheduleContextSupplier(() -> schedulerService.currentWindowText(
                        s.getAgentName(), System.currentTimeMillis()));
            }
        }
    }

    public void clearAll() {
        // D1: 清场时同时停止所有群组生成任务
        conversationManager.stopAll();
        conversationManager.clearPassiveAgents();
        if (schedulerService != null) schedulerService.clear();
        world.clearAgents();
        suspendedAgents.clear();
        socialState.clear();
    }

    /** 动态加入一般模式 2D 世界的 AI。 */
    public synchronized Map<String, Object> addSocialAgent(String name, String personaDesc) {
        if (name == null || name.isBlank()) return Map.of("status", "error", "message", "name required");
        if (world.getAgent(name) != null || suspendedAgents.containsKey(name)) {
            return Map.of("status", "error", "message", "Agent already exists or is suspended");
        }
        Persona persona = new Persona(name);
        persona.setPersonaDesc(personaDesc == null ? "" : personaDesc);
        Agent agent = new Agent(persona, "npc", llmClient);
        double[] spawn = pickSpawnPoint(world.getObstacles());
        world.registerAgent(agent, spawn[0], spawn[1], 85 + Math.random() * 25,
                moveSpeedBase + Math.random() * moveSpeedRandomRange);
        socialState.registerAgent(name);
        ensureSchedulesAndSuppliers();
        return Map.of("status", "ok", "agent", name, "x", spawn[0], "y", spawn[1]);
    }

    /** 动态移除一般模式 2D 世界的 AI，并清理其社会状态。 */
    public synchronized Map<String, Object> removeSocialAgent(String name) {
        SuspendedAgent suspended = name == null ? null : suspendedAgents.remove(name);
        if (suspended != null) {
            socialState.removeAgent(name);
            return Map.of("status", "ok", "agent", name, "detached_groups", 0);
        }
        if (name == null || world.getAgent(name) == null) return Map.of("status", "error", "message", "Agent not found");
        int detachedGroups = conversationManager.detachAgent(name);
        world.removeAgent(name);
        socialState.removeAgent(name);
        return Map.of("status", "ok", "agent", name, "detached_groups", detachedGroups);
    }

    /** 从调度世界摘除但保留对象、状态、记忆和社会关系，供生命周期 DORMANT/ARCHIVED 使用。 */
    public synchronized Map<String, Object> suspendSocialAgent(String name) {
        if (name == null || name.isBlank()) return Map.of("status", "error", "message", "name required");
        if (suspendedAgents.containsKey(name)) return Map.of("status", "ok", "agent", name, "already", true);
        Agent agent = world.getAgent(name);
        AgentState state = world.getState(name);
        if (agent == null || state == null) return Map.of("status", "error", "message", "Agent not found");
        int detachedGroups = conversationManager.detachAgent(name);
        suspendedAgents.put(name, new SuspendedAgent(agent, state));
        world.removeAgent(name);
        return Map.of("status", "ok", "agent", name, "detached_groups", detachedGroups);
    }

    public synchronized Map<String, Object> resumeSocialAgent(String name) {
        SuspendedAgent suspended = name == null ? null : suspendedAgents.remove(name);
        if (suspended == null) return Map.of("status", "error", "message", "Agent is not suspended");
        if (world.getAgent(name) != null) {
            suspendedAgents.put(name, suspended);
            return Map.of("status", "error", "message", "Agent name is occupied");
        }
        world.restoreAgent(suspended.agent(), suspended.state());
        return Map.of("status", "ok", "agent", name);
    }

    public boolean isSuspendedAgent(String name) {
        return name != null && suspendedAgents.containsKey(name);
    }

    public void setAgentPassive(String name, boolean passive) {
        conversationManager.setAgentPassive(name, passive);
    }

    public boolean isAgentPassive(String name) {
        return conversationManager.isAgentPassive(name);
    }

    private record SuspendedAgent(Agent agent, AgentState state) {}

    public Map<String, Object> getSocialState() { return socialState.toMap(); }
    public Map<String, Object> getSocialState(String agent) { return socialState.forAgent(agent); }
    public void setSocialGoal(String agent, String goal, String targetAgent) {
        socialState.setGoal(agent, goal, targetAgent);
    }
    public void clearSocialGoal(String agent) { socialState.clearGoal(agent); }

    /**
     * P-0802-P3（改造方案 §4.2.2）：2D 局中改名 —— world.renameAgent（agents/states 换键 + persona 改名）
     * + 重新断言 playerControlled（玩家本人标记随绑定迁移；判定读点 :450/:553 无需改动，标记随 state 走）。
     * 方法锁：与 tick/对话并发时防半同步状态被读取（2D 单世界）。
     */
    public synchronized void renamePlayerCharacter(String oldName, String newName) {
        world.renameAgent(oldName, newName);
        AgentState st = world.getState(newName);
        if (st != null) {
            st.setPlayerControlled(true);
        }
        log.info("2D player character renamed: {} → {} (playerControlled re-asserted)", oldName, newName);
    }

    /** P-0802-P3：2D 世界 agents 名单是否含指定名字（局中改名会话收集用）。 */
    public boolean hasAgent(String name) {
        return name != null && world.getAgent(name) != null;
    }

    /**
     * P-0814-A：2D 对话组「播出完毕」信号（POST /api/simulation/playback_done → group_id 路径）——
     * 委托 ConversationManager 唤醒该组等待中的轮次循环生成下一轮（一轮=组内各成员按发言顺序各一句）。
     * 幂等：组不存在或未处于等待态 → false（信号忽略，不产生多余轮次）。
     *
     * @return true=信号已送达等待中的组；false=组不存在/未等待
     */
    public boolean notifyPlaybackDone(String groupId) {
        return conversationManager.notifyPlaybackDone(groupId);
    }

    /** P-0802-P3：指定角色是否被标记为玩家控制（局中改名后断言标记保留用）。 */
    public boolean isPlayerControlled(String name) {
        AgentState st = world.getState(name);
        return st != null && st.isPlayerControlled();
    }

    public void start() {
        conversationManager.resetStopped();
        lastDirectorTime = System.currentTimeMillis();
        world.start();
    }

    public void stop() {
        world.stop();
        // D1: 中断系统 —— 停止世界 tick 之外，硬停止所有进行中的 LLM 生成任务
        conversationManager.stopAll();
        if (interruptManager != null) {
            interruptManager.cancelAll(StopType.HARD, "模拟停止 /api/simulation/stop");
        }
        // Save final snapshot on stop
        try {
            saveSnapshot();
        } catch (Exception e) {
            log.warn("Failed to save final snapshot: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
        taskExecutor.shutdownNow();
        conversationManager.shutdown();
    }

    public Map<String, Object> getState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", world.isRunning());
        result.put("tick", world.getTickCount());
        result.put("agentCount", world.getAgentCount());
        List<Map<String, Object>> agentList = new ArrayList<>();
        for (AgentState s : world.getAllStates().values()) {
            agentList.add(s.toMap());
        }
        result.put("agents", agentList);
        result.put("recentConversations", world.getRecentConversations());
        result.put("worldNarration", world.getWorldNarration());
        result.put("directorActive", world.isDirectorActive());
        result.put("social", socialState.toMap());
        return result;
    }

    public Map<String, Object> getConversationStatus() {
        return conversationManager.getStatus();
    }

    /** 方案A（轨道系统用户加入）：玩家加入现有对话组（委托 ConversationManager 原语）。 */
    public ConversationManager.JoinResult joinGroup(String groupId, String playerName) {
        return conversationManager.joinGroup(groupId, playerName);
    }

    /** 方案A（轨道系统用户加入）：玩家离开对话组。 */
    public ConversationManager.JoinResult leaveGroup(String groupId, String playerName) {
        return conversationManager.leaveGroup(groupId, playerName);
    }

    public void sendUserDirective(String directive) {
        world.setUserDirective(directive);
        log.info("User directive: {}", directive);
        runDirectorRound(true);
    }

    public void sendUserMessage(String agentName, String message) {
        AgentState state = world.getState(agentName);
        if (state == null) return;
        state.setCurrentMessage(message);
        pendingUserMessages.add(agentName);
        // P-0814-B：玩家输入唤醒所在组等待（AI-user 组解卡；「输入=点击」语义）——
        // 玩家已在对话组内时（自动 DYAD/手动加入），信号唤醒该组轮次循环：等待态即生成下一轮
        // （该输入经 executeRound 调序入史当轮生效）；不在任何组 → false，tick 按既有路径
        // 用玩家消息自动建 DYAD 组（语义不变）。
        conversationManager.wakeGroupForAgent(agentName);
    }

    // ── 演讲与广播合并地基：AI 发言自动选择形态，接入统一公告管线 ──

    /**
     * 演讲听众判定（管线层单事实源）：
     * <ul>
     *   <li>{@code merged}（正式版默认）——HearingSystem 声学判定
     *       （{@code HearingSystem.countHearingListeners}：computeAudibility+canHear 距离衰减，
     *       半径内可听听众计数），判定集中回管线层，与方案B split 共用同一声学工具方法；</li>
     *   <li>{@code auto}（方案A 旧行为回退）——ModeClassifier.wouldOthersListen 硬编码启发式
     *       （2.5×hearRange/距离>50/≥2，D-004 纪律欠账仅保留于回退路径）。</li>
     * </ul>
     */
    private boolean hasAudience(AgentState state) {
        if ("merged".equals(announcementService.getSpeechMode())) {
            return world.getHearingSystem().countHearingListeners(state, world.getAllStates().values()) > 0;
        }
        // auto（方案A 旧行为）｜split（演示端点沿用 auto 判定，内联路径与回调互斥）
        return new ModeClassifier().wouldOthersListen(state, world.getAllStates());
    }

    /**
     * 无听众兜底开关（merged 生效，单事实源）：
     * merged → roleplay.broadcast.fallback-to-global 配置；auto/split（旧行为回退）→ 恒 true
     * （方案A 原语义：无听众自动升级全局公告，供回退对比不读配置）。
     */
    private boolean effectiveFallback() {
        if (!"merged".equals(announcementService.getSpeechMode())) return true;
        return announcementService.isFallbackToGlobal();
    }

    /**
     * 2D 演讲轮次产出回调：按当前模式判定听众并接入统一公告管线——
     * merged=HearingSystem 声学判定（有听众→演讲 area / 无听众→按兜底配置升级全局公告或保持区域）；
     * auto=wouldOthersListen 硬编码判定（无听众恒全局公告）。
     */
    private void onSpeechBroadcast(ConversationManager.SpeechTurn turn) {
        try {
            AgentState state = world.getState(turn.speaker());
            if (state == null) return;
            boolean hasAudience = hasAudience(state);
            announcementService.enqueueAutoSpeech(turn.speaker(), turn.text(),
                    state.getX(), state.getY(), state.getHearRange(),
                    hasAudience, effectiveFallback());
        } catch (Exception e) {
            log.warn("Speech→broadcast failed: {}", e.getMessage());
        }
    }

    /**
     * 演示/外部触发：AI 自动演讲或广播（POST /api/simulation/speech）。
     * 不指定 speaker 时自动选第一个 NPC；不指定 text 时用演示文案。
     * 形态由系统自动判定（merged=HearingSystem 声学判定 + 兜底配置 / auto=wouldOthersListen），
     * 响应 mode 反映实际入队形态（speech=区域 / announcement=全局）。
     */
    public Map<String, Object> publishAiSpeech(String speaker, String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (speaker == null || speaker.isBlank()) {
            speaker = world.getAgentNames().stream()
                    .filter(n -> !PLAYER_AGENT_NAME.equals(n))
                    .findFirst().orElse("");
        }
        if (speaker.isBlank()) {
            result.put("status", "error");
            result.put("message", "2D 世界无 NPC，请先 POST /api/simulation/init");
            return result;
        }
        AgentState state = world.getState(speaker);
        if (state == null) {
            result.put("status", "error");
            result.put("message", "未知 agent: " + speaker);
            return result;
        }
        if (text == null || text.isBlank()) {
            text = "诸位静一静，听我说！我有一件重要的事要宣布——只要我们齐心协力，没有解决不了的难题！";
        }
        boolean hasAudience = hasAudience(state);
        boolean fallback = effectiveFallback();
        boolean area = hasAudience || !fallback;
        BroadcastMessage msg = announcementService.enqueueAutoSpeech(
                speaker, text, state.getX(), state.getY(), state.getHearRange(),
                hasAudience, fallback);
        result.put("status", msg != null ? "ok" : "dropped");
        result.put("speaker", speaker);
        result.put("mode", area ? BroadcastMessage.MODE_SPEECH : BroadcastMessage.MODE_ANNOUNCEMENT);
        result.put("has_audience", hasAudience);
        result.put("x", Math.round(state.getX()));
        result.put("y", Math.round(state.getY()));
        result.put("radius", Math.round(state.getHearRange()));
        result.put("text", text);
        return result;
    }

    // ── Phase 4: Track REST 支撑 ───────────────────────────────

    /** 轨道运动约束：每 tick 移动前，用最近一次轨道分配生成约束目标并写回。 */
    private void applyMovementConstraints() {
        Map<String, TrackAssignment> assignments = lastTrackAssignments;
        if (assignments == null || assignments.isEmpty()) return;
        try {
            Map<String, MovementTarget> targets = movementConstraint.compute(
                    world, assignments, trackDirector.getSecretAgents());
            movementConstraint.apply(world, targets);
        } catch (Exception e) {
            log.warn("MovementConstraint failed: {}", e.getMessage());
        }
    }

    /** Track REST: World Director 手动目标（POST /track/goal）。 */
    public void setTrackGoal(String agent, String goal) {
        orchestrator.setGoal(agent, goal);
        log.info("Track goal set: {} → {}", agent, goal);
    }

    /** Track REST: 清除手动目标，恢复规则驱动。 */
    public void clearTrackGoal(String agent) {
        orchestrator.clearGoal(agent);
        log.info("Track goal cleared: {}", agent);
    }

    /** Track REST: 秘密任务注入（POST /track/secret，强制 ISOLATED）。 */
    public void setSecretAgents(java.util.Set<String> names) {
        orchestrator.setSecretAgents(names);
        log.info("Secret agents set: {}", names);
    }

    /** Track REST: 汇总轨道状态（GET /track/state）。 */
    public Map<String, Object> getTrackState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goals", worldDirector.getAllGoals());
        result.put("secret_agents", new ArrayList<>(orchestrator.getSecretAgents()));
        InteractionDetector.TrackScore score = orchestrator.getLastTrackScore();
        result.put("last_score", score == null ? Map.of() : score.toMap());
        // 附加：当前轨道分配摘要（agent → MERGED/WEAK/ISOLATED），供前端 2D 页展示。
        Map<String, String> modeSummary = new LinkedHashMap<>();
        for (Map.Entry<String, TrackAssignment> e : lastTrackAssignments.entrySet()) {
            modeSummary.put(e.getKey(), e.getValue().type().name());
        }
        result.put("assignments", modeSummary);
        return result;
    }

    // ── Director LLM (主控) ─────────────────────────────────────

    /** P-0813-F（测试支持，包可见）：导演轮间隔 ms（由 roleplay.pacing.director-interval-ms 注入）。 */
    long getDirectorIntervalMs() { return directorIntervalMs; }

    /** P-0813-F（测试支持，包可见）：2D 角色移动速度基准（roleplay.pacing.move-speed-base）。 */
    double getMoveSpeedBase() { return moveSpeedBase; }

    /** P-0813-F（测试支持，包可见）：2D 角色移动速度随机幅度（roleplay.pacing.move-speed-random-range）。 */
    double getMoveSpeedRandomRange() { return moveSpeedRandomRange; }

    /** P-0814-I（测试支持，包可见）：玩家角色移动速度基准（roleplay.pacing.move-speed-player-base）。 */
    double getMoveSpeedPlayerBase() { return moveSpeedPlayerBase; }

    /** P-0814-I（测试支持，包可见）：玩家角色速度随机幅度（roleplay.pacing.move-speed-player-random-range）。 */
    double getMoveSpeedPlayerRandomRange() { return moveSpeedPlayerRandomRange; }

    private void checkDirectorCycle() {
        long now = System.currentTimeMillis();
        if (now - lastDirectorTime < directorIntervalMs) return;
        if (world.isDirectorActive()) return;
        lastDirectorTime = now;

        boolean anyActivity = false;
        for (AgentState s : world.getAllStates().values()) {
            if (s.isInConversation() || Math.abs(s.getVx()) > 0.5 || Math.abs(s.getVy()) > 0.5) {
                anyActivity = true; break;
            }
        }
        if (!anyActivity && world.getTickCount() > 50) return;

        runDirectorRound(false);
    }

    private void runDirectorRound(boolean userTriggered) {
        world.setDirectorActive(true);

        CompletableFuture.runAsync(() -> {
            try {
                String prompt = buildDirectorPrompt(userTriggered);
                // D-023：主控轮次大 JSON（decisions[]×角色数 + narration），600 偏紧，提升至 1000
                Map<String, Object> result = llmClient.callJson(prompt, 1000);
                if (result.isEmpty()) { world.setWorldNarration("(主控思考中...)"); return; }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
                String narration = (String) result.getOrDefault("narration", "");
                if (narration != null && !narration.isBlank()) world.setWorldNarration(narration);

                if (decisions != null) {
                    applyDirectorDecisions(decisions);
                }
                applyDirectorEvents(asMapList(result.get("events")), userTriggered);

                // Clear pending user message flags after director processes them
                pendingUserMessages.clear();

                String userDirective = world.getUserDirective();
                if (userTriggered && userDirective != null && !userDirective.isBlank()) {
                    world.setUserDirective("");
                }

                log.info("Director: {} decisions, \"{}\"",
                        decisions != null ? decisions.size() : 0,
                        narration != null ? narration : "");
            } catch (Exception e) {
                log.warn("Director failed: {}", e.getMessage());
            } finally {
                world.setDirectorActive(false);
            }
        }, taskExecutor);
    }

    /**
     * P-0813-E：导演决定应用（抽取为包可见方法，供单测直调）。
     * 跳过规则：对话中 / 玩家控制（playerControlled）/ 玩家手动指定目标（manualTarget，未超时）
     * 的角色不被导演重设目标；manualTarget 超时（MANUAL_TARGET_HOLD_MS）后释放标记，导演恢复接管。
     */
    void applyDirectorDecisions(List<Map<String, Object>> decisions) {
        for (Map<String, Object> d : decisions) {
            String name = (String) d.get("agent");
            AgentState state = world.getState(name);
            if (state == null || state.isInConversation()) continue;
            // Skip player-controlled agent — don't let AI control them
            if (state.isPlayerControlled()) continue;
            // P-0813-E：玩家手动指定目标（/target 端点 manualTarget）与 playerControlled 同等对待——
            // 导演不覆盖其目标（实测此前 ~10s 内被导演重设）；超时后释放标记由导演接管。
            if (state.isManualTarget()) {
                if (System.currentTimeMillis() - state.getManualTargetSince() < MANUAL_TARGET_HOLD_MS) {
                    continue;
                }
                state.setManualTarget(false);
            }

            Object ax = d.get("target_x");
            Object ay = d.get("target_y");
            if (ax instanceof Number && ay instanceof Number) {
                state.setAutonomousTarget(
                        clamp(((Number) ax).doubleValue(), 30, world.getWorldWidth() - 30),
                        clamp(((Number) ay).doubleValue(), 30, world.getWorldHeight() - 30));
            }

            String action = (String) d.get("action");
            if (action != null && !action.isBlank()) {
                state.setCurrentMessage("(主控: " + action + ")");
            }

            String emotionStr = (String) d.get("emotion");
            if (emotionStr != null) {
                Emotion e = Emotion.fromText(emotionStr);
                if (e != Emotion.NEUTRAL) state.setEmotion(e);
            }
        }
    }

    /** 将 DM 的“世界事实”校验后送入统一公告管线；不把 AREA/TARGET 事件写进全局旁白。 */
    void applyDirectorEvents(List<Map<String, Object>> rawEvents) {
        applyDirectorEvents(rawEvents, false);
    }

    void applyDirectorEvents(List<Map<String, Object>> rawEvents, boolean userTriggered) {
        if (rawEvents == null) return;
        for (Map<String, Object> raw : rawEvents) {
            WorldEvent event = WorldEvent.from(raw);
            if (event == null) continue;
            // SYSTEM 是 Engine 权限。自主 Director 不得用类型字段绕过世界事件限流。
            if (event.type() == WorldEvent.Type.SYSTEM) {
                log.warn("Ignoring autonomous DM SYSTEM event");
                continue;
            }
            long now = System.currentTimeMillis();
            boolean bypassCooldown = userTriggered;
            if (!bypassCooldown && now - lastDirectorEventTime < DIRECTOR_EVENT_COOLDOWN_MS) continue;
            world.addWorldEvent(event);
            lastDirectorEventTime = now;
            // TARGET 是角色私有感知，不能复用面向所有 SSE 客户端的 AnnouncementService。
            if (event.scope() == WorldEvent.Scope.TARGET) continue;
            String channel = event.scope() == WorldEvent.Scope.GLOBAL ? "global" : "area";
            double x = event.scope() == WorldEvent.Scope.AREA ? event.x() : -1;
            double y = event.scope() == WorldEvent.Scope.AREA ? event.y() : -1;
            double radius = event.scope() == WorldEvent.Scope.AREA ? event.radius() : 0;
            announcementService.enqueue(BroadcastMessage.of(
                    event.type() == WorldEvent.Type.SYSTEM ? BroadcastMessage.Level.SYSTEM : BroadcastMessage.Level.EVENT,
                    channel, "世界", event.text(), x, y, radius, BroadcastMessage.MODE_ANNOUNCEMENT));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
    }

    private String buildDirectorPrompt(boolean userTriggered) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是虚拟世界的导演/主控（DM）。决定世界事件、环境和角色高层目标；不要替角色决定具体说什么或是否说话。\n\n");

        sb.append("世界：").append((int) world.getWorldWidth()).append("×")
                .append((int) world.getWorldHeight()).append("px，Tick #")
                .append(world.getTickCount()).append("，共").append(world.getAgentCount()).append("个角色\n\n");

        sb.append("角色状态：\n");
        for (AgentState s : world.getAllStates().values()) {
            Agent agent = world.getAgent(s.getAgentName());
            sb.append("---\n");
            sb.append(s.getAgentName());
            sb.append(" | 位置(").append(Math.round(s.getX())).append(",").append(Math.round(s.getY())).append(")");
            sb.append(" | 情绪:").append(s.getEmotion().getLabel()).append(s.getEmotion().getEmoji());
            sb.append(" | 对话:").append(s.isInConversation() ? "是" : "否");
            if (s.isHasTarget()) sb.append(" | 走向(").append(Math.round(s.getTargetX())).append(",").append(Math.round(s.getTargetY())).append(")");
            if (agent != null) {
                String trait = agent.getPersona().getPersonaDesc();
                sb.append(" | ").append(trait.length() > 50 ? trait.substring(0, 50) : trait);
            }
            String msg = s.getCurrentMessage();
            if (msg != null && !msg.isEmpty() && !msg.startsWith("(主控")) {
                sb.append(" | 说:").append(msg.length() > 40 ? msg.substring(0, 40) : msg);
            }
            sb.append("\n");
        }

        List<HearingSystem.HearingResult> hearing = world.getHearingSystem().computeAudibility(world.getAllStates().values());
        boolean anyAudible = false;
        sb.append("\n声音传播（可互相听到的组合）：\n");
        for (HearingSystem.HearingResult h : hearing) {
            if (h.canHear() && h.speakerName().compareTo(h.listenerName()) < 0) {
                sb.append("- ").append(h.speakerName()).append("←→").append(h.listenerName());
                sb.append(" 距离").append(Math.round(h.distance())).append("px");
                sb.append(" 清晰度").append(Math.round(h.clarity() * 100)).append("%\n");
                anyAudible = true;
            }
        }
        if (!anyAudible) sb.append("(无)\n");

        String directive = world.getUserDirective();
        if (directive != null && !directive.isBlank()) {
            sb.append("\n【用户指令！！！最高优先级】").append(directive).append("\n");
        }

        List<Map<String, Object>> convs = world.getRecentConversations();
        if (!convs.isEmpty()) {
            int start = Math.max(0, convs.size() - 2);
            sb.append("\n最近对话：\n");
            for (int i = start; i < convs.size(); i++) {
                sb.append("- ").append(convs.get(i).get("pair")).append("\n");
            }
        }

        sb.append("\n请为每个角色生成一个决定。已正在对话的角色保持原地(target=当前位置)。\n");
        if (userTriggered) sb.append("优先执行用户指令！\n");

        // Skip player-controlled agents — don't generate decisions for human players
        List<String> playerAgents = new ArrayList<>();
        for (AgentState s : world.getAllStates().values()) {
            if (s.isPlayerControlled()) playerAgents.add(s.getAgentName());
        }
        if (!playerAgents.isEmpty()) {
            sb.append("注意：以下角色由玩家亲自控制，不要为他们生成任何决定（target_x/target_y/action/emotion 字段留空）：");
            sb.append(String.join("、", playerAgents)).append("\n");
        }

        // Skip agents with pending user messages
        if (!pendingUserMessages.isEmpty()) {
            sb.append("注意：以下角色由用户亲自控制对话，不要为他们生成对话内容（action字段留空即可）：");
            sb.append(String.join("、", pendingUserMessages)).append("\n");
        }
        sb.append("JSON格式：\n");
        sb.append("仅在确有新世界事实（用户触发、日程、后果、剧情节点、停滞或环境变化）时生成 events，普通周期不要刷屏。\n");
        sb.append("事件可为 SOUND/VISUAL/ENVIRONMENT/ANNOUNCEMENT/PRIVATE/SYSTEM，scope 仅 GLOBAL/AREA/TARGET；AREA 必填 x,y,radius，TARGET 必填 targets 数组。\n");
        sb.append("{\"narration\":\"导演旁白\",\"events\":[{\"type\":\"SOUND\",\"scope\":\"AREA\",\"x\":420,\"y\":180,\"radius\":150,\"text\":\"远处传来一声巨响\"}],\"decisions\":[");
        sb.append("{\"agent\":\"名字\",\"target_x\":坐标,\"target_y\":坐标,\"action\":\"行为描述\",\"emotion\":\"情绪\"}");
        sb.append("]}");
        return sb.toString();
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * P-0811-G：角色出生点避开障碍（LLM 地图障碍密集时随机点可能落在墙内）。
     * 策略：随机试探最多 30 次找不在任何障碍内的点；全部失败则沿网格扫描第一个可走点；
     * 仍失败（障碍铺满）→ 回退世界边缘。无自定义障碍 → 随机点（旧行为）。
     */
    private double[] pickSpawnPoint(List<Obstacle> obstacles) {
        if (obstacles == null || obstacles.isEmpty()) {
            return new double[]{100 + Math.random() * 800, 100 + Math.random() * 400};
        }
        for (int i = 0; i < 30; i++) {
            double x = 40 + Math.random() * (world.getWorldWidth() - 80);
            double y = 40 + Math.random() * (world.getWorldHeight() - 80);
            if (!insideAnyObstacle(x, y, obstacles)) return new double[]{x, y};
        }
        // 网格扫描：找第一个可走点
        double step = 30;
        for (double y = 40; y < world.getWorldHeight() - 40; y += step) {
            for (double x = 40; x < world.getWorldWidth() - 40; x += step) {
                if (!insideAnyObstacle(x, y, obstacles)) return new double[]{x, y};
            }
        }
        // 全部失败 → 左上角兜底
        return new double[]{50, 50};
    }

    /** 点是否落在任意障碍矩形内。 */
    private boolean insideAnyObstacle(double x, double y, List<Obstacle> obstacles) {
        for (Obstacle o : obstacles) {
            if (o.contains(x, y)) return true;
        }
        return false;
    }

    // ── DB Persistence ──────────────────────────────────────────

    /** Save current world state as a snapshot to DB. */
    private void saveSnapshot() {
        int tick = world.getTickCount();
        String scene = world.getCurrentScene();

        // Build agent states list
        List<Map<String, Object>> agentStates = new ArrayList<>();
        for (AgentState s : world.getAllStates().values()) {
            agentStates.add(s.toMap());
        }

        String snapshotName = "snapshot_tick_" + tick;
        databaseService.saveWorldSnapshot(snapshotName, agentStates, scene, tick);

        // Save recent conversations as conversation logs
        List<Map<String, Object>> convs = world.getRecentConversations();
        if (!convs.isEmpty()) {
            List<String> participants = new ArrayList<>();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Map<String, Object> conv : convs) {
                String pair = (String) conv.getOrDefault("pair", "");
                if (pair != null && !pair.isEmpty()) {
                    String[] parts = pair.split("[←→]");
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty() && !participants.contains(trimmed)) {
                            participants.add(trimmed);
                        }
                    }
                }
                Map<String, Object> msg = new LinkedHashMap<>(conv);
                messages.add(msg);
            }
            databaseService.logConversation("simulation_" + scene, "2d", participants, messages, tick);
        }
    }
}
