package com.roleplay.engine.service;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.conversation.ConversationGroup;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.conversation.SpeechGate;
import com.roleplay.engine.simulation.director.WorldDirectorService;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * ⭐ Complete werewolf game engine — rules, phase management, win detection.
 * Maps from Python core/werewolf_api.py + core/werewolf_game.py + games/werewolf_engine.py.
 *
 * <p>P-0802-F（主人授权后端批次）：在手工状态机之上补齐「自动推进」闭环——
 * <ul>
 *   <li>G0-1：init 全量进局 + 返回 session_id + 注册 RouterService（controller 层）</li>
 *   <li>D-014 纪律：customRoles 宽容解析（大小写不敏感 + 中英文别名，wolf→WEREWOLF、预言家→SEER…）</li>
 *   <li>G1-3：toMap 补 visible 键（狼人互认，原构造了 visible 却从未 put 进返回 map）</li>
 *   <li>G1-1：猎人夜间死亡保留一次开枪机会（原 resolveNight 直接置 hunterCanShoot=false 永久拒绝）</li>
 *   <li>G0-2：AI 夜间行动器（WerewolfAiPlanner，纯规则零 LLM）+ AI 白天投票 + AI 猎人反杀</li>
 *   <li>G0-3：werewolf_* SSE 推送（复用 SSEController.broadcast 既有管线，SseBroadcaster 接口注入可测）</li>
 *   <li>G0-4：白天讨论接对话引擎（独立 ConversationManager + TrackStrategy + SpeechGate，复用 D-012/D-022 资产）</li>
 * </ul>
 *
 * <p>autoPlay=true（配置 roleplay.game.werewolf.auto-play，默认 true）时整局自动推进：
 * 夜间 AI 行动 → 真人行动后自动结算 → 白天讨论自动驱动 → 自动进投票 → AI 投票 + 真人投票 →
 * 全员投完自动结算（D7 审批门保留，autoPlay 下按 auto-approve-ms 自动批准，0=等 DM 手动批准）。
 * autoPlay=false 保持纯手工 admin 驱动旧行为（resolve_night/start_voting/resolve_vote 手动调用）。
 */
@Service
public class WerewolfService {
    private static final Logger log = LoggerFactory.getLogger(WerewolfService.class);

    public enum Role { WEREWOLF, SEER, WITCH, VILLAGER, HUNTER }
    public enum Phase { NIGHT, DAY_DISCUSS, DAY_VOTE, JUDGMENT, ENDED }

    public static class GameState {
        final Map<String, Role> roles = new LinkedHashMap<>();
        final List<String> alive = new ArrayList<>();
        Phase phase = Phase.NIGHT;
        int round = 1;
        String winner = "";

        // Night actions
        String wolfTarget = "";
        String seerTarget = "";
        String seerResult = "";
        String witchSaveTarget = "";
        String witchPoisonTarget = "";
        boolean witchUsedAntidote = false;
        boolean witchUsedPoison = false;
        String lastNightVictim = "";
        String lastNightSaved = "";
        boolean hunterCanShoot = true;

        // P-0802-I (G1-2)：女巫获知被刀者机制 —— 狼刀决策完成后 witchInformed=true（女巫获知狼刀目标），
        // 获知后才允许救/毒决策；witchDeclinedSave=女巫明确选择不使用解药（不救）；witchInfoSent=已推送获知事件。
        volatile boolean witchInformed = false;
        boolean witchDeclinedSave = false;
        boolean witchInfoSent = false;

        // Voting
        final Map<String, String> votes = new LinkedHashMap<>(); // voter → target
        final List<Map<String, Object>> eliminated = new ArrayList<>();
        // P-0802-J: 角色令牌 —— player → roleKey（每玩家唯一；断线重连/顶号认证，对齐剧本杀 C3 roleKey 体系）
        final Map<String, String> playerKeys = new LinkedHashMap<>();

        // P-0802-F：当夜已完成行动决策（kill/check/save/poison，resetNight 清空，每夜重新决策）
        final Set<String> nightDecisions = new HashSet<>();
        // P-0802-F：白天讨论发言记录（对话引擎 transcript）
        final List<Map<String, String>> discussionTranscript = new ArrayList<>();
        // P-0802-F：人类白天讨论发言事件队列（讨论引擎每轮排空）
        final java.util.concurrent.BlockingQueue<Map<String, Object>> pendingHumanEvents =
                new java.util.concurrent.LinkedBlockingQueue<>();
        volatile boolean discussionActive = false;
        volatile boolean autoPlay = false;

