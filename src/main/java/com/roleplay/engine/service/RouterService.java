package com.roleplay.engine.service;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.core.TrackConfig;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TrackChangeEvent;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.model.CompressedChunk;
import com.roleplay.engine.model.Session;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import com.roleplay.engine.service.ArbiterService.UserInputCategory;
import com.roleplay.engine.service.TrackRequestService.TrackChangeRequest;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TaskCancelledException;
import com.roleplay.engine.interrupt.TaskType;
import com.roleplay.engine.interrupt.TrackChangeEvent;
import com.roleplay.engine.interrupt.WorldEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ⭐ Round orchestrator — the heart of the roleplay system.
 *
 * <p>Coordinates Arbiter (track config) → AgentExecutor (parallel agent execution)
 * → Arbiter (output integration) → MemoryStore (persistence).
 *
 * <p>Maps from Python core/router.py (2200+ lines, the biggest mess).
 * In Java this is CLEAN — no mixin, no dead code, no serial agent execution.
 */
@Service
public class RouterService {
    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final ArbiterService arbiter;
    private final AgentExecutor executor;
    private final MemoryStore memory;
    private final Compressor compressor;
    private final Monitor monitor;
    private final GeneratorService generator;
    private final TrackRequestService trackRequestService;
    private final LLMClient llmClient;
    private final com.roleplay.engine.controller.HistoryController historyController;
    private final LorebookService lorebookService;
    /** D8: SSE 广播器 —— 回合管线关键节点推送事件（agent_output / round_complete 等），前端实时回显。 */
    private final SSEController sse;
    /** D1: 中断管理器 —— stop() 时取消进行中的生成任务（需求文档第八条）。 */
    private final InterruptManager interruptManager;
    /** D1: 世界事件总线 —— 轨道变化时发布 TrackChangeEvent（§七）。 */
    private final WorldEventBus eventBus;
    /** P-0802-P2（改造方案 Phase 2）：玩家身份解析器 —— player_id → 当前绑定角色名（纯 DB 查询零缓存）。 */
    private final PlayerIdentityService identityService;

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    /** 自治世界文本模式的非破坏性休眠槽；保留原 Agent、记忆与内部状态。 */
    private final Map<String, Agent> worldSuspendedAgents = new ConcurrentHashMap<>();
    /** 世界运行时拥有的角色名；旧 /api/agents 不得覆盖、删除或触发全局 SSE。 */
    private final Set<String> worldOwnedAgentNames = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    /** C-2: 一般模式串行调度开关（roleplay.round.serial，默认 false=保持并行行为；true=同轮按序生成、每完成一个即时入史）。 */
    @Value("${roleplay.round.serial:false}")
    private boolean serialRound;

    /** P-0810-23-D2：AI 角色单次发言中文字数提醒阈值（roleplay.llm.remind-threshold，默认 150；<=0 关闭）。 */
    @Value("${roleplay.llm.remind-threshold:150}")
    private int remindThreshold;

    /** P-0813-A：自动续轮延时（roleplay.round.auto-continue-ms，毫秒；<=0 禁用；默认 3000）。
     *  一般模式每轮完成后延时自动跑下一轮（导演模式 AI 自主推进）；玩家发言/stop/新会话打断 pending 任务。
     *  注意：@Value 仅对 Spring bean（默认单例 router）生效；SessionRegistry createRouter new 出来的
     *  会话实例由 SessionRegistry 经 {@link #setAutoContinueMs(long)} 显式注入（同 serialRound 模式）。 */
    @Value("${roleplay.round.auto-continue-ms:3000}")
    private long autoContinueMs;

    /** P-0814-A：点击驱动对话模式开关（roleplay.round.playback-driven；默认 false=回退旧行为，
     *  生产 yml 置 true=主人拍板新语义）。true：一轮生成完即停、不再定时自续，置「等待播出完毕」
     *  标志，由 POST /api/simulation/playback_done → {@link #onPlaybackDone()} 驱动下一轮；
     *  auto-continue-ms 被忽略。false：D-056 旧行为（auto-continue-ms 定时自续）。
     *  注意：@Value 仅对 Spring bean 生效；SessionRegistry createRouter new 出来的会话实例
     *  经 {@link #setPlaybackDriven(boolean)} 显式注入（同 autoContinueMs 模式）。 */
    @Value("${roleplay.round.playback-driven:false}")
    private boolean playbackDriven;

    /** P-0813-B：校准轮间隔（roleplay.round.calibrate-every；默认 6，0=禁用）。
     *  每 calibrate-every 个「AI 自主推进轮」（无玩家输入的轮次）向会话消息列表注入校准提醒
     *  （layer0 前 3 条 + 反差 + 角色关系，Author's Note 式尾部注入防漂移）；
     *  玩家发言轮不计数不触发；initSession/loadSession 重置计数（会话重建不误触发）。
     *  注意：@Value 仅对 Spring bean 生效；SessionRegistry createRouter new 出来的会话实例
     *  经 {@link #setCalibrateEvery(int)} 显式注入（同 autoContinueMs 模式）。 */
    @Value("${roleplay.round.calibrate-every:6}")
    private int calibrateEvery;

    /** P-0813-B：距上次校准已推进的 AI 轮数（仅无玩家输入的轮次计数；initSession/loadSession 重置）。 */
    private int roundsSinceCalibration = 0;

