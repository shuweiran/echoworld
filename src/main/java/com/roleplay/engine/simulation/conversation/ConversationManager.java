package com.roleplay.engine.simulation.conversation;

import jakarta.annotation.PreDestroy;
import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);

    private static final long GROUP_IDLE_TIMEOUT_MS = 30_000;
    private static final long CONVERSATION_COOLDOWN_MS = 5_000;
    /**
     * Runtime-owned fallback for the legacy spatial-only path. The default and
     * validation rule are shared with {@link SpatialTrackResolver};
     * {@code SimulationService} applies the configured value to every runtime.
     */
    private volatile double conversationDistance = SpatialTrackResolver.DEFAULT_CONVERSATION_DISTANCE;

    /** 调研报告 2.4 #3：玩家 joinGroup 距组最近成员的最大距离（px，对齐前端 findApproachableGroups
     *  成员 100px / 群中心 120px 语义，取宽松端 120）。超距拒绝（防“人在千里外也能入组”）。 */
    private static final double JOIN_GROUP_MAX_DISTANCE = 120.0;
    /** 调研报告 2.4 #3：玩家消息自动建 DYAD 时，最近 AI 的最大距离（px）。超距不建组
     *  （玩家在地图一角，最近的 AI 在几百 px 外也会被拉进 1v1 组的修复）。 */
    private static final double MAX_AUTO_DYAD_DISTANCE = 200.0;

    private final Map<ConversationMode, ConversationStrategy> strategies = new EnumMap<>(ConversationMode.class);
    private final Map<String, ConversationGroup> activeGroups = new ConcurrentHashMap<>();
    private final Map<String, Long> recentPairCooldowns = new ConcurrentHashMap<>();
    private final Map<String, TopicManager> groupTopicManagers = new ConcurrentHashMap<>();
    /** 仅仲裁发言机会：DM/Track 不介入角色是否真正说话。 */
    private final SpeakerArbitrator speakerArbitrator = new SpeakerArbitrator();
    /**
     * LLM 生成线程只把待落地的发言放入此队列；世界 tick 线程随后用最新坐标与障碍解析实际听众。
     * 生成时的 Track/Perception 上下文仅用于角色决策，绝不能当作最终投递名单。
     */
    private final Queue<SpeechCommitCommand> pendingSpeechCommits = new ConcurrentLinkedQueue<>();
    /** 生命周期 PASSIVE 硬门：角色仍在世界移动/渲染，但不自动入组或触发对话 LLM。 */
    private final Set<String> passiveAgents = ConcurrentHashMap.newKeySet();
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

    /** 一般模式社会状态回写：对话组每轮完成后通知关系/记忆层。 */
    private java.util.function.Consumer<Map<String, Object>> conversationCompletedListener;

    /** 方案B 共存开关：init 注入的 AnnouncementService（可空）。executeRound 在
     *  split 模式下抑制方案A 回调，避免与 SpeechStrategy 内联广播重复推送。 */
    private AnnouncementService announcementService;

    // ── P-0813-F：节奏控制（roleplay.pacing.*）─────────────────────
    // 仅 2D 模拟世界（SimulationService 构造时显式 setPacing 注入）；
    // 剧本杀/狼人杀各局自有的 ConversationManager 实例不注入 → pacingEnabled=false
    // → 轮次间隔/组对冷却全部回退原硬编码行为（模式守卫，零影响）。
    /** 节奏控制总开关（默认 false=未注入，回退原行为）。 */
    private volatile boolean pacingEnabled = false;
    /** 对话轮次基础间隔 ms（DYAD 等，默认 2000=原硬编码值）。 */
    private volatile long pacingRoundCooldownMs = 2_000;
    /** 群聊轮次基础间隔 ms（GROUP_DISCUSSION，默认 3000=原硬编码值）。 */
    private volatile long pacingGroupRoundCooldownMs = 3_000;
    /** 组对再次自动建组冷却 ms（默认 5000=原硬编码值）。 */
    private volatile long pacingConversationCooldownMs = CONVERSATION_COOLDOWN_MS;
    /** 未进入对话（无玩家对话轨道）时轮次间隔倍率（默认 1.0=不降频，由注入方给实际值）。 */
    private volatile double pacingIdleMultiplier = 1.0;
    /** 进入对话后非当前轨道的轮次间隔倍率（默认 1.0=不降频，由注入方给实际值）。 */
    private volatile double pacingInactiveMultiplier = 1.0;

    // ── P-0813-H：其他轨道（非玩家轨道）对话节奏拉长 + 气泡停留时长下发 ──
    // 需求更正（主人澄清）：不做全局串行队列——多个对话群同时存在、同时推进（并行保持）；
    // 轨道内成员轮流发言（现状）保持不变；玩家所在轨道全速，其他轨道轮次间隔大幅拉长
    // （inactive 倍率 ×3~×5，比 F 的 ×2 更强，配置可调），并把其他轨道发言的
    // 展示间隔/气泡停留时长参数暴露给前端（conversation-status 下发）。
    /** 非玩家轨道发言的气泡停留/展示时长基准 ms（roleplay.pacing.speech-bubble-hold-ms，
     *  默认 4000；经 getStatus 下发，前端可用作气泡展示时长/展示间隔）。 */
    private volatile long pacingSpeechBubbleHoldMs = 4_000;

    /** P-0814-A：点击驱动对话模式开关（roleplay.round.playback-driven）——仅 2D 模拟世界
     *  （SimulationService 构造时显式 setPlaybackDriven 注入）；剧本杀/狼人杀各局自有的
     *  ConversationManager 实例不注入 → false=旧行为（pacing 间隔自动连跑），零影响。
     *  true：组轮次循环一轮生成完即停，等 {@link #notifyPlaybackDone(String)} 信号再下一轮。 */
    private volatile boolean playbackDriven = false;

    /** P-0814-B：无玩家组「等待播出完毕」超时（roleplay.round.group-await-timeout-ms，默认 30000）。
     *  仅 2D 世界注入；等待中的无玩家组（AI-AI / 单 AI）超过该时长被 tick 自动解散（防 awaitPlayback
     *  永久阻塞、组内角色冻结）；有玩家成员的组不自动解散（玩家输入/点击唤醒，见 wakeGroupForAgent）。 */
    private volatile long groupAwaitTimeoutMs = 30_000;

    /** LLM 返回但尚未落地的角色发言；位置不在这里缓存，必须在 commit 时重新读取。 */
    public record PendingUtterance(String speakerId, String text, SpeechVolume volume,
                                   long generationStartedAt, long generationFinishedAt) {}

    /** 发言在世界线程落地后得到的实际听众快照。 */
    public record SpeechDelivery(PendingUtterance utterance, Set<String> actualListeners,
                                 long resolvedAtTick) {}

    private record SpeechCommitCommand(PendingUtterance utterance,
                                       java.util.function.Consumer<SpeechDelivery> onDelivered) {}

    /** P-0814-B：注入无玩家组等待播出完毕超时（SimulationService 构造时显式注入；未注入=默认 30s）。 */
    public void setGroupAwaitTimeoutMs(long ms) {
        this.groupAwaitTimeoutMs = Math.max(1, ms);
    }

    /** P-0814-B：当前等待播出完毕超时（测试/监控用）。 */
    long getGroupAwaitTimeoutMs() { return groupAwaitTimeoutMs; }

    /** P-0814-B：组内是否含玩家控制成员（无玩家组等待超时自动解散的判定依据）。 */
    private boolean groupHasPlayer(ConversationGroup group) {
        for (AgentState s : group.getParticipantList()) {
            if (s.isPlayerControlled()) return true;
        }
        return false;
    }

    /** P-0814-B：玩家输入唤醒所在组等待（AI-user 解卡；「输入=点击」语义）——
     *  玩家消息经 {@code SimulationService.sendUserMessage} 存入 currentMessage 后调用本方法：
     *  组在等待播出完毕 → 信号置位唤醒（该输入下一轮被 executeRound 消费）；组在生成中 →
     *  信号保持（下一轮等待时立即通过，不丢输入=点击语义）。组不存在/非活跃 → false。
     *
     * @return true=已找到玩家所在活跃组并送达信号；false=玩家不在任何活跃组（tick 将按既有
     *         路径用玩家消息自动建 DYAD 组）
     */
    public boolean wakeGroupForAgent(String agentName) {
        if (agentName == null || agentName.isBlank()) return false;
        for (ConversationGroup g : activeGroups.values()) {
            if (g.isActive() && g.containsAgent(agentName)) {
                g.signalPlaybackDone();
                return true;
            }
        }
        return false;
    }

    /**
     * P-0813-F：注入节奏控制参数（仅 2D 模拟世界调用；剧本杀/狼人杀各局实例不调用=禁用）。
     *
     * @param enabled            总开关（roleplay.pacing.enabled）
     * @param roundCooldownMs    DYAD 等轮次基础间隔
     * @param groupRoundCooldownMs 群聊轮次基础间隔
     * @param conversationCooldownMs 组对再次建组冷却
     * @param idleMultiplier     未进入对话时的轮次间隔倍率
     * @param inactiveMultiplier 进入对话后非当前轨道倍率（P-0813-H：默认 ×4 大幅拉长对话时间）
     */
    public void setPacing(boolean enabled, long roundCooldownMs, long groupRoundCooldownMs,
                          long conversationCooldownMs, double idleMultiplier, double inactiveMultiplier) {
        this.pacingEnabled = enabled;
        this.pacingRoundCooldownMs = Math.max(1, roundCooldownMs);
        this.pacingGroupRoundCooldownMs = Math.max(1, groupRoundCooldownMs);
        this.pacingConversationCooldownMs = Math.max(1, conversationCooldownMs);
        this.pacingIdleMultiplier = Math.max(1.0, idleMultiplier);
        this.pacingInactiveMultiplier = Math.max(1.0, inactiveMultiplier);
        log.info("ConversationManager pacing {} (round={}ms group={}ms cooldown={}ms idle=×{} inactive=×{})",
                enabled ? "enabled" : "disabled", pacingRoundCooldownMs, pacingGroupRoundCooldownMs,
                pacingConversationCooldownMs, pacingIdleMultiplier, pacingInactiveMultiplier);
    }

    /** P-0813-H：注入非玩家轨道发言的气泡停留/展示时长基准（conversation-status 下发前端）。 */
    public void setSpeechBubbleHoldMs(long ms) {
        this.pacingSpeechBubbleHoldMs = Math.max(1, ms);
    }

    /** P-0814-A：注入点击驱动开关（仅 2D 模拟世界；剧本杀/狼人杀实例不调用 → false 旧行为）。 */
    public void setPlaybackDriven(boolean playbackDriven) {
        this.playbackDriven = playbackDriven;
        log.info("ConversationManager playback-driven {}", playbackDriven ? "enabled" : "disabled");
    }

    /** P-0814-A: 当前是否点击驱动模式。 */
    public boolean isPlaybackDriven() { return playbackDriven; }

    /**
     * P-0814-A：播放完毕信号（前端「播出完毕」→ POST /api/simulation/playback_done → 本方法）——
     * 唤醒该对话组等待中的轮次循环，生成下一轮（一轮=组内各成员按发言顺序各一句）。
     * 幂等：组不存在或未处于等待态 → false（信号忽略，不产生多余轮次）。
     *
     * @return true=信号已送达等待中的组；false=组不存在/未等待
     */
    public boolean notifyPlaybackDone(String groupId) {
        if (groupId == null || groupId.isBlank()) return false;
        ConversationGroup g = activeGroups.get(groupId);
        if (g == null) return false;
        g.signalPlaybackDone();
        return true;
    }

    /** P-0813-F：节奏控制是否启用（2D 世界注入；剧本杀/狼人杀各局实例=false）。 */
    public boolean isPacingEnabled() { return pacingEnabled; }

    /** P-0816-R：按 id 取活动讨论组（质询 pressed 标记定位实时发言用——讨论进行中发言在组内
     *  messageHistory，讨论结束才整体拷入对局 discussionTranscript；无组返回 null）。 */
    public ConversationGroup getActiveGroup(String groupId) {
        return groupId == null ? null : activeGroups.get(groupId);
    }

    /**
     * P-0813-F：当前对话轨道 = 含玩家控制成员的活跃群组（玩家进入对话 → 该轨道全速）；
     * 无 → null（未进入对话，全部轨道按未对话态节奏）。
     */
    public ConversationGroup getCurrentPlayerGroup() {
        for (ConversationGroup g : activeGroups.values()) {
            if (!g.isActive()) continue;
            for (AgentState s : g.getParticipantList()) {
                if (s.isPlayerControlled()) return g;
            }
        }
        return null;
    }

    /**
     * P-0813-F：轮次间隔计算（对话密度时序控制）。P-0813-H 需求更正：不做全局串行队列，
     * 多对话群并行推进；本方法只负责节奏——玩家所在轨道全速、其他轨道间隔大幅拉长。
     *
     * <ul>
     *   <li>pacing 未注入（剧本杀/狼人杀各局实例）→ 原硬编码行为（GROUP_DISCUSSION 3000 / 其余 2000）；</li>
     *   <li>无玩家对话轨道（未进入对话）→ 所有轨道 ×{@link #pacingIdleMultiplier}（降低密度）；</li>
     *   <li>有玩家对话轨道 → 当前轨道全速（基础间隔），其余轨道
     *       ×{@link #pacingInactiveMultiplier}（P-0813-H 默认 ×4：大幅拉长对话时间/降低密度）。</li>
     * </ul>
     *
     * <p>包可见供单测直调（不依赖真实轮次循环时序）。
     */
    long computeRoundCooldownMs(ConversationGroup group, ConversationMode mode) {
        long base = mode == ConversationMode.GROUP_DISCUSSION ? 3_000 : 2_000;
        if (!pacingEnabled) return base;
        long pacedBase = mode == ConversationMode.GROUP_DISCUSSION
                ? pacingGroupRoundCooldownMs : pacingRoundCooldownMs;
        ConversationGroup current = getCurrentPlayerGroup();
        if (current == null) {
            return (long) (pacedBase * pacingIdleMultiplier);
        }
        if (group != null && group.getGroupId().equals(current.getGroupId())) {
            return pacedBase;
        }
        return (long) (pacedBase * pacingInactiveMultiplier);
    }

    /** P-0813-F：组对再次自动建组冷却（pacing 注入时用配置值，否则原硬编码 5000）。 */
    private long effectiveConversationCooldownMs() {
        return pacingEnabled ? pacingConversationCooldownMs : CONVERSATION_COOLDOWN_MS;
    }

    /**
     * P-0810-17（B1）：剧本杀讨论发言逐轮实时回调（讨论组专用，与 speechBroadcastListener 分离——
     * 后者是 2D 世界 PUBLIC_SPEAKING 演讲广播回调，语义不同不混用）。
     * 在 {@link #runScriptDiscussionRounds} 每轮结束后对新增发言逐条回调，订阅方
     * （ScriptGameService）转 script_speech SSE 实时回显；null = 不回调（旧行为逐字节不变）。
     */
    private java.util.function.Consumer<SpeechTurn> scriptSpeechListener;

    /** 一次演讲产出（供广播管线使用：谁、说了什么、所属群组）。 */
    public record SpeechTurn(String groupId, String speaker, String text) {}

    public void setSpeechBroadcastListener(java.util.function.Consumer<SpeechTurn> listener) {
        this.speechBroadcastListener = listener;
    }

    public void setConversationCompletedListener(java.util.function.Consumer<Map<String, Object>> listener) {
        this.conversationCompletedListener = listener;
    }

    /** P-0810-17（B1）：设置剧本杀讨论发言逐轮回调（见 {@link #scriptSpeechListener}）。 */
    public void setScriptSpeechListener(java.util.function.Consumer<SpeechTurn> listener) {
        this.scriptSpeechListener = listener;
    }

    public ConversationManager() {}

    /**
     * Configures the direct-conversation distance used only when no
     * {@link TrackDirectorService} has been attached. Keeping this value here
     * makes test/runtime fallback behavior match the configured Track resolver.
     */
    public void setConversationDistance(double conversationDistance) {
        this.conversationDistance = conversationDistance > 0
                ? conversationDistance
                : SpatialTrackResolver.DEFAULT_CONVERSATION_DISTANCE;
    }

    /** Exposes the effective fallback distance for diagnostics and tests. */
    public double getConversationDistance() {
        return conversationDistance;
    }

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

    /** D1: 停止全部群组生成（模拟停止时调用，配合 InterruptManager.cancelAll）。
     *  P-0814-A：同时唤醒等待「播出完毕」的组循环（active=false + notify），防播放驱动线程悬挂。 */
    public void stopAll() {
        stopped = true;
        for (ConversationGroup g : activeGroups.values()) {
            g.setActive(false);
            g.wakePlaybackWaiters();
        }
        log.info("ConversationManager stopped ({} active groups)", activeGroups.size());
    }

    /** D1: 恢复生成（模拟重新 start 时调用）。 */
    public void resetStopped() {
        stopped = false;
    }

    public boolean isStopped() { return stopped; }

    public void setAgentPassive(String agentName, boolean passive) {
        if (agentName == null || agentName.isBlank()) return;
        if (passive) {
            passiveAgents.add(agentName);
            detachAgent(agentName);
        } else {
            passiveAgents.remove(agentName);
        }
    }

    public boolean isAgentPassive(String agentName) {
        return agentName != null && passiveAgents.contains(agentName);
    }

    public void clearPassiveAgents() {
        passiveAgents.clear();
    }

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

    /** 方案A（轨道系统用户加入）：加入/离开对话组的结果。 */
    public record JoinResult(boolean success, String message, ConversationGroup group) {
        public static JoinResult ok(ConversationGroup group) {
            return new JoinResult(true, "ok", group);
        }
        public static JoinResult fail(String message) {
            return new JoinResult(false, message, null);
        }
    }

    /**
     * 方案A：玩家加入现有对话组（唯一入口：POST /api/simulation/group/{groupId}/join）。
     * 校验链：组存在且活跃 → 玩家对应角色存在且在场 → 未在组内 → 组未满 →
     * 距最近成员 < JOIN_GROUP_MAX_DISTANCE（P-0815-A 距离校验）→ 加入。
     * 加入后：在场状态与既有 startGroup 路径一致（inConversation=true + 冻结 + 速度清零），
     * 组内轨道重算（玩家与 NPC 同等按距离/规则判定）；组标记 USER_JOINED（P-0815-A 组类型）；
     * tick 不得踢出（见 tick 的 isBusy 防护）。
     * 并发安全：成员表变更与后台 executeRound 的读取经组对象锁互斥（addParticipant/getParticipantList）。
     */
    public JoinResult joinGroup(String groupId, String playerName) {
        ConversationGroup group = activeGroups.get(groupId);
        if (group == null) return JoinResult.fail("group not found: " + groupId);
        if (!group.isActive()) return JoinResult.fail("group not active: " + groupId);
        AgentState player = world == null ? null : world.getState(playerName);
        if (player == null) return JoinResult.fail("agent not found in world: " + playerName);
        if (group.containsAgent(playerName)) return JoinResult.fail("player already in group: " + playerName);
        if (group.getParticipantCount() >= group.getMaxParticipants()) {
            return JoinResult.fail("group full: " + groupId);
        }
        // P-0815-A：距离校验（调研报告 2.4 #3）——玩家与组最近成员距离 < JOIN_GROUP_MAX_DISTANCE，
        // 超距拒绝（前端 findApproachableGroups 只做展示层限制，后端校验是本原；
        // 校验在 addParticipant 之前，拒绝不产生任何成员表副作用）。
        if (!nearGroup(player, group)) {
            return JoinResult.fail("too far from group: " + groupId
                    + " (nearest member distance >= " + JOIN_GROUP_MAX_DISTANCE + "px)");
        }
        boolean added = group.addParticipant(player);
        if (!added) return JoinResult.fail("join rejected by group: " + groupId);
        // P-0815-A：玩家加入的组标记 USER_JOINED（组类型区分，getStatus 下发 kind）。
        group.setKind(GroupKind.USER_JOINED);
        player.setInConversation(true);
        player.setVx(0);
        player.setVy(0);
        group.freeze(playerName);
        recomputeTrackAssignments(group);
        log.info("Player {} joined group {} | mode={} | members={}", playerName, groupId, group.getMode(),
                group.getParticipantList().stream().map(AgentState::getAgentName).toList());
        return JoinResult.ok(group);
    }

    /**
     * 方案A：玩家离开对话组。还原在场状态（inConversation=false + 解冻 + 速度清零）并重算组内轨道；
     * 组内已无成员 → 解散（含冷却/主题收尾，对齐 dissolveGroup）。
     */
    public JoinResult leaveGroup(String groupId, String playerName) {
        ConversationGroup group = activeGroups.get(groupId);
        if (group == null) return JoinResult.fail("group not found: " + groupId);
        AgentState player = world == null ? null : world.getState(playerName);
        if (player == null) return JoinResult.fail("agent not found in world: " + playerName);
        if (!group.containsAgent(playerName)) return JoinResult.fail("player not in group: " + playerName);
        boolean removed = group.removeParticipant(playerName);
        if (!removed) return JoinResult.fail("leave rejected by group: " + groupId);
        player.setInConversation(false);
        player.setVx(0);
        player.setVy(0);
        group.unfreeze(playerName);
        if (group.getParticipantCount() == 0) {
            dissolveGroup(groupId);
        } else {
            recomputeTrackAssignments(group);
        }
        log.info("Player {} left group {} | members={}", playerName, groupId,
                group.getParticipantList().stream().map(AgentState::getAgentName).toList());
        return JoinResult.ok(group);
    }

    /** 玩家与组内最近成员距离 < {@link #JOIN_GROUP_MAX_DISTANCE}（px）且声线路径未被墙体阻断，才允许加入。 */
    private boolean nearGroup(AgentState player, ConversationGroup group) {
        double best = Double.MAX_VALUE;
        for (AgentState m : group.getParticipantList()) {
            if (canConverse(player, m)) {
                best = Math.min(best, player.distanceTo(m));
            }
        }
        return best < JOIN_GROUP_MAX_DISTANCE;
    }

    /**
     * P-0824-L：角色从世界退场前的事务性脱离。
     * 先取消该角色仍在进行的生成任务，再从所有活动组移除；不足两人的组立即解散，避免遗留
     * 冻结成员、悬挂轮次或持有已经从 SimulationWorld 删除的 AgentState 引用。
     */
    public int detachAgent(String agentName) {
        if (agentName == null || agentName.isBlank()) return 0;
        if (interruptManager != null) {
            interruptManager.cancelAgent(agentName, StopType.STATE_INVALID, "角色生命周期退场");
        }
        int detached = 0;
        for (ConversationGroup group : new ArrayList<>(activeGroups.values())) {
            if (!group.removeParticipant(agentName)) continue;
            detached++;
            AgentState state = world == null ? null : world.getState(agentName);
            if (state != null) {
                state.setInConversation(false);
                state.setVx(0);
                state.setVy(0);
                state.clearTarget();
            }
            if (group.getParticipantCount() < 2) dissolveGroup(group.getGroupId());
            else recomputeTrackAssignments(group);
        }
        return detached;
    }

    /** 释放本实例拥有的虚拟线程执行器；对局 TTL 淘汰和 Spring 销毁均调用。 */
    @PreDestroy
    public void shutdown() {
        stopAll();
        executor.shutdownNow();
        activeGroups.clear();
        recentPairCooldowns.clear();
        groupTopicManagers.clear();
    }

    /**
     * 对话资格的唯一空间判定：既要在声学范围内，也不能被 blocksSound 墙体/建筑遮挡。
     * 这样手动加入、玩家消息自动建组及已建组的持续性共用同一条规则。
     */
    private boolean canConverse(AgentState a, AgentState b) {
        return world != null && world.getHearingSystem().canHearEachOther(a, b);
    }

    /**
     * 群聊允许成员经可听见的同伴形成连通团；若隔墙后图不再连通，则自然散场，
     * 防止既有会话在角色迁移/移动后继续穿墙交流。
     */
    private boolean groupIsAudiblyConnected(ConversationGroup group) {
        List<AgentState> members = group.getParticipantList();
        if (members.size() < 2) return true;
        Set<String> reached = new HashSet<>();
        ArrayDeque<AgentState> queue = new ArrayDeque<>();
        queue.add(members.get(0));
        reached.add(members.get(0).getAgentName());
        while (!queue.isEmpty()) {
            AgentState current = queue.removeFirst();
            for (AgentState candidate : members) {
                if (!reached.contains(candidate.getAgentName()) && canConverse(current, candidate)) {
                    reached.add(candidate.getAgentName());
                    queue.addLast(candidate);
                }
            }
        }
        return reached.size() == members.size();
    }

    /** 重算组内全部成员的轨道分配（join/leave 后即时生效；每 tick 由 SimulationOrchestrator 再回写）。 */
    private void recomputeTrackAssignments(ConversationGroup group) {
        try {
            Map<String, TrackAssignment> assignments;
            if (trackDirector != null) {
                // Phase 3: Track Director decides who-knows-what (score + secrets + goals).
                Map<String, String> goals = goalSupplier != null ? goalSupplier.get() : Map.of();
                assignments = trackDirector.assign(group.getParticipantList(), goals);
            } else {
                // Legacy Phase 1/2 path: pure spatial resolution (unchanged behavior).
                SpatialTrackResolver trackResolver = new SpatialTrackResolver(conversationDistance, Set.of(),
                        world == null ? null : world.getHearingSystem());
                assignments = trackResolver.resolve(group.getParticipantList());
            }
            group.setTrackAssignments(assignments);
            log.info("Group {} track assignments: {}", group.getGroupId(),
                    assignments.values().stream()
                            .map(a -> a.agentId() + "=" + a.type()).toList());
        } catch (Exception e) {
            log.warn("Track assignment failed for group {}, continuing without: {}", group.getGroupId(), e.getMessage());
        }
    }

    public void tick(long now) {
        // 此方法由 SimulationWorld 的单一 tick 回调在移动更新之后调用；先提交已完成的 LLM 发言，
        // 才开始本 tick 的新组识别。这样 resolve 与 delivery 使用同一个最新世界状态。
        resolvePendingSpeechCommits();
        List<AgentState> allStates = new ArrayList<>(world.getAllStates().values());
        if (allStates.size() < 2) return;

        List<HearingSystem.HearingResult> hearing =
                world.getHearingSystem().computeAudibility(allStates);

        Map<String, AgentState> available = new LinkedHashMap<>();
        for (AgentState s : allStates) {
            // Keep "me" out of auto-initiated conversations (proximity-based)
            // Only converse when user sends a message manually
            if (s.isPlayerControlled()) continue;
            if (passiveAgents.contains(s.getAgentName())) continue;
            if (!isBusy(s)) available.put(s.getAgentName(), s);
        }

        // If any player-controlled agent has a pending message, start a conversation with nearest agent
        for (AgentState player : allStates) {
            if (!player.isPlayerControlled()) continue;
            // 方案A：玩家已在任一对话组内（手动加入或既有 DYAD）→ 跳过自动建 DYAD 双路径；
            // 其消息由所在组 executeRound 的玩家发言链路（下方 L346-354 同款）下一轮消费，
            // 不新建第二组，tick 排除自动建组的逻辑不误伤手动加入的组。
            if (isBusy(player)) continue;
            if (player.getCurrentMessage() == null || player.getCurrentMessage().isEmpty()
                    || player.getCurrentMessage().startsWith("(主控")) continue;
            AgentState nearest = null;
            double minDist = Double.MAX_VALUE;
            for (AgentState a : available.values()) {
                if (a.isPlayerControlled()) continue;
                double d = player.distanceTo(a);
                // P-0815-A：自动 DYAD 距离上限（调研报告 2.4 #3）——nearest 超过
                // MAX_AUTO_DYAD_DISTANCE 不建组（玩家在角落时最近的 AI 也可能几百 px 外）。
                if (d < minDist && world.getHearingSystem()
                        .canAutoDyadWithinDistance(player, a, MAX_AUTO_DYAD_DISTANCE)) {
                    minDist = d;
                    nearest = a;
                }
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
                    && now - recentPairCooldowns.get(pairKey) < effectiveConversationCooldownMs()) {
                continue;
            }

            startGroup(gid, cand.mode(), cand.members());
        }

        List<String> toRemove = new ArrayList<>();
        for (ConversationGroup group : activeGroups.values()) {
            if (!group.isActive()) {
                toRemove.add(group.getGroupId());
            } else if (!group.allFrozen() && !groupIsAudiblyConnected(group)) {
                // 墙体/建筑或移动造成声学网络断开：不保留“隔墙仍在聊”的僵尸会话。
                // 正在对话的成员已被冻结；此时坐标不应再参与移动断连判定，
                // 否则前端/移动更新的瞬时位置会把刚生成首轮的组错误拆散。
                toRemove.add(group.getGroupId());
            } else if (group.isAwaitingPlayback()) {
                // P-0814-B：等待「播出完毕」的组——有玩家成员不自动解散（玩家输入/点击唤醒，
                // AI-user 解卡）；无玩家成员（AI-AI / 单 AI 组）等待超时自动解散——
                // 修复「tick idle 解散守卫对等待组跳过 → 组永不解散、角色冻结」的卡死。
                if (!groupHasPlayer(group) && group.idleMs() > groupAwaitTimeoutMs) {
                    toRemove.add(group.getGroupId());
                }
            } else if (group.idleMs() > GROUP_IDLE_TIMEOUT_MS) {
                // P-0814-A：导演思考/播放期间组不拆（等待态跳过 idle 解散）——
                // 非等待态的普通 idle 超时解散保持原语义。
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

        ConversationGroup group = new ConversationGroup(groupId, mode, members,
                // 方案A：DYAD 对偶组有参与者上限 2（一对一语义，禁 join 破坏 1v1，见调研 §4.2 #5）；
                // 其余模式默认不限（上限 Integer.MAX_VALUE）。
                mode == ConversationMode.DYAD ? 2 : Integer.MAX_VALUE);

        // Phase 1 Track fusion: compute spatial track assignments (MERGED/WEAK/ISOLATED)
        // at group creation and store them on the group. Phase 1 only stores —
        // Phase 2 (TrackStrategy) will read these to drive context visibility.
        recomputeTrackAssignments(group);

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
                    if (group.getKind() == GroupKind.AI_AUTO) {
                        applyNaturalDepartures(group, finalMode);
                    }
                    if (!group.isActive() || group.getParticipantCount() < 2) break;
                    if (playbackDriven) {
                        // P-0814-A：点击驱动 —— 一轮生成完即停，等「播出完毕」信号（notifyPlaybackDone）
                        // 再生成下一轮；无玩家/未点击时组保持等待（tick 跳过 idle 解散），
                        // stop/解散时 active=false 唤醒返回。
                        group.awaitPlayback();
                    } else {
                        // P-0813-F：轮次间隔由节奏控制计算（未注入=原 3000/2000；
                        // 未进入对话=全轨道×idle 倍率；对话中=当前轨道全速、其余×inactive 倍率——
                        // P-0813-H 需求更正：其余轨道不挂起，并行推进但间隔大幅拉长）。
                        Thread.sleep(computeRoundCooldownMs(group, finalMode));
                    }
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

    /**
     * 世界线程上的 Speech Commit 阶段。仅把发言写入真正听到的角色 perception；
     * 不改变 Track 成员、不自动加入 Gal 对话，也不复用 LLM 请求时的 listener cache。
     *
     * <p>公开用于定向竞态测试；运行时只应由 {@link #tick(long)} 调用。</p>
     */
    public int resolvePendingSpeechCommits() {
        int resolved = 0;
        SpeechCommitCommand command;
        while ((command = pendingSpeechCommits.poll()) != null) {
            PendingUtterance utterance = command.utterance();
            AgentState speaker = world == null ? null : world.getState(utterance.speakerId());
            if (speaker == null) {
                log.debug("Discard speech commit for departed speaker {}", utterance.speakerId());
                continue;
            }
            Set<String> listeners = new LinkedHashSet<>();
            for (AgentState listener : world.getAllStates().values()) {
                if (listener == speaker) continue;
                if (!world.getHearingSystem().canHear(speaker, listener, utterance.volume())) continue;
                listeners.add(listener.getAgentName());
                // Perception 与 UI/Track 分离：路过者可以听见，但不会因此进入当前对话组。
                listener.getVisibleMessages().add("[听见] " + speaker.getAgentName() + "：" + utterance.text());
            }
            SpeechDelivery delivery = new SpeechDelivery(utterance, Set.copyOf(listeners), world.getTickCount());
            try {
                command.onDelivered().accept(delivery);
            } catch (Exception e) {
                log.warn("Speech commit callback failed for {}: {}", utterance.speakerId(), e.getMessage());
            }
            resolved++;
        }
        return resolved;
    }

    /** LLM 回调入队入口；调用者不得在此读取或缓存听众集合。 */
    public void enqueueSpeechCommit(PendingUtterance utterance,
                                    java.util.function.Consumer<SpeechDelivery> onDelivered) {
        if (utterance == null || utterance.text() == null || utterance.text().isBlank()) return;
        pendingSpeechCommits.add(new SpeechCommitCommand(utterance,
                onDelivered == null ? ignored -> {} : onDelivered));
    }

    /**
     * 普通 AI 对话的自然离场：每轮结束后按节奏抽样，不打断正在生成的本轮发言。
     * 安全上限仍由策略保留，但正常结束优先由离场/剩余人数决定。
     */
    private void applyNaturalDepartures(ConversationGroup group, ConversationMode mode) {
        int round = group.getRoundCount();
        if (round < 2 || group.getParticipantCount() <= 1) return;
        List<AgentState> candidates = new ArrayList<>();
        for (AgentState state : group.getParticipantList()) {
            if (!state.isPlayerControlled()) candidates.add(state);
        }
        if (candidates.isEmpty()) return;

        double base = mode == ConversationMode.DYAD ? 0.10 : 0.07;
        int departures = 0;
        for (AgentState state : candidates) {
            if (group.getParticipantCount() - departures <= 1) break;
            double chance = base + Math.min(0.14, Math.max(0, round - 3) * 0.018);
            chance += switch (state.getEmotion()) {
                case BORED, SAD, SHY -> 0.08;
                case ANGRY, EXCITED -> 0.04;
                default -> 0.0;
            };
            chance += (1.0 - group.getEngagement(state.getAgentName())) * 0.12;
            if (ThreadLocalRandom.current().nextDouble() >= Math.min(0.38, chance)) continue;

            if (!group.removeParticipant(state.getAgentName())) continue;
            state.setInConversation(false);
            state.setVx(0);
            state.setVy(0);
            state.clearTarget();
            departures++;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", "conversation_departure");
            event.put("group", group.getGroupId());
            event.put("mode", group.getMode().name());
            event.put("tick", world.getTickCount());
            event.put("round", round);
            event.put("agent", state.getAgentName());
            event.put("reason", departureReason(state));
            world.addConversationEntry(event);
            if (conversationCompletedListener != null) {
                try { conversationCompletedListener.accept(new LinkedHashMap<>(event)); }
                catch (Exception e) { log.warn("Departure social state callback failed: {}", e.getMessage()); }
            }
            log.info("Natural departure: group={} agent={} round={} reason={}",
                    group.getGroupId(), state.getAgentName(), round, event.get("reason"));
        }
        if (departures > 0 && group.getParticipantCount() >= 2) recomputeTrackAssignments(group);
    }

    private String departureReason(AgentState state) {
        return switch (state.getEmotion()) {
            case BORED -> "话题变得无聊";
            case SAD, SHY -> "暂时想独处";
            case ANGRY -> "情绪不适合继续交谈";
            case EXCITED -> "突然想去做别的事";
            default -> "想去处理自己的事情";
        };
    }

    private void executeRound(ConversationGroup group, ConversationStrategy strategy) {
        executeRound(group, strategy, null, null);
    }

    /**
     * GAP-3: 可捕获上下文的轮次执行。contextCapture 非空时，每轮 prepareContext 后记录
     * 各成员本轮上下文（name → context），供剧本杀讨论验证 WEAK 隔离（A3-2）。
     */
    private void executeRound(ConversationGroup group, ConversationStrategy strategy,
                              Map<String, String> contextCapture) {
        executeRound(group, strategy, contextCapture, null);
    }

    /**
     * GAP-3: 可捕获上下文 + 门控决策的轮次执行（批次 D）。
     * gate 非空时：skipSpeakers 完全跳过（人类已发言、AI 不代声）；speakMap=false 的成员
     * 跳过 LLM 生成（成本控制）并以静默占位入 responses（processResults 统一入发言记录）。
     */
    private void executeRound(ConversationGroup group, ConversationStrategy strategy,
                              Map<String, String> contextCapture, RoundGateDecision gate) {
        // D1: 已停止 → 不再启动新一轮生成，直接解散
        if (stopped) {
            group.setActive(false);
            return;
        }
        // P-0814-B（调序修复）：玩家消息先入史再构建 AI 上下文——玩家发言消费当轮即进组
        // messageHistory，prepareContext 构建的上下文（MERGED 对话记录/WEAK 摘要）包含玩家本轮
        // 发言；此前顺序（先 prepareContext 后消费玩家消息）导致玩家发言被消费那一轮 AI 看不到、
        // 下一轮才看到（错位一轮）。入史后 currentMessage 清空 → 下方玩家分支自然跳过
        // （不再进 responses → processResults 不会重复记录；ISOLATED 语义零变化）。
        for (AgentState s : group.getParticipantList()) {
            if (!s.isPlayerControlled()) continue;
            String msg = s.getCurrentMessage();
            if (msg == null || msg.isEmpty() || msg.startsWith("(\u4e3b\u63a7")) continue;
            group.recordTurn(s.getAgentName(), msg);
            s.setCurrentMessage(null);
        }
        Map<String, Map<String, String>> agentContexts = new ConcurrentHashMap<>();
        strategy.prepareContext(group, agentContexts);
        // 所有策略共用局部感知与一次发言控制协议，避免不同模式泄露全局角色。
        HearingSystem hearing = world.getHearingSystem();
        Collection<AgentState> states = world.getAllStates().values();
        for (var entry : agentContexts.entrySet()) {
            AgentState self = world.getState(entry.getKey());
            if (self == null) continue;
            String base = entry.getValue().get("context");
            String events = world.getPerceivedWorldEvents(self).stream()
                    .map(event -> "- " + event.text()).collect(java.util.stream.Collectors.joining("\n"));
            String protocol = "\n" + LocalPerceptionSnapshot.from(self, states, hearing).toPrompt()
                    + (events.isBlank() ? "" : "【刚刚感知到的世界事件】\n" + events + "\n")
                    + "【发言控制】不想回应时只输出 " + SpeechDecision.NO_OUTPUT
                    + "。要说话可在末尾加【音量：WHISPER/LOW/NORMAL/LOUD/SHOUT】；该标记不会展示。\n";
            entry.setValue(Map.of("context", (base == null ? "" : base) + protocol,
                    "role", entry.getValue().getOrDefault("role", "active")));
        }
        // 普通空间对话采用“机会→Agent 自决”的连续事件模型：每次只请求优先级最高的
        // 一个 AI。若其返回 <NO_OUTPUT>，下一次调度会因机会惩罚转给下一候选，绝不强迫发言。
        // Script roundGate 仍保留其既有批量语义，避免改动剧本杀流程。
        if (gate == null) {
            String selected = speakerArbitrator.select(group, agentContexts);
            if (selected != null) {
                group.markOpportunity(selected);
                agentContexts.entrySet().removeIf(entry -> !selected.equals(entry.getKey()));
            }
        }
        if (contextCapture != null) {
            contextCapture.clear();
            for (var e : agentContexts.entrySet()) {
                Map<String, String> info = e.getValue();
                if (info != null && info.get("context") != null) {
                    contextCapture.put(e.getKey(), info.get("context"));
                }
            }
        }

        long generationStartedAt = System.currentTimeMillis();
        Map<String, String> responses = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(agentContexts.size());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        for (var entry : agentContexts.entrySet()) {
            String name = entry.getKey();
            String context = entry.getValue().get("context");

            // 批次 D：门控——本轮已由人类发言的角色，AI 不代声（完全跳过，不产生占位）
            if (gate != null && gate.skipSpeakers().contains(name)) {
                latch.countDown();
                continue;
            }

            // Skip LLM for player-controlled agents - use their existing message
            AgentState playerState = world.getState(name);
            if (playerState != null && playerState.isPlayerControlled()) {
                String existingMsg = playerState.getCurrentMessage();
                if (existingMsg != null && !existingMsg.isEmpty() && !existingMsg.startsWith("(\u4e3b\u63a7")) {
                    responses.put(name, existingMsg);
                    // P-0813-K：单次消费——玩家消息仅进当前轮（加入的群轨道一次），
                    // 消费后即清空，防后续轮次把同一句重复回放（群聊/1v1 同链路）；
                    // processResults 对玩家成员不再回写 currentMessage（各策略已加守卫）。
                    playerState.setCurrentMessage(null);
                }
                // P-0813-K：玩家无新消息时完全跳过（不产生 "..." 占位轮次）——
                // 玩家发言只在真实发言时进群轨道，沉默不刷屏；AI 成员正常继续。
                latch.countDown();
                continue;
            }

            // 批次 D：门控——静默成员跳过 LLM 生成，输出静默占位（“……（沉默）”）
            if (gate != null && !gate.speakMap().getOrDefault(name, true)) {
                responses.put(name, SpeechGate.SILENCE_MARKER);
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

        // LLM 主观静默是控制协议，不是“沉默台词”：在任何状态写入之前无痕丢弃。
        Map<String, SpeechDecision> decisions = new HashMap<>();
        responses.forEach((name, response) -> decisions.put(name, SpeechDecision.parse(response)));
        decisions.forEach((name, decision) -> {
            if (decision.speak()) group.setPendingSpeechVolume(name, decision.volume());
        });
        responses.replaceAll((name, response) -> decisions.get(name).text());
        responses.entrySet().removeIf(entry -> !decisions.get(entry.getKey()).speak());

        // 只有常规 2D 世界把 LLM 输出改为「入队 → 下一个 world tick resolve → commit」。
        // 剧本杀/狼人杀讨论使用 roundGate，并不运行 SimulationWorld tick，保持既有同步语义。
        if (gate == null && world.isRunning() && !responses.isEmpty()) {
            long generationFinishedAt = System.currentTimeMillis();
            for (var entry : responses.entrySet()) {
                SpeechDecision decision = decisions.get(entry.getKey());
                PendingUtterance utterance = new PendingUtterance(entry.getKey(), entry.getValue(),
                        decision == null ? SpeechVolume.NORMAL : decision.volume(),
                        generationStartedAt, generationFinishedAt);
                enqueueSpeechCommit(utterance, delivery -> commitRoundResults(group, strategy,
                        Map.of(utterance.speakerId(), utterance.text()), delivery));
            }
            return;
        }
        commitRoundResults(group, strategy, responses, null);
    }

    /**
     * 真正提交一轮已落地的发言。普通 2D 路径只会由 Speech Commit 回调进入这里；
     * 非 tick 的剧本讨论保持同步直入，避免把其非空间轨道语义耦合到声学投递。
     */
    private void commitRoundResults(ConversationGroup group, ConversationStrategy strategy,
                                    Map<String, String> responses, SpeechDelivery delivery) {
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
        if (delivery != null) {
            convEntry.put("delivery_tick", delivery.resolvedAtTick());
            convEntry.put("actual_listeners", new ArrayList<>(delivery.actualListeners()));
            convEntry.put("generation_started_at", delivery.utterance().generationStartedAt());
            convEntry.put("generation_finished_at", delivery.utterance().generationFinishedAt());
        }
        for (var entry : responses.entrySet()) {
            String val = entry.getValue();
            if (val != null && val.length() > 80) val = val.substring(0, 80);
            convEntry.put(entry.getKey(), val);
        }
        world.addConversationEntry(convEntry);
        if (conversationCompletedListener != null) {
            try {
                conversationCompletedListener.accept(new LinkedHashMap<>(convEntry));
            } catch (Exception e) {
                log.warn("Conversation social state callback failed: {}", e.getMessage());
            }
        }

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
        return createScriptDiscussionGroup(groupId, members, trackAssignments, GroupKind.SCRIPT_DISCUSSION);
    }

    /**
     * 建组阶段（同步）：创建固定成员讨论组并注册到 activeGroups。
     * 轨道分配由调用方显式给出（剧本杀：持秘密角色 WEAK / 未持 MERGED），
     * 不经过空间听觉与 ModeClassifier。
     *
     * <p>P-0815-A：kind 可显式指定（剧本杀/狼人杀讨论；缺省 3 参委托 SCRIPT_DISCUSSION 零破坏）。
     *
     * @return 讨论组（供 {@link #runScriptDiscussionRounds} 驱动）
     */
    public ConversationGroup createScriptDiscussionGroup(
            String groupId, List<AgentState> members,
            Map<String, TrackAssignment> trackAssignments, GroupKind kind) {
        ConversationGroup group = new ConversationGroup(
                groupId, ConversationMode.GROUP_DISCUSSION, members);
        group.setTrackAssignments(trackAssignments == null ? Map.of() : trackAssignments);
        group.setKind(kind == null ? GroupKind.SCRIPT_DISCUSSION : kind);

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
     * 旧签名（无门控）保留：全员每轮必发言（批次 A 行为，2D/旧测试路径不变）。
     */
    public ScriptDiscussionResult runScriptDiscussionRounds(ConversationGroup group, int maxRounds) {
        return runScriptDiscussionRounds(group, maxRounds, null);
    }

    /**
     * 轮次阶段（同步驱动，批次 D 门控版）：每轮先经 {@code roundGate} 决策“谁发言/谁静默”，
     * 再走 TrackStrategy。静默成员跳过 LLM 调用（省成本），输出静默占位“……（沉默）”入发言记录；
     * skipSpeakers（本轮已由人类发言、AI 不代声）完全跳过不产生占位。
     *
     * @param roundGate 每轮门控决策（group, 轮次下标 0-based → 决策）；null = 全员必发言（旧行为）
     */
    public ScriptDiscussionResult runScriptDiscussionRounds(ConversationGroup group, int maxRounds,
            java.util.function.BiFunction<ConversationGroup, Integer, RoundGateDecision> roundGate) {
        if (group == null) return new ScriptDiscussionResult(List.of(), Map.of());
        ConversationStrategy strategy = strategies.get(ConversationMode.GROUP_DISCUSSION);
        if (strategy == null) strategy = strategies.get(ConversationMode.DYAD);
        if (strategy == null) {
            dissolveGroup(group.getGroupId());
            return new ScriptDiscussionResult(List.of(), Map.of());
        }

        Map<String, String> lastContexts = new LinkedHashMap<>();
        int rounds = Math.max(1, maxRounds);
        // P-0810-17（B1）：讨论发言逐轮实时回调——记录已回调的发言条数，每轮结束后对新增发言
        // 逐条回调 scriptSpeechListener（ScriptGameService 订阅后转 script_speech SSE 实时回显，
        // 不再等全部轮次结束后才落盘）。未注册监听器（null）时循环零开销、行为逐字节不变。
        int emittedTurns = 0;
        for (int r = 0; r < rounds && group.isActive() && strategy.shouldContinue(group) && !stopped; r++) {
            try {
                RoundGateDecision gate = roundGate == null ? null : roundGate.apply(group, r);
                executeRound(group, strategy, lastContexts, gate);
                if (scriptSpeechListener != null) {
                    List<Map<String, String>> history = group.getMessageHistory();
                    for (int i = emittedTurns; i < history.size(); i++) {
                        Map<String, String> turn = history.get(i);
                        String speaker = turn == null ? null : turn.get("speaker");
                        String msg = turn == null ? null : turn.get("message");
                        if (speaker == null || msg == null || msg.isBlank()) continue;
                        try {
                            scriptSpeechListener.accept(new SpeechTurn(group.getGroupId(), speaker, msg));
                        } catch (Exception e) {
                            log.warn("Script speech listener failed for {}: {}", speaker, e.getMessage());
                        }
                    }
                    emittedTurns = history.size();
                }
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

    /**
     * 剧本杀讨论门控决策（批次 D）：每轮
     * <ul>
     *   <li>{@code speakMap}：name → true=LLM 发言 / false=静默占位（跳过 LLM）</li>
     *   <li>{@code skipSpeakers}：完全跳过（人类已发言，AI 不代声；不产生占位）</li>
     * </ul>
     */
    public record RoundGateDecision(Map<String, Boolean> speakMap, Set<String> skipSpeakers) {}

    /**
     * 解散群组（P-0813-H：包可见——串行队列推进单测直调；内部调用点不变）。
     * 解散后重算串行状态（若解散的是当前 RUNNING 群，队列下一个晋升）。
     */
    private void dissolveGroup(String groupId) {
        ConversationGroup group = activeGroups.remove(groupId);
        if (group == null) return;

        // P-0814-A：解散即唤醒该组等待中的轮次循环（active=false 后 notify，等待循环退出）
        group.setActive(false);
        group.wakePlaybackWaiters();

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
            // P-0815-A：组类型（AI_AUTO / USER_JOINED / SCRIPT_DISCUSSION / WEREWOLF_DISCUSSION）
            gs.put("kind", g.getKind().name());
            // P-0815-A：participants 保持 string[]（旧契约不动，前端 includes 消费兼容）；
            // 新增 participantInfo 逐项附 isPlayer 标记（AI 组 vs 用户组区分，前端未知字段不崩）。
            List<AgentState> participantStates = g.getParticipantList();
            gs.put("participants", participantStates.stream().map(AgentState::getAgentName).toList());
            List<Map<String, Object>> participantInfo = new ArrayList<>();
            for (AgentState s : participantStates) {
                Map<String, Object> pi = new LinkedHashMap<>();
                pi.put("name", s.getAgentName());
                pi.put("isPlayer", s.isPlayerControlled());
                participantInfo.add(pi);
            }
            gs.put("participantInfo", participantInfo);
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
        // P-0813-F：对话状态可查询（前端「接近提示→点击进入对话」配合）：
        // currentTrack=当前对话轨道（含玩家的活跃群组 id，空=未进入对话）；
        // pacing={state: in-dialogue|idle|disabled, 倍率/间隔}。
        ConversationGroup current = getCurrentPlayerGroup();
        status.put("currentTrack", current != null ? current.getGroupId() : "");
        status.put("pacingEnabled", pacingEnabled);
        Map<String, Object> pacing = new LinkedHashMap<>();
        if (pacingEnabled) {
            pacing.put("state", current != null ? "in-dialogue" : "idle");
            pacing.put("idleMultiplier", pacingIdleMultiplier);
            pacing.put("inactiveMultiplier", pacingInactiveMultiplier);
            pacing.put("roundCooldownMs", pacingRoundCooldownMs);
            pacing.put("groupRoundCooldownMs", pacingGroupRoundCooldownMs);
            pacing.put("conversationCooldownMs", pacingConversationCooldownMs);
            // P-0813-H：非玩家轨道发言的气泡停留/展示时长基准（前端可用作气泡展示时长/展示间隔）
            pacing.put("speechBubbleHoldMs", pacingSpeechBubbleHoldMs);
        } else {
            pacing.put("state", "disabled");
        }
        status.put("pacing", pacing);
        return status;
    }
}