        public Map<String, Object> toMap(String playerName) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase.name().toLowerCase());
            m.put("round", round);
            m.put("alive", new ArrayList<>(alive));
            m.put("your_role", roles.getOrDefault(playerName, Role.VILLAGER).name().toLowerCase());
            m.put("game_over", !winner.isEmpty());
            m.put("winner", winner);
            m.put("eliminated", new ArrayList<>(eliminated));
            if (roles.containsKey(playerName)) {
                Map<String, String> visible = new LinkedHashMap<>();
                roles.forEach((name, role) -> {
                    if (role == Role.WEREWOLF && roles.get(playerName) == Role.WEREWOLF)
                        visible.put(name, role.name().toLowerCase());
                    else if (name.equals(playerName))
                        visible.put(name, role.name().toLowerCase());
                });
                // G1-3 修复：visible 原构造后从未 put 进返回 map（狼人互认 API 层缺失）
                m.put("visible", visible);
                // P-0802-J: 角色令牌 —— 仅向本人暴露自己的 roleKey（重连认证/防冒充凭证；广播/匿名视图不含）
                m.put("role_key", playerKeys.getOrDefault(playerName, ""));
                if (roles.get(playerName) == Role.SEER && !seerResult.isEmpty()) {
                    m.put("seer_result", seerResult);
                    m.put("seer_target", seerTarget);
                }
                // P-0802-I (G1-2)：女巫视角暴露获知的被刀者（仅女巫本人可见；SSE 丢失/重连轮询兜底）
                if (roles.get(playerName) == Role.WITCH && witchInformed && !wolfTarget.isEmpty()) {
                    m.put("witch_victim", wolfTarget);
                }
            }
            if (!discussionTranscript.isEmpty()) {
                m.put("discussion", new ArrayList<>(discussionTranscript));
            }
            return m;
        }

        public boolean isGameOver() { return !winner.isEmpty(); }

        /** P-0802-P3：测试/编排钩子 —— 角色键表副本（局中改名断言用）。 */
        public Map<String, Role> getRoles() { return new LinkedHashMap<>(roles); }

        /** P-0802-P3：测试/编排钩子 —— 存活玩家列表副本。 */
        public List<String> getAlive() { return new ArrayList<>(alive); }
    }

    private final Map<String, GameState> games = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> humanPlayers = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> advancing = new ConcurrentHashMap<>();
    private final ApprovalService approvalService;
    // 非 final：测试可注入种子固定的 planner（确定性断言）
    private WerewolfAiPlanner planner = new WerewolfAiPlanner();

    // P-0802-F：可空注入（测试直构路径为 null，全部 null 守卫）
    private LLMClient llmClient;
    private SseBroadcaster sse;
    // P-0802-I：可空注入（测试直构路径为 null，快照落库 null 守卫）
    private DatabaseService databaseService;

    /** D7: 狼人杀审批门总开关 —— true=投票结算挂起等待 DM 审批（超时自动驳回回滚），false=自动通过。 */
    @Value("${roleplay.game.approval.enabled:true}")
    private boolean approvalEnabled = true;

    /** D7: 审批等待超时（秒），超时视为驳回。 */
    @Value("${roleplay.game.approval.timeout-seconds:60}")
    private long approvalTimeoutSeconds = 60;

    // ── P-0802-F 配置（roleplay.game.werewolf.*，对齐 D-004 阈值可配纪律）──
    @Value("${roleplay.game.werewolf.ai-night-actions:true}")
    private boolean aiNightActions = true;

    /** autoPlay：init 后整局自动推进（真人行动后自动结算流转）；false=纯手工 admin 驱动。 */
    @Value("${roleplay.game.werewolf.auto-play:true}")
    private boolean autoPlayDefault = true;

    /** autoPlay 下投票结算审批门自动批准延迟（ms）；0=不自动批，等 DM 手动批准（对齐 D7 语义）。 */
    @Value("${roleplay.game.werewolf.auto-approve-ms:3000}")
    private long autoApproveMs = 3000;

    /** AI 女巫后续夜使用毒药概率（0-1）。 */
    @Value("${roleplay.game.werewolf.witch-poison-probability:0.5}")
    private double witchPoisonProbability = 0.5;

    /** P-0802-I (G1-2)：AI 女巫获知被刀者后首夜使用解药的概率（0-1；1.0=经典首夜必救）。 */
    @Value("${roleplay.game.werewolf.witch-save-probability:1.0}")
    private double witchSaveProbability = 1.0;

    // ── 白天讨论引擎（复用 D-012/D-022 资产：独立 ConversationManager + TrackStrategy + SpeechGate）──
    @Value("${roleplay.game.discussion.max-rounds:2}")
    private int discussionMaxRounds = 2;
    @Value("${roleplay.game.discussion.silence-floor:0.15}")
    private double discussionSilenceFloor = 0.15;
    @Value("${roleplay.game.discussion.wait-bias:0.5}")
    private double discussionWaitBias = 0.5;
    @Value("${roleplay.game.discussion.cold-break:true}")
    private boolean discussionColdBreak = true;

    // ── P-0802-I：讨论引擎 per-game（按对局/会话隔离，多局并发不互扰；替代原 service 实例级共享）──
    // D-012 已知限制「讨论引擎为 service 实例级共享（同时只开一局讨论）」的阶段 1 修复：
    // 每局独立 SimulationWorld + ConversationManager + WorldDirectorService，互不覆盖/互不串状态。
    private final Map<String, SimulationWorld> discussionWorlds = new ConcurrentHashMap<>();
    private final Map<String, ConversationManager> discussionConversations = new ConcurrentHashMap<>();
    private final Map<String, WorldDirectorService> discussionDirectors = new ConcurrentHashMap<>();

    /** 游戏推进串行化（单线程）：自动结算/讨论收尾不并发，防状态竞争。 */
    private final ExecutorService gameExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "werewolf-game");
        t.setDaemon(true);
        return t;
    });
    /** 讨论轮次驱动（虚拟线程，LLM 大量 IO 等待）。 */
    private final ExecutorService discussionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public WerewolfService(ApprovalService approvalService) {
        this(approvalService, null, null, null);
    }

    /** 测试/旧调用路径：三参（无落库）。 */
    public WerewolfService(ApprovalService approvalService,
                           LLMClient llmClient,
                           SseBroadcaster sse) {
        this(approvalService, llmClient, sse, null);
    }

    /** Spring 注入路径（P-0802-F：LLMClient（白天讨论引擎）+ SseBroadcaster（werewolf_* SSE 推送）；
     *  P-0802-I：+ DatabaseService（对局快照落库/断线重连恢复））。 */
    @Autowired
    public WerewolfService(ApprovalService approvalService,
                           LLMClient llmClient,
                           SseBroadcaster sse,
                           DatabaseService databaseService) {
        this.approvalService = approvalService;
        this.llmClient = llmClient;
        this.sse = sse;
        this.databaseService = databaseService;
    }

    public GameState getGame(String sessionId) {
        return games.computeIfAbsent(sessionId, k -> new GameState());
    }

    /**
     * P-0802-P3（改造方案 §4.2.3）：局中改名 —— GameState 名字键全量迁移（per-session 锁 synchronized(g)）：
     * roles（:61）/alive（:62 列表元素）/votes（:86 voter→target 双向）/playerKeys（:89）/
     * eliminated（:87 内 name 字段）/discussionTranscript（:94 内 speaker 字段）/night 目标字符串
     * （wolfTarget/seerTarget/witchSaveTarget/witchPoisonTarget/lastNightVictim/lastNightSaved）；
     * humanPlayers（:141）集合换名；讨论引擎 world（若本局讨论已建）同步改名。
     * :614-619 判定（AI=alive∉humans）无需改——名字键同步后自动正确。
     * pendingHumanEvents 队列内既有事件不追溯（事件已在途，语义可接受，方案 §4.2.3 明确）。
     */
    public void renamePlayer(String sessionId, String oldName, String newName) {
        GameState g = games.get(sessionId);
        if (g == null) return;
        synchronized (g) {
            // roles: 换键
            Role role = g.roles.remove(oldName);
            if (role != null) g.roles.put(newName, role);
            // alive: 列表元素替换
            int ai = g.alive.indexOf(oldName);
            if (ai >= 0) g.alive.set(ai, newName);
            // votes: voter→target 双向替换
            Map<String, String> newVotes = new LinkedHashMap<>();
            g.votes.forEach((voter, target) -> newVotes.put(
                    voter.equals(oldName) ? newName : voter,
                    target.equals(oldName) ? newName : target));
            g.votes.clear();
            g.votes.putAll(newVotes);
            // playerKeys: 换键
            String key = g.playerKeys.remove(oldName);
            if (key != null) g.playerKeys.put(newName, key);
            // eliminated: 内嵌 name 字段替换
            for (Map<String, Object> e : g.eliminated) {
                if (oldName.equals(e.get("name"))) e.put("name", newName);
            }
            // discussionTranscript: 内嵌 speaker 字段替换
            for (Map<String, String> t : g.discussionTranscript) {
                if (oldName.equals(t.get("speaker"))) t.put("speaker", newName);
            }
            // night 目标字符串（若本夜引用旧名）
            if (oldName.equals(g.wolfTarget)) g.wolfTarget = newName;
            if (oldName.equals(g.seerTarget)) g.seerTarget = newName;
            if (oldName.equals(g.witchSaveTarget)) g.witchSaveTarget = newName;
            if (oldName.equals(g.witchPoisonTarget)) g.witchPoisonTarget = newName;
            if (oldName.equals(g.lastNightVictim)) g.lastNightVictim = newName;
            if (oldName.equals(g.lastNightSaved)) g.lastNightSaved = newName;
        }
        // humanPlayers 集合换名（服务级 map，独立于 GameState 锁）
        Set<String> humans = humanPlayers.get(sessionId);
        if (humans != null && humans.remove(oldName)) humans.add(newName);
        // 讨论引擎 world（若本局讨论已建）：agent 名同步（世界为 per-game 隔离实例）
        SimulationWorld w = discussionWorlds.get(sessionId);
        if (w != null) w.renameAgent(oldName, newName);
        // 改名是状态变更 → 快照落库（重连按新名恢复）
        saveSnapshot(sessionId, g);
        log.info("Werewolf player renamed in {}: {} → {}", sessionId, oldName, newName);
    }

    /** P-0802-P3：含指定玩家的所有活跃对局 sessionId（局中改名会话收集用）。 */
    public java.util.Set<String> sessionsOfPlayer(String playerName) {
        Set<String> out = new java.util.HashSet<>();
        games.forEach((sid, g) -> {
            if (g.roles.containsKey(playerName) || g.alive.contains(playerName)) out.add(sid);
        });
        return out;
    }

    /** P-0802-P3：是否任一活跃对局含指定玩家（局中改名撞名检查用）。 */
    public boolean anyGameHasPlayer(String playerName) {
        return !sessionsOfPlayer(playerName).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0802-F：角色宽容解析（D-014 纪律）与玩家登记
    // ═══════════════════════════════════════════════════════════

    /** D-014 宽容解析：大小写不敏感 + 中英文别名（wolf/狼/狼人→WEREWOLF 等）；非法输入返回 null。 */
    public static Role parseRole(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "wolf", "werewolf", "狼", "狼人" -> Role.WEREWOLF;
            case "seer", "预言家", "神婆", "先知" -> Role.SEER;
            case "witch", "女巫" -> Role.WITCH;
            case "hunter", "猎人" -> Role.HUNTER;
            case "villager", "村民", "平民", "好人" -> Role.VILLAGER;
            default -> {
                try {
                    yield Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    /** 登记本局人类玩家（AI = 存活玩家中不在 humans 集合中的玩家）。 */
    public void setHumanPlayers(String sessionId, Set<String> humans) {
        if (humans == null || humans.isEmpty()) {
            humanPlayers.remove(sessionId);
        } else {
            humanPlayers.put(sessionId, new HashSet<>(humans));
        }
    }

    /**
     * P-0802-P3（改造方案 §4.2.3）：仅同步 humanPlayers 集合换名（供只登记人类改名的场景复用）。
     * 与 {@link #renamePlayer} 的 humanPlayers 迁移逻辑同款，独立方法避免整体改名副作用。
     */
    public void renameHumanPlayer(String sessionId, String oldName, String newName) {
        Set<String> humans = humanPlayers.get(sessionId);
        if (humans != null && humans.remove(oldName)) {
            humans.add(newName);
        }
    }

    public Set<String> getHumanPlayers(String sessionId) {
        return humanPlayers.getOrDefault(sessionId, Set.of());
    }

    /** 开启/关闭本局自动推进（controller init 后调用）。 */
    public void setAutoPlay(String sessionId, boolean autoPlay) {
        GameState g = games.get(sessionId);
        if (g != null) g.autoPlay = autoPlay;
    }

    /** 测试用：替换为种子固定的 AI 行动器（确定性断言）。 */
    void setPlanner(WerewolfAiPlanner planner) {
        if (planner != null) this.planner = planner;
    }

    /** 测试用：覆盖审批自动批准延迟（ms）。 */
    void setAutoApproveMs(long ms) {
        this.autoApproveMs = ms;
    }

    /** 测试用：覆盖 AI 女巫毒药概率。 */
    void setWitchPoisonProbability(double p) {
        this.witchPoisonProbability = p;
    }

    /** 测试用：覆盖 AI 女巫首夜解药决策概率（G1-2）。 */
    void setWitchSaveProbability(double p) {
        this.witchSaveProbability = p;
    }

    // ═══════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> initGame(String sessionId, List<String> players,
                                          Map<String, String> customRoles) {
        GameState g = new GameState();
        if (players != null) g.alive.addAll(players);

        if (customRoles != null && !customRoles.isEmpty()) {
            customRoles.forEach((name, roleStr) -> {
                Role role = parseRole(roleStr);
                if (role == null) {
                    log.warn("Werewolf game {}: unknown role '{}' for {}, fallback VILLAGER", sessionId, roleStr, name);
                    role = Role.VILLAGER;
                }
                g.roles.put(name, role);
            });
            // Assign remaining players as villagers
            for (String p : players) {
                if (!g.roles.containsKey(p)) g.roles.put(p, Role.VILLAGER);
            }
        } else {
            assignDefaultRoles(g, players);
        }

        g.phase = Phase.NIGHT;
        g.round = 1;
        g.winner = "";
        resetNight(g);
        // P-0802-J: 每玩家生成唯一 roleKey（对齐剧本杀 C3：开房生成每角色 roleKey；断线重连/顶号认证一体）
        for (String p : g.alive) {
            g.playerKeys.put(p, UUID.randomUUID().toString());
        }
        games.put(sessionId, g);
        // P-0802-I：开局落快照（断线重连恢复的基础）
        saveSnapshot(sessionId, g);

        log.info("Werewolf game {}: {} players, roles={}", sessionId, players.size(), g.roles);
        return g.toMap(players.isEmpty() ? "" : players.get(0));
    }

    private void assignDefaultRoles(GameState g, List<String> players) {
        List<String> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        int n = shuffled.size();
        if (n >= 6) {
            g.roles.put(shuffled.get(0), Role.WEREWOLF);
            g.roles.put(shuffled.get(1), Role.WEREWOLF);
            g.roles.put(shuffled.get(2), Role.SEER);
            g.roles.put(shuffled.get(3), Role.WITCH);
            g.roles.put(shuffled.get(4), Role.HUNTER);
            for (int i = 5; i < n; i++) g.roles.put(shuffled.get(i), Role.VILLAGER);
        } else if (n >= 4) {
            g.roles.put(shuffled.get(0), Role.WEREWOLF);
            g.roles.put(shuffled.get(1), Role.WEREWOLF);
            g.roles.put(shuffled.get(2), Role.SEER);
            if (n > 3) g.roles.put(shuffled.get(3), Role.WITCH);
            for (int i = 4; i < n; i++) g.roles.put(shuffled.get(i), Role.VILLAGER);
        } else {
            players.forEach(p -> g.roles.put(p, Role.VILLAGER));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0802-F：夜间 AI 行动 + 自动推进
    // ═══════════════════════════════════════════════════════════

    /** 开局/每夜进入 NIGHT 后调用：AI 角色夜间自动行动；autoPlay 下若全员行动完毕自动结算。 */
    public void startNight(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return;
        if (g.autoPlay && aiNightActions) {
            runAiNightActions(sessionId);
            // P-0802-I (G1-2)：狼刀决策完成后向女巫定向推送获知事件（人类女巫先获知再决策）
            notifyWitchVictim(sessionId, g);
            advanceIfNightComplete(sessionId);
        }
    }

    /** 执行本局所有 AI 存活角色的夜间行动（狼刀/预言家验/女巫救毒）。返回行动描述。 */
    public List<String> runAiNightActions(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null || g.phase != Phase.NIGHT || !aiNightActions) return List.of();
        // P-0802-I (G1-2)：AI 女巫先获知被刀者（witchInformed），再按 witchSaveProbability 决策救/不救
        List<String> msgs = planner.planNight(g, getHumanPlayers(sessionId),
                witchPoisonProbability, witchSaveProbability);
        if (!msgs.isEmpty()) {
            log.info("Werewolf game {} AI night actions: {}", sessionId, msgs);
        }
        // P-0802-I (G1-2)：AI 狼刀完成后同样推送获知事件（若本局女巫为人类，供其先获知再决策）
        notifyWitchVictim(sessionId, g);
        return msgs;
    }

    /**
     * P-0802-I (G1-2)：女巫获知被刀者推送 —— 狼刀目标确定且女巫存活且未推送过时，
     * 定向推送 werewolf_witch_info（victim=狼刀目标，供人类女巫先获知再决定救/不救/毒）。
     */
    private void notifyWitchVictim(String sessionId, GameState g) {
        if (g == null || !g.witchInformed || g.witchInfoSent) return;
        boolean witchAlive = g.alive.stream().anyMatch(p -> g.roles.get(p) == Role.WITCH);
        if (!witchAlive) return;
        g.witchInfoSent = true;
        sse(sessionId, "werewolf_witch_info", Map.of(
                "session_id", sessionId,
                "victim", g.wolfTarget,
                "phase", "night",
                "round", g.round,
                "hint", "🌙 女巫获知：昨夜被刀者是 " + g.wolfTarget + "（请决定是否使用解药）"));
    }

    /** 当夜全员行动是否完毕（AI 已决策 + 人类已提交；无对应角色/药水已用完视为完成）。 */
    public boolean isNightComplete(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return false;
        return nightComplete(g, getHumanPlayers(sessionId));
    }

    /** 夜间完成判定（静态，供测试直接断言）：狼刀/查验/解药/毒药四项决策就绪即完成。 */
    public static boolean nightComplete(GameState g, Set<String> humans) {
        boolean noWolf = g.alive.stream().noneMatch(p -> g.roles.get(p) == Role.WEREWOLF);
        boolean noSeer = g.alive.stream().noneMatch(p -> g.roles.get(p) == Role.SEER);
        boolean noWitch = g.alive.stream().noneMatch(p -> g.roles.get(p) == Role.WITCH);
        boolean wolfDone = noWolf || g.nightDecisions.contains("kill");
        boolean seerDone = noSeer || g.nightDecisions.contains("check");
        // P-0802-I (G1-2)：解药决策 = 已救（save）或明确不救（nosave，保留解药），二者皆放行；
        // 毒药决策 = 已毒（poison）或明确不用毒（nopoison，保留毒药），二者皆放行
        boolean witchSaveDone = noWitch || g.witchUsedAntidote
                || g.nightDecisions.contains("save") || g.nightDecisions.contains("nosave");
        boolean witchPoisonDone = noWitch || g.witchUsedPoison
                || g.nightDecisions.contains("poison") || g.nightDecisions.contains("nopoison");
        return wolfDone && seerDone && witchSaveDone && witchPoisonDone;
    }

    /**
     * 夜间行动后自动推进：全员行动完毕 → 自动结算 → 白天讨论（autoPlay 模式）。
     * 用 per-game 原子标志防并发双结算（HTTP 线程与讨论收尾线程）。
     */
    public void advanceIfNightComplete(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null || !g.autoPlay || g.phase != Phase.NIGHT) return;
        if (!isNightComplete(sessionId)) {
            broadcastWaitHuman(sessionId, "night");
            return;
        }
        AtomicBoolean flag = advancing.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) return;
        try {
            if (g.phase != Phase.NIGHT) return;
            Map<String, Object> result = resolveNight(sessionId);
            broadcastNightResult(sessionId, g, result);
            if (g.phase == Phase.DAY_DISCUSS) {
                startDayDiscussion(sessionId);
            } else if (g.phase == Phase.ENDED) {
                broadcastGameOver(sessionId);
            }
        } finally {
            flag.set(false);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Night phase
    // ═══════════════════════════════════════════════════════════

    /** Record a night action. Returns result message for the player. */
    public String recordNightAction(String sessionId, String player, String action, String target) {
        GameState g = games.get(sessionId);
        if (g == null || g.phase != Phase.NIGHT) return "当前不是夜晚阶段";

        Role role = g.roles.get(player);
        String result = switch (action) {
            case "kill" -> {
                if (role == Role.WEREWOLF && g.alive.contains(target) && !target.equals(player)) {
                    g.wolfTarget = target;
                    g.nightDecisions.add("kill");
                    // P-0802-I (G1-2)：狼刀决策完成 → 女巫获知被刀者身份 + 定向推送（人类女巫先获知再决策）
                    g.witchInformed = true;
                    notifyWitchVictim(sessionId, g);
                    yield "狼人已选择目标：" + target;
                }
                yield "你不能执行此行动";
            }
            case "check" -> {
                if (role == Role.SEER && g.alive.contains(target)) {
                    g.seerTarget = target;
                    g.seerResult = g.roles.get(target).name().toLowerCase();
                    g.nightDecisions.add("check");
                    yield "预言家查验：" + target + " 的身份是 " + g.seerResult;
                }
                yield "你不能执行此行动";
            }
            case "save" -> {
                if (role == Role.WITCH && !g.witchUsedAntidote) {
                    if (!g.witchInformed) {
                        yield "女巫尚未获知被刀者信息（等待狼人行动）";
                    }
                    if (g.wolfTarget.isEmpty() || !g.alive.contains(g.wolfTarget)) {
                        yield "当前没有可救的被刀者";
                    }
                    if (!g.wolfTarget.equals(target)) {
                        yield "你只能救被刀者：" + g.wolfTarget;
                    }
                    g.witchSaveTarget = target;
                    g.witchUsedAntidote = true;
                    g.nightDecisions.add("save");
                    yield "女巫使用了解药，救起被刀者：" + target;
                }
                yield "女巫无法使用解药（已使用或无此目标）";
            }
            // P-0802-I (G1-2)：女巫明确选择不使用解药（保留解药，决策完成放行夜间）
            case "nosave" -> {
                if (role == Role.WITCH) {
                    if (g.witchUsedAntidote) yield "女巫已使用解药，无需选择";
                    if (!g.witchInformed) yield "女巫尚未获知被刀者信息（等待狼人行动）";
                    g.witchDeclinedSave = true;
                    g.nightDecisions.add("nosave");
                    yield "女巫选择不使用解药（保留解药）";
                }
                yield "你不能执行此行动";
            }
            case "poison" -> {
                if (role == Role.WITCH && !g.witchUsedPoison && g.alive.contains(target)) {
                    g.witchPoisonTarget = target;
                    g.witchUsedPoison = true;
                    g.nightDecisions.add("poison");
                    yield "女巫使用了毒药，目标：" + target;
                }
                yield "女巫无法使用毒药（已使用或无此目标）";
            }
            // P-0802-I (G1-2)：女巫明确选择不使用毒药（保留毒药，决策完成放行夜间）
            case "nopoison" -> {
                if (role == Role.WITCH) {
                    if (g.witchUsedPoison) yield "女巫已使用毒药，无需选择";
                    g.nightDecisions.add("nopoison");
                    yield "女巫选择不使用毒药（保留毒药）";
                }
                yield "你不能执行此行动";
            }
            default -> "未知行动";
        };
        // P-0802-F：人类行动后自动推进（autoPlay 模式）
        if (g.autoPlay && g.phase == Phase.NIGHT) {
            advanceIfNightComplete(sessionId);
        }
        return result;
    }

    /** End night phase, resolve actions, transition to day. Returns night results narration. */
    public Map<String, Object> resolveNight(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return Map.of("error", "游戏不存在");

        List<String> died = new ArrayList<>();

        // Resolve wolf kill
        if (!g.wolfTarget.isEmpty() && g.alive.contains(g.wolfTarget)) {
            if (g.witchSaveTarget.equals(g.wolfTarget)) {
                g.lastNightSaved = g.wolfTarget;
            } else {
                died.add(g.wolfTarget);
                g.lastNightVictim = g.wolfTarget;
            }
        }

        // Resolve witch poison
        if (!g.witchPoisonTarget.isEmpty() && g.alive.contains(g.witchPoisonTarget)) {
            if (!died.contains(g.witchPoisonTarget)) {
                died.add(g.witchPoisonTarget);
            }
        }

        // Apply deaths
        for (String d : died) {
            g.alive.remove(d);
            g.eliminated.add(Map.of("name", d, "reason", killedBy(g, d), "round", g.round));
        }

        // Check win condition
        String winner = checkWinCondition(g);
        if (!winner.isEmpty()) {
            g.winner = winner;
            g.phase = Phase.ENDED;
        } else {
            // G1-1 修复：猎人夜间死亡保留一次开枪机会（原逻辑此处置 hunterCanShoot=false 导致永久拒绝）。
            // hunterCanShoot 保持 true，由 hunterShoot 消费；AI 猎人（autoPlay 模式）立即自动反杀。
            if (g.autoPlay) {
                autoShootDeadHunter(sessionId, g);
                winner = checkWinCondition(g);
                if (!winner.isEmpty()) {
                    g.winner = winner;
                    g.phase = Phase.ENDED;
                }
            }
            if (g.phase != Phase.ENDED) {
                g.phase = Phase.DAY_DISCUSS;
            }
        }

        resetNight(g);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("died", died);
        result.put("saved", g.lastNightSaved.isEmpty() ? "" : g.lastNightSaved);
        result.put("phase", g.phase.name().toLowerCase());
        result.put("round", g.round);
        result.put("game_over", g.isGameOver());
        result.put("winner", g.winner);
        // P-0802-I：夜间结算结果落快照（重连恢复白天讨论起点）
        saveSnapshot(sessionId, g);
        return result;
    }

    /** AI 猎人反杀：本夜死亡的 AI 猎人自动开枪带走随机存活玩家（autoPlay 模式；无存活目标不开枪）。 */
    private void autoShootDeadHunter(String sessionId, GameState g) {
        Set<String> humans = getHumanPlayers(sessionId);
        List<String> deadHunters = g.eliminated.stream()
                .filter(e -> Integer.valueOf(g.round).equals(e.get("round")))
                .map(e -> (String) e.get("name"))
                .filter(n -> g.roles.get(n) == Role.HUNTER)
                .filter(n -> !humans.contains(n))
                .toList();
        for (String hunter : deadHunters) {
            if (g.isGameOver()) break;
            String target = planner.planHunterShoot(g, hunter);
            if (target.isEmpty()) continue;
            g.hunterCanShoot = false;
            g.alive.remove(target);
            g.eliminated.add(Map.of("name", target, "reason", "被猎人反击击杀", "round", g.round));
            log.info("AI hunter {} shot {}", hunter, target);
            String w = checkWinCondition(g);
            if (!w.isEmpty()) {
                g.winner = w;
                g.phase = Phase.ENDED;
                break;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Voting
    // ═══════════════════════════════════════════════════════════

    /** Record a vote from one player to another. */
    public String castVote(String sessionId, String voter, String target) {
        GameState g = games.get(sessionId);
        if (g == null) return "游戏不存在";
        if (g.phase != Phase.DAY_VOTE) return "当前不是投票阶段";
        if (!g.alive.contains(voter)) return "已死亡玩家不能投票";
        if (voter.equals(target)) return "不能投自己";
        g.votes.put(voter, target);
        // P-0802-F：投票进度推送（P-0802-I：定向推送）
        sse(sessionId, "werewolf_vote_update", Map.of(
                "session_id", sessionId, "phase", g.phase.name().toLowerCase(),
                "round", g.round, "votes_count", g.votes.size()));
        // P-0802-I：票面落快照（重连恢复投票进度）
        saveSnapshot(sessionId, g);
        // P-0802-F：全员投完自动结算（autoPlay 模式）
        if (g.autoPlay) {
            maybeAdvanceVote(sessionId);
        }
        return voter + " 投票给了 " + target;
    }

    /** 全员（存活）投票完毕 → 自动结算（AI 补投 + 真人投完触发）。 */
    private void maybeAdvanceVote(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null || !g.autoPlay || g.phase != Phase.DAY_VOTE) return;
        AtomicBoolean flag = advancing.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) return;
        try {
            if (g.phase != Phase.DAY_VOTE) return;
            // AI 补投
            Map<String, String> aiVotes = planner.planVotes(g, getHumanPlayers(sessionId));
            for (Map.Entry<String, String> v : aiVotes.entrySet()) {
                if (!g.votes.containsKey(v.getKey())) g.votes.put(v.getKey(), v.getValue());
            }
            Set<String> alive = new HashSet<>(g.alive);
            if (alive.equals(g.votes.keySet())) {
                resolveVoteAuto(sessionId);
            } else {
                broadcastWaitHuman(sessionId, "vote");
            }
        } finally {
            flag.set(false);
        }
    }

    /**
     * 异步结算投票（阻塞审批门，跑在虚拟线程）：结算 → 广播 → 回 NIGHT 自动开局夜 / ENDED 广播终局。
     * autoPlay 下按 auto-approve-ms 自动批准（0=等 DM 手动批准/驳回）。
     */
    private void resolveVoteAuto(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return;
        // P-0802-F：审批门挂起提示（前端据此显示批准/驳回按钮，D7 语义保留）
        sse(sessionId, "werewolf_vote_update", Map.of(
                "session_id", sessionId, "approval", "pending",
                "phase", "day_vote", "round", g.round));
        discussionExecutor.submit(() -> {
            try {
                Map<String, Object> result = resolveVote(sessionId);
                broadcastVoteResult(sessionId, g, result);
                if (g.phase == Phase.NIGHT) {
                    startNight(sessionId);
                } else if (g.phase == Phase.ENDED) {
                    broadcastGameOver(sessionId);
                }
            } catch (Exception e) {
                log.warn("Werewolf game {} auto resolve vote failed: {}", sessionId, e.getMessage());
            }
        });
        if (autoApproveMs > 0) {
            scheduleAutoApprove(sessionId);
        }
    }

    /** autoPlay 自动批准：延迟 autoApproveMs 后若仍 pending 则批准（对齐 D7 语义的 solo 兜底）。 */
    private void scheduleAutoApprove(String sessionId) {
        gameExecutor.submit(() -> {
            try {
                Thread.sleep(autoApproveMs);
                if ("pending".equals(approvalService.getStatus(sessionId))) {
                    approvalService.approve(sessionId);
                    log.info("Werewolf game {} vote settlement auto-approved after {}ms", sessionId, autoApproveMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Resolve votes, eliminate the most-voted player.（D7：投票结算接入审批门） */
    public Map<String, Object> resolveVote(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return Map.of("error", "游戏不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("votes", new LinkedHashMap<>(g.votes));

        // 先统计得票（先计算、后应用，便于审批回滚）
        String topTarget = "";
        int topCount = 0;
        boolean tie = false;
        if (!g.votes.isEmpty()) {
            Map<String, Integer> count = new LinkedHashMap<>();
            g.votes.values().forEach(t -> count.merge(t, 1, Integer::sum));
            topTarget = Collections.max(count.entrySet(), Map.Entry.comparingByValue()).getKey();
            topCount = count.get(topTarget);
            final int topCountFinal = topCount; // effectively-final 副本（lambda 引用要求）
            tie = count.values().stream().filter(c -> c == topCountFinal).count() > 1;
        }

        // D7: 投票结算为关键决策点 —— 挂起等待 DM 审批，批准后才应用放逐；驳回/超时回滚且保留票数
        if (approvalEnabled && g.phase == Phase.DAY_VOTE && !g.isGameOver() && !g.votes.isEmpty()) {
            Map<String, Object> decision = awaitVoteSettlementApproval(sessionId, g, topTarget, topCount, tie);
            if (decision != null) return decision;
            result.put("approval", "approved");
        }

        // 应用结算（原逻辑）
        if (g.votes.isEmpty()) {
            result.put("exiled", "");
            result.put("reason", "无人投票，无人被放逐");
        } else if (tie) {
            result.put("exiled", "");
            result.put("reason", "平票，无人被放逐");
        } else {
            g.alive.remove(topTarget);
            g.eliminated.add(Map.of("name", topTarget, "reason", "被投票放逐", "round", g.round));
            result.put("exiled", topTarget);
            result.put("reason", topTarget + " 被放逐");
            // P-0802-F：被放逐的 AI 猎人（autoPlay）自动开枪反杀
            if (g.autoPlay && g.roles.get(topTarget) == Role.HUNTER
                    && !getHumanPlayers(sessionId).contains(topTarget) && !g.isGameOver()) {
                autoShootExiledHunter(g, topTarget);
            }
        }

        // Clear votes for next round
        g.votes.clear();

        // Check win condition
        String winner = checkWinCondition(g);
        if (!winner.isEmpty()) {
            g.winner = winner;
            g.phase = Phase.ENDED;
        } else {
            g.round++;
            g.phase = Phase.NIGHT;
        }

        result.put("phase", g.phase.name().toLowerCase());
        result.put("round", g.round);
        result.put("game_over", g.isGameOver());
        result.put("winner", g.winner);
        // P-0802-I：投票结算结果落快照（重连恢复新夜晚起点）
        saveSnapshot(sessionId, g);
        return result;
    }

    /** 白天被放逐的 AI 猎人立即开枪（autoPlay 模式）。 */
    private void autoShootExiledHunter(GameState g, String hunter) {
        String target = planner.planHunterShoot(g, hunter);
        if (target.isEmpty()) return;
        g.hunterCanShoot = false;
        g.alive.remove(target);
        g.eliminated.add(Map.of("name", target, "reason", "被猎人反击击杀", "round", g.round));
        log.info("AI hunter {} (exiled) shot {}", hunter, target);
    }

    /**
     * D7: 投票结算审批门 —— 拟放逐结果提交 ApprovalService 挂起等待 DM 审批。
     * 返回 null 表示批准（调用方继续结算）；返回 Map 表示已回滚（驳回/超时/中断，票数保留）。
     */
    private Map<String, Object> awaitVoteSettlementApproval(String sessionId, GameState g,
                                                            String topTarget, int topCount, boolean tie) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gate", "werewolf_vote_settlement");
        payload.put("session_id", sessionId);
        payload.put("round", g.round);
        payload.put("votes", new LinkedHashMap<>(g.votes));
        payload.put("proposed_exile", tie ? "" : topTarget);
        payload.put("vote_count", topCount);
        payload.put("tie", tie);
        payload.put("phase", "day_vote_settlement");

        List<Map<String, Object>> outputs = new ArrayList<>();
        for (Map.Entry<String, String> v : g.votes.entrySet()) {
            outputs.add(Map.of("voter", v.getKey(), "target", v.getValue()));
        }

        RouterService.RoundResult round = new RouterService.RoundResult(
            "werewolf_vote_settlement",
            outputs,
            payload,
            "狼人杀投票结算：拟放逐=" + (tie ? "平票" : topTarget) + "，得票=" + topCount,
            Map.of("gate", "werewolf_vote_settlement"));

        try {
            RouterService.RoundResult approved = approvalService.submitForApproval(round, sessionId, approvalTimeoutSeconds);
            if (approved == null) {
                log.warn("Werewolf game {} vote settlement rejected/timeout, rollback (votes kept)", sessionId);
                Map<String, Object> rollback = new LinkedHashMap<>();
                rollback.put("votes", new LinkedHashMap<>(g.votes));
                rollback.put("exiled", "");
                rollback.put("reason", "投票结算被驳回或超时，已回滚，可重新投票");
                rollback.put("phase", g.phase.name().toLowerCase());
                rollback.put("round", g.round);
                rollback.put("game_over", g.isGameOver());
                rollback.put("winner", g.winner);
                rollback.put("approval", "rejected");
                rollback.put("rollback", true);
                return rollback;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Werewolf game {} vote settlement approval interrupted", sessionId);
            Map<String, Object> rollback = new LinkedHashMap<>();
            rollback.put("votes", new LinkedHashMap<>(g.votes));
            rollback.put("exiled", "");
            rollback.put("reason", "审批流程被中断，已回滚");
            rollback.put("phase", g.phase.name().toLowerCase());
            rollback.put("round", g.round);
            rollback.put("game_over", g.isGameOver());
            rollback.put("winner", g.winner);
            return rollback;
        }
        return null;
    }

    /** Start voting phase (transition from discussion). */
    public void startVoting(String sessionId) {
        GameState g = games.get(sessionId);
        if (g != null && g.phase == Phase.DAY_DISCUSS) {
            g.phase = Phase.DAY_VOTE;
            g.votes.clear();
            sse(sessionId, "werewolf_phase", Map.of("session_id", sessionId, "phase", "day_vote", "round", g.round));
            broadcastPlayers(sessionId);
            // P-0802-I：阶段流转落快照（重连恢复投票中状态）
            saveSnapshot(sessionId, g);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0802-F：白天讨论接对话引擎（复用 D-012/D-022 资产）
    // ═══════════════════════════════════════════════════════════

    /**
     * 白天讨论：建组（同步）→ 后台虚拟线程驱动轮次（SpeechGate 门控）→ 结束自动进投票。
     * 无 LLM（测试/降级路径）或存活不足 2 人 → 直接进投票。
     */
    public void startDayDiscussion(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null || g.phase != Phase.DAY_DISCUSS || g.discussionActive) return;
        sse(sessionId, "werewolf_phase", Map.of("session_id", sessionId, "phase", "day_discuss", "round", g.round));
        if (llmClient == null || g.alive.size() < 2) {
            log.info("Werewolf game {}: no LLM/alive<2, skip discussion → voting", sessionId);
            gameExecutor.submit(() -> finishDayDiscussion(sessionId));
            return;
        }
        g.discussionActive = true;
        discussionExecutor.submit(() -> {
            try {
                runDiscussionEngine(g, sessionId);
            } catch (Exception e) {
                log.warn("Werewolf game {} day discussion failed: {}", sessionId, e.getMessage());
            } finally {
                g.discussionActive = false;
                gameExecutor.submit(() -> finishDayDiscussion(sessionId));
            }
        });
    }

    /** 讨论收尾（串行化）：结束自动进投票 + AI 投票 + 全员投完自动结算。 */
    private void finishDayDiscussion(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null || g.phase != Phase.DAY_DISCUSS) return;
        if (g.isGameOver()) {
            broadcastGameOver(sessionId);
            return;
        }
        startVoting(sessionId);
        if (g.autoPlay) {
            maybeAdvanceVote(sessionId);
        }
    }

    /** 人类白天讨论发言：入队待讨论引擎下轮排空（与剧本杀 discussionSay 同模式）。 */
    public Map<String, Object> discussionSay(String sessionId, String player, String message) {
        GameState g = games.get(sessionId);
        if (g == null) return Map.of("error", "游戏不存在");
        if (g.phase != Phase.DAY_DISCUSS || !g.discussionActive) {
            return Map.of("error", "当前不是白天讨论阶段");
        }
        if (!g.alive.contains(player)) return Map.of("error", "已死亡玩家不能发言");
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) return Map.of("error", "发言内容为空");
        g.pendingHumanEvents.offer(Map.of("player", player, "text", text));
        log.info("Werewolf game {} human {} spoke in day discussion: {}", sessionId, player, text);
        // P-0810-17（B4）：Map.of（不可变）→ LinkedHashMap —— WerewolfController.discussionSay
        // 对返回 map 再 put session_id，不可变 map 抛 UnsupportedOperationException → HTTP 500
        // （P-0810-06 真机已复现）；改可变 map 一行修复（控制器侧另有防御性拷贝兜底）。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("player", player);
        result.put("message", text);
        return result;
    }

    /**
     * P-0802-I：per-game 懒创建讨论引擎 —— 每局独立 ConversationManager + SimulationWorld + WorldDirectorService
     * （替代原 service 实例级共享，多局并发互不覆盖/互不串状态；对齐 D-012 设计但隔离维度从实例级升级为对局级）。
     */
    private ConversationManager ensureDiscussionEngine(String sessionId) {
        return discussionConversations.computeIfAbsent(sessionId, k -> {
            SimulationWorld world = new SimulationWorld();
            ConversationManager cm = new ConversationManager();
            cm.init(world, llmClient,
                    name -> world.getAgent(name),
                    () -> world.getWorldNarration());
            discussionWorlds.put(k, world);
            discussionDirectors.put(k, new WorldDirectorService());
            return cm;
        });
    }

    /** 测试钩子（同包）：指定对局的讨论引擎实例（断言 per-game 隔离用）。 */
    ConversationManager getDiscussionConversation(String sessionId) {
        return discussionConversations.get(sessionId);
    }

    /** 讨论引擎编排：注册角色（身份卡不含他人秘密）→ 目标注入 → 全员 MERGED 轨道 → 建组 → 门控驱动轮次。 */
    private void runDiscussionEngine(GameState g, String sessionId) {
        // P-0802-I：改用 per-game 实例（世界/引擎/导演均按对局隔离）
        ConversationManager cm = ensureDiscussionEngine(sessionId);
        SimulationWorld world = discussionWorlds.get(sessionId);
        WorldDirectorService director = discussionDirectors.get(sessionId);
        world.clearAgents();
        world.setWorldNarration("你们正在狼人杀白天讨论阶段，通过发言找出狼人。");

        for (String p : g.alive) {
            Agent agent = new Agent(buildWerewolfDiscussionPersona(g, p), "npc", llmClient);
            double x = 100 + Math.random() * 800;
            double y = 100 + Math.random() * 400;
            world.registerAgent(agent, x, y, 220, 60);
            AgentState st = world.getState(p);
            if (st != null) st.setEmotion(Emotion.NEUTRAL);
        }

        // 目标注入（狼人隐藏身份 / 好人找出狼人）
        for (String p : g.alive) {
            boolean wolf = g.roles.get(p) == Role.WEREWOLF;
            director.setGoal(p, wolf ? GOAL_WOLF_HIDE : GOAL_VILLAGER_FIND);
        }

        List<AgentState> members = new ArrayList<>();
        Map<String, TrackAssignment> tracks = new LinkedHashMap<>();
        for (String p : g.alive) {
            AgentState st = world.getState(p);
            if (st == null) continue;
            members.add(st);
            List<String> others = g.alive.stream().filter(q -> !q.equals(p)).toList();
            tracks.put(p, TrackAssignment.of(p, Track.Mode.MERGED, others,
                    "公开讨论（MERGED）：狼人身份不进上下文，靠发言博弈"));
        }

        ConversationGroup group = cm.createScriptDiscussionGroup(
                "ww-disc-" + sessionId, members, tracks);
        SpeechGate speechGate = new SpeechGate(discussionSilenceFloor, discussionWaitBias, discussionColdBreak);
        int[] scannedTurns = {0};
        BiFunction<ConversationGroup, Integer, ConversationManager.RoundGateDecision> gate =
                buildWerewolfGate(g, speechGate, scannedTurns);
        ConversationManager.ScriptDiscussionResult result =
                cm.runScriptDiscussionRounds(group, discussionMaxRounds, gate);
        g.discussionTranscript.addAll(result.transcript());
        for (Map<String, String> turn : result.transcript()) {
            String speaker = turn.getOrDefault("speaker", "");
            String msg = turn.getOrDefault("message", "");
            if (msg.isBlank()) continue;
            sse(sessionId, "werewolf_speech", Map.of("session_id", sessionId, "speaker", speaker, "message", msg));
        }
        log.info("Werewolf game {} day discussion done: {} turns", sessionId, result.transcript().size());
    }

    /** 狼人杀讨论 persona —— 身份卡只含本人身份 + 狼人互认 + 昨夜信息，不含他人秘密（对齐 A3-2 纪律）。 */
    private Persona buildWerewolfDiscussionPersona(GameState g, String player) {
        Role role = g.roles.get(player);
        String roleLabel = roleLabel(role);
        StringBuilder desc = new StringBuilder();
        desc.append("你正在玩一局狼人杀，玩家名为").append(player).append("。你的身份：").append(roleLabel).append("。");
        switch (role) {
            case WEREWOLF -> {
                List<String> mates = g.alive.stream()
                        .filter(p -> !p.equals(player) && g.roles.get(p) == Role.WEREWOLF)
                        .toList();
                if (!mates.isEmpty()) desc.append("你的狼人同伴：").append(String.join("、", mates)).append("。");
                desc.append("隐藏身份，白天伪装成普通玩家参与讨论，误导他人、保护狼队。");
            }
            case SEER -> desc.append("你每晚可以查验一名玩家的身份。白天利用查验结果引导好人找出狼人，但注意隐藏身份避免被狼刀。");
            case WITCH -> desc.append("你有一瓶解药和一瓶毒药。白天隐藏身份，暗中协助好人阵营。");
            case HUNTER -> desc.append("你被淘汰时可以开枪带走一名玩家。白天隐藏身份，谨慎发言。");
            default -> desc.append("你是普通村民，白天通过发言、试探与推理找出狼人。");
        }
        if (!g.lastNightVictim.isEmpty() && !g.lastNightVictim.equals(player)) {
            desc.append("昨夜死者：").append(g.lastNightVictim).append("。");
        }
        desc.append("白天讨论时请基于己方信息发言、试探他人、隐藏身份。");
        Persona p = new Persona(player);
        p.setPersonaDesc(desc.toString());
        p.setVoice("贴合狼人杀玩家身份，发言谨慎，避免暴露身份");
        return p;
    }

    /**
     * 讨论门控（每轮一次）：排空人类发言（人类发言权豁免，该角色 AI 不代声）→ 扫描点名/提问触发 →
     * 触发/轮次首句必发言 → 其余 SpeechGate 打分（talkativeness=0.5 × 动机 8 vs silence-floor）。
     */
    private BiFunction<ConversationGroup, Integer, ConversationManager.RoundGateDecision>
            buildWerewolfGate(GameState g, SpeechGate speechGate, int[] scannedTurns) {
        return (group, roundIdx) -> {
            List<Map<String, Object>> events = new ArrayList<>();
            Map<String, Object> ev;
            while ((ev = g.pendingHumanEvents.poll()) != null) events.add(ev);
            Set<String> humanSpoken = new HashSet<>();
            for (Map<String, Object> e : events) {
                String p = String.valueOf(e.get("player"));
                String txt = String.valueOf(e.getOrDefault("text", ""));
                humanSpoken.add(p);
                group.recordTurn(p, txt.isBlank() ? "（发言）" : txt);
            }

            List<SpeechGate.SpeechTrigger> triggers = new ArrayList<>();
            List<Map<String, String>> history = group.getMessageHistory();
            int from = Math.min(scannedTurns[0], history.size());
            scannedTurns[0] = history.size();
            for (int i = from; i < history.size(); i++) {
                Map<String, String> turn = history.get(i);
                String speaker = turn.get("speaker");
                String msg = turn.get("message");
                if (speaker == null || msg == null || msg.isBlank()) continue;
                for (String member : g.alive) {
                    if (member.equals(speaker)) continue;
                    if (SpeechGate.isMentioning(msg, member)) {
                        triggers.add(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.MENTION, member));
                    }
                    if (SpeechGate.isQuestioning(msg, member)) {
                        triggers.add(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.QUESTION, member));
                    }
                }
            }

            Map<String, Boolean> speakMap = new LinkedHashMap<>();
            Set<String> skip = new HashSet<>(humanSpoken);
            for (String p : g.alive) {
                if (skip.contains(p)) continue;
                boolean triggered = triggers.stream().anyMatch(t -> t.target() == null || t.target().equals(p));
                if (triggered || roundIdx == 0) {
                    speakMap.put(p, true);
                    continue;
                }
                SpeechGate.GateDecision d = speechGate.decide(p, 0.5, 8, triggers, false, !humanSpoken.isEmpty());
                speakMap.put(p, d.speak());
            }
            return new ConversationManager.RoundGateDecision(speakMap, skip);
        };
    }

    private static final String GOAL_WOLF_HIDE = "隐藏狼人身份，混淆视听";
    private static final String GOAL_VILLAGER_FIND = "找出狼人";

    private static String roleLabel(Role role) {
        return switch (role) {
            case WEREWOLF -> "狼人";
            case SEER -> "预言家";
            case WITCH -> "女巫";
            case HUNTER -> "猎人";
            case VILLAGER -> "村民";
        };
    }

    // ═══════════════════════════════════════════════════════════
    //  Win condition
    // ═══════════════════════════════════════════════════════════

    private String checkWinCondition(GameState g) {
        long wolves = g.alive.stream().filter(p -> g.roles.get(p) == Role.WEREWOLF).count();
        long villagers = g.alive.stream().filter(p -> g.roles.get(p) != Role.WEREWOLF).count();

        if (wolves == 0) return "villager";
        if (wolves >= villagers) return "werewolf";
        return "";
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void resetNight(GameState g) {
        g.wolfTarget = "";
        g.seerTarget = "";
        g.seerResult = "";
        g.witchSaveTarget = "";
        g.witchPoisonTarget = "";
        g.lastNightVictim = "";
        g.lastNightSaved = "";
        g.nightDecisions.clear();
        // P-0802-I (G1-2)：每夜重置女巫获知状态（新夜重新获知/重新决策/重新推送）
        g.witchInformed = false;
        g.witchDeclinedSave = false;
        g.witchInfoSent = false;
    }

    /** Hunter retaliates — picks a target to shoot after death. */
    public String hunterShoot(String sessionId, String player, String target) {
        GameState g = games.get(sessionId);
        if (g == null) return "游戏不存在";
        if (!g.eliminated.stream().anyMatch(e -> player.equals(e.get("name"))))
            return "只有被淘汰的猎人才能开枪";
        if (!g.hunterCanShoot) return "猎人已经开过枪了";
        if (!g.alive.contains(target)) return target + " 已死亡";
        g.hunterCanShoot = false;
        g.alive.remove(target);
        g.eliminated.add(Map.of("name", target, "reason", "被猎人反击击杀", "round", g.round));

        // Re-check win condition after hunter shot
        String winner = checkWinCondition(g);
        if (!winner.isEmpty()) {
            g.winner = winner;
            g.phase = Phase.ENDED;
        }
        sse(sessionId, "werewolf_player_eliminated", Map.of("session_id", sessionId, "name", target, "role", ""));
        broadcastPlayers(sessionId);
        if (g.phase == Phase.ENDED) {
            broadcastGameOver(sessionId);
        }
        // P-0802-I：开枪结果落快照
        saveSnapshot(sessionId, g);
        return "猎人 " + player + " 开枪击杀了 " + target;
    }

    private String killedBy(GameState g, String player) {
        if (player.equals(g.wolfTarget) && !player.equals(g.witchSaveTarget)) return "被狼人杀害";
        if (player.equals(g.witchPoisonTarget)) return "被女巫毒杀";
        return "死亡";
    }

    public void endGame(String sessionId) {
        GameState g = games.get(sessionId);
        if (g != null) {
            g.phase = Phase.ENDED;
            // P-0802-I：终态落快照（重连可恢复终态结果）
            saveSnapshot(sessionId, g);
        }
    }

    public boolean isPlayerAlive(String sessionId, String player) {
        GameState g = games.get(sessionId);
        return g != null && g.alive.contains(player);
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0802-F：werewolf_* SSE 推送（复用 SSEController.broadcast 既有管线，null 守卫）
    //  P-0802-I：改为按会话定向推送（broadcastToSession）—— 多客户端/多对局并发互不串扰（D-013 已知限制修复）
    // ═══════════════════════════════════════════════════════════

    private void sse(String sessionId, String event, Map<String, Object> payload) {
        if (sse != null) {
            try {
                sse.broadcastToSession(sessionId, event, payload);
            } catch (Exception e) {
                log.warn("Werewolf SSE {} failed: {}", event, e.getMessage());
            }
        }
    }

    /** 初始化通知：玩家列表 + 人类角色 + 阶段（controller init 后调用）。 */
    public void notifyGameInit(String sessionId, String humanPlayer) {
        GameState g = games.get(sessionId);
        if (g == null) return;
        broadcastPlayers(sessionId);
        if (humanPlayer != null && !humanPlayer.isEmpty()) {
            Role r = g.roles.get(humanPlayer);
            if (r != null) {
                sse(sessionId, "werewolf_my_role", Map.of("session_id", sessionId, "role", r.name().toLowerCase()));
            }
        }
        sse(sessionId, "werewolf_phase", Map.of("session_id", sessionId, "phase", g.phase.name().toLowerCase(), "round", g.round));
    }

    /** 玩家列表推送（全局广播，不含角色身份——角色按玩家视角经 status API 获取，防信息泄露）。 */
    private void broadcastPlayers(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return;
        List<Map<String, Object>> players = new ArrayList<>();
        Set<String> aliveSet = new HashSet<>(g.alive);
        for (String name : g.roles.keySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name);
            p.put("alive", aliveSet.contains(name));
            p.put("role", "");
            p.put("roleRevealed", false);
            players.add(p);
        }
        sse(sessionId, "werewolf_player_update", Map.of("session_id", sessionId, "players", players));
    }

    /** 夜间结算推送：结果 + 出局公告 + 女巫提示。 */
    private void broadcastNightResult(String sessionId, GameState g, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>(result);
        payload.put("session_id", sessionId);
        sse(sessionId, "werewolf_night_result", payload);
        @SuppressWarnings("unchecked")
        List<String> died = (List<String>) result.getOrDefault("died", List.of());
        for (String d : died) {
            Role r = g.roles.get(d);
            sse(sessionId, "werewolf_player_eliminated", Map.of(
                    "session_id", sessionId, "name", d,
                    "role", r == null ? "" : r.name().toLowerCase()));
        }
        if (!died.isEmpty()) {
            String victimText = String.join("、", died);
            sse(sessionId, "werewolf_witch_info", Map.of(
                    "session_id", sessionId, "victim", victimText,
                    "hint", "昨夜死亡：" + victimText));
        }
        broadcastPlayers(sessionId);
    }

    /** 投票结算推送：结果 + 出局公告 + 玩家列表刷新。 */
    private void broadcastVoteResult(String sessionId, GameState g, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>(result);
        payload.put("session_id", sessionId);
        sse(sessionId, "werewolf_vote_update", payload);
        String exiled = String.valueOf(result.getOrDefault("exiled", ""));
        if (!exiled.isEmpty() && !"null".equals(exiled)) {
            Role r = g.roles.get(exiled);
            sse(sessionId, "werewolf_player_eliminated", Map.of(
                    "session_id", sessionId, "name", exiled,
                    "role", r == null ? "" : r.name().toLowerCase()));
            broadcastPlayers(sessionId);
        }
    }

    /** 等待真人行动推送。 */
    private void broadcastWaitHuman(String sessionId, String phaseKey) {
        GameState g = games.get(sessionId);
        if (g == null) return;
        String phase = g.phase.name().toLowerCase();
        String message = "night".equals(phaseKey)
                ? "请真人玩家完成夜间行动（狼刀/查验/救毒）"
                : "请真人玩家投票";
        sse(sessionId, "werewolf_wait_human", Map.of(
                "session_id", sessionId, "phase", phase,
                "round", g.round, "message", message));
    }

    /** 终局推送。 */
    private void broadcastGameOver(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return;
        String message = "werewolf".equals(g.winner) ? "🐺 狼人阵营获胜！" : "🕊️ 好人阵营获胜！";
        sse(sessionId, "werewolf_game_over", Map.of(
                "session_id", sessionId, "winner", g.winner,
                "phase", "ended", "message", message));
        broadcastPlayers(sessionId);
    }

    /** 当前局简要状态（供 controller status 附加 session_id 等）。 */
    public Map<String, Object> statusMap(String sessionId, String player) {
        GameState g = games.get(sessionId);
        if (g == null) return Map.of("game_over", true, "phase", "idle");
        Map<String, Object> m = new LinkedHashMap<>(g.toMap(player));
        m.put("session_id", sessionId);
        m.put("waiting_human", !isNightComplete(sessionId) || g.votes.size() < g.alive.size());
        return m;
    }

    // ═══════════════════════════════════════════════════════════
    //  P-0802-I：对局快照落库 / 断线重连恢复（对齐 D-017 C3 剧本杀先例）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对局快照落库 —— 全量状态写 ScriptEntity（type=snapshot，name 前缀「对局快照:ww:<sessionId>」，
     * 复用 DatabaseService.getLatestScriptSnapshot("ww:" + sessionId) 读取，零新表/零 DatabaseService 改动）。
     * databaseService 为 null（直接构造的测试）时跳过。
     */
    private void saveSnapshot(String sessionId, GameState g) {
        if (databaseService == null || g == null) return;
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "snapshot");
            content.put("session_id", sessionId);
            content.put("saved_at", java.time.LocalDateTime.now().toString());
            content.put("phase", g.phase.name());
            content.put("round", g.round);
            content.put("winner", g.winner);
            Map<String, String> roles = new LinkedHashMap<>();
            g.roles.forEach((n, r) -> roles.put(n, r.name()));
            content.put("roles", roles);
            content.put("alive", new ArrayList<>(g.alive));
            content.put("wolf_target", g.wolfTarget);
            content.put("seer_target", g.seerTarget);
            content.put("seer_result", g.seerResult);
            content.put("witch_save_target", g.witchSaveTarget);
            content.put("witch_poison_target", g.witchPoisonTarget);
            content.put("witch_used_antidote", g.witchUsedAntidote);
            content.put("witch_used_poison", g.witchUsedPoison);
            content.put("witch_informed", g.witchInformed);
            content.put("witch_declined_save", g.witchDeclinedSave);
            content.put("last_night_victim", g.lastNightVictim);
            content.put("last_night_saved", g.lastNightSaved);
            content.put("hunter_can_shoot", g.hunterCanShoot);
            content.put("votes", new LinkedHashMap<>(g.votes));
            content.put("eliminated", new ArrayList<>(g.eliminated));
            content.put("night_decisions", new ArrayList<>(g.nightDecisions));
            content.put("discussion_transcript", new ArrayList<>(g.discussionTranscript));
            content.put("discussion_active", g.discussionActive);
            content.put("auto_play", g.autoPlay);
            content.put("humans", new ArrayList<>(getHumanPlayers(sessionId)));
            // P-0802-J: roleKey 随快照持久化（重启恢复后 resume 仍可校验玩家身份）
            content.put("player_keys", new LinkedHashMap<>(g.playerKeys));
            databaseService.saveScript("对局快照:ww:" + sessionId, content);
        } catch (Exception e) {
            log.warn("Werewolf game {} snapshot save failed: {}", sessionId, e.getMessage());
        }
    }

    /**
     * P-0802-J: 玩家 roleKey 是否有效（匹配该玩家在本局的令牌）。
     * 对齐剧本杀 C3：有 key 校验匹配（防冒充）；无对局/无玩家/空 key 均判无效。
     */
    public boolean isPlayerKeyValid(String sessionId, String player, String playerKey) {
        if (sessionId == null || player == null || player.isBlank()
                || playerKey == null || playerKey.isBlank()) return false;
        GameState g = games.get(sessionId);
        if (g == null) return false;
        String expected = g.playerKeys.get(player);
        return expected != null && expected.equals(playerKey);
    }

    /** P-0802-J: 某玩家的 roleKey（客户端存储/重连凭证；无对局/无玩家返回空串）。 */
    public String getRoleKey(String sessionId, String player) {
        GameState g = games.get(sessionId);
        if (g == null || player == null) return "";
        return g.playerKeys.getOrDefault(player, "");
    }

    /** P-0802-J: 由 roleKey 反查玩家（仅内存对局；重启后需先经 session_id 恢复再反查）。 */
    public String findPlayerByKey(String sessionId, String playerKey) {
        if (sessionId == null || playerKey == null || playerKey.isBlank()) return "";
        GameState g = games.get(sessionId);
        if (g == null) return "";
        for (Map.Entry<String, String> e : g.playerKeys.entrySet()) {
            if (playerKey.equals(e.getValue())) return e.getKey();
        }
        return "";
    }

    /** P-0802-J: 由 playerKey 反查其所属对局 sessionId（仅内存对局；重启后请用 session_id/room_code 定位）。 */
    public String findSessionByPlayerKey(String playerKey) {
        if (playerKey == null || playerKey.isBlank()) return "";
        for (Map.Entry<String, GameState> e : games.entrySet()) {
            if (e.getValue().playerKeys.containsValue(playerKey)) return e.getKey();
        }
        return "";
    }

    /** P-0802-J: 全员 roleKey 一览（DM/主持人分发令牌用；无对局返回空 map）。 */
    public Map<String, String> getPlayerKeys(String sessionId) {
        GameState g = games.get(sessionId);
        return g == null ? Map.of() : new LinkedHashMap<>(g.playerKeys);
    }

    /**
     * P-0802-J：断线重连恢复入口（roleKey 认证版）—— 内存对局存在直接返回该玩家视角；
     * 不存在则从持久化快照重建（重启后可用）；已终局返回终态（terminal）。
     *
     * <p>防冒充（对齐剧本杀 C3 roleKey 体系）：resume 必须携带 player_key（本人 roleKey）——
     * 仅传 key 时由 key 反查玩家；传了 player 名时校验 key 与其匹配。缺 key / key 不匹配
     * → 拒绝恢复（错误信息含「身份校验失败」），杜绝「拿到 session_id 即可恢复任意角色」。
     *
     * @return 玩家视图（含 restored/resumed/session_id 附加键；终局含 terminal/winner）
     */
    public Map<String, Object> resumeGame(String sessionId, String player, String playerKey) {
        if (sessionId == null || sessionId.isBlank()) return Map.of("error", "缺少对局标识");
        GameState g = games.get(sessionId);
        boolean restored = false;
        if (g == null) {
            g = restoreFromSnapshot(sessionId);
            if (g == null) return Map.of("error", "对局不存在且无快照可恢复");
            restored = true;
        }
        // 由 roleKey 反查玩家（重连场景客户端可能只持 key，不传玩家名）
        String resolved = (player == null || player.isBlank()) ? findPlayerByKey(sessionId, playerKey) : player;
        if (resolved == null || resolved.isBlank() || playerKey == null || playerKey.isBlank()
                || !isPlayerKeyValid(sessionId, resolved, playerKey)) {
            return Map.of("error", "身份校验失败：player_key 缺失或不匹配");
        }
        Map<String, Object> view = new LinkedHashMap<>(g.toMap(resolved));
        view.put("session_id", sessionId);
        view.put("restored", restored);
        view.put("resumed", true);
        view.put("player", resolved);
        if (g.isGameOver()) {
            view.put("terminal", true);
            view.put("winner", g.winner);
        }
        log.info("Werewolf game {} resumed by player {} (restored={}, phase={})",
                sessionId, resolved, restored, g.phase.name().toLowerCase());
        return view;
    }

    /** 从持久化快照重建对局（重启后恢复）。无快照返回 null；重建后重新放入 games 缓存。 */
    private GameState restoreFromSnapshot(String sessionId) {
        if (databaseService == null) return null;
        Optional<Map<String, Object>> snap = databaseService.getLatestScriptSnapshot("ww:" + sessionId);
        if (snap.isEmpty()) return null;
        Map<String, Object> c = snap.get();
        if (!sessionId.equals(str(c.get("session_id")))) return null;

        GameState g = new GameState();
        Object rolesObj = c.get("roles");
        if (rolesObj instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                Role r = parseRole(str(e.getValue()));
                if (r != null) g.roles.put(str(e.getKey()), r);
            }
        }
        for (String p : strList(c.get("alive"))) {
            if (!g.roles.containsKey(p)) g.roles.put(p, Role.VILLAGER);
            g.alive.add(p);
        }
        g.phase = parsePhase(str(c.get("phase")));
        g.round = intOf(c.get("round"), 1);
        g.winner = str(c.get("winner"));
        g.wolfTarget = str(c.get("wolf_target"));
        g.seerTarget = str(c.get("seer_target"));
        g.seerResult = str(c.get("seer_result"));
        g.witchSaveTarget = str(c.get("witch_save_target"));
        g.witchPoisonTarget = str(c.get("witch_poison_target"));
        g.witchUsedAntidote = boolOf(c.get("witch_used_antidote"), false);
        g.witchUsedPoison = boolOf(c.get("witch_used_poison"), false);
        g.witchInformed = boolOf(c.get("witch_informed"), false);
        g.witchDeclinedSave = boolOf(c.get("witch_declined_save"), false);
        g.lastNightVictim = str(c.get("last_night_victim"));
        g.lastNightSaved = str(c.get("last_night_saved"));
        g.hunterCanShoot = boolOf(c.get("hunter_can_shoot"), true);
        g.votes.putAll(strMap(c.get("votes")));
        for (Object o : mapList(c.get("eliminated"))) {
            if (o instanceof Map<?, ?> mm) {
                Map<String, Object> e = new LinkedHashMap<>();
                mm.forEach((k, v) -> e.put(str(k), v));
                g.eliminated.add(e);
            }
        }
        g.nightDecisions.addAll(strList(c.get("night_decisions")));
        for (Object o : mapList(c.get("discussion_transcript"))) {
            if (o instanceof Map<?, ?> mm) {
                Map<String, String> turn = new LinkedHashMap<>();
                mm.forEach((k, v) -> turn.put(str(k), str(v)));
                g.discussionTranscript.add(turn);
            }
        }
        g.discussionActive = boolOf(c.get("discussion_active"), false);
        g.autoPlay = boolOf(c.get("auto_play"), true);
        // P-0802-J: roleKey 快照恢复（旧快照无此键 → 空 map，resume 需重新发放前的 key 不可用）
        g.playerKeys.putAll(strMap(c.get("player_keys")));
        Set<String> humans = new HashSet<>(strList(c.get("humans")));
        if (!humans.isEmpty()) humanPlayers.put(sessionId, humans);

        games.put(sessionId, g);
        log.info("Werewolf game {} restored from snapshot (phase={}, alive={}, round={})",
                sessionId, g.phase.name(), g.alive.size(), g.round);
        return g;
    }

    // ── P-0802-I：快照恢复的宽容转换辅助（Jackson 反序列化后类型为 Map/List/Number 等）──

    private static Phase parsePhase(String s) {
        if (s == null || s.isBlank()) return Phase.NIGHT;
        try {
            return Phase.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Phase.NIGHT;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean boolOf(Object o, boolean dflt) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return dflt;
    }

    private static int intOf(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) { }
        }
        return dflt;
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x != null) out.add(String.valueOf(x));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map) out.add((Map<String, Object>) x);
            }
        }
        return out;
    }
}