    /** P-0813-A：自动续轮共享调度器（2 daemon 线程；按 session 隔离 —— 每个会话 RouterService 实例只
     *  调度/取消自己的 pending 任务，runRound 方法级同步保证同会话续轮与玩家发言串行互斥；
     *  共享而非每实例一个调度器，避免 SessionRegistry 会话只增不减导致的线程泄漏）。 */
    private static final ScheduledExecutorService AUTO_CONTINUE_SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "router-auto-continue");
                t.setDaemon(true);
                return t;
            });

    /** P-0813-A：待执行的自动续轮任务（null=无；schedule 时替换、cancel 时置空 —— 每会话至多一个 pending）。 */
    private volatile ScheduledFuture<?> pendingAutoContinue = null;
    /** P-0813-A：调度续轮时所处的轮次 —— 触发时若轮次已推进（玩家发言/手动驱动先执行）则放弃本次续轮（防重复）。 */
    private volatile int autoContinueScheduledRound = 0;
    /** P-0813-A：手动批量轮次进行中（runTurns/runAutoRounds）→ 轮末不再调度自动续轮（防批量后多跑一轮）。 */
    private volatile boolean manualRoundBatch = false;
    /** P-0814-A：播放驱动模式下「等待播出完毕」标志——本轮生成完即置位，
     *  onPlaybackDone 消费后清除；玩家发言/stop/新会话/手动批量清除（防串场/防重复轮）。 */
    private volatile boolean awaitingPlayback = false;

    private volatile boolean running = false;
    /** P-0810-14: 起局后自动第一轮（AI 开场白）已触发标志 —— 每会话仅触发一次（initSession 重置）。 */
    private volatile boolean autoFirstRoundFired = false;
    private String mode = "free";        // free | protagonist | multi_track | director | werewolf | script
    private String protagonist = "";
    private String directorCharacter = "";
    private List<String> goals = new ArrayList<>();
    private Set<String> restrictedAgents = new HashSet<>();
    private String sceneDescription = "";
    private int roundCount = 0;
    private List<Map<String, Object>> previousTracks = new ArrayList<>();
    /** P-0811-G：上一轮主控整合时对本轮出场的预测（next_round）——下轮 configureTracks 注入闭环。 */
    private volatile Map<String, Object> pendingNextRound = null;
    private String sessionId = "";
    // D5: 剧本杀当前对局 —— 用于把 secrets 发放到对应角色上下文（仅 script 模式生效）
    private ScriptGameService.ScriptGame scriptGame = null;
    // P-0802-F: 狼人杀当前对局 —— 用于把身份（含狼人互认）发放到对应角色上下文（仅 werewolf 模式生效）
    private WerewolfService.GameState werewolfGame = null;
    // ═══ P-0810-09: 场景目标机制（一般模式「场景与场景目标」） ═══
    /** 目标生成/判定服务（SessionRegistry 创建会话后注入；null=未启用，测试直构零影响）。 */
    private volatile SceneGoalService sceneGoalService = null;
    /** 当前会话目标集（null=无目标；结构见 SceneGoalService 类注释）。 */
    private volatile Map<String, Object> sceneGoals = null;
    /** 已向玩家揭示过全文的目标键（完成/失败各揭示一次，防重复广播）。 */
    private final Set<String> goalRevealed = ConcurrentHashMap.newKeySet();

    public RouterService(ArbiterService arbiter, AgentExecutor executor,
                         MemoryStore memory, Compressor compressor,
                         Monitor monitor, GeneratorService generator,
                         TrackRequestService trackRequestService,
                         LLMClient llmClient,
                         com.roleplay.engine.controller.HistoryController historyController,
                         LorebookService lorebookService,
                         InterruptManager interruptManager,
                         WorldEventBus eventBus,
                         SSEController sse,
                         PlayerIdentityService playerIdentityService) {
        this.arbiter = arbiter;
        this.executor = executor;
        this.memory = memory;
        this.compressor = compressor;
        this.monitor = monitor;
        this.generator = generator;
        this.trackRequestService = trackRequestService;
        this.llmClient = llmClient;
        this.historyController = historyController;
        this.lorebookService = lorebookService;
        this.interruptManager = interruptManager;
        this.eventBus = eventBus;
        this.sse = sse;
        this.identityService = playerIdentityService;
        memory.setCompressor(compressor);
    }

    // ═══════════════════════════════════════════════════════════
    //  Session lifecycle
    // ═══════════════════════════════════════════════════════════

    public void initSession(String sessionId, List<Persona> personas,
                             String sceneDescription, String mode,
                             String protagonist, String directorCharacter) {
        this.sessionId = sessionId;
        this.sceneDescription = sceneDescription;
        this.mode = mode;
        this.protagonist = protagonist != null ? protagonist : "";
        this.directorCharacter = directorCharacter != null ? directorCharacter : "";
        this.roundCount = 0;
        this.previousTracks = new ArrayList<>();
        this.goals = new ArrayList<>();
        this.restrictedAgents = new HashSet<>();
        // P-0811-G：新会话清空上一轮出场预测（旧会话预测不串场）
        this.pendingNextRound = null;
        this.running = true;
        // P-0810-09: 新会话重置目标集（旧会话残留目标不串场）
        this.sceneGoals = null;
        this.goalRevealed.clear();
        // P-0810-11: 新会话清空对局历史快照 —— startScene 起局复用默认单例 router，
        // roundHistory 跨会话残留会让 /api/round/rollback 恢复旧会话的消息快照
        // （D-007 同类修法：新会话 init 时清状态；普通 init 会话走独立实例本就为空，零影响）
        this.roundHistory.clear();
        // P-0810-14: 新会话重置自动开场标志（每会话起局后自动第一轮仅触发一次）
        this.autoFirstRoundFired = false;
        // P-0813-A: 新会话重置自动续轮 pending —— 同一 router 实例重初始化时，旧会话遗留的续轮任务不得续跑（防泄漏/防串场）
        cancelPendingAutoContinue();
        // P-0814-A: 新会话重置「等待播出完毕」标志（旧会话等待态不串场）
        this.awaitingPlayback = false;
        // P-0813-B: 新会话重置校准计数 —— 会话重建不误触发（旧会话已校准轮数不串场）
        this.roundsSinceCalibration = 0;

        agents.clear();
        worldSuspendedAgents.clear();
        worldOwnedAgentNames.clear();
        List<String> agentNames = new ArrayList<>();
        for (Persona p : personas) {
            Agent agent = new Agent(p, "agent", llmClient);
            agents.put(p.getName(), agent);
            agentNames.add(p.getName());
        }

        // Create memory session
        memory.createSession(sessionId, agentNames, Map.of(
            "mode", mode, "scene", sceneDescription));
        state.put("status", "initialized");
        log.info("Session {} initialized with {} agents, mode={}", sessionId, agentNames.size(), mode);
    }

    public void loadSession(Session session, List<Agent> agentList) {
        this.sessionId = session.getSessionId();
        this.sceneDescription = session.getCurrentScene();
        this.roundCount = session.getRoundCount();
        // D12: restore the routing mode saved at init time (config = {mode, scene}),
        // so a loaded werewolf/protagonist session keeps its original behavior.
        if (session.getConfig() != null && session.getConfig().get("mode") != null
                && !String.valueOf(session.getConfig().get("mode")).isBlank()) {
            this.mode = String.valueOf(session.getConfig().get("mode"));
        }
        memory.setSession(session);
        agents.clear();
        worldSuspendedAgents.clear();
        worldOwnedAgentNames.clear();
        for (Agent a : agentList) {
            agents.put(a.getName(), a);
        }
        running = true;
        // P-0813-A: 加载会话同样清理遗留的自动续轮任务（防旧会话 pending 在新会话上误触发）
        cancelPendingAutoContinue();
        // P-0814-A: 加载会话同样清除「等待播出完毕」标志（防旧会话等待态误触发）
        this.awaitingPlayback = false;
        // P-0813-B: 加载会话重置校准计数 —— 恢复的会话从头计数，不因旧轮数立刻触发校准
        this.roundsSinceCalibration = 0;
        log.info("Loaded session {}", sessionId);
    }

    public Map<String, Object> getState() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", running ? "running" : "idle");
        // P-0814-C：暴露「等待播出完毕」状态（playback-driven 轮间门）——前端自动推进的轮询兜底
        // 据此精确武装（仅 awaiting 时才该发 playback_done 信号；status 常驻 running 不能作完成信号）
        s.put("awaiting_playback", awaitingPlayback);
        s.put("mode", mode);
        s.put("session_id", sessionId);
        s.put("round", roundCount);
        s.put("scene", sceneDescription);
        s.put("agent_count", agents.size());
        s.put("agent_names", agents.keySet().stream().toList());
        s.put("agents", agents.keySet().stream().toList());
        s.put("protagonist", protagonist);
        s.put("director_character", directorCharacter);
        s.put("goals", goals);
        s.put("message_count", memory.hasSession() ? memory.getSession().getMessages().size() : 0);
        if (monitor != null) s.put("cost_report", monitor.getCostReport());
        return s;
    }

    public boolean isRunning() { return running; }

    /**
     * P-0802-P3（改造方案 §4.2.1，主人授权 2026-08-02 沿 P-0802 授权链）：局中改名 ——
     * agents map 换键（:68）+ persona 改名（Persona.setName :64，Agent.getName 委托 persona :35-36）
     * + protagonist/directorCharacter（initSession :126-127）/restrictedAgents（:80）引用同步替换。
     * 历史消息保留旧名（不可篡改，正确行为）；后续轮次经 Agent.getName 自然用新名。
     * 方法锁：单会话 Router，方法级锁防与 runRound 并发（改名与对话轮/自动轮互斥）。
     */
    public synchronized void renameAgent(String oldName, String newName) {
        Agent a = agents.remove(oldName);
        if (a == null) return;
        a.getPersona().setName(newName);
        agents.put(newName, a);
        // 引用名同步替换：主角 / 导演角色 / 受限角色集合
        if (protagonist != null && protagonist.equals(oldName)) protagonist = newName;
        if (directorCharacter != null && directorCharacter.equals(oldName)) directorCharacter = newName;
        if (restrictedAgents != null && restrictedAgents.remove(oldName)) restrictedAgents.add(newName);
        log.info("Router agent renamed: {} → {}", oldName, newName);
    }

    /** P-0802-P3：当前会话 agents 名单是否含指定名字（局中改名会话收集用）。 */
    public boolean hasAgent(String name) {
        return name != null && agents.containsKey(name);
    }

    /** P-0802-P3：主角引用是否为指定角色（局中改名后断言引用同步用）。 */
    public boolean isProtagonist(String name) {
        return name != null && name.equals(protagonist);
    }

    /** P-0802-P3：受限角色集合副本（局中改名后断言引用同步用）。 */
    public Set<String> getRestrictedAgents() {
        return new HashSet<>(restrictedAgents);
    }

    /** P-0802-P3：设置受限角色集合（测试/运行时注入用；renameAgent 同步替换引用）。 */
    public void setRestrictedAgents(Set<String> names) {
        this.restrictedAgents = names != null ? new HashSet<>(names) : new HashSet<>();
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0810-09: 场景目标机制（一般模式「场景与场景目标」）
    // ═══════════════════════════════════════════════════════════

    /** 注入目标服务（SessionRegistry 创建会话 router 后调用；测试可直设）。 */
    public void setSceneGoalService(SceneGoalService sceneGoalService) {
        this.sceneGoalService = sceneGoalService;
    }

    public boolean hasSceneGoals() {
        return sceneGoals != null && !sceneGoals.isEmpty();
    }

    public Map<String, Object> getSceneGoalsRaw() {
        return sceneGoals;
    }

    /**
     * 一般模式 init 时确保目标集就绪：已有 → 跳过；DB 场景有 goals → 立即装载；否则先装载
     * 规则目标并在后台生成 LLM 目标。这样起局不再等待模型网络调用，目标仍会在生成完成后替换并持久化。
     * 非一般模式（werewolf/script 等）零触碰。
     */
    public synchronized void ensureSceneGoals(String sceneId, String sceneDesc, String customPlayerGoal) {
        if (!isGeneralMode(mode) || sceneGoalService == null) return;
        if (hasSceneGoals()) return;
        List<String> roleNames = new ArrayList<>(agents.keySet());
        Map<String, Object> stored = sceneGoalService.loadStoredGoals(sceneId, roleNames).orElse(null);
        if (stored != null && !stored.isEmpty()) {
            setSceneGoals(stored);
            log.info("Session {} loaded cached scene goals", sessionId);
            return;
        }

        Map<String, Object> fallback = sceneGoalService.fallbackGoals(roleNames, customPlayerGoal);
        setSceneGoals(fallback);
        String expectedSessionId = sessionId;
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> generated = sceneGoalService.generateAndPersist(
                        sceneId, sceneDesc, roleNames, customPlayerGoal);
                applyGeneratedSceneGoals(expectedSessionId, fallback, generated);
            } catch (Exception e) {
                // 规则目标已经可用；后台生成失败不影响已进入的对局。
                log.warn("Scene goal background generation skipped: session={} err={}", expectedSessionId, e.getMessage());
            }
        });
        log.info("Session {} started with fallback scene goals; LLM generation scheduled", sessionId);
    }

    /** 仅当前会话仍使用这份规则目标时，才接纳后台生成结果，避免旧任务串进重开后的会话。 */
    private synchronized void applyGeneratedSceneGoals(String expectedSessionId, Map<String, Object> fallback,
                                                        Map<String, Object> generated) {
        if (!Objects.equals(sessionId, expectedSessionId) || sceneGoals != fallback || generated == null || generated.isEmpty()) {
            return;
        }
        preserveGoalStatuses(fallback, generated);
        setSceneGoals(generated);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("goals", getSceneGoalsView());
        if (sse != null) sse.broadcastToSession(sessionId, "scene_goals_ready", payload);
        log.info("Session {} background scene goals ready", sessionId);
    }

    /** 后台生成期间已发生的进展优先保留，避免新描述覆盖已经推进过的状态。 */
    @SuppressWarnings("unchecked")
    private static void preserveGoalStatuses(Map<String, Object> current, Map<String, Object> replacement) {
        for (String key : List.of(SceneGoalService.KEY_GLOBAL, SceneGoalService.KEY_PLAYER)) {
            Object from = current.get(key);
            Object to = replacement.get(key);
            if (from instanceof Map<?, ?> source && to instanceof Map<?, ?> target && source.get("status") != null) {
                ((Map<String, Object>) target).put("status", String.valueOf(source.get("status")));
            }
        }
        Object fromRoles = current.get(SceneGoalService.KEY_ROLE_GOALS);
        Object toRoles = replacement.get(SceneGoalService.KEY_ROLE_GOALS);
        if (!(fromRoles instanceof Map<?, ?> sourceRoles) || !(toRoles instanceof Map<?, ?> targetRoles)) return;
        for (Map.Entry<?, ?> entry : sourceRoles.entrySet()) {
            Object from = entry.getValue();
            Object to = targetRoles.get(entry.getKey());
            if (from instanceof Map<?, ?> source && to instanceof Map<?, ?> target && source.get("status") != null) {
                ((Map<String, Object>) target).put("status", String.valueOf(source.get("status")));
            }
        }
    }

    /** 装载目标集并注入各 Agent 隐藏目标。 */
    public synchronized void setSceneGoals(Map<String, Object> goals) {
        this.sceneGoals = goals;
        injectHiddenGoals();
    }

    /** 隐藏目标注入：role_goals[agentName].desc → Agent.hiddenGoal（buildContext 系统提示用）。 */
    private void injectHiddenGoals() {
        if (sceneGoals == null) return;
        Object rg = sceneGoals.get(SceneGoalService.KEY_ROLE_GOALS);
        if (!(rg instanceof Map<?, ?> roleGoals)) return;
        for (Map.Entry<String, Agent> e : agents.entrySet()) {
            Object entry = roleGoals.get(e.getKey());
            if (entry instanceof Map<?, ?> m && m.get("desc") != null) {
                e.getValue().setHiddenGoal(String.valueOf(m.get("desc")));
            } else {
                e.getValue().setHiddenGoal(null);
            }
        }
    }

    /**
     * init 响应视图：玩家目标明文；全局/角色目标仅「？？」占位 + 数量（隐藏不泄露）。
     */
    public Map<String, Object> getSceneGoalsView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", hasSceneGoals());
        if (!hasSceneGoals()) return view;
        Map<String, Object> goals = sceneGoals;
        Object pg = goals.get(SceneGoalService.KEY_PLAYER);
        if (pg instanceof Map<?, ?> m) {
            view.put(SceneGoalService.KEY_PLAYER, Map.of(
                "desc", descOf(m, ""),
                "status", statusOfGoal(m, SceneGoalService.NOT_STARTED)));
        }
        Object gg = goals.get(SceneGoalService.KEY_GLOBAL);
        if (gg instanceof Map<?, ?> m) {
            view.put(SceneGoalService.KEY_GLOBAL, Map.of(
                "desc", SceneGoalService.MASK,
                "status", statusOfGoal(m, SceneGoalService.NOT_STARTED)));
        }
        Map<String, Object> roleView = new LinkedHashMap<>();
        Object rg = goals.get(SceneGoalService.KEY_ROLE_GOALS);
        if (rg instanceof Map<?, ?> roleGoals) {
            for (Map.Entry<?, ?> e : roleGoals.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m) {
                    roleView.put(String.valueOf(e.getKey()), Map.of(
                        "desc", SceneGoalService.MASK,
                        "status", statusOfGoal(m, SceneGoalService.NOT_STARTED)));
                }
            }
        }
        view.put(SceneGoalService.KEY_ROLE_GOALS, roleView);
        view.put("ai_goal_count", roleView.size());
        return view;
    }

    /** 读取目标条目 desc（Map&lt;?,?&gt; 捕获类型安全取值）。 */
    private static String descOf(Map<?, ?> m, String def) {
        Object d = m.get("desc");
        return d != null ? String.valueOf(d) : def;
    }

    /** 读取目标条目 status（Map&lt;?,?&gt; 捕获类型安全取值）。 */
    private static String statusOfGoal(Map<?, ?> m, String def) {
        Object s = m.get("status");
        return s != null ? String.valueOf(s) : def;
    }

    /**
     * runRound 末尾钩子：异步目标进展判定（不阻塞主流程；失败静默降级）。
     * 判定频率：每轮一次（任务先做每轮判定，性能优化留后续）。
     */
    private void submitGoalJudgment() {
        if (sceneGoalService == null || !hasSceneGoals()) return;
        Map<String, Object> goalsSnapshot = sceneGoals;
        String transcript = buildGoalTranscript();
        CompletableFuture.runAsync(() -> {
            try {
                SceneGoalService.JudgeResult result =
                        sceneGoalService.judgeGoals(goalsSnapshot, transcript);
                if (result != null) applyGoalJudgment(result);
            } catch (Exception e) {
                // 判定失败静默降级：不阻塞、不广播、不影响对话主流程
                log.warn("Scene goal judgment skipped: {}", e.getMessage());
            }
        });
    }

    /** 应用判定结果：状态有变化 → 组装 scene_target_update 定向广播（完成/失败揭示全文一次）。 */
    private synchronized void applyGoalJudgment(SceneGoalService.JudgeResult r) {
        if (!hasSceneGoals()) return;
        Map<String, Object> goals = sceneGoals;
        boolean changed = false;
        List<String> revealed = new ArrayList<>();

        Map<String, String> roleStatusOut = new LinkedHashMap<>();
        Object rg = goals.get(SceneGoalService.KEY_ROLE_GOALS);
        if (rg instanceof Map<?, ?> roleGoals) {
            for (Map.Entry<String, String> e : r.roleStatuses().entrySet()) {
                Object entry = roleGoals.get(e.getKey());
                if (!(entry instanceof Map<?, ?> m)) continue; // LLM 幻觉角色名忽略
                String cur = statusOfGoal(m, SceneGoalService.NOT_STARTED);
                roleStatusOut.put(e.getKey(), e.getValue());
                if (!cur.equals(e.getValue())) {
                    ((Map<String, Object>) entry).put("status", e.getValue());
                    changed = true;
                    if (isTerminal(e.getValue()) && goalRevealed.add(e.getKey())) {
                        revealed.add(descOf(m, ""));
                    }
                }
            }
        }
        String globalOut = SceneGoalService.NOT_STARTED;
        if (r.globalStatus() != null) {
            Object gg = goals.get(SceneGoalService.KEY_GLOBAL);
            if (gg instanceof Map<?, ?> m) {
                String cur = statusOfGoal(m, SceneGoalService.NOT_STARTED);
                globalOut = r.globalStatus();
                if (!cur.equals(r.globalStatus())) {
                    ((Map<String, Object>) m).put("status", r.globalStatus());
                    changed = true;
                    if (isTerminal(r.globalStatus()) && goalRevealed.add(SceneGoalService.KEY_GLOBAL)) {
                        revealed.add(descOf(m, ""));
                    }
                }
            }
        }
        String playerOut = SceneGoalService.NOT_STARTED;
        if (r.playerStatus() != null) {
            Object pp = goals.get(SceneGoalService.KEY_PLAYER);
            if (pp instanceof Map<?, ?> m) {
                String cur = statusOfGoal(m, SceneGoalService.NOT_STARTED);
                playerOut = r.playerStatus();
                if (!cur.equals(r.playerStatus())) {
                    ((Map<String, Object>) m).put("status", r.playerStatus());
                    changed = true;
                    // 玩家目标本就明文展示，状态变化同样广播（全文不重复进 revealed）
                }
            }
        }
        if (!changed) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("role_goal_status", roleStatusOut);
        payload.put("global_goal_status", globalOut);
        payload.put("player_goal_status", playerOut);
        payload.put("revealed", revealed);
        if (sse != null) sse.broadcastToSession(sessionId, "scene_target_update", payload);
    }

    private static boolean isTerminal(String status) {
        return SceneGoalService.COMPLETED.equals(status) || SceneGoalService.FAILED.equals(status);
    }

    /** 最近对话转录（最近 3 轮 ≈ 10 条消息），判定 LLM 输入用。 */
    private String buildGoalTranscript() {
        List<Map<String, String>> raw = memory.getShortTermContextRaw(3);
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> m : raw) {
            sb.append(m.getOrDefault("name", "?")).append(": ")
              .append(m.getOrDefault("content", "")).append("\n");
        }
        return sb.toString();
    }

    private static boolean isGeneralMode(String mode) {
        return mode != null && ("free".equals(mode) || "protagonist".equals(mode)
                || "multi_track".equals(mode) || "director".equals(mode));
    }

    /**
     * P-0815-C：双人场景判定 —— 本轮轨道中「实际参与 LLM 生成」的 active 角色数 ≤ 2。
     *
     * <p>从 TrackConfig 的 agent_actions=active 统计（语义准确，非 agentOutputs 简化判定）：
     * AI+AI 双人 = 2 active；AI+用户双人（protagonist 模式玩家角色被强制 active，但
     * {@code runRound} 的 agentMap 已排除玩家角色、不参与生成）= 排除 protagonist 后 1 active AI。
     * 双人场景下 next_round 预测（谁出场/谁隔离/顺序）无意义，跳过主控整合省一次 LLM 调用。</p>
     */
    private boolean isDuoScene(TrackConfig config) {
        Set<String> active = config.getActiveAgentNames();
        int activeCount = active.size();
        // 玩家角色不参与 LLM 生成，不计入活跃生成角色（AI+用户双人 = 1 active AI）
        if (!protagonist.isEmpty() && active.contains(protagonist)) {
            activeCount--;
        }
        return activeCount <= 2;
    }

    /**
     * 停止会话。D1 增强：除置位停止标志外，立即硬停止所有进行中的生成任务
     * （取消令牌 + 中断 LLM 调用线程），使 /api/stop 真正能中断进行中的生成。
     */
    public void stop() {
        running = false;
        // P-0813-A：停止会话 → 取消待执行的自动续轮任务（防泄漏/防 stop 后仍自动跑轮）
        cancelPendingAutoContinue();
        // P-0814-A：停止会话 → 清除「等待播出完毕」标志（stop 后不再接受推进信号）
        this.awaitingPlayback = false;
        if (interruptManager != null) {
            interruptManager.cancelAll(StopType.HARD, "用户停止 /api/stop");
        }
        // D8: 停止推送（前端 "已停止" + 解除运行锁）
        if (sse != null) sse.broadcastStopped();
    }

    /**
     * SessionRegistry 淘汰专用：只关闭本 Router 的续轮/等待状态，不触发共享
     * InterruptManager.cancelAll，避免清理一个过期会话时中断其他活跃会话。
     */
    public synchronized void closeSessionResources() {
        running = false;
        autoRunning = false;
        cancelPendingAutoContinueLocked();
        awaitingPlayback = false;
        manualRoundBatch = false;
    }

    // ═══════════════════════════════════════════════════════════
    //  Core round execution
    // ═══════════════════════════════════════════════════════════

    /**
     * Execute one full conversation round:
     * 1. Arbiter configures tracks
     * 2. AgentExecutor runs all agents in parallel (Virtual Threads!)
     * 3. Arbiter integrates outputs
     * 4. Memory saves, compressor checks
     */
    public RoundResult runRound(String userInput, String userInterjection) {
        return runRound(userInput, userInterjection, null);
    }

    /**
     * P0-2（2026-08-02）修复：
     * ① 会话已停止（running=false，如 /api/stop）但角色仍在时，发消息/自动轮视为恢复会话自动置 running=true——
     *    消除「send 前 stop 后永久停摆」；仅 agents 为空才报 No active session。
     * ② 支持显式发言人 speaker（前端 player_name）：命中 agent 名单时，该角色原文直接入史（角色说），
     *    跳过主控旁白化，本轮该角色不参与 LLM 生成（避免自问自答双声）；未命中走原主控旁白链路。
     */
    public RoundResult runRound(String userInput, String userInterjection, String speaker) {
        return runRound(userInput, userInterjection, speaker, null);
    }

    /**
     * P-0802-P2（改造方案《玩家角色改名与 AI 识别》Phase 2，主人授权 2026-08-02）：
     * runRound 四参重载 —— playerId 解析式豁免。判定优先级（方案 §3.3）：player_id 存在且能解析
     * → 用解析出的当前角色名；否则用 player_name 字符串（现状逻辑，零行为变化）。
     *
     * <p>P-0810-14：方法级同步（与 renameAgent 同策略）——起局自动第一轮在后台线程执行，
     * 与 /api/send 等并发入口互斥串行：开场轮先完成，玩家发言按序接入（MemoryStore 非线程安全，
     * 并发 runRound 会造成同轮消息交错/CME）。
     */
    public synchronized RoundResult runRound(String userInput, String userInterjection, String speaker, String playerId) {
        return runRoundTargeted(userInput, userInterjection, speaker, playerId, List.of());
    }

    /** 非 2D 会话定向轮：仅指定成员生成回复；空集合保持原有全体调度语义。 */
    public synchronized RoundResult runRoundTargeted(String userInput, String userInterjection,
                                                      String speaker, String playerId,
                                                      Collection<String> responseAgents) {
        if (agents.isEmpty()) {
            if (sse != null) sse.broadcastError("No active session");
            return RoundResult.error("No active session");
        }
        if (!running) {
            // P0-2：恢复会话（用户再次发消息/点三轮 = 恢复运行）
            running = true;
        }
        // P-0813-A：玩家发言 → 取消该会话待执行的自动续轮任务（防玩家发言驱动轮次与自动续轮重复/冲突）
        if (userInput != null && !userInput.isBlank()) {
            cancelPendingAutoContinueLocked();
            // P-0814-A：播放驱动下玩家发言 = 点击驱动的一种（输入即推进轮次）→ 清除等待标志
            awaitingPlayback = false;
        }
        // P0-2：发言人命中 agent → 该角色直接发言（跳过 arbiter 主控旁白化）
        // P-0802-P2：加 playerId 解析式豁免 —— 角色库改名后前端仍传旧名 speaker 时，
        // 按 player_id 解析当前角色名命中 agent 名单同样豁免（防主控代声 = 防被识别为 AI）；
        // 解析未命中（player_id 缺省/未绑定/identityService 缺失）→ 回退现状 speakers 字符串逻辑。
        boolean speakerIsAgent = speaker != null && !speaker.isBlank()
                && (agents.containsKey(speaker)
                    || (playerId != null && !playerId.isBlank() && identityService != null
                        && agents.containsKey(identityService.resolveCharacterName(playerId).orElse(""))));

        roundCount++;
        // Snapshot current round before starting
        if (memory.hasSession()) {
            snapshotRound(sessionId);
        }
        List<String> agentNames = new ArrayList<>(agents.keySet());
        String historySummary = memory.getSummaryContext();
        // Phase 1 Track isolation (leak bypass fix): if the previous round ran
        // multiple tracks and any of them was non-public (WEAK/ISOLATED), the raw
        // history summary would leak isolated-track conversation content to the
        // Arbiter. Replace it with one-line per-track descriptors only.
        historySummary = sanitizeSummaryForArbiter(historySummary, previousTracks);

        // Step 1b: Lorebook injection
        String loreContext = "";
        if (lorebookService != null) {
            if (userInput != null) {
                loreContext = lorebookService.buildLoreContext(sessionId, userInput);
            }
            // Also scan existing messages
            if (memory.hasSession()) {
                List<Message> msgs = memory.getSession().getMessages();
                if (!msgs.isEmpty()) {
                    String lastMsg = msgs.get(msgs.size() - 1).getContent();
                    loreContext += lorebookService.buildLoreContext(sessionId, lastMsg);
                }
            }
        }

        // Step 2: Silent process pending track requests
        trackRequestService.silentProcessPending(sessionId, goals);

        // Step 2: Configure tracks via Arbiter
        String enrichedScene = loreContext.isEmpty() ? sceneDescription
            : sceneDescription + loreContext;
        List<Map<String, Object>> prevTrackLayout = previousTracks;
        TrackConfigResult trackResult = arbiter.configureTracks(
            enrichedScene, agentNames, historySummary,
            mode, protagonist, previousTracks, goals, restrictedAgents, pendingNextRound);
        // D1: 轨道变化 → 发布 TrackChangeEvent（事件驱动中断：取消不属于新轨道的生成任务）
        boolean layoutChanged = prevTrackLayout != null
                && tracksLayoutChanged(prevTrackLayout, trackResult.tracks);
        previousTracks = trackResult.tracks;
        if (layoutChanged && eventBus != null) {
            publishTrackChange(trackResult.tracks);
        }
        // D8: 轨道增删 → 推送 track_created / track_closed（前端轨道提示）
        if (layoutChanged) {
            publishTrackLifecycle(prevTrackLayout, trackResult.tracks);
        }

        // Build TrackConfig for executor
        TrackConfig config = buildTrackConfig(trackResult.tracks, roundCount);

        // D8: SSE 广播 —— 回合开始 + 轨道任务分配 + 旁听角色
        if (sse != null) {
            sse.broadcastRoundStart(roundCount);
            sse.broadcastArbiterTask(roundCount, buildTaskList(config));
            for (Track track : config.getTracks()) {
                for (String silentAgent : track.getSilentAgents()) {
                    sse.broadcastAgentSilent(silentAgent);
                }
            }
        }

        // Step 2: Handle user input (convert to narration)
        String narration = null;
        String userCategory = "";
        if (userInput != null && !userInput.isBlank()) {
            if (userInput.startsWith("/")) {
                // Handle commands
                narration = handleCommand(userInput);
            } else if (speakerIsAgent) {
                // P0-2：玩家以自己角色身份发言 → 原文直接入史（角色说），不再主控旁白化；
                // 该角色本轮不参与 LLM 生成（避免同一句被角色再生成一遍的双声）
                Message agentSpoke = new Message(Message.Role.AGENT, speaker, userInput);
                agentSpoke.setRoundNumber(roundCount);
                memory.addMessage(agentSpoke);
                if (sse != null) {
                    sse.broadcastUserInput(sessionId, userInput, "human_discussion", speaker, roundCount);
                }
            } else {
                UserInputCategory cat = arbiter.classifyUserInput(
                    userInput, "always", memory.getShortTermContextRaw(2));
                userCategory = cat.name().toLowerCase();
                narration = arbiter.processUserInput(
                    userInput, cat, sceneDescription, agentNames, goals);
            }
            if (narration != null) {
                // P0-1：历史发言人不再硬编码 "me"——有显式发言人时用其名字，避免与同名角色混淆
                String speakerName = (speaker != null && !speaker.isBlank()) ? speaker : "me";
                Message userMsg = new Message(Message.Role.USER, speakerName, narration);
                userMsg.setRoundNumber(roundCount);
                memory.addMessage(userMsg);
                // D8: 非命令输入 → 推送 user_input 事件（前端回显主控输入）
                if (sse != null && !userInput.startsWith("/")) {
                    sse.broadcastUserInput(sessionId, narration, userCategory, speakerName, roundCount);
                }
            }
        }

        // Step 3: Execute all agents (parallel default; serial when roleplay.round.serial=true)
        Map<String, Agent> agentMap = new HashMap<>(agents);
        if (speakerIsAgent) {
            // P0-2：发言人角色本轮已“说过话”，从生成任务中排除（buildTasks 对缺失 agent 自动跳过）
            // P-0802-P2：排除键优先 speaker 命中名；speaker 为旧名时改用 playerId 解析出的当前名
            //（否则改名场景下解析名 agent 仍会参与 LLM 生成 → 同一句双声）
            String excludeName = agents.containsKey(speaker) ? speaker : null;
            if (excludeName == null && playerId != null && !playerId.isBlank() && identityService != null) {
                excludeName = identityService.resolveCharacterName(playerId).orElse(null);
            }
            if (excludeName != null) agentMap.remove(excludeName);
        }

        // P-0810-25-2：玩家角色（protagonist）不参与 LLM 生成 —— 玩家发言只来自玩家输入（/api/send），AI 绝不能代答。
        // 并行路径 buildTasks 对缺失 agent 自动跳过（AgentExecutor ~L306 `if (agent == null) continue`）；
        // 串行路径 executeRoundSerial 对缺失 agent 自动跳过（~L967 `if (!agentMap.containsKey(agentName)) continue`）——
        // 两条路径一次修复（与上方 P0-2 speaker 排除同款机制）。
        // 仅在 protagonist 非空时排除（director/free 模式 protagonist 为空 → 不影响 AI 自动对话）。
        if (!protagonist.isEmpty()) {
            agentMap.remove(protagonist);
        }
        if (responseAgents != null && !responseAgents.isEmpty()) {
            Set<String> allowed = responseAgents.stream()
                    .filter(Objects::nonNull).map(String::trim).filter(name -> !name.isEmpty())
                    .filter(agents::containsKey).collect(Collectors.toSet());
            agentMap.keySet().retainAll(allowed);
        }

        // P-0813-B：校准轮注入 —— 每 calibrate-every 个「AI 自主推进轮」向会话消息列表追加校准提醒
        // （layer0 前 3 条 + 反差 + buildDriftPreventionPrompt 角色关系，Author's Note 式防漂移）。
        // 规则：①仅一般模式（狼人杀/剧本杀走各自状态机，零影响）；②玩家发言轮（userInput 非空）不计数不触发
        // （仅轮次推进触发）；③0=禁用；④计数在 initSession/loadSession 重置（会话重建不误触发）。
        // 注入点在 agent 生成前 → 本轮 buildAgentContext 的【对话历史】即含校准块（尾部高影响）。
        boolean playerDrivenRound = userInput != null && !userInput.isBlank();
        if (calibrateEvery > 0 && isGeneralMode(mode) && !playerDrivenRound) {
            roundsSinceCalibration++;
            if (roundsSinceCalibration >= calibrateEvery) {
                injectCalibrationMessages();
                roundsSinceCalibration = 0;
            }
        }

        AgentExecutor.ExecutionResult execResult;
        List<Map<String, Object>> agentOutputs = new ArrayList<>();
        // D8: trackId → 轨道信息映射（agent_output 事件的 track_label / track_mode）
        Map<String, Map<String, Object>> trackById = new HashMap<>();
        for (Map<String, Object> t : trackResult.tracks) {
            trackById.put(String.valueOf(t.getOrDefault("id", "main")), t);
        }

        String memoryQuery = memoryQuery(userInput, userInterjection);
        if (serialRound) {
            // C-2 串行调度：按轨道顺序 × 轨道内 agent 顺序逐个生成，每个 agent 输出完成
            // 立即 memory.addMessage + SSE 推送 —— 后发言者 buildAgentContext 读到
            // 的对话历史即包含前面角色本轮已完成的发言（解决「同轮上下文不共享」）。
            execResult = executeRoundSerial(config, agentMap, trackById, agentOutputs, memoryQuery);
        } else {
            AgentExecutor.ContextBuilder ctxBuilder = (agentName, trackMode, trackId, cfg) ->
                buildAgentContext(agentName, trackMode, trackId, memoryQuery);
            execResult = executor.executeRound(config, agentMap, ctxBuilder);
        }

        // D1: 回合被取消（如 /api/stop）→ 立即返回，不再做 Arbiter 整合 / 落库
        if (execResult.cancelled()) {
            log.info("Round {} aborted by interrupt request", roundCount);
            if (sse != null) sse.broadcastStopped();
            return RoundResult.error("生成已中断");
        }

        // Step 4: Collect agent outputs（并行路径；串行路径已在 executeRoundSerial 内即时入史+收集）
        if (!serialRound) {
            for (AgentExecutor.AgentOutput output : execResult.outputs()) {
                if (output.isSuccess() && output.content() != null && !output.content().isBlank()) {
                    // P-0810-23-D2：AI 角色单次发言落盘前检测超长（中文字数 > 阈值 → 记录下一轮提醒）
                    maybeRecordOverLengthReminder(agents.get(output.agentName()), output.content());
                    Message agentMsg = new Message(Message.Role.AGENT, output.agentName(), output.content());
                    agentMsg.setRoundNumber(roundCount);
                    agentMsg.setTrackId(output.trackId());
                    agentMsg.setVisibleTo(output.visibleTo());
                    memory.addMessage(agentMsg);

                    Map<String, Object> outMap = new LinkedHashMap<>();
                    outMap.put("agent_name", output.agentName());
                    outMap.put("content", output.content());
                    outMap.put("track_id", output.trackId());
                    agentOutputs.add(outMap);

                    // D8: 每个 Agent 输出即时推送（前端 addAgentMsg 实时上屏）
                    if (sse != null) {
                        Map<String, Object> trackMap = trackById.getOrDefault(output.trackId(), Map.of());
                        sse.broadcastAgentOutput(
                            sessionId, output.agentName(), output.content(), output.trackId(),
                            String.valueOf(trackMap.getOrDefault("label", "")),
                            String.valueOf(trackMap.getOrDefault("mode", "merged")),
                            output.visibleTo());
                    }
                }
            }
        }

        // Step 5: Integrate outputs via Arbiter
        // P-0815-C：一般模式双人场景（实际参与 LLM 生成的 active 角色 ≤ 2）跳过主控整合 LLM——
        // next_round 预测（谁出场/谁隔离/顺序）在双人场景无意义，省一次主控 LLM 调用（每轮串行链省卡顿）。
        // P-0815-E：director 导演模式排除在本短路之外——导演模式是叙事驱动，主控旁白 + next_round 预测
        // 一体（叙事承接 + 谁出场/谁隔离连续决策），无论几人恒走 integrateOutputs，双人导演局同样要旁白推进。
        // 狼人杀/剧本杀（非一般模式）必须保留 integrateOutputs（GM 推进 + isWerewolf 分支），不能短路；
        // 一般模式多人（>2 active）保留预测闭环，零变化。
        Map<String, Object> integration;
        if (isGeneralMode(mode) && !"director".equals(mode) && isDuoScene(config)) {
            integration = new LinkedHashMap<>();
            integration.put("narration", "");
            integration.put("scene_progress", "");
            integration.put("next_round", Map.of());
            integration.put("chain_analysis", Map.of());
        } else {
            integration = arbiter.integrateOutputs(
                sceneDescription, trackResult.tracks, agentOutputs, "werewolf".equals(mode));
        }

        // P-0811-G：保存主控对下一轮的出场预测（next_round）→ 下轮 configureTracks 注入闭环
        // （「上轮预测→下轮执行」：谁出场/谁隔离由主控跨轮连续决策）
        Object nr = integration.get("next_round");
        if (nr instanceof Map<?, ?> m && !m.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nextRound = (Map<String, Object>) m;
            this.pendingNextRound = nextRound;
        } else {
            this.pendingNextRound = null;
        }

        String narrationText = (String) integration.getOrDefault("narration", "");
        // P-0811-G：一般模式（free/protagonist/multi_track）删除主控整合叙事——
        // 不入史、不推 arbiter_integrate SSE（角色发言即对话，主控不再附加 80-100 字总结旁白）；
        // P-0815-E：director 导演模式例外（叙事驱动）——主控旁白恢复入史（Role.ARBITER "主控"）+
        // SSE arbiter_integrate 推送（前端 Gal 旁白样式 / 经典视图 arbiter-box 均展示），
        // 与 next_round 预测一体（本批已保证 director 恒走 integrateOutputs 拿到 narration）；
        // 狼人杀/剧本杀保留（其 GM 整合推进阶段）。next_round 仍照常保存供下轮调度。
        if ((!isGeneralMode(mode) || "director".equals(mode))
                && narrationText != null && !narrationText.isBlank()) {
            Message arbiterMsg = new Message(Message.Role.ARBITER, "主控", narrationText);
            arbiterMsg.setRoundNumber(roundCount);
            memory.addMessage(arbiterMsg);
            // D8: 主控整合旁白推送（前端 addIntegration 上屏）
            if (sse != null) sse.broadcastArbiterIntegrate(roundCount, narrationText);
        }

        // Step 6: Check compression
        if (compressor.shouldCompress(roundCount)) {
            List<Map<String, String>> recentRaw = memory.getShortTermContextRaw(compressor.getCompressionInterval());
            CompressedChunk chunk = compressor.compress(recentRaw,
                Math.max(0, roundCount - compressor.getCompressionInterval()), roundCount);
            if (memory.hasSession()) {
                memory.getSession().getCompressedChunks().add(chunk);
            }
            // D8: 记忆压缩完成推送（前端系统提示）
            if (sse != null) sse.broadcastCompression(chunk.getSummary());
        }

        memory.incrementRound();
        String status = execResult.metrics() != null
            ? String.format("%d agents in %.0fms", agentOutputs.size(), execResult.metrics().totalRoundTimeMs())
            : agentOutputs.size() + " agents done";

        // Auto-save to history
        if (historyController != null && memory.hasSession()) {
            historyController.saveSession(sessionId, memory.getSession());
            // D8: 自动保存完成推送
            if (sse != null) sse.broadcastSaved();
        }

        // D8: 回合完成推送（前端 setRunning(false) + "第N轮完成"）
        if (sse != null) sse.broadcastRoundComplete(sessionId, roundCount);

        // P-0810-09: 场景目标进展判定（异步，不阻塞主流程；失败静默降级；仅一般模式有目标时触发）
        if (sceneGoalService != null && hasSceneGoals() && isGeneralMode(mode)) {
            submitGoalJudgment();
        }

        // P-0813-A：一般模式自动续轮 —— 本轮完成后延时调度下一轮（导演模式 AI 自主推进；
        // 玩家发言打断 pending、手动批量/非一般模式/剧情终局不触发；fire 时复查会话活跃与轮次未变）
        scheduleAutoContinue();

        return new RoundResult(status, agentOutputs, integration, trackResult.reasoning,
            execResult.metrics() != null ? execResult.metrics().toMap() : Map.of());
    }

    /** Run multiple automatic rounds. */
    public List<RoundResult> runAutoRounds(int count) {
        List<RoundResult> results = new ArrayList<>();
        // P0-2：会话已停止但角色仍在 → 自动恢复（用户点「三轮/自动」= 恢复运行，不再 0 轮静默）
        if (!running && !agents.isEmpty()) running = true;
        // P-0813-A：手动接管 → 取消遗留的自动续轮任务；批量进行中轮末不再调度（防批量后多跑一轮）
        cancelPendingAutoContinue();
        manualRoundBatch = true;
        try {
            for (int i = 0; i < count && running; i++) {
                results.add(runRound(null, null));
            }
        } finally {
            manualRoundBatch = false;
        }
        // D8: 自动对话结束推送（前端 "自动对话结束，共 N 轮"）
        if (sse != null) sse.broadcastAutoComplete(results.size());
        return results;
    }

    /**
     * P-0810-21-D：玩家发言候选话术生成（一般模式玩家回合可选项，任务 D1）。
     *
     * <p>LLM 路径：基于当前场景 + 最近对话 + 在场角色，生成 2-4 条第一人称候选
     * （覆盖推进 / 询问 / 情感 / 行动等方向）；失败（无 key / 超时 / 解析失败）→
     * 规则兜底通用候选，恒不抛（前端可选项永远可用）。
     *
     * @param count 请求条数（钳制到 2-4）
     */
    public List<String> suggestPlayerLines(int count) {
        int n = Math.max(2, Math.min(4, count));
        if (agents.isEmpty()) return List.of();
        // LLM 路径（callJson 结构化输出 {"suggestions":[...]}）
        try {
            StringBuilder recent = new StringBuilder();
            List<Message> msgs = getConversationMessages();
            for (int i = Math.max(0, msgs.size() - 8); i < msgs.size(); i++) {
                Message m = msgs.get(i);
                recent.append("[").append(m.getName()).append("] ").append(m.getContent()).append("\n");
            }
            String prompt = "你是这款文字角色扮演游戏的玩家助手。当前场景："
                + (sceneDescription == null || sceneDescription.isBlank() ? "（未设置）" : sceneDescription)
                + "\n在场角色：" + String.join("、", agents.keySet())
                + "\n最近对话：\n" + (recent.length() == 0 ? "（尚无对话）" : recent)
                + "\n请以玩家视角，给出 " + n + " 条合适的发言候选（第一人称、简短口语、每条约 5-20 字，"
                + "覆盖：推进剧情 / 询问信息 / 表达情感 / 采取行动 等不同方向）。"
                + "只输出 JSON，格式 {\"suggestions\":[\"...\",\"...\"]}，不要输出其他任何文字。";
            Map<String, Object> out = llmClient.callJson(prompt, 800);
            Object raw = out != null ? out.get("suggestions") : null;
            if (raw instanceof List<?> list && !list.isEmpty()) {
                List<String> result = new ArrayList<>();
                for (Object o : list) {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty() && s.length() <= 80) result.add(s);
                    if (result.size() >= n) break;
                }
                if (result.size() >= 2) return result;
            }
        } catch (Exception e) {
            // 失败静默走规则兜底
            log.warn("suggestPlayerLines LLM 失败，规则兜底: {}", e.getMessage());
        }
        return fallbackSuggestions(n);
    }

    /** 规则兜底候选（零 LLM 零成本；LLM 失败/无 key 时恒可用）。 */
    private List<String> fallbackSuggestions(int n) {
        List<String> base = new ArrayList<>();
        String first = agents.keySet().stream().findFirst().orElse("");
        if (!first.isEmpty()) {
            base.add("询问" + first + "的想法");
        } else {
            base.add("继续刚才的话题");
        }
        base.add("聊聊我自己的事");
        base.add("换个话题，看看大家怎么接");
        base.add("观察一下周围的环境");
        return base.subList(0, Math.min(n, base.size()));
    }

    /**
     * P-0810-14：起局后自动触发第一轮（AI 开场白）。
     * <ul>
     *   <li><b>范围</b>：仅一般模式（free/protagonist/multi_track/director）——狼人杀/剧本杀各自有剧本流程，不触发；</li>
     *   <li><b>异步</b>：CompletableFuture.runAsync 后台执行，不阻塞 init/startScene 响应；</li>
     *   <li><b>幂等</b>：每会话仅触发一次（autoFirstRoundFired，initSession 重置）；</li>
     *   <li><b>链路</b>：复用 {@link #runRound(String, String)} 全路径（userInput=null 纯 AI 开场）——
     *       SSE 推 round_start → arbiter_task → agent_output（逐条）→ arbiter_integrate → round_complete；</li>
     *   <li><b>并发</b>：runRound 为方法级同步，与 /api/send 串行互斥，开场轮不会与玩家发言交错。</li>
     * </ul>
     */
    public synchronized void triggerAutoFirstRound() {
        if (!isGeneralMode(mode) || autoFirstRoundFired) return;
        autoFirstRoundFired = true;
        CompletableFuture.runAsync(() -> {            try {
                runRound(null, null);
            } catch (Exception e) {
                // 自动开场失败不抛给调用方（init/startScene 已返回）；下一轮玩家发言仍可正常驱动
                log.warn("起局自动第一轮失败: session={} err={}", sessionId, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0813-A: 自动续轮（一般模式每轮完成后延时自动跑下一轮）
    // ═══════════════════════════════════════════════════════════

    /**
     * 本轮完成后调度下一轮（延时 autoContinueMs；由 runRound 末尾调用，调用时持有方法锁）。
     *
     * <p>守卫（任一不满足即不调度）：
     * <ul>
     *   <li>{@code autoContinueMs > 0}（0=禁用）；</li>
     *   <li>仅一般模式（free/protagonist/multi_track/director）——狼人杀/剧本杀走各自状态机，不误触发；</li>
     *   <li>非手动批量（runTurns/runAutoRounds 进行中 → 防批量后多跑一轮）；</li>
     *   <li>会话仍活跃（running + agents 非空）；</li>
     *   <li>未达剧情终局（{@link #goalsAchieved()}，与 runTurns 同款保守启发式）。</li>
     * </ul>
     *
     * <p>并发：替换式调度 —— 每会话至多一个 pending；玩家发言（runRound 入口取消）/stop/新会话 init 会取消它；
     * 调度与取消都在方法锁内完成，与 runRound 天然串行。
     */
    private void scheduleAutoContinue() {
        if (playbackDriven) {
            // P-0814-A：点击驱动 —— auto-continue-ms 被忽略（不再定时自续）；仅一般模式/活跃会话/
            // 未达终局时置「等待播出完毕」标志。下一轮由 POST /api/simulation/playback_done
            // → onPlaybackDone() 驱动；玩家发言/stop/手动批量/新会话清除（见各清理点）。
            if (!isGeneralMode(mode) || manualRoundBatch) return;
            if (!running || agents.isEmpty()) return;
            if (goalsAchieved()) return;
            awaitingPlayback = true;
            return;
        }
        if (autoContinueMs <= 0 || !isGeneralMode(mode) || manualRoundBatch) return;
        if (!running || agents.isEmpty()) return;
        if (goalsAchieved()) return;
        cancelPendingAutoContinueLocked();
        int scheduledAtRound = roundCount;
        autoContinueScheduledRound = scheduledAtRound;
        try {
            pendingAutoContinue = AUTO_CONTINUE_SCHEDULER.schedule(this::fireAutoContinue,
                    autoContinueMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 调度器已关闭（测试收尾/进程退出）→ 静默放弃续轮
            pendingAutoContinue = null;
            autoContinueScheduledRound = 0;
        }
    }

    /**
     * 自动续轮任务执行体（调度线程）。触发前复查：
     * ① pending 仍在（未被玩家发言/stop 取消）；
     * ② 会话仍活跃、仍为一般模式、续轮未禁用；
     * ③ 轮次未被外部推进 —— 玩家发言/手动驱动若先执行了 runRound，roundCount 已变 → 放弃本次续轮（防重复轮）。
     *
     * <p>方法级同步 + runRound 同步 → 同会话续轮与玩家发言天然串行互斥；
     * 续轮完成后 runRound 末尾会再次 {@link #scheduleAutoContinue()}（自续）。
     */
    private synchronized void fireAutoContinue() {
        ScheduledFuture<?> task = pendingAutoContinue;
        if (task == null || task.isCancelled()) return;
        if (autoContinueMs <= 0 || !isGeneralMode(mode) || !running || agents.isEmpty()) return;
        if (autoContinueScheduledRound != roundCount) return;
        pendingAutoContinue = null;
        autoContinueScheduledRound = 0;
        awaitingPlayback = false; // P-0814-A：旧定时路径触发时同步清除等待标志（防双路径互切残留）
        try {
            runRound(null, null);
        } catch (Exception e) {
            // 续轮失败不向上抛（调度线程）；下一轮调度由 runRound 末尾自然决定
            log.warn("自动续轮失败: session={} err={}", sessionId, e.getMessage());
        }
    }

    /**
     * P-0814-A：播放驱动 —— 前端「播出完毕」信号入口（POST /api/simulation/playback_done 无 group_id 时）。
     *
     * <p>守卫（任一不满足即返回 false 不推进）：
     * <ul>
     *   <li>playback-driven 模式开启；</li>
     *   <li>当前处于「等待播出完毕」状态（一轮已生成完、尚未被推进）；</li>
     *   <li>会话仍活跃（running + agents 非空）、仍为一般模式、未达剧情终局。</li>
     * </ul>
     *
     * <p>方法级同步 + runRound 同步 → 与玩家发言/续轮天然串行互斥；重复信号（未等待时）直接 no-op。
     *
     * @return true=已推进下一轮；false=未处于等待态（重复信号/非播放驱动/会话不活跃），信号被忽略
     */
    public synchronized boolean onPlaybackDone() {
        if (!playbackDriven) return false;
        if (!awaitingPlayback) return false;
        if (!running || agents.isEmpty()) return false;
        if (!isGeneralMode(mode)) return false;
        if (goalsAchieved()) return false;
        awaitingPlayback = false;
        try {
            runRound(null, null);
        } catch (Exception e) {
            // 续轮失败不向上抛（REST 线程）；下一轮等待状态由 runRound 末尾自然重建
            log.warn("播放驱动续轮失败: session={} err={}", sessionId, e.getMessage());
        }
        return true;
    }

    /** P-0814-A: 测试/监控用 —— 当前是否处于「等待播出完毕」状态。 */
    public boolean isAwaitingPlayback() {
        return awaitingPlayback;
    }

    /** 取消待执行的自动续轮任务（stop/新会话 init/loadSession 等外部入口调用；防泄漏）。 */
    public void cancelPendingAutoContinue() {
        synchronized (this) {
            cancelPendingAutoContinueLocked();
        }
    }

    /** P-0813-A: 测试/监控用 —— 是否存在待执行的自动续轮任务。 */
    boolean hasPendingAutoContinue() {
        return pendingAutoContinue != null;
    }

    /** 取消待执行的自动续轮任务（调用方已持有方法锁时用本变体，避免重入开销）。 */
    private void cancelPendingAutoContinueLocked() {
        ScheduledFuture<?> task = pendingAutoContinue;
        pendingAutoContinue = null;
        autoContinueScheduledRound = 0;
        if (task != null) {
            // false=不中断已开始执行的 fire 线程（fire 自身有轮次复查守卫，不会跑多余轮）
            task.cancel(false);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Agent context building
    // ═══════════════════════════════════════════════════════════

    private String buildAgentContext(String agentName, String trackMode, String trackId, String memoryQuery) {
        Agent agent = agents.get(agentName);
        if (agent == null) return "";

        List<String> contextParts = new ArrayList<>();

        // Identity lock — keep the agent strictly in character
        Persona persona = agent.getPersona();
        String personaDesc = persona != null ? persona.getPersonaDesc() : "";
        StringBuilder identity = new StringBuilder("【你的身份】\n你是 ").append(agentName).append("。");
        if (personaDesc != null && !personaDesc.isEmpty()) {
            identity.append("\n").append(personaDesc).append("。");
        }
        identity.append("\n你只能以该角色的身份、语气、性格说话，不得跳出角色。");
        contextParts.add(identity.toString());

        // Scene context
        if (sceneDescription != null && !sceneDescription.isEmpty()) {
            contextParts.add("【当前场景】\n" + sceneDescription);
        }

        // D5: 剧本杀角色卡 —— 每个角色只看到自己的 secret（仿狼人杀"身份只在自家 prompt"）
        if ("script".equals(mode) && scriptGame != null) {
            StringBuilder scriptCard = new StringBuilder("【剧本杀·角色卡】\n");
            String role = scriptGame.getRoleOf(agentName);
            if (role != null && !role.isEmpty()) {
                scriptCard.append("你扮演的角色：").append(role).append("\n");
            }
            String secret = scriptGame.getSecretFor(agentName);
            if (secret != null && !secret.isEmpty()) {
                scriptCard.append("【你的秘密】只有你自己知道：").append(secret)
                          .append("。严守秘密——除非剧情需要，绝不向任何人透露，也不要主动提及这是剧本设定。");
            }
            if (scriptGame.phase != null) {
                scriptCard.append("\n当前阶段：").append(scriptPhaseLabel(scriptGame.phase));
            }
            contextParts.add(scriptCard.toString());
        }

        // P-0802-F: 狼人杀角色卡 —— 每个角色只见自己身份 + 狼人互认（对齐"身份只在自家 prompt"纪律）
        if ("werewolf".equals(mode) && werewolfGame != null) {
            StringBuilder wwCard = new StringBuilder("【狼人杀·角色卡】\n");
            WerewolfService.Role role = werewolfGame.roles.get(agentName);
            if (role != null) {
                wwCard.append("你的身份：").append(werewolfRoleLabel(role)).append("。");
                if (role == WerewolfService.Role.WEREWOLF) {
                    List<String> mates = werewolfGame.alive.stream()
                            .filter(p -> !p.equals(agentName) && werewolfGame.roles.get(p) == WerewolfService.Role.WEREWOLF)
                            .toList();
                    if (!mates.isEmpty()) {
                        wwCard.append("你的狼人同伴：").append(String.join("、", mates)).append("。");
                    }
                    wwCard.append("隐藏身份，白天伪装成普通玩家参与讨论，误导他人、保护狼队。");
                } else if (role == WerewolfService.Role.SEER) {
                    wwCard.append("你每晚可以查验一名玩家的身份。白天利用查验结果引导好人找出狼人，注意隐藏身份避免被狼刀。");
                } else if (role == WerewolfService.Role.WITCH) {
                    wwCard.append("你有一瓶解药和一瓶毒药。白天隐藏身份，暗中协助好人阵营。");
                } else if (role == WerewolfService.Role.HUNTER) {
                    wwCard.append("你被淘汰时可以开枪带走一名玩家。白天隐藏身份，谨慎发言。");
                } else {
                    wwCard.append("你是普通村民，白天通过发言、试探与推理找出狼人。");
                }
                wwCard.append("\n当前阶段：").append(werewolfPhaseLabel(werewolfGame.phase));
            }
            contextParts.add(wwCard.toString());
        }

        // Track info
        contextParts.add("【轨道】\n" + trackId + " (" + trackMode + "模式)");

        // Summary context
        // 压缩摘要没有逐角色 visibleTo 元数据；只允许在完整 merged 轨道读取，
        // 防止 WEAK/ISOLATED 经长期记忆反向取回不可感知的秘密。
        if ("merged".equalsIgnoreCase(trackMode)) {
            String summary = memory.getSummaryContext();
            if (!summary.isEmpty()) contextParts.add(summary);
        }

        // 相关性检索只补充不在短期窗口内的旧消息；非 merged 轨道不检索无可见性标记的压缩块。
        String relatedMemory = memory.getRelevantMemoryContext(agentName, memoryQuery, 3, 2, 30,
                "merged".equalsIgnoreCase(trackMode));
        if (!relatedMemory.isEmpty()) contextParts.add(relatedMemory);

        // Recent messages visible to this agent
        List<Message> visible = memory.getAgentContext(agentName, 30);
        if (!visible.isEmpty()) {
            StringBuilder history = new StringBuilder("【对话历史】\n");
            for (Message m : visible) {
                history.append("[").append(m.getName()).append("]: ")
                       .append(m.getContent()).append("\n");
            }
            contextParts.add(history.toString());
        }

        return String.join("\n\n", contextParts);
    }

    /** 优先使用本轮玩家输入检索旧记忆；自动轮没有输入时按场景召回。 */
    private String memoryQuery(String userInput, String userInterjection) {
        if (userInput != null && !userInput.isBlank()) return userInput;
        if (userInterjection != null && !userInterjection.isBlank()) return userInterjection;
        return sceneDescription == null ? "" : sceneDescription;
    }

    // ═══════════════════════════════════════════════════════════
    //  C-2 串行调度（roleplay.round.serial=true）
    // ═══════════════════════════════════════════════════════════

    /**
     * C-2 串行调度（roleplay.round.serial=true）：
     * 按轨道顺序 × 轨道内 agent 顺序逐个生成（PLAYER > DM > NPC 优先级，与
     * AgentExecutor.buildTasks 的排序逻辑一致），每个 agent 输出完成**立即**
     * memory.addMessage + SSE 推送 —— 后发言者 buildAgentContext 读
     * memory.getAgentContext 即包含前面角色本轮已完成的发言（解决「同轮上下文不共享」断点）。
     *
     * <p>D1 中断语义保留：每个 agent 注册 AgentTask + CancellationToken
     * （register/toRunning/toDone/unregister），/api/stop → cancelAll → 令牌置位
     * → 生成前检查点抛 TaskCancelledException → 中断循环返回 cancelled
     * （与并行路径共用调用方处理）。
     *
     * <p>性能代价：n agents 从 1× LLM delay → n× LLM delay（主人方案明确「不限速」，
     * 开关默认 false 保持并行，仅按需开启）。
     */
    private AgentExecutor.ExecutionResult executeRoundSerial(
            TrackConfig config,
            Map<String, Agent> agentMap,
            Map<String, Map<String, Object>> trackById,
            List<Map<String, Object>> agentOutputs,
            String memoryQuery) {

        Instant roundStart = Instant.now();
        List<AgentExecutor.AgentOutput> outputs = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        boolean cancelled = false;
        long taskSeq = 0;

        // 顺序：轨道顺序 × 轨道内 agent 顺序，按优先级 PLAYER > DM > NPC 稳定排序
        List<AgentExecutor.AgentTask> ordered = new ArrayList<>();
        for (Track track : config.getTracks()) {
            String trackMode = track.getMode().name().toLowerCase();
            for (String agentName : track.getActiveAgents()) {
                if (!agentMap.containsKey(agentName)) continue; // P0-2 speaker 排除
                AgentExecutor.Priority priority = computeSerialPriority(agentName);
                ordered.add(new AgentExecutor.AgentTask(
                        agentName, track.getId(), trackMode, priority,
                        List.copyOf(track.getActiveAgents()), null));
            }
        }
        ordered.sort(Comparator.comparingInt(t -> t.priority().ordinal()));

        for (AgentExecutor.AgentTask task : ordered) {
            // D1: 每步前检查 —— stop() 置 running=false 则中断循环
            if (!running) { cancelled = true; break; }
            Agent agent = agentMap.get(task.agentName());
            if (agent == null) continue;

            // D1: 注册中断任务（/api/stop → cancelAll 可取消生成中令牌）
            com.roleplay.engine.interrupt.AgentTask it = new com.roleplay.engine.interrupt.AgentTask(
                    task.agentName() + "_dialogue_" + (++taskSeq),
                    task.agentName(), TaskType.DIALOGUE,
                    Map.of("trackId", task.trackId(), "trackMode", task.trackMode()));
            interruptManager.register(it);
            it.toRunning();
            Instant taskStart = Instant.now();
            try {
                CancellationToken token = it.getCancelToken();
                token.checkpoint(); // 生成前检查点
                // 上下文在生成时构建：此时 memory 已含本轮前面角色已完成的发言
                String context = buildAgentContext(task.agentName(), task.trackMode(), task.trackId(), memoryQuery);
                token.checkpoint(); // 上下文构建后检查点
                // P-0802-M：后端真·流式 —— 增量经 SSE agent_token 逐片推送（前端逐字渲染）；
                // 完整内容仍由下方 broadcastAgentOutput 结算（流式失败自动降级非流式，内容不丢）
                String content = agent.generateWithContextStream(context, token, delta -> {
                    if (sse != null && delta != null && !delta.isEmpty()) {
                        Map<String, Object> trackMap = trackById.getOrDefault(task.trackId(), Map.of());
                        sse.broadcastAgentToken(
                            sessionId, task.agentName(), delta, task.trackId(),
                            String.valueOf(trackMap.getOrDefault("label", "")),
                            String.valueOf(trackMap.getOrDefault("mode", "merged")));
                    }
                });
                long elapsed = Duration.between(taskStart, Instant.now()).toMillis();
                it.toDone();
                interruptManager.unregister(it.getId());

                if (content != null && !content.isBlank()) {
                    // P-0810-23-D2：AI 角色单次发言落盘前检测超长（中文字数 > 阈值 → 记录下一轮提醒）
                    maybeRecordOverLengthReminder(agent, content);
                    // 即时入史：后发言者 buildAgentContext 立即可见
                    Message agentMsg = new Message(Message.Role.AGENT, task.agentName(), content);
                    agentMsg.setRoundNumber(roundCount);
                    agentMsg.setTrackId(task.trackId());
                    agentMsg.setVisibleTo(task.visibleTo());
                    memory.addMessage(agentMsg);

                    Map<String, Object> outMap = new LinkedHashMap<>();
                    outMap.put("agent_name", task.agentName());
                    outMap.put("content", content);
                    outMap.put("track_id", task.trackId());
                    agentOutputs.add(outMap);

                    // D8: 每个 Agent 输出即时推送（前端 addAgentMsg 实时上屏）
                    if (sse != null) {
                        Map<String, Object> trackMap = trackById.getOrDefault(task.trackId(), Map.of());
                        sse.broadcastAgentOutput(
                            sessionId, task.agentName(), content, task.trackId(),
                            String.valueOf(trackMap.getOrDefault("label", "")),
                            String.valueOf(trackMap.getOrDefault("mode", "merged")),
                            task.visibleTo());
                    }
                    outputs.add(new AgentExecutor.AgentOutput(
                            task.agentName(), content, task.trackId(), task.visibleTo(), elapsed, null));
                    latencies.add(elapsed);
                }
            } catch (TaskCancelledException e) {
                // D1: 任务被取消 → 中断循环（软停止的未完成内容保存到任务上）
                cancelled = true;
                it.saveUnfinished(e.getPartial());
                interruptManager.unregister(it.getId());
                log.info("Agent {} serial task cancelled: {}", task.agentName(), e.getReason());
                break;
            } catch (Exception e) {
                // 与并行路径一致：失败不中断整轮，输出占位（isSuccess=false，不入史）
                long elapsed = Duration.between(taskStart, Instant.now()).toMillis();
                interruptManager.markFailed(it.getId(), e.getMessage());
                interruptManager.unregister(it.getId());
                outputs.add(new AgentExecutor.AgentOutput(
                        task.agentName(),
                        "[" + task.agentName() + " 走神了: " + e.getMessage() + "]",
                        task.trackId(), List.of(), elapsed, e.getMessage()));
                log.warn("Agent {} serial failed: {}", task.agentName(), e.getMessage());
            }
        }

        double totalTimeMs = Duration.between(roundStart, Instant.now()).toMillis();
        double avgLatency = latencies.isEmpty() ? 0
                : latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        AgentExecutor.ExecutorMetrics metrics = new AgentExecutor.ExecutorMetrics(
                ordered.size(), 1, avgLatency, maxLatency, totalTimeMs);

        log.info("Agent round complete (serial): {} agents in {}ms (avg {}ms/agent){}",
                outputs.size(), Math.round(totalTimeMs), Math.round(avgLatency),
                cancelled ? " [CANCELLED]" : "");
        return new AgentExecutor.ExecutionResult(outputs, metrics, cancelled);
    }

    /** 串行调度优先级（与 AgentExecutor.computePriority 同规则：PLAYER > DM > NPC）。 */
    private AgentExecutor.Priority computeSerialPriority(String agentName) {
        if (agentName.equals(protagonist) || agentName.equals("me")) {
            return AgentExecutor.Priority.PLAYER;
        }
        if (agentName.equals(directorCharacter)) {
            return AgentExecutor.Priority.DM;
        }
        return AgentExecutor.Priority.NPC;
    }

    // ═══════════════════════════════════════════════════════════
    //  Commands
    // ═══════════════════════════════════════════════════════════

    private String handleCommand(String cmd) {
        String stripped = cmd.strip().toLowerCase();
        if (stripped.startsWith("/mode")) {
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                mode = parts[1].trim();
                return "【系统】模式切换为: " + mode;
            }
            return "【系统】当前模式: " + mode;
        }
        if (stripped.startsWith("/goal") || stripped.startsWith("/goals")) {
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                goals = Arrays.asList(parts[1].trim().split("[,，]"));
                return "【系统】剧情目标已更新: " + String.join(", ", goals);
            }
            return "【系统】当前目标: " + (goals.isEmpty() ? "无" : String.join(", ", goals));
        }
        if (stripped.startsWith("/protagonist")) {
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                protagonist = parts[1].trim();
                mode = "protagonist";
                return "【系统】主角模式，主角: " + protagonist;
            }
            return "【系统】当前主角: " + (protagonist.isEmpty() ? "未设置" : protagonist);
        }
        if (stripped.startsWith("/restrict")) {
            restrictedAgents.clear();
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                restrictedAgents.addAll(Arrays.asList(parts[1].trim().split("[,，]")));
                return "【系统】禁止出场: " + String.join(", ", restrictedAgents);
            }
            return "【系统】禁止列表已清空";
        }
        if (stripped.equals("/stop") || stripped.equals("/end")) {
            running = false;
            return "【系统】对话已停止";
        }
        if (stripped.equals("/status")) {
            return "【系统状态】模式=" + mode + " 轮次=" + roundCount
                + " Agent数=" + agents.size() + " 运行中=" + running;
        }
        return "【系统】未知命令: " + cmd;
    }

    // ═══════════════════════════════════════════════════════════
    //  Config mutations
    // ═══════════════════════════════════════════════════════════

    public void setMode(String mode) { this.mode = mode; }

    /** P-0813-A: 测试钩子 —— 显式设置自动续轮延时（毫秒；0=禁用）。
     *  生产值来自 roleplay.round.auto-continue-ms（Spring bean 走 @Value；
     *  SessionRegistry new 出来的会话实例由 SessionRegistry 注入）。 */
    public void setAutoContinueMs(long autoContinueMs) { this.autoContinueMs = autoContinueMs; }

    /** P-0814-A: 测试/SessionRegistry 注入 —— 点击驱动开关（true=轮完即停等播出完毕信号；false=旧定时续轮）。 */
    public void setPlaybackDriven(boolean playbackDriven) { this.playbackDriven = playbackDriven; }

    /** P-0814-A: 当前是否点击驱动模式。 */
    public boolean isPlaybackDriven() { return playbackDriven; }

    /** P-0813-B: 测试钩子 —— 显式设置校准轮间隔（0=禁用；默认 6）。
     *  生产值来自 roleplay.round.calibrate-every（Spring bean 走 @Value；
     *  SessionRegistry new 出来的会话实例由 SessionRegistry 注入，同 autoContinueMs 模式）。 */
    public void setCalibrateEvery(int calibrateEvery) { this.calibrateEvery = calibrateEvery; }
    public int getCalibrateEvery() { return calibrateEvery; }
    public String getMode() { return mode; }

    /** C-2: 串行调度开关（roleplay.round.serial，默认 false）。测试/运行时切换用。 */
    public void setSerialRound(boolean serialRound) { this.serialRound = serialRound; }
    public boolean isSerialRound() { return serialRound; }

    // ── P-0810-23-D2：AI 角色发言超长提醒（仅下一轮生效，玩家无感知） ────────

    /** 设置提醒阈值（测试/运行时覆盖；<=0 关闭检测）。 */
    public void setRemindThreshold(int remindThreshold) { this.remindThreshold = remindThreshold; }

    public int getRemindThreshold() { return remindThreshold; }

    /** 统计文本中的中文字符（CJK 统一表意文字，UnicodeScript.HAN）数。 */
    static int countChineseChars(String text) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) n++;
        }
        return n;
    }

    /**
     * D2：AI 角色单次发言（agent_output 落盘前）中文字符数超过阈值 →
     * 记录待发提醒（下一轮该角色构建系统提示时注入，之后自动清除；不持续、不广播旁白、无前端 UI）。
     */
    private void maybeRecordOverLengthReminder(Agent agent, String content) {
        if (agent == null || content == null || content.isBlank() || remindThreshold <= 0) return;
        if (countChineseChars(content) > remindThreshold) {
            agent.setPendingReminder("你上一轮发言超过 " + remindThreshold + " 字，本轮请精简输出");
        }
    }

    public void setProtagonist(String name) { this.protagonist = name; }
    public void setDirectorCharacter(String name) { this.directorCharacter = name; }
    public void setGoals(List<String> goals) { this.goals = goals; }
    public List<String> getGoals() { return goals; }
    public int getRoundCount() { return roundCount; }
    public void setSceneDescription(String desc) { this.sceneDescription = desc; }

    /** D5: 剧本杀模式 —— 绑定当前剧本局，使 secrets 能在 buildAgentContext 注入对应角色。 */
    public void setScriptGame(ScriptGameService.ScriptGame game) {
        this.scriptGame = game;
        log.info("Script game registered to router ({} secrets)", game != null ? game.getSecrets().size() : 0);
    }

    /** D5: 解绑剧本局（新剧本 init 时会自动覆盖，通常无需手动调用）。 */
    public void clearScriptGame() {
        this.scriptGame = null;
    }

    /** P-0802-F: 狼人杀模式 —— 绑定当前狼人杀局，使身份（含狼人互认）能在 buildAgentContext 注入对应角色。 */
    public void setWerewolfGame(WerewolfService.GameState game) {
        this.werewolfGame = game;
        log.info("Werewolf game registered to router ({} players)", game != null ? game.alive.size() : 0);
    }

    /** P-0802-F: 解绑狼人杀局。 */
    public void clearWerewolfGame() {
        this.werewolfGame = null;
    }

    /** 狼人杀角色中文标签（供角色卡上下文注入）。 */
    private static String werewolfRoleLabel(WerewolfService.Role role) {
        return switch (role) {
            case WEREWOLF -> "狼人";
            case SEER -> "预言家";
            case WITCH -> "女巫";
            case HUNTER -> "猎人";
            case VILLAGER -> "村民";
        };
    }

    /** 狼人杀阶段中文标签（供角色卡上下文注入）。 */
    private static String werewolfPhaseLabel(WerewolfService.Phase phase) {
        return switch (phase) {
            case NIGHT -> "夜晚（选择行动或闭眼等待）";
            case DAY_DISCUSS -> "白天讨论（发言推理找出狼人）";
            case DAY_VOTE -> "投票阶段（选出你怀疑的狼人）";
            case JUDGMENT -> "判定中";
            case ENDED -> "游戏已结束";
        };
    }

    /** 剧本杀阶段中文标签（供角色卡上下文注入）。 */
    private String scriptPhaseLabel(ScriptGameService.Phase phase) {
        return switch (phase) {
            case SETUP -> "准备阶段";
            case INVESTIGATION -> "搜证阶段（可搜索地点收集线索）";
            case DISCUSSION -> "讨论阶段（交流线索、指认嫌疑人）";
            case VOTE -> "投票阶段（选出你怀疑的真凶）";
            case REVEAL -> "揭晓阶段（真相即将公布）";
            case ENDED -> "游戏已结束";
        };
    }

    public synchronized void addAgent(String name, Persona persona) {
        if (worldOwnedAgentNames.contains(name)) {
            throw new IllegalStateException("world-owned agent cannot be overwritten: " + name);
        }
        agents.put(name, new Agent(persona, "agent", llmClient));
        refreshAgentRosterState();
        // D8: 角色加入推送
        if (sse != null) sse.broadcastAgentAdded(name, "active");
    }

    public synchronized void removeAgent(String name) {
        if (worldOwnedAgentNames.contains(name)) {
            throw new IllegalStateException("world-owned agent cannot be removed by legacy API: " + name);
        }
        agents.remove(name);
        refreshAgentRosterState();
        // D8: 角色离开推送
        if (sse != null) sse.broadcastAgentRemoved(name);
    }

    /** 世界运行时专用：不发旧的全局 SSE，由 WorldRuntimeService 负责按 session 定向广播。 */
    public synchronized void addWorldAgent(String name, Persona persona) {
        if (agents.containsKey(name) || worldSuspendedAgents.containsKey(name)) {
            throw new IllegalStateException("agent already exists: " + name);
        }
        agents.put(name, new Agent(persona, "agent", llmClient));
        worldOwnedAgentNames.add(name);
        refreshAgentRosterState();
    }

    /** 文本模式降载：移动原 Agent 对象而非重建，保留内部记忆与状态。 */
    public synchronized boolean suspendWorldAgent(String name) {
        Agent agent = agents.remove(name);
        if (agent == null) return worldSuspendedAgents.containsKey(name);
        worldSuspendedAgents.put(name, agent);
        refreshAgentRosterState();
        return true;
    }

    public synchronized boolean resumeWorldAgent(String name) {
        if (agents.containsKey(name)) return false;
        Agent agent = worldSuspendedAgents.remove(name);
        if (agent == null) return false;
        agents.put(name, agent);
        refreshAgentRosterState();
        return true;
    }

    public synchronized boolean removeWorldAgent(String name) {
        boolean removed = agents.remove(name) != null;
        removed |= worldSuspendedAgents.remove(name) != null;
        worldOwnedAgentNames.remove(name);
        refreshAgentRosterState();
        return removed;
    }

    public boolean isWorldSuspendedAgent(String name) {
        return name != null && worldSuspendedAgents.containsKey(name);
    }

    public boolean isWorldOwnedAgent(String name) {
        return name != null && worldOwnedAgentNames.contains(name);
    }

    private void refreshAgentRosterState() {
        List<String> agentNames = new ArrayList<>(agents.keySet());
        state.put("agents", agentNames);
        state.put("agent_count", agentNames.size());
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //  Arbiter input sanitization (Phase 1 Track isolation)
    // ═══════════════════════════════════════════════════════════

    /**
     * If the previous round's track layout contains a non-public track (WEAK/ISOLATED)
     * alongside others, strip ALL conversation content from the Arbiter's history input
     * and keep only per-track structural one-liners (track id + mode + participants).
     * This prevents the Arbiter — and anything downstream — from reading isolated
     * tracks' full conversation. Single-track / all-public layouts pass through unchanged.
     */
    private String sanitizeSummaryForArbiter(String historySummary, List<Map<String, Object>> trackMaps) {
        if (historySummary == null || historySummary.isBlank()) return historySummary;
        if (trackMaps == null || trackMaps.size() < 2) return historySummary;

        boolean hasNonPublicTrack = false;
        for (Map<String, Object> t : trackMaps) {
            String mode = String.valueOf(t.getOrDefault("mode", "merged")).toLowerCase();
            if (!"merged".equals(mode)) { hasNonPublicTrack = true; break; }
        }
        if (!hasNonPublicTrack) return historySummary;

        StringBuilder sb = new StringBuilder("【轨道概况（多轨道隔离，仅保留结构摘要）】\n");
        for (int i = 0; i < trackMaps.size(); i++) {
            Map<String, Object> t = trackMaps.get(i);
            sb.append(i + 1).append(". 轨道「")
              .append(t.getOrDefault("label", "轨道" + (i + 1)))
              .append("」(模式=")
              .append(t.getOrDefault("mode", "merged"))
              .append("): 参与者=")
              .append(t.getOrDefault("agents", List.of()))
              .append("\n");
        }
        return sb.toString();
    }

    private TrackConfig buildTrackConfig(List<Map<String, Object>> trackMaps, int round) {
        TrackConfig config = new TrackConfig(round);
        for (Map<String, Object> m : trackMaps) {
            Track track = Track.fromMap(m);
            config.addTrack(track);
        }
        return config;
    }

    // ═══════════════════════════════════════════════════════════
    //  D1: TrackChangeEvent（需求文档第八条 §七：轨道变化 → 取消失效任务）
    // ═══════════════════════════════════════════════════════════

    /** 相邻两轮的轨道布局是否不同（轨道 id / 参与者集合任一变化）。 */
    private boolean tracksLayoutChanged(List<Map<String, Object>> oldTracks,
                                        List<Map<String, Object>> newTracks) {
        if (oldTracks.size() != newTracks.size()) return true;
        for (int i = 0; i < oldTracks.size(); i++) {
            Map<String, Object> a = oldTracks.get(i);
            Map<String, Object> b = newTracks.get(i);
            if (!Objects.equals(a.get("id"), b.get("id"))) return true;
            if (!Objects.equals(a.get("agents"), b.get("agents"))) return true;
        }
        return false;
    }

    /** 发布 TrackChangeEvent：新轨道 id 集合 + 轨道参与者映射。 */
    private void publishTrackChange(List<Map<String, Object>> trackMaps) {
        List<String> trackIds = new ArrayList<>();
        Map<String, List<String>> trackAgents = new LinkedHashMap<>();
        for (Map<String, Object> m : trackMaps) {
            String id = String.valueOf(m.getOrDefault("id", "track"));
            trackIds.add(id);
            Object agentsObj = m.get("agents");
            List<String> agentList = new ArrayList<>();
            if (agentsObj instanceof List<?> list) {
                for (Object o : list) agentList.add(String.valueOf(o));
            }
            trackAgents.put(id, agentList);
        }
        eventBus.publish(new TrackChangeEvent("router", trackIds, trackAgents,
                List.of(), trackAgents.values().stream()
                        .flatMap(List::stream).distinct().toList()));
        log.info("Track layout changed, published TrackChangeEvent: {}", trackIds);
    }

    // ═══════════════════════════════════════════════════════════
    //  D8: SSE 辅助（回合广播）
    // ═══════════════════════════════════════════════════════════

    /** 由本轮轨道配置构造任务列表（arbiter_task 事件，前端「本轮任务分配」面板）。 */
    private List<Map<String, Object>> buildTaskList(TrackConfig config) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Track track : config.getTracks()) {
            String taskDesc = "参与轨道「" + track.getLabel() + "」("
                + track.getMode().name().toLowerCase() + "模式)";
            for (String agentName : track.getActiveAgents()) {
                tasks.add(Map.of("agent_name", agentName, "task", taskDesc));
            }
        }
        return tasks;
    }

    /** 轨道增删广播（track_created / track_closed），基于新旧轨道 id 差集。 */
    private void publishTrackLifecycle(List<Map<String, Object>> oldTracks,
                                       List<Map<String, Object>> newTracks) {
        if (sse == null) return;
        Set<String> oldIds = trackIds(oldTracks);
        Set<String> newIds = trackIds(newTracks);
        for (Map<String, Object> t : newTracks) {
            String id = String.valueOf(t.getOrDefault("id", "track"));
            if (!oldIds.contains(id)) {
                sse.broadcastTrackCreated(id, String.valueOf(t.getOrDefault("label", id)));
            }
        }
        for (Map<String, Object> t : oldTracks) {
            String id = String.valueOf(t.getOrDefault("id", "track"));
            if (!newIds.contains(id)) {
                sse.broadcastTrackClosed(id, String.valueOf(t.getOrDefault("label", id)));
            }
        }
    }

    private Set<String> trackIds(List<Map<String, Object>> tracks) {
        Set<String> ids = new HashSet<>();
        if (tracks != null) {
            for (Map<String, Object> t : tracks) {
                ids.add(String.valueOf(t.getOrDefault("id", "track")));
            }
        }
        return ids;
    }

    // ═══════════════════════════════════════════════════════════
    //  N1: 历史契约数据源（供 HistoryController /api/history）
    // ═══════════════════════════════════════════════════════════

    /** 当前会话全部消息（/api/history 的 messages 数据源）。 */
    public List<Message> getConversationMessages() {
        return memory.hasSession() ? memory.getSession().getMessages() : List.of();
    }

    /** 当前会话 round_log（/api/history 的 round_logs 数据源）。 */
    public List<Map<String, Object>> getConversationRoundLogs() {
        return memory.hasSession() ? memory.getSession().getRoundLog() : List.of();
    }

    // ═══════════════════════════════════════════════════════════
    //  Character Relations
    // ═══════════════════════════════════════════════════════════

    private final Map<String, Map<String, Map<String, String>>> characterRelations = new ConcurrentHashMap<>();

    /** Build relationship graph from script data. */
    public void buildCharacterRelations(Map<String, Object> scriptData) {
        characterRelations.clear();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) scriptData.getOrDefault("relationships", List.of());
        for (Map<String, Object> rel : relationships) {
            String from = (String) rel.getOrDefault("from", rel.getOrDefault("from_char", ""));
            String to = (String) rel.get("to");
            String relation = (String) rel.get("relation");
            String desc = (String) rel.get("description");
            if (from.isEmpty() || to == null) continue;
            characterRelations.computeIfAbsent(from, k -> new ConcurrentHashMap<>())
                .put(to, Map.of("relation", relation != null ? relation : "",
                                "description", desc != null ? desc : ""));
        }
        // Store in memory
        if (!characterRelations.isEmpty()) {
            StringBuilder sb = new StringBuilder("【角色关系图】\n");
            characterRelations.forEach((from, targets) ->
                targets.forEach((to, info) ->
                    sb.append(from).append("→").append(to).append(": ")
                      .append(info.get("relation")).append("（").append(info.get("description")).append("）\n")));
            String currentSession = this.state.getOrDefault("session_id", "").toString();
            if (!currentSession.isEmpty() && memory.hasSession()) {
                memory.addMessage(new com.roleplay.engine.core.Message(
                    com.roleplay.engine.core.Message.Role.SYSTEM, "主控", sb.toString()));
            }
        }
    }

    /** Get drift prevention prompt for an agent. */
    public String buildDriftPreventionPrompt(String agentName) {
        Map<String, Map<String, String>> rels = characterRelations.get(agentName);
        if (rels == null || rels.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【角色关系】\n");
        rels.forEach((target, info) ->
            sb.append("与").append(target).append("的关系：")
              .append(info.get("relation")).append("（").append(info.get("description")).append("）\n"));
        // P-0813-B：结尾措辞软化 —— 从「严格依据/不要偏离」改为正向引导（可即兴，但不得违背核心身份与关系）
        sb.append("基于以上关系自然行动，可即兴发挥，但不得违背核心身份与关系。");
        return sb.toString();
    }

    /**
     * P-0813-B：向会话消息列表追加校准提醒（每个五层角色一条，SYSTEM role + 「【校准提醒】」前缀 ——
     * Agent.buildContext 对带此前缀的 SYSTEM 消息放行进入 LLM 上下文；其余 SYSTEM 消息维持跳过语义）。
     * 内容 = layer0 前 3 条 + 反差（surface/actual/hint）+ buildDriftPreventionPrompt 角色关系，
     * Author's Note 式自然衔接（「（校准提醒：…）」）。
     *
     * <p>注入点为 agent 生成前（runRound Step 3 之前）→ 本轮 buildAgentContext 的【对话历史】
     * 即包含校准块（memory.getAgentContext 不按 role 过滤，尾部高影响；ST Author's Note 同款）。
     */
    private void injectCalibrationMessages() {
        if (!memory.hasSession() || agents.isEmpty()) return;
        for (Agent agent : agents.values()) {
            Persona persona = agent.getPersona();
            // 仅五层角色有 layer0/反差可校准（旧 4 字段角色零影响，走既有路径）
            if (persona == null || !persona.hasLayers()) continue;

            StringBuilder sb = new StringBuilder("【校准提醒】（第 " + roundCount
                    + " 轮，请保持言行一致，自然融入对话）：");
            // layer0 前 3 条
            Object layer0 = persona.getLayers().get("layer0");
            if (layer0 instanceof List<?> rules) {
                int idx = 0;
                for (Object r : rules) {
                    if (r == null || String.valueOf(r).isBlank()) continue;
                    sb.append("\n").append(++idx).append(". ").append(r);
                    if (idx >= 3) break;
                }
            }
            // 反差（surface/actual/hint）
            Object contrast = persona.getLayers().get("contrast");
            if (contrast instanceof Map<?, ?> cm) {
                Object surface = cm.get("surface");
                Object actual = cm.get("actual");
                if (surface != null || actual != null) {
                    sb.append("\n反差：表面=").append(surface == null ? "—" : surface)
                      .append("，实际=").append(actual == null ? "—" : actual);
                }
                Object hint = cm.get("hint");
                if (hint != null) {
                    sb.append("；提示=").append(hint);
                }
            }
            // 角色关系（buildDriftPreventionPrompt 死代码复活为校准块的一部分）
            String rels = buildDriftPreventionPrompt(agent.getName());
            if (!rels.isEmpty()) {
                sb.append("\n").append(rels);
            }

            Message cal = new Message(Message.Role.SYSTEM, "系统", sb.toString());
            cal.setRoundNumber(roundCount);
            memory.addMessage(cal);
        }
        log.info("Session {}: injected calibration reminders at round {} ({} agents)",
                sessionId, roundCount, agents.size());
    }

    // ═══════════════════════════════════════════════════════════
    //  Round Rollback
    // ═══════════════════════════════════════════════════════════

    private final List<List<Message>> roundHistory = new ArrayList<>();

    /** Save current round's messages before overwriting. */
    private void snapshotRound(String sessionId) {
        List<Message> snapshot = new ArrayList<>(memory.getSession().getMessages());
        roundHistory.add(snapshot);
        if (roundHistory.size() > 50) roundHistory.remove(0); // cap
    }

    /** Rollback to a previous round number. */
    public String rollbackToRound(String sessionId, int targetRound) {
        if (targetRound < 0 || targetRound >= roundHistory.size())
            return "无效回合: " + targetRound;
        memory.getSession().setMessages(new ArrayList<>(roundHistory.get(targetRound)));
        roundCount = targetRound;
        state.put("round", targetRound);
        return "已回滚到第 " + targetRound + " 轮";
    }

    // ═══════════════════════════════════════════════════════════
    //  Auto Rounds
    // ═══════════════════════════════════════════════════════════

    private volatile boolean autoRunning = false;

    /** Run multiple rounds automatically. */
    public List<RoundResult> runAutoRounds(String userInput, int turns) {
        List<RoundResult> results = new ArrayList<>();
        autoRunning = true;
        // P0-2：同 runTurns —— 停止后恢复
        if (!running && !agents.isEmpty()) running = true;
        // P-0813-A：手动接管 → 取消遗留的自动续轮任务；批量进行中轮末不再调度（防批量后多跑一轮）
        cancelPendingAutoContinue();
        // P-0814-A：手动批量同样清除「等待播出完毕」标志（批量即推进，等待态作废）
        this.awaitingPlayback = false;
        manualRoundBatch = true;
        try {
            for (int i = 0; i < turns && autoRunning; i++) {
                RoundResult result = runRound(i == 0 ? userInput : null, null);
                results.add(result);
                if (result.status.contains("error")) break;
            }
        } finally {
            manualRoundBatch = false;
        }
        autoRunning = false;
        // D8: 自动对话结束推送
        if (sse != null) sse.broadcastAutoComplete(results.size());
        return results;
    }

    public void stopAutoRounds() { autoRunning = false; }
    public boolean isAutoRunning() { return autoRunning; }

    // ═══════════════════════════════════════════════════════════
    //  D13: turns 多轮执行（/api/round/start）
    // ═══════════════════════════════════════════════════════════

    /**
     * D13: 明确收束信号 —— 主控旁白 / 角色台词出现这些标记时判定剧情目标达成。
     */
    private static final List<String> CLOSURE_MARKERS = List.of(
        "（完）", "（全文完）", "全剧终", "剧终", "故事结束", "本剧终",
        "完结", "(完)", "END", "The End");

    /**
     * 按 turns 执行 N 轮对话（前端"三轮"按钮等）。每轮走 {@link #runRound}。
     *
     * <p>停止条件（任一满足即提前结束，实际轮数 &lt; turns）：
     * <ol>
     *   <li>中途 stop —— running 被置 false（/api/stop、/stop 命令）；</li>
     *   <li>目标达成 —— 保守启发式 {@link #goalsAchieved()}（仅当设置过剧情目标且
     *       最近对话出现明确收束信号）；</li>
     *   <li>单轮错误 —— 无活动会话 / 生成被中断。</li>
     * </ol>
     *
     * @param userInput 仅第一轮携带的用户输入，后续轮次自动推进（null）
     * @param turns     目标轮数（&lt;=0 视为 1）
     * @return 实际执行的轮次结果列表（可能少于 turns）
     */
    public List<RoundResult> runTurns(String userInput, int turns) {
        List<RoundResult> results = new ArrayList<>();
        int target = Math.max(1, turns);
        // P0-2：会话已停止但角色仍在 → 自动恢复（/api/round/start 后不再「No active session / 0 轮」）
        if (!running && !agents.isEmpty()) running = true;
        // P-0813-A：手动接管 → 取消遗留的自动续轮任务；批量进行中轮末不再调度（防批量后多跑一轮）
        cancelPendingAutoContinue();
        // P-0814-A：手动批量同样清除「等待播出完毕」标志（批量即推进，等待态作废）
        this.awaitingPlayback = false;
        manualRoundBatch = true;
        try {
            for (int i = 0; i < target && running; i++) {
                RoundResult result = runRound(i == 0 ? userInput : null, null);
                results.add(result);
                if (result.status != null && result.status.startsWith("error")) break;
                if (goalsAchieved()) break;
            }
        } finally {
            manualRoundBatch = false;
        }
        // D8: 多轮自动对话结束推送（前端 "自动对话结束，共 N 轮"；单轮走 round_complete 不重复广播）
        if (sse != null && target > 1) sse.broadcastAutoComplete(results.size());
        return results;
    }

    /**
     * D13: 目标达成检测（保守启发式，避免误提前终止）。
     *
     * <p>仅当：剧情目标非空 且 最近 30 条消息中出现明确收束信号时返回 true；
     * 未设置目标时恒为 false（跑满 turns）。
     */
    private boolean goalsAchieved() {
        if (goals == null || goals.isEmpty() || !memory.hasSession()) return false;
        List<Message> msgs = memory.getSession().getMessages();
        int from = Math.max(0, msgs.size() - 30);
        for (int i = msgs.size() - 1; i >= from; i--) {
            Message m = msgs.get(i);
            if (m.getContent() == null) continue;
            for (String marker : CLOSURE_MARKERS) {
                if (m.getContent().contains(marker)) return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  Message snapshot in runRound
    // ═══════════════════════════════════════════════════════════

    public void addSnapshotToRunRound(String sessionId) {
        snapshotRound(sessionId);
    }

    // ═══════════════════════════════════════════════════════════
    //  Value objects
    // ═══════════════════════════════════════════════════════════

    public static class RoundResult {
        public final String status;
        public final List<Map<String, Object>> agentOutputs;
        public final Map<String, Object> integration;
        public final String reasoning;
        public final Map<String, Object> metrics;

        public RoundResult(String status, List<Map<String, Object>> agentOutputs,
                           Map<String, Object> integration, String reasoning,
                           Map<String, Object> metrics) {
            this.status = status;
            this.agentOutputs = agentOutputs;
            this.integration = integration;
            this.reasoning = reasoning;
            this.metrics = metrics;
        }

        public static RoundResult error(String msg) {
            return new RoundResult("error: " + msg, List.of(), Map.of(), "", Map.of());
        }
    }
}
