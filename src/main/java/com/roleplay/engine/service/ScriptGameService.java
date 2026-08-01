package com.roleplay.engine.service;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.broadcast.BroadcastMessage;
import com.roleplay.engine.controller.SSEController;
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

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ⭐ Script murder mystery — full game lifecycle.
 *
 * <p>Phases:
 * <ol>
 *   <li>SETUP — generate script, assign roles</li>
 *   <li>INVESTIGATION — players search locations for clues</li>
 *   <li>DISCUSSION — players share/synth clues, accuse</li>
 *   <li>VOTE — players vote for suspect（平票时清空票数复用 VOTE 重投，D6）</li>
 *   <li>REVEAL — show truth + score（揭晓前经审批门挂起等待 DM 审批，D7）</li>
 * </ol>
 *
 * <p>Maps from Python core/script_runtime.py (which was empty — this is new).
 */
@Service
public class ScriptGameService {
    private static final Logger log = LoggerFactory.getLogger(ScriptGameService.class);

    private final LLMClient llmClient;
    private final ApprovalService approvalService;

    /** C1: 统一剧本生成路径（唯一生成器，Schema v1 输出，见 docs/剧本-schema-v1.md）。 */
    private final ScriptService scriptService;

    /** GAP-4c: 剧本落库（可空——测试直接构造时不传，saveScript 调用有 null 守卫）。 */
    private final DatabaseService databaseService;
    /** GAP-8: script SSE 推送（可空——测试直接构造时不传，broadcast 调用有 null 守卫）。 */
    private final SSEController sse;

    /** 方案B Step 3：剧本杀阶段切换 → SYSTEM 级广播到 announcement 全局横幅通道
     *  （可空——二/四参构造不传；与 script_phase 会话面板通道并存不冲突）。 */
    private final AnnouncementService announcementService;

    /** D7: 审批门总开关 —— true=揭晓挂起等待 DM 审批（超时自动驳回回滚），false=自动通过。 */
    @Value("${roleplay.game.approval.enabled:true}")
    private boolean approvalEnabled = true;

    /** D7: 审批等待超时（秒），超时视为驳回。 */
    @Value("${roleplay.game.approval.timeout-seconds:60}")
    private long approvalTimeoutSeconds = 60;

    /** GAP-3: 剧本杀讨论轮数（可配置，默认 2，与蓝图降级路径对齐）。 */
    @Value("${roleplay.game.discussion.max-rounds:2}")
    private int discussionMaxRounds = 2;

    /** 批次 D: 发言门控静默阈值（demo 实测安全区间 [0.10, 0.20]，默认 0.15；0.25 会冷场失衡）。 */
    @Value("${roleplay.game.discussion.silence-floor:0.15}")
    private double discussionSilenceFloor = 0.15;

    /** 批次 D: 临时应激目标（被质疑→辩解）衰减轮数（demo：pri100 衰减 25/轮≈4 轮；默认 3）。 */
    @Value("${roleplay.game.discussion.priority-decay-rounds:3}")
    private int priorityDecayRounds = 3;

    /** 批次 D: 人类发言中，未被点名的 AI 静默等待系数（P × wait-bias；默认 0.5，对齐 demo）。 */
    @Value("${roleplay.game.discussion.wait-bias:0.5}")
    private double discussionWaitBias = 0.5;

    /** 批次 D: 冷场破冰总开关（连续全员静默 → 按动机指定破冰者；默认 true）。 */
    @Value("${roleplay.game.discussion.cold-break:true}")
    private boolean discussionColdBreak = true;

    /** C2: 玩家初始行动点基础值（角色 ap_bonus 叠加；行动力限制，蓝图 P2，可配置）。 */
    @Value("${roleplay.game.ap.base:3}")
    private int apBase = 3;

    /** C2: 搜证阶段提示文案（AP 不足拒绝）。 */
    public static final String ERR_AP_INSUFFICIENT = "行动点不足";

    /** GAP-3: 目标驱动 —— 未持秘密角色注入“查明真相”。 */
    public static final String GOAL_FIND_TRUTH = "查明真相";
    /** GAP-3: 目标驱动 —— 持秘密角色注入“隐藏秘密”。 */
    public static final String GOAL_HIDE_SECRET = "隐藏秘密";
    /** 批次 D: 临时应激目标 —— 被质疑/被点名时瞬时拉满优先级的“辩解”目标（衰减后回落）。 */
    public static final String GOAL_DEFEND = "辩解";
    /** GAP-3: 讨论组 ID 前缀。 */
    private static final String DISCUSSION_GROUP_PREFIX = "script_discussion_";

    /** GAP-3: 讨论目标管理（角色想做什么，WorldDirectorService.setGoal 注入）。 */
    private final WorldDirectorService worldDirector;
    /** GAP-3: 讨论世界（轻量 SimulationWorld，仅作 Agent 容器，不起 tick）。懒创建。 */
    private SimulationWorld discussionWorld;
    /** GAP-3: 讨论对话引擎。独立实例（不复用 2D 模拟的 Spring 单例，避免重 init 互相覆盖）。懒创建。 */
    private ConversationManager discussionConversation;
    /** GAP-3: 讨论轮次执行线程池（虚拟线程，讨论结束自动进 VOTE）。 */
    private final ExecutorService discussionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public enum Phase { SETUP, INVESTIGATION, DISCUSSION, VOTE, REVEAL, ENDED }

    public static class ScriptGame {
        String sessionId;
        volatile Phase phase = Phase.SETUP;

        // Script data
        String name = "未命名剧本";
        String background = "";
        String truth = "";
        /** C1: 剧本 Schema v1 全量（generateScript 输出，落库与双生成器一致性验证用）。 */
        Map<String, Object> scriptSchema;
        /** C1: schema 中的凶手角色 id（role_x；兼容旧数据可为空，运行时判定仍走 truth 解析 D6）。 */
        String killerId = "";
        final List<String> roles = new ArrayList<>();
        final List<String> players = new ArrayList<>();
        final Map<String, String> assignments = new LinkedHashMap<>(); // player → role
        final Map<String, String> secrets = new LinkedHashMap<>();     // role → secret（D5：每个角色只看到自己的）

        // Game state
        int round = 1;
        final List<Map<String, Object>> clues = new ArrayList<>(); // all discovered clues
        final Map<String, List<String>> playerClues = new LinkedHashMap<>(); // player → clueIds
        // C2: 行动点 —— player → 当前剩余 AP（搜证消耗；初始 = 基础值 + 角色 ap_bonus）
        final Map<String, Integer> playerAp = new LinkedHashMap<>();
        // C2: 行动点上限 —— player → 初始 AP（status/ap_max 展示用）
        final Map<String, Integer> playerApMax = new LinkedHashMap<>();
        // 批次 D: 人格化健谈度 —— player → talkativeness（发言门控概率输入；缺省 0.5，schema roles[].talkativeness）
        final Map<String, Double> playerTalkativeness = new LinkedHashMap<>();
        // 批次 D: 人类玩家标记 —— player → isHuman（当前全部玩家为真人；人类发言豁免门控、AI 按事件响应）
        final Map<String, Boolean> playerIsHuman = new LinkedHashMap<>();
        // 批次 D: 人类发言事件队列（discussionSay 入队，讨论线程每轮开头排空注入）
        final java.util.concurrent.ConcurrentLinkedQueue<Map<String, Object>> pendingHumanEvents =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        // C3: 角色令牌 —— player → roleKey（每个玩家唯一；断线重连/顶号认证，对齐 Chronos roleKey）
        final Map<String, String> playerKeys = new LinkedHashMap<>();
        final Map<String, String> votes = new LinkedHashMap<>(); // voter → suspect
        final List<String> locations = new ArrayList<>();
        String winner = "";
        boolean simulationStarted = false;

        // GAP-4b: 判定结果缓存（resolveVote 揭晓时写入，ENDED 落库/展示用）
        String murderer = "";
        boolean correctVerdict = false;

        // GAP-3: 讨论引擎产物 —— 发言记录 + 最后一轮各成员上下文（WEAK 隔离验证 A3-2）
        final List<Map<String, String>> discussionTranscript = new ArrayList<>();
        final Map<String, String> discussionContexts = new LinkedHashMap<>();
        /** 讨论组已建且轮次进行中（A3-1/A3-3 验收）。 */
        volatile boolean discussionActive = false;

        public Map<String, Object> toMap(String playerName) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase.name().toLowerCase());
            m.put("session_id", sessionId);
            m.put("schema_version", scriptSchema == null ? 0 : ScriptSchemaV1.CURRENT_VERSION);
            m.put("name", name);
            m.put("background", background);
            m.put("roles", new ArrayList<>(roles));
            m.put("players", new ArrayList<>(players));
            String role = assignments.getOrDefault(playerName, "");
            m.put("your_role", role);
            // D5: secrets 发放 —— 每个玩家只能看到自己扮演角色的秘密
            m.put("your_secret", role.isEmpty() ? "" : secrets.getOrDefault(role, ""));
            m.put("round", round);
            m.put("game_over", !winner.isEmpty());
            m.put("winner", winner);
            m.put("simulation_started", simulationStarted);
            if (simulationStarted) {
                m.put("simulation_url", "/simulation.html");
            }
            m.put("clues", clues.stream()
                .filter(c -> c.getOrDefault("public", false).equals(true)
                    || (playerName != null && playerClues.getOrDefault(playerName, List.of())
                        .contains(c.get("id"))))
                .collect(Collectors.toList()));
            // C2: 行动点 —— 请求者剩余 AP / 上限 + 全员 AP 一览（各玩家剩余 AP 透明可见）
            m.put("ap", playerName == null ? 0 : playerAp.getOrDefault(playerName, 0));
            m.put("ap_max", playerName == null ? 0 : playerApMax.getOrDefault(playerName, 0));
            Map<String, Object> apPool = new LinkedHashMap<>();
            for (String p : players) apPool.put(p, playerAp.getOrDefault(p, 0));
            m.put("ap_pool", apPool);
            // C2: 请求者持有的全部线索对象（含转入的；转交 UI 数据源）
            m.put("my_clues", heldCluesOf(playerName));
            // C3: 角色令牌 —— 仅向本人暴露自己的 roleKey（重连认证用；广播/匿名视图不含，防泄露）
            if (playerName != null && !playerName.isBlank()) {
                m.put("role_key", playerKeys.getOrDefault(playerName, ""));
            }
            m.put("locations", new ArrayList<>(locations));
            if (!discussionTranscript.isEmpty()) {
                m.put("discussion", new ArrayList<>(discussionTranscript));
            }
            // C3: ENDED 终态附加判定结果（终态全员可见真相，终局展示用；附加键不破坏既有契约）
            if (phase == Phase.ENDED) {
                m.put("murderer", murderer);
                m.put("correct", correctVerdict);
            }
            return m;
        }

        /** D5: 该玩家分配到的角色名（未分配返回空串）。 */
        public String getRoleOf(String playerName) {
            if (playerName == null) return "";
            return assignments.getOrDefault(playerName, "");
        }

        /** D5: 发放给对应角色的秘密 —— 每个角色只能看到自己的 secret。 */
        public String getSecretFor(String playerName) {
            if (playerName == null) return "";
            String role = assignments.getOrDefault(playerName, "");
            return role.isEmpty() ? "" : secrets.getOrDefault(role, "");
        }

        /** C2: 玩家持有的线索对象列表（按持有顺序；转交后 ownership 变更，接收方即可见）。 */
        public List<Map<String, Object>> heldCluesOf(String playerName) {
            List<Map<String, Object>> out = new ArrayList<>();
            if (playerName == null) return out;
            for (String id : playerClues.getOrDefault(playerName, List.of())) {
                for (Map<String, Object> c : clues) {
                    if (id.equals(c.get("id"))) {
                        out.add(c);
                        break;
                    }
                }
            }
            return out;
        }

        public Map<String, String> getSecrets() {
            return secrets;
        }

        public List<String> getPlayers() {
            return new ArrayList<>(players);
        }

        public boolean isSimulationStarted() {
            return simulationStarted;
        }
    }

    private final Map<String, ScriptGame> games = new ConcurrentHashMap<>();

    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService) {
        this(llmClient, approvalService, null, null, null);
    }

    /** 测试/兼容构造（GAP-4c/GAP-8 路径，无方案B announcement 注入）。 */
    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService,
                             DatabaseService databaseService, SSEController sse) {
        this(llmClient, approvalService, databaseService, sse, null);
    }

    /** Spring 注入路径（GAP-4c/GAP-8 + 方案B Step 3）：剧本落库 + script SSE 推送 + announcement SYSTEM 广播。 */
    @Autowired
    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService,
                             DatabaseService databaseService, SSEController sse,
                             AnnouncementService announcementService) {
        this.llmClient = llmClient;
        this.approvalService = approvalService;
        this.databaseService = databaseService;
        this.sse = sse;
        this.announcementService = announcementService;
        this.worldDirector = new WorldDirectorService(llmClient);
        this.scriptService = new ScriptService(llmClient);
    }

    /** Phase 1: Generate script and assign roles. */
    public Map<String, Object> initGame(String sessionId, String theme, List<String> playerNames) {
        ScriptGame game = new ScriptGame();
        game.sessionId = sessionId;
        game.players.addAll(playerNames);

        // C1: 统一生成路径 —— 委托 ScriptService.generateScript（Schema v1 输出，宽容解析旧/新格式）
        Map<String, Object> script = scriptService.generateScript(theme, playerNames);
        game.scriptSchema = script;

        game.name = ScriptSchemaV1.title(script);
        game.background = ScriptSchemaV1.background(script);
        game.truth = ScriptSchemaV1.truth(script);
        game.killerId = ScriptSchemaV1.killerId(script);

        // roles: 规范角色名序列（secrets 键集合恒等于 roles，A1-3）
        List<String> roles = ScriptSchemaV1.roleNames(script);
        game.roles.addAll(roles);

        game.locations.addAll(ScriptSchemaV1.locations(script));

        // clues: 规范化线索（id/title/location/content/transferable/visible_to_owner_only + public 兼容键）
        game.clues.addAll(ScriptSchemaV1.clueList(script));

        // D5: secrets 发放 —— 从 schema 解析角色秘密（角色名 → 秘密），按角色存储
        game.secrets.putAll(ScriptSchemaV1.secretsByRole(script));

        // Assign roles to players (shuffle)
        List<String> shuffledRoles = new ArrayList<>(roles);
        Collections.shuffle(shuffledRoles);
        for (int i = 0; i < playerNames.size() && i < shuffledRoles.size(); i++) {
            game.assignments.put(playerNames.get(i), shuffledRoles.get(i));
        }
        // Leftover players get generic roles
        for (int i = shuffledRoles.size(); i < playerNames.size(); i++) {
            game.assignments.put(playerNames.get(i), "嫌疑人_" + (i - shuffledRoles.size() + 1));
        }

        // C2: 按角色分配初始 AP（基础值 + 角色 ap_bonus；侦探类角色行动点多，蓝图 P2 角色差异化搜证）
        Map<String, Integer> apBonusByName = ScriptSchemaV1.apBonusByRoleName(script);
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, "");
            int bonus = role.isEmpty() ? 0 : apBonusByName.getOrDefault(role, 0);
            int ap = Math.max(1, apBase + bonus); // 至少 1 点，避免 0 AP 死局
            game.playerAp.put(player, ap);
            game.playerApMax.put(player, ap);
        }

        // 批次 D: 按角色分配 talkativeness（schema roles[].talkativeness，缺省 0.5；发言门控概率输入）
        Map<String, Double> talkativenessByName = ScriptSchemaV1.talkativenessByRoleName(script);
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, "");
            double tt = role.isEmpty() ? ScriptSchemaV1.DEFAULT_TALKATIVENESS
                    : talkativenessByName.getOrDefault(role, ScriptSchemaV1.DEFAULT_TALKATIVENESS);
            game.playerTalkativeness.put(player, Math.max(0.0, Math.min(1.0, tt)));
        }
        // 批次 D: 人类玩家标记（当前剧本杀全员为真人玩家；AI 讨论角色由引擎代声）
        for (String player : game.players) {
            game.playerIsHuman.put(player, true);
        }

        // C3: 每玩家生成唯一 roleKey（对齐 Chronos：开房生成每角色 roleKey；断线重连/顶号认证一体）
        for (String player : game.players) {
            game.playerKeys.put(player, UUID.randomUUID().toString());
        }

        game.phase = Phase.INVESTIGATION;
        game.round = 1;
        games.put(sessionId, game);

        // GAP-4c: 剧本生成即落库（对局重启不丢剧本；A4-3 验收依赖）
        persistScript(game);
        // C3: 初始快照落库（开房即有恢复点；断线重连/崩溃恢复基础）
        saveSnapshot(game);
        // GAP-8: 剧本生成完成，推送首阶段 + 状态
        broadcastPhase(game, "investigation");
        broadcastStatus(game);

        log.info("Script game {}: {} players, {} locations, {} clues, {} secrets",
            sessionId, playerNames.size(), game.locations.size(), game.clues.size(), game.secrets.size());

        return game.toMap(playerNames.isEmpty() ? "" : playerNames.get(0));
    }

    /**
     * Phase 2: Search a location for clues（C2：行动点机制——搜证为“搜索地点”动作，消耗 AP）。
     *
     * <p>设计（对齐 Chronos CLUE_SEARCH）：① 一次搜索 = 探索一个地点，获得该地点全部未持有的
     * 可搜线索（public=false，即持有者专属线索），消耗 AP = 各线索 ap_cost 之和（旧剧本无 ap_cost → 默认 1）；
     * ② AP 不足 → 整次拒绝，不部分授予（“行动点不足”提示）；③ 公开线索（public=true）不消耗 AP、
     * 无需搜证，始终随状态可见；④ 非本局玩家不可搜证。
     */
    public Map<String, Object> search(String sessionId, String player, String location) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase != Phase.INVESTIGATION) return Map.of("error", "当前不是搜证阶段");
        if (player == null || player.isBlank()) return Map.of("error", "缺少玩家名");
        if (location == null || location.isBlank()) return Map.of("error", "缺少搜索地点");
        if (!game.players.contains(player)) return Map.of("error", "玩家不在本局中");

        // 该地点玩家尚未获得的可搜线索（public=false = 持有者专属，需搜证；public=true = 公开，无需搜证）
        List<Map<String, Object>> found = game.clues.stream()
            .filter(c -> location.equals(c.get("location")))
            .filter(c -> !Boolean.TRUE.equals(c.get("public")))
            .filter(c -> !game.playerClues.getOrDefault(player, List.of()).contains(c.get("id")))
            .collect(Collectors.toList());

        // 公开线索（始终可见，不耗 AP）
        List<Map<String, Object>> publicClues = game.clues.stream()
            .filter(c -> location.equals(c.get("location")))
            .filter(c -> Boolean.TRUE.equals(c.get("public")))
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("location", location);
        result.put("public_clues", publicClues.stream()
            .map(c -> Map.of("id", c.get("id"), "content", c.get("content")))
            .collect(Collectors.toList()));

        if (found.isEmpty()) {
            result.put("found", List.of());
            result.put("clues", List.of());
            result.put("result", "该地点没有更多可搜证线索");
            result.put("ap", game.playerAp.getOrDefault(player, 0));
            result.put("ap_cost", 0);
            return result;
        }

        // C2: 本次搜证总消耗 = 各线索 ap_cost 之和（旧剧本无 ap_cost → 默认 1）
        int cost = found.stream().mapToInt(ScriptSchemaV1::apCost).sum();
        int ap = game.playerAp.getOrDefault(player, 0);
        if (ap < cost) {
            // AP 不足 → 整次拒绝，不部分授予（行动力限制生效）
            result.put("found", List.of());
            result.put("clues", List.of());
            result.put("result", ERR_AP_INSUFFICIENT + "：需要 " + cost + " AP，当前 " + ap + " AP");
            result.put("ap", ap);
            result.put("ap_cost", cost);
            return result;
        }

        // 扣 AP + 授予线索（ownership 记入 playerClues）
        game.playerAp.put(player, ap - cost);
        List<String> foundIds = new ArrayList<>();
        for (Map<String, Object> clue : found) {
            String clueId = (String) clue.get("id");
            game.playerClues.computeIfAbsent(player, k -> new ArrayList<>()).add(clueId);
            foundIds.add(clueId);
        }
        // C3: 状态变更 → 快照落库（搜证结果/AP 扣减可恢复）
        saveSnapshot(game);

        result.put("found", foundIds);
        result.put("clues", found.stream()
            .map(c -> Map.of("id", c.get("id"), "content", c.get("content"), "ap_cost", ScriptSchemaV1.apCost(c)))
            .collect(Collectors.toList()));
        result.put("result", "搜证成功：获得 " + foundIds.size() + " 条线索，消耗 " + cost + " AP");
        result.put("ap", game.playerAp.get(player));
        result.put("ap_cost", cost);
        return result;
    }

    /**
     * C2: 线索转交（对齐 Chronos CLUE_SEARCH：clue.transferable 控制可否转交，转交后 ownership 变更）。
     *
     * <p>规则：① 仅搜证/讨论阶段可转交（讨论阶段交换线索是剧本杀常规玩法；投票/揭晓/结束后拒绝）；
     * ② 转交方必须持有该线索（可见性归属：visible_to_owner_only 的线索只有持有者（所有者）能转交，
     * 持有即归属）；③ 目标玩家必须在本局且非自己；④ 线索 transferable=false 拒绝转交；
     * ⑤ 转交成功后接收方 status/my_clues 即可见转入线索（ownership 变更）。
     */
    public Map<String, Object> transferClue(String sessionId, String player, String targetPlayer, String clueId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase != Phase.INVESTIGATION && game.phase != Phase.DISCUSSION) {
            return Map.of("error", "当前阶段不能转交线索");
        }
        if (player == null || player.isBlank()) return Map.of("error", "缺少转交方玩家名");
        if (targetPlayer == null || targetPlayer.isBlank()) return Map.of("error", "缺少接收方玩家名");
        if (player.equals(targetPlayer)) return Map.of("error", "不能转交给自己");
        if (!game.players.contains(player)) return Map.of("error", "转交方不在本局玩家中");
        if (!game.players.contains(targetPlayer)) return Map.of("error", "接收方不在本局玩家中");
        if (clueId == null || clueId.isBlank()) return Map.of("error", "缺少线索 id");

        // 线索存在性
        Map<String, Object> clue = null;
        for (Map<String, Object> c : game.clues) {
            if (clueId.equals(c.get("id"))) { clue = c; break; }
        }
        if (clue == null) return Map.of("error", "线索不存在: " + clueId);

        // 归属校验：只有持有者（所有者）能转交（visible_to_owner_only 的线索持有即归属）
        List<String> myClues = game.playerClues.getOrDefault(player, List.of());
        if (!myClues.contains(clueId)) {
            return Map.of("error", "你未持有该线索，无法转交（仅持有者可转交）");
        }
        // transferable 门：schema 预留的“可否转交”开关（缺省 false）
        if (!Boolean.TRUE.equals(clue.get("transferable"))) {
            return Map.of("error", "该线索不可转交（transferable=false）");
        }

        // 转交：ownership 变更（源移除 → 目标加入）
        myClues.remove(clueId);
        game.playerClues.computeIfAbsent(targetPlayer, k -> new ArrayList<>()).add(clueId);
        // 批次 D: 讨论阶段转交线索 = 线索公开事件 → 相关 AI 按动机触发发言（人类线索链）
        if (game.phase == Phase.DISCUSSION) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("player", player);
            ev.put("text", player + " 公开了线索「" + clue.get("title") + "」并转交给 " + targetPlayer);
            ev.put("clue", true);
            ev.put("ts", System.currentTimeMillis());
            game.pendingHumanEvents.add(ev);
        }
        // C3: 状态变更 → 快照落库（线索归属可恢复）
        saveSnapshot(game);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", player + " 将线索「" + clue.get("title") + "」转交给了 " + targetPlayer);
        result.put("clue_id", clueId);
        result.put("from", player);
        result.put("to", targetPlayer);
        result.put("owner", targetPlayer);
        return result;
    }

    /** Phase 3-4: Cast vote for suspect. */
    public String castVote(String sessionId, String voter, String suspect) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return "游戏不存在";
        if (game.phase != Phase.VOTE) return "当前不是投票阶段";
        if (voter.equals(suspect)) return "不能投自己";
        // D6: 只接受本局玩家名或角色名，杜绝无效/残缺票面导致揭晓误判
        if (suspect == null || suspect.isBlank()) return "投票对象不能为空";
        if (!game.players.contains(suspect) && !game.roles.contains(suspect)) {
            return "投票对象无效（必须是本局玩家或角色名）：" + suspect;
        }
        game.votes.put(voter, suspect);
        // C3: 状态变更 → 快照落库（票型可恢复）
        saveSnapshot(game);
        return voter + " 投票给了 " + suspect;
    }

    /**
     * Resolve votes and reveal truth（D6 + D7）。
     *
     * <p>D6 判定重做：① 票数按玩家名/角色名精确归一统计（非法票忽略）；② 真凶从真相文本中
     * 精确识别（凶手指向词 + 玩家/角色全名匹配，排除 contains 子串误判）；③ 平票 → 清空投票
     * 复用 VOTE 阶段重投，不再误入 REVEAL、不再误设 winner。
     *
     * <p>D7 审批门：揭晓为剧本杀关键决策点 —— 判定结果先提交 ApprovalService 挂起等待 DM 审批；
     * 批准 → 进入 REVEAL 正式揭晓；驳回/超时 → 回滚至 VOTE 重新投票。
     */
    public Map<String, Object> resolveVote(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase != Phase.VOTE) return Map.of("error", "当前不是投票阶段");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("votes", new LinkedHashMap<>(game.votes));

        // 1) 精确统计：只计合法票（票面嫌疑人归一为规范玩家名，非法票忽略）
        Map<String, Integer> voteCount = countValidVotes(game);
        String mostVoted = "";
        int maxVotes = 0;
        for (Map.Entry<String, Integer> e : voteCount.entrySet()) {
            if (e.getValue() > maxVotes) {
                maxVotes = e.getValue();
                mostVoted = e.getKey();
            }
        }
        result.put("most_voted", mostVoted);
        result.put("vote_count", maxVotes);

        // 2) 无人投票 → 不揭晓，留在投票阶段
        if (game.votes.isEmpty()) {
            result.put("result", "无人投票，无人被定罪，请先投票再揭晓");
            result.put("revote", true);
            return result;
        }
        // 票面全为无效值 → 同样不揭晓
        if (voteCount.isEmpty()) {
            result.put("result", "无有效投票（票面必须为本局玩家或角色名），请重新投票");
            result.put("revote", true);
            return result;
        }

        // 3) 平票 → 清空投票，复用 VOTE 阶段重投（D6：平票不再进入 REVEAL / 误设 winner）
        final int maxVotesFinal = maxVotes; // effectively-final 副本（lambda 引用要求）
        long ties = voteCount.values().stream().filter(c -> c == maxVotesFinal).count();
        if (ties > 1) {
            game.votes.clear();
            // C3: 清票也是状态变更 → 快照（避免重启后恢复出平票前的旧票型）
            saveSnapshot(game);
            result.put("votes", new LinkedHashMap<>());
            result.put("result", "平票，无人被定罪，已清空投票，请重新投票");
            result.put("tie", true);
            result.put("revote", true);
            return result;
        }

        // 4) 唯一得票最高 → 从真相精确解析真凶（玩家/角色全名，非 contains 子串）
        String murderer = resolveMurderer(game);
        boolean correct = !murderer.isEmpty() && murderer.equals(mostVoted);
        String verdict = correct ? "剧本杀成功！真凶被找到" : "冤枉了好人...";

        // 5) D7 审批门：揭晓为关键决策点，挂起等待 DM 审批
        if (approvalEnabled) {
            Map<String, Object> decision = awaitRevealApproval(game, mostVoted, maxVotes, murderer, correct, verdict);
            if (decision != null) return decision; // 驳回/超时/中断 → 已回滚至投票阶段
            result.put("approval", "approved");
        }

        // 6) 批准 → 正式进入揭晓阶段（GAP-4b：REVEAL 展示后由 confirmEnded 收尾进 ENDED）
        result.put("result", verdict);
        result.put("correct", correct);
        result.put("murderer", murderer.isEmpty() ? "未识别" : murderer);
        result.put("truth", game.truth);
        game.phase = Phase.REVEAL;
        game.winner = mostVoted; // 保持原语义：winner=被定罪者，game_over=true 表示已揭晓
        game.murderer = murderer;      // GAP-4b: 缓存判定结果（ENDED 落库/展示用）
        game.correctVerdict = correct; // GAP-4b: 缓存判定结果
        // C3: 状态变更 → 快照落库（REVEAL 揭晓结果可恢复）
        saveSnapshot(game);
        // GAP-8: 揭晓推送（script_reveal + script_phase=reveal）
        if (sse != null) {
            sse.broadcastScriptReveal(game.sessionId, result);
            broadcastPhase(game, "reveal");
        }
        return result;
    }

    /**
     * D7: 揭晓审批门 —— 将判定结果提交 ApprovalService 挂起等待 DM 审批。
     * 返回 null 表示批准（调用方继续揭晓）；返回 Map 表示已回滚（驳回/超时/中断）。
     */
    private Map<String, Object> awaitRevealApproval(ScriptGame game, String mostVoted, int maxVotes,
                                                    String murderer, boolean correct, String verdict) {
        Map<String, Object> revealPayload = new LinkedHashMap<>();
        revealPayload.put("gate", "script_reveal");
        revealPayload.put("session_id", game.sessionId);
        revealPayload.put("votes", new LinkedHashMap<>(game.votes));
        revealPayload.put("most_voted", mostVoted);
        revealPayload.put("vote_count", maxVotes);
        revealPayload.put("murderer", murderer.isEmpty() ? "未识别" : murderer);
        revealPayload.put("correct", correct);
        revealPayload.put("verdict", verdict);
        revealPayload.put("truth", game.truth);
        revealPayload.put("phase", "reveal");

        RouterService.RoundResult round = new RouterService.RoundResult(
            "script_reveal_approval",
            votesToAgentOutputs(game.votes),
            revealPayload,
            "剧本杀揭晓判定：得票最高=" + mostVoted + "，真凶=" + (murderer.isEmpty() ? "未识别" : murderer)
                + "，判定=" + (correct ? "命中" : "冤枉"),
            Map.of("gate", "script_reveal"));

        try {
            RouterService.RoundResult approved = approvalService.submitForApproval(round, game.sessionId, approvalTimeoutSeconds);
            if (approved == null) {
                log.warn("Script game {} reveal rejected/timeout, rollback to VOTE", game.sessionId);
                game.votes.clear();
                Map<String, Object> rollback = new LinkedHashMap<>();
                rollback.put("votes", new LinkedHashMap<>());
                rollback.put("most_voted", mostVoted);
                rollback.put("vote_count", maxVotes);
                rollback.put("result", "揭晓被驳回或超时，已回滚至投票阶段，请重新投票");
                rollback.put("revote", true);
                rollback.put("approval", "rejected");
                rollback.put("approval_hint", "DM 可通过 POST /api/approval/approve 批准，或 /reject 驳回");
                return rollback;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Script game {} reveal approval interrupted", game.sessionId);
            game.votes.clear();
            Map<String, Object> rollback = new LinkedHashMap<>();
            rollback.put("votes", new LinkedHashMap<>());
            rollback.put("most_voted", mostVoted);
            rollback.put("vote_count", maxVotes);
            rollback.put("result", "审批流程被中断，已回滚至投票阶段");
            rollback.put("revote", true);
            return rollback;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    //  D6: 揭晓判定辅助
    // ═══════════════════════════════════════════════════════════

    /** D6: 合法票精确统计 —— 嫌疑人归一为规范玩家名（角色名经 assignments 反查），非法票忽略。 */
    private Map<String, Integer> countValidVotes(ScriptGame game) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Map.Entry<String, String> v : game.votes.entrySet()) {
            String player = normalizeSuspect(game, v.getValue());
            if (player == null) continue;
            count.merge(player, 1, Integer::sum);
        }
        return count;
    }

    /** D6: 票面嫌疑人 → 规范玩家名；无法识别返回 null。 */
    private String normalizeSuspect(ScriptGame game, String suspect) {
        if (suspect == null) return null;
        String s = suspect.trim();
        if (game.players.contains(s)) return s;
        for (Map.Entry<String, String> e : game.assignments.entrySet()) {
            if (s.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /**
     * D6: 从真相文本精确解析真凶（返回玩家名；无法识别返回空串）。
     * 三级策略：① 凶手指向词后紧跟的名字（“凶手是X / 真凶就是X…”）→ 精确映射玩家；
     * ② 玩家全名出现在真相中（最长名优先，避免“张”命中“张伟”的子串误判）；
     * ③ 角色全名出现在真相中 → 经 assignments 反查玩家。
     */
    private String resolveMurderer(ScriptGame game) {
        String truth = game.truth == null ? "" : game.truth;
        if (truth.isEmpty()) return "";

        String marker = extractNameAfterMurderMarker(truth);
        if (!marker.isEmpty()) {
            String p = mapNameToPlayer(game, marker);
            if (!p.isEmpty()) return p;
        }

        List<String> candidates = new ArrayList<>();
        for (String player : game.players) {
            if (player != null && !player.isEmpty() && truth.contains(player)) candidates.add(player);
        }
        if (candidates.isEmpty()) {
            for (String role : game.roles) {
                if (role == null || role.isEmpty() || !truth.contains(role)) continue;
                for (Map.Entry<String, String> e : game.assignments.entrySet()) {
                    if (role.equals(e.getValue())) {
                        candidates.add(e.getKey());
                        break;
                    }
                }
            }
        }
        if (candidates.size() > 1) {
            candidates.sort((a, b) -> Integer.compare(b.length(), a.length()));
        }
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    /** D6: 提取凶手指向词后紧跟的名字片段（如“凶手是管家”→“管家”）。 */
    private String extractNameAfterMurderMarker(String truth) {
        Matcher m = Pattern
            .compile("(?:凶手|真凶|犯人|幕后真凶)(?:就是|便是|是|为|：|:)?\\s*([^，。；,!！?？、\\s]{1,20})")
            .matcher(truth);
        return m.find() ? m.group(1).trim() : "";
    }

    /** D6: 名字 → 玩家名（先玩家全名，再角色名反查 assignments）。 */
    private String mapNameToPlayer(ScriptGame game, String name) {
        if (name == null) return "";
        String n = name.trim();
        if (game.players.contains(n)) return n;
        for (Map.Entry<String, String> e : game.assignments.entrySet()) {
            if (n.equals(e.getValue())) return e.getKey();
        }
        return "";
    }

    /** D7: 投票明细 → RoundResult.agentOutputs（供 DM 审批时查看）。 */
    private List<Map<String, Object>> votesToAgentOutputs(Map<String, String> votes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> v : votes.entrySet()) {
            out.add(Map.of("voter", v.getKey(), "suspect", v.getValue()));
        }
        return out;
    }

    /** Start voting phase. */
    public void startVoting(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null && (game.phase == Phase.INVESTIGATION || game.phase == Phase.DISCUSSION)) {
            game.phase = Phase.VOTE;
            // C3: 阶段变更 → 快照
            saveSnapshot(game);
            broadcastPhase(game, "vote");
        }
    }

    /**
     * Transition to discussion phase（GAP-3：接对话引擎）。
     *
     * <p>升级前：只改 phase+round++。升级后：建讨论组（同步）→ 注入目标/轨道 →
     * 后台 tick 驱动轮次 → 讨论结束自动进 VOTE（A3-3）。
     */
    public boolean startDiscussion(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null && game.phase == Phase.INVESTIGATION) {
            game.phase = Phase.DISCUSSION;
            game.round++;
            // C3: 阶段变更 → 快照（恢复后若处于 DISCUSSION 但讨论线程已随重启丢失，DM 可调 start_voting 推进）
            saveSnapshot(game);
            broadcastPhase(game, "discussion");
            try {
                runDiscussionEngine(game);
            } catch (Exception e) {
                log.warn("Script game {} discussion engine setup failed, advancing to VOTE: {}",
                        sessionId, e.getMessage());
                game.phase = Phase.VOTE;
                broadcastPhase(game, "vote");
            }
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  GAP-3: 讨论接对话引擎（ConversationManager + TrackStrategy）
    // ═══════════════════════════════════════════════════════════

    /**
     * GAP-3 讨论引擎编排：注册角色（讨论 persona 不含秘密明文，A3-2）→ 目标注入
     * （隐藏秘密/查明真相，A3-4）→ 轨道分配（持秘密 WEAK / 未持 MERGED）→ 建组（同步，
     * A3-1）→ 后台驱动轮次 → 结束自动进 VOTE（A3-3）。
     */
    private void runDiscussionEngine(ScriptGame game) {
        ensureDiscussionEngine();
        if (game.players.size() < 2) {
            // 单人局无讨论对象，直接进投票
            game.phase = Phase.VOTE;
            broadcastPhase(game, "vote");
            return;
        }

        discussionWorld.clearAgents();
        discussionWorld.setWorldNarration((game.background == null || game.background.isBlank())
                ? "你们正在针对这起案件进行讨论。"
                : game.background + "。你们正在针对这起案件进行讨论。");

        // 1) 注册讨论角色（A3-2：讨论 persona 不含秘密明文）
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, player);
            Agent agent = new Agent(buildDiscussionPersona(game, player, role), "npc", llmClient);
            double x = 100 + Math.random() * 800;
            double y = 100 + Math.random() * 400;
            discussionWorld.registerAgent(agent, x, y, 220, 60);
            AgentState st = discussionWorld.getState(player);
            if (st != null) st.setEmotion(Emotion.NEUTRAL);
        }

        // 2) 目标驱动：按角色秘密注入（A3-4）
        for (String player : game.players) {
            boolean hasSecret = !game.getSecretFor(player).isBlank();
            worldDirector.setGoal(player, hasSecret ? GOAL_HIDE_SECRET : GOAL_FIND_TRUTH);
        }

        // 3) 轨道分配：持秘密 → WEAK（只给摘要不给明文），未持 → MERGED（全文）
        List<AgentState> members = new ArrayList<>();
        Map<String, TrackAssignment> tracks = new LinkedHashMap<>();
        for (String player : game.players) {
            AgentState st = discussionWorld.getState(player);
            if (st == null) continue;
            members.add(st);
            List<String> others = game.players.stream().filter(p -> !p.equals(player)).toList();
            boolean hasSecret = !game.getSecretFor(player).isBlank();
            tracks.put(player, TrackAssignment.of(player,
                    hasSecret ? Track.Mode.WEAK : Track.Mode.MERGED, others,
                    hasSecret ? "持秘密角色：仅摘要观察（WEAK），秘密不进入讨论上下文"
                              : "未持秘密角色：公开讨论（MERGED）"));
        }

        // 4) 建组（同步，A3-1 可立即断言）→ 后台驱动轮次（批次 D：SpeechGate 发言门控）→ 结束自动进 VOTE（A3-3）
        ConversationGroup group = discussionConversation.createScriptDiscussionGroup(
                DISCUSSION_GROUP_PREFIX + game.sessionId, members, tracks);
        game.discussionActive = true;
        // 批次 D: 门控实例（阈值可配，对齐 D-004；默认值=demo 实测安全区间）
        SpeechGate speechGate = new SpeechGate(discussionSilenceFloor, discussionWaitBias, discussionColdBreak);
        // 批次 D: 发言扫描游标（只扫描自上次门控以来的新增发言，防重复触发）
        final int[] scannedTurns = {0};
        discussionExecutor.submit(() -> {
            try {
                java.util.function.BiFunction<ConversationGroup, Integer, ConversationManager.RoundGateDecision> gate =
                        buildRoundGate(game, speechGate, scannedTurns);
                ConversationManager.ScriptDiscussionResult result =
                        discussionConversation.runScriptDiscussionRounds(group, discussionMaxRounds, gate);
                game.discussionTranscript.addAll(result.transcript());
                game.discussionContexts.putAll(result.lastContexts());
                // 批次 D: 排空讨论结束后到达的人类发言（仍入讨论记录，保证不丢）
                drainLateHumanEvents(game);
                log.info("Script game {} discussion done: {} turns",
                        game.sessionId, result.transcript().size());
            } catch (Exception e) {
                log.warn("Script game {} discussion rounds failed: {}", game.sessionId, e.getMessage());
            } finally {
                game.discussionActive = false;
                game.phase = Phase.VOTE;
                // C3: 讨论结束自动进 VOTE，阶段变更 → 快照
                saveSnapshot(game);
                // GAP-8: 讨论结束自动进 VOTE，推送阶段变更
                broadcastPhase(game, "vote");
            }
        });
    }

    /**
     * 批次 D: 讨论门控编排（每轮一次，由 ConversationManager 在每轮 LLM 生成前调用）——
     * ① 排空人类发言事件（人类发言权豁免：直接注入对话流，不过门控；该角色本轮 AI 不代声）；
     * ② 扫描自上次以来的新增发言（含人类 @）→ 点名/提问触发 + 被点名角色注入辩解临时目标
     *    （瞬时高优先、N 轮衰减回落，对齐 Bates 情绪→目标再评价）；
     * ③ 人类公开新线索 → 相关 AI（动机优先级≥50）按动机触发发言；
     * ④ 情绪超阈值 → 必发言；⑤ 轮次首句 → 全员必发言（开局自我介绍）；
     * ⑥ 冷场破冰：上一轮全员静默且无人类发言 → 按动机选破冰者（侦探提问/凶手转移焦点）；
     * ⑦ 逐成员 SpeechGate 决策（talkativeness × 动机分 × wait_bias vs silence-floor）。
     */
    private java.util.function.BiFunction<ConversationGroup, Integer, ConversationManager.RoundGateDecision>
            buildRoundGate(ScriptGame game, SpeechGate speechGate, int[] scannedTurns) {
        return (group, roundIdx) -> {
            // 0) 临时应激目标每轮衰减（被质疑→辩解 pri100，N 轮后回落主动机）
            worldDirector.decayTemporaryGoals();

            // 1) 排空人类发言事件 → 直接注入对话流（人类发言权豁免，不过门控）
            List<Map<String, Object>> events = new ArrayList<>();
            Map<String, Object> ev;
            while ((ev = game.pendingHumanEvents.poll()) != null) events.add(ev);
            boolean humanSpokeThisRound = !events.isEmpty();
            Set<String> humanSpokenPlayers = new HashSet<>();
            for (Map<String, Object> e : events) {
                String p = str(e.get("player"));
                String txt = str(e.get("text"));
                humanSpokenPlayers.add(p);
                group.recordTurn(p, txt.isBlank() ? "（发言）" : txt);
            }

            // 2) 扫描新增发言（人类与 AI 统一：@某AI → 该 AI 强制发言；被点名 → 辩解应激）
            List<SpeechGate.SpeechTrigger> triggers = new ArrayList<>();
            List<Map<String, String>> history = group.getMessageHistory();
            int from = Math.min(scannedTurns[0], history.size());
            scannedTurns[0] = history.size();
            for (int i = from; i < history.size(); i++) {
                Map<String, String> turn = history.get(i);
                String speaker = turn.get("speaker");
                String msg = turn.get("message");
                if (speaker == null || msg == null || msg.isBlank()) continue;
                for (String member : game.players) {
                    if (member.equals(speaker)) continue;
                    if (SpeechGate.isMentioning(msg, member)) {
                        SpeechGate.TriggerType tt = SpeechGate.isQuestioning(msg, member)
                                ? SpeechGate.TriggerType.QUESTION : SpeechGate.TriggerType.MENTION;
                        triggers.add(new SpeechGate.SpeechTrigger(tt, member));
                        // 被质疑 → 辩解临时目标瞬时拉满（Bates：情绪→目标再评价；priority-decay-rounds 轮衰减）
                        worldDirector.pushTemporaryGoal(member, GOAL_DEFEND, 100, priorityDecayRounds);
                    }
                }
            }

            // 3) 人类公开新线索 → 相关 AI 按动机触发发言（动机优先级≥50 的角色=高相关：凶手脱罪/平民隐藏）
            for (Map<String, Object> e : events) {
                if (Boolean.TRUE.equals(e.get("clue"))) {
                    for (String member : game.players) {
                        if (worldDirector.getGoalPriority(member) >= 50) {
                            triggers.add(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.HUMAN_CLUE, member));
                        }
                    }
                }
            }

            // 4) 情绪超阈值 → 必发言（ANGRY/SAD/CONFUSED/SURPRISED，对齐 demo emotion_threshold）
            for (String member : game.players) {
                AgentState st = discussionWorld.getState(member);
                if (st != null && (st.getEmotion() == Emotion.ANGRY || st.getEmotion() == Emotion.SAD
                        || st.getEmotion() == Emotion.CONFUSED || st.getEmotion() == Emotion.SURPRISED)) {
                    triggers.add(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.EMOTION, member));
                }
            }

            // 5) 轮次首句：开局每人自我介绍（混合节奏开局的固定节拍，防冷场第一道闸）
            if (roundIdx == 0) {
                for (String member : game.players) {
                    triggers.add(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.ROUND_FIRST, member));
                }
            }

            // 6) 冷场破冰：上一轮全员静默且无人类发言 → 按动机选破冰者（侦探提问/凶手转移焦点/外向者兜底）
            String iceBreaker = null;
            if (speechGate.isColdBreakEnabled()) {
                int n = game.players.size();
                List<Map<String, String>> h = group.getMessageHistory();
                if (h.size() >= n) {
                    boolean allSilent = true;
                    for (int i = h.size() - n; i < h.size(); i++) {
                        String m = h.get(i).get("message");
                        if (m == null || !m.contains(SpeechGate.SILENCE_MARKER)) { allSilent = false; break; }
                    }
                    if (allSilent) {
                        iceBreaker = pickIceBreaker(game);
                    }
                }
            }

            // 7) 逐成员 SpeechGate 决策（talkativeness × 动机分 × wait_bias vs silence-floor）
            Map<String, Boolean> speakMap = new LinkedHashMap<>();
            Set<String> skip = new HashSet<>(humanSpokenPlayers);
            for (String member : game.players) {
                if (skip.contains(member)) continue; // 人类已发言 → AI 不代声（人类发言权豁免）
                double talk = game.playerTalkativeness.getOrDefault(member, ScriptSchemaV1.DEFAULT_TALKATIVENESS);
                int pri = worldDirector.getGoalPriority(member);
                SpeechGate.GateDecision d = speechGate.decide(member, talk, pri, triggers,
                        member.equals(iceBreaker), humanSpokeThisRound);
                speakMap.put(member, d.speak());
            }
            return new ConversationManager.RoundGateDecision(speakMap, skip);
        };
    }

    /** 冷场破冰者：按动机选择——查明真相（侦探位）优先，其次最高动机优先级、最健谈者兜底。 */
    private String pickIceBreaker(ScriptGame game) {
        for (String member : game.players) {
            if (GOAL_FIND_TRUTH.equals(worldDirector.getGoal(member))) return member;
        }
        String best = null;
        int bestPri = -1;
        double bestTalk = -1;
        for (String member : game.players) {
            int pri = worldDirector.getGoalPriority(member);
            double talk = game.playerTalkativeness.getOrDefault(member, ScriptSchemaV1.DEFAULT_TALKATIVENESS);
            if (pri > bestPri || (pri == bestPri && talk > bestTalk)) {
                bestPri = pri;
                bestTalk = talk;
                best = member;
            }
        }
        return best;
    }

    /** 批次 D: 排空讨论结束后到达的人类发言（仍入讨论记录；引擎排空过的队列此处为空，不重复）。 */
    private void drainLateHumanEvents(ScriptGame game) {
        Map<String, Object> ev;
        while ((ev = game.pendingHumanEvents.poll()) != null) {
            Map<String, String> turn = new LinkedHashMap<>();
            turn.put("speaker", str(ev.get("player")));
            turn.put("message", str(ev.get("text")));
            turn.put("round", String.valueOf(game.round));
            game.discussionTranscript.add(turn);
        }
    }

    /**
     * 批次 D: 人类发言入口（人机混合讨论）——人类发言权豁免（不过门控，直接注入讨论流）；
     * 消息中 @角色名 会被解析为强制触发（目标 AI 必发言、其余 AI 本轮倾向静默等待 wait_bias）；
     * isClue=true 表示人类公开新线索 → 相关 AI 按动机触发发言。
     */
    public Map<String, Object> discussionSay(String sessionId, String player, String message, boolean isClue) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase != Phase.DISCUSSION) return Map.of("error", "当前不是讨论阶段");
        if (player == null || player.isBlank()) return Map.of("error", "缺少玩家名");
        if (!game.players.contains(player)) return Map.of("error", "玩家不在本局中");
        if (message == null || message.isBlank()) return Map.of("error", "发言内容不能为空");

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("player", player);
        ev.put("text", message);
        ev.put("clue", isClue);
        ev.put("ts", System.currentTimeMillis());
        game.pendingHumanEvents.add(ev);

        // 可观测：被 @ 的目标（强制发言名单，响应回显供前端展示）
        List<String> mentioned = new ArrayList<>();
        for (String member : game.players) {
            if (!member.equals(player) && SpeechGate.isMentioning(message, member)) mentioned.add(member);
        }
        log.info("Script game {} human {} spoke: {}{} @{}",
                sessionId, player,
                message.length() > 60 ? message.substring(0, 60) : message,
                isClue ? "（公开线索）" : "", mentioned);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("player", player);
        r.put("message", message);
        r.put("clue", isClue);
        r.put("mentions", mentioned);
        return r;
    }

    /** 懒创建讨论引擎（独立实例，不与 2D 模拟共享，避免重 init 互相覆盖）。 */
    private void ensureDiscussionEngine() {
        if (discussionConversation == null) {
            discussionWorld = new SimulationWorld();
            discussionConversation = new ConversationManager();
            discussionConversation.init(discussionWorld, llmClient,
                    name -> discussionWorld.getAgent(name),
                    () -> discussionWorld.getWorldNarration());
        }
    }

    /**
     * GAP-3: 讨论用 persona —— 不写入秘密明文（秘密隐藏由 WEAK 轨道 + 目标承担，A3-2）。
     * 秘密原文仍通过 getSecretFor / your_secret 仅发放给对应玩家。
     */
    private Persona buildDiscussionPersona(ScriptGame game, String player, String role) {
        boolean hasSecret = !game.getSecretFor(player).isBlank();
        StringBuilder desc = new StringBuilder();
        desc.append("你是剧本杀《").append(game.name).append("》中的").append(role)
            .append("，玩家名为").append(player).append("。");
        if (game.background != null && !game.background.isBlank()) {
            desc.append("案件背景：").append(game.background).append("。");
        }
        desc.append(hasSecret
                ? "你有一个不可告人的秘密，务必保守，绝不能向任何人透露。"
                : "你此行没有需要隐瞒的秘密，可以放心参与讨论。");
        desc.append("讨论时应根据已知线索发言、试探他人、隐藏不利信息。");
        Persona persona = new Persona(player);
        persona.setPersonaDesc(desc.toString());
        persona.setBackground(game.background);
        persona.setVoice("贴合剧本杀角色身份，发言谨慎，避免直接泄露私密信息");
        return persona;
    }

    /** GAP-3: 查询角色讨论目标（A3-4 验收：持秘密角色返回注入目标）。 */
    public String getDiscussionGoal(String sessionId, String player) {
        if (player == null) return "";
        String goal = worldDirector.getGoal(player);
        return goal == null ? "" : goal;
    }

    /** GAP-3: 讨论是否进行中（A3-1 验收：建组后 phase==DISCUSSION 且 discussionActive）。 */
    public boolean isDiscussionRunning(String sessionId) {
        ScriptGame game = games.get(sessionId);
        return game != null && game.phase == Phase.DISCUSSION && game.discussionActive;
    }

    /** GAP-3: 讨论发言记录（A3-3 验收）。 */
    public List<Map<String, String>> getDiscussionTranscript(String sessionId) {
        ScriptGame game = games.get(sessionId);
        return game == null ? List.of() : new ArrayList<>(game.discussionTranscript);
    }

    public void markSimulationStarted(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null) {
            game.simulationStarted = true;
            // C3: 状态变更 → 快照（simulation_started 标志可恢复）
            saveSnapshot(game);
        }
    }

    public List<Persona> buildSimulationPersonas(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return List.of();
        List<Persona> personas = new ArrayList<>();
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, player);
            String secret = game.secrets.getOrDefault(role, "");
            Persona persona = new Persona(player);
            persona.setPersonaDesc(buildPersonaDescription(game, player, role, secret));
            persona.setBackground(game.background);
            persona.setVoice("贴合剧情杀角色身份，发言谨慎，避免直接泄露私密信息");
            personas.add(persona);
        }
        return personas;
    }

    public Set<String> getSecretPlayers(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, "");
            if (!role.isBlank() && game.secrets.containsKey(role)) {
                names.add(player);
            }
        }
        return names;
    }

    public Map<String, String> buildDiscussionGoals(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of();
        Map<String, String> goals = new LinkedHashMap<>();
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, player);
            String secret = game.secrets.getOrDefault(role, "");
            String goal = secret.isBlank()
                ? "参与剧情杀讨论，结合公开线索推理真凶"
                : "参与剧情杀讨论，保护自己的秘密，同时根据线索推理真凶";
            goals.put(player, goal);
        }
        return goals;
    }

    private String buildPersonaDescription(ScriptGame game, String player, String role, String secret) {
        StringBuilder desc = new StringBuilder();
        desc.append("你是剧情杀《").append(game.name).append("》中的").append(role)
            .append("，玩家名为").append(player).append("。");
        if (game.background != null && !game.background.isBlank()) {
            desc.append("案件背景：").append(game.background).append("。");
        }
        if (secret != null && !secret.isBlank()) {
            desc.append("你的秘密：").append(secret)
                .append("。除非剧情推进到必要时刻，否则不要主动公开这段秘密。");
        }
        desc.append("讨论时应根据已知线索发言、试探他人、隐藏不利信息。");
        return desc.toString();
    }

    public ScriptGame getGame(String sessionId) {
        return games.get(sessionId);
    }

    // ═══════════════════════════════════════════════════════════
    //  GAP-4b/4c/8: ENDED 终态 + 剧本落库 + script SSE 推送
    // ═══════════════════════════════════════════════════════════

    /**
     * GAP-4b: 对局收尾 —— REVEAL 展示后由前端/调用方确认进入 ENDED（终态，A4-4），
     * 并落库对局结果（GAP-4c，A4-3）。幂等：已 ENDED 直接返回当前状态；非 REVEAL 拒绝。
     */
    public Map<String, Object> confirmEnded(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase == Phase.ENDED) return game.toMap(null); // 幂等：重复确认不越界
        if (game.phase != Phase.REVEAL) {
            return Map.of("error", "当前不是揭晓阶段，无法结束对局",
                    "phase", game.phase.name().toLowerCase());
        }
        game.phase = Phase.ENDED;
        persistGameResult(game);
        // C3: 终态快照（ENDED 对局 resume 返回终态结果的恢复来源）
        saveSnapshot(game);
        broadcastPhase(game, "ended");
        broadcastStatus(game);
        log.info("Script game {} ended (winner={}, killer={}, correct={})",
                sessionId, game.winner, game.murderer, game.correctVerdict);
        return game.toMap(null);
    }

    /** GAP-4c + C1: 剧本落库（initGame 剧本生成后调用；A4-3 剧本落库来源）—— 按 Schema v1 存取。 */
    private void persistScript(ScriptGame game) {
        if (databaseService == null) return;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "script");
        content.put("schema_version", ScriptSchemaV1.CURRENT_VERSION);
        content.put("session_id", game.sessionId);
        content.put("metadata", ScriptSchemaV1.metadata(game.scriptSchema));
        content.put("name", game.name);
        content.put("background", game.background);
        content.put("truth", game.truth);
        content.put("killer_id", game.killerId);
        content.put("roles", ScriptSchemaV1.roleObjects(game.scriptSchema));
        content.put("locations", new ArrayList<>(game.locations));
        content.put("clues", new ArrayList<>(game.clues));
        content.put("secrets", new LinkedHashMap<>(game.secrets));
        content.put("assignments", new LinkedHashMap<>(game.assignments));
        content.put("players", new ArrayList<>(game.players));
        databaseService.saveScript("剧本：" + game.name, content);
        log.info("Script game {} script persisted to DB (schema v{})",
                game.sessionId, ScriptSchemaV1.CURRENT_VERSION);
    }

    /** GAP-4c: 对局结果落库（confirmEnded 对局结束后调用；含玩家/凶手/投票/讨论摘要）。 */
    private void persistGameResult(ScriptGame game) {
        if (databaseService == null) return;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "result");
        content.put("session_id", game.sessionId);
        content.put("name", game.name);
        content.put("phase", "ended");
        content.put("players", new ArrayList<>(game.players));
        content.put("assignments", new LinkedHashMap<>(game.assignments));
        content.put("killer", game.murderer);
        content.put("winner", game.winner);
        content.put("votes", new LinkedHashMap<>(game.votes));
        content.put("correct", game.correctVerdict);
        content.put("truth", game.truth);
        content.put("discussion_turns", game.discussionTranscript.size());
        content.put("discussion", new ArrayList<>(game.discussionTranscript));
        content.put("ended_at", LocalDateTime.now().toString());
        databaseService.saveScript("对局结果：" + game.name, content);
        log.info("Script game {} result persisted to DB (killer={}, correct={})",
                game.sessionId, game.murderer, game.correctVerdict);
    }

    /** GAP-8: 阶段变更推送（script_phase）+ 正式版：SYSTEM 级广播到 announcement 全局横幅通道。 */
    private void broadcastPhase(ScriptGame game, String phase) {
        if (game == null) return;
        // 会话面板通道（台账 #35 script_* SSE 事件，前端 ScriptStatePanel 消费）
        if (sse != null) {
            sse.broadcastScriptPhase(game.sessionId, phase);
        }
        // 全局横幅通道（正式版：announcement SYSTEM 级广播，前端 AnnouncementBanner 消费，
        // 无条件启用不再依赖 speech-mode；总开关 roleplay.broadcast.script-phase-broadcast）
        broadcastSystemAnnouncement(game, phase);
    }

    /**
     * 剧本杀阶段切换 → SYSTEM 级广播（banner 显示「阶段切换」，全局横幅通道，进正式版）。
     * 无条件启用（不再依赖 split 模式），仅受总开关 roleplay.broadcast.script-phase-broadcast
     * （默认 true）门控；与 script_phase SSE（会话面板通道）并存不冲突；null 守卫（测试直构不注入时静默）。
     */
    private void broadcastSystemAnnouncement(ScriptGame game, String phase) {
        if (announcementService == null || !announcementService.isScriptPhaseBroadcast()) return;
        String title = game.name == null || game.name.isBlank() ? "剧本杀" : game.name;
        String text = switch (phase) {
            case "investigation" -> "【" + title + "】阶段切换：进入搜证阶段——请玩家们调查线索！";
            case "discussion" -> "【" + title + "】阶段切换：进入讨论阶段——请交流线索与怀疑！";
            case "vote" -> "【" + title + "】阶段切换：进入投票阶段——请投票指认凶手！";
            case "reveal" -> "【" + title + "】揭晓时刻——投票结果揭晓，真相大白！";
            case "ended" -> "【" + title + "】对局结束——本局已收官！";
            default -> "【" + title + "】阶段切换：" + phase;
        };
        announcementService.enqueue(new BroadcastMessage(
                UUID.randomUUID().toString(),
                BroadcastMessage.Level.SYSTEM, "system", "system",
                text, -1, -1, 0, BroadcastMessage.MODE_ANNOUNCEMENT,
                // 阶段切换是离散横幅事件：coalesceKey 按阶段区分，避免同窗口内
                // 两次切换被合并成 ×N（如 investigation→discussion 快速连发）
                "script_phase|" + phase,
                java.time.Instant.now().toEpochMilli()));
    }

    /** GAP-8: 状态推送（script_status，playerName 传空串——不含 your_secret，广播安全）。 */
    private void broadcastStatus(ScriptGame game) {
        if (sse == null || game == null) return;
        sse.broadcastScriptStatus(game.sessionId, game.toMap(""));
    }

    /** C1: 剧本 Schema v1 全量（双生成器一致性验证/外部读取用）。 */
    public Map<String, Object> getScriptSchema(String sessionId) {
        ScriptGame game = games.get(sessionId);
        return game == null || game.scriptSchema == null ? Map.of() : game.scriptSchema;
    }

    // ═══════════════════════════════════════════════════════════
    //  C3: 断线重连与会话恢复 —— roleKey 认证 + 对局快照（对齐 Chronos：
    //  roleKey 顶号一体 / 内存 Room 仅缓存，状态变更写持久化，崩溃后 restore 重建）
    // ═══════════════════════════════════════════════════════════

    /**
     * C3: 玩家级端点身份校验 —— 有 player_key 时校验其与该玩家在本次对局的 roleKey 匹配；
     * 无 player_key 时向后兼容（仍按玩家名，现状不变）。
     *
     * @return null 表示通过；非 null 为错误 map（controller 转 403）
     */
    public Map<String, Object> checkPlayerAccess(String sessionId, String player, String playerKey) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (playerKey == null || playerKey.isBlank()) return null; // 向后兼容：无 key 按玩家名
        String expected = playerKeysOf(game).get(player);
        if (expected == null || !expected.equals(playerKey)) {
            return Map.of("error", "身份校验失败：player_key 与玩家不匹配");
        }
        return null;
    }

    /** C3: 玩家 roleKey 是否有效（匹配该玩家在本局的令牌）。 */
    public boolean isPlayerKeyValid(String sessionId, String player, String playerKey) {
        if (playerKey == null || playerKey.isBlank()) return true; // 无 key 兼容
        ScriptGame game = games.get(sessionId);
        if (game == null) return false;
        String expected = game.playerKeys.get(player);
        return expected != null && expected.equals(playerKey);
    }

    /** C3: 某玩家的 roleKey（客户端存储/重连凭证；无对局/无玩家返回空串）。 */
    public String getRoleKey(String sessionId, String player) {
        ScriptGame game = games.get(sessionId);
        return game == null ? "" : game.playerKeys.getOrDefault(player, "");
    }

    /** C3: 全员 roleKey 一览（DM 面板分发令牌用；无对局返回空 map）。 */
    public Map<String, String> getPlayerKeys(String sessionId) {
        ScriptGame game = games.get(sessionId);
        return game == null ? Map.of() : new LinkedHashMap<>(game.playerKeys);
    }

    // ═══════════════════════════════════════════════════════════
    //  C4: DM 面板 —— 全量视图（state:dm_dashboard）+ 手动推进（dm:advance）
    //  ═══════════════════════════════════════════════════════════

    /**
     * C4: DM 全量视图（对齐 Chronos state:dm_dashboard）—— 主持人可见全部玩家的
     * 角色/秘密/AP/线索数/投票状态/roleKey + 对局元数据（真相/killer_id/判定/审批状态）。
     * 与玩家级 toMap（脱敏）不同：本视图为 DM 专用，不脱敏；越权由 controller 层 DM key 门承担。
     */
    public Map<String, Object> dmStatus(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在", "phase", "not_found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", game.sessionId);
        m.put("phase", game.phase.name().toLowerCase());
        m.put("round", game.round);
        m.put("name", game.name);
        m.put("background", game.background);
        m.put("truth", game.truth);
        m.put("killer_id", game.killerId);
        m.put("murderer", game.murderer);
        m.put("correct", game.correctVerdict);
        m.put("winner", game.winner);
        m.put("roles", new ArrayList<>(game.roles));
        m.put("locations", new ArrayList<>(game.locations));
        m.put("clues", new ArrayList<>(game.clues));
        // 玩家表：角色 / 秘密 / AP / 线索数 / 投票状态 / roleKey（DM 全量，供面板分发令牌）
        List<Map<String, Object>> players = new ArrayList<>();
        for (String p : game.players) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p);
            row.put("role", game.assignments.getOrDefault(p, ""));
            row.put("secret", game.secrets.getOrDefault(game.assignments.getOrDefault(p, ""), ""));
            row.put("ap", game.playerAp.getOrDefault(p, 0));
            row.put("ap_max", game.playerApMax.getOrDefault(p, 0));
            row.put("clue_count", game.playerClues.getOrDefault(p, List.of()).size());
            row.put("clues", new ArrayList<>(game.playerClues.getOrDefault(p, List.of())));
            row.put("voted", game.votes.containsKey(p));
            row.put("vote", game.votes.getOrDefault(p, ""));
            row.put("role_key", game.playerKeys.getOrDefault(p, ""));
            players.add(row);
        }
        m.put("players", players);
        m.put("votes", new LinkedHashMap<>(game.votes));
        if (!game.discussionTranscript.isEmpty()) {
            m.put("discussion", new ArrayList<>(game.discussionTranscript));
        }
        // 审批门状态（DM 面板可见待审揭晓并可批准/驳回）
        m.put("approval_status", approvalService == null ? "none" : approvalService.getStatus(game.sessionId));
        return m;
    }

    /**
     * C4: DM 手动推进阶段（对齐 Chronos dm:advance）—— 按状态机逐级推进：
     * INVESTIGATION → DISCUSSION（startDiscussion 接讨论引擎）
     * DISCUSSION → VOTE（startVoting；C3 已知限制“恢复后 DM 手动推进”的入口）
     * VOTE → REVEAL（resolveVote，经 D7 审批门，阻塞等待 DM 批准/驳回/超时）
     * REVEAL → ENDED（confirmEnded，落库对局结果）
     * ENDED → 幂等返回终态（不越界）
     * 返回响应与各既有端点一致（附加 advanced 键标明推进动作）。
     */
    public Map<String, Object> advancePhase(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在", "phase", "not_found");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        switch (game.phase) {
            case INVESTIGATION -> {
                startDiscussion(sessionId);
                result.put("phase", game.phase.name().toLowerCase());
                result.put("advanced", "discussion");
                result.put("message", "已进入讨论阶段");
            }
            case DISCUSSION -> {
                startVoting(sessionId);
                result.put("phase", game.phase.name().toLowerCase());
                result.put("advanced", "vote");
                result.put("message", "已进入投票阶段");
            }
            case VOTE -> {
                // 揭晓经 D7 审批门：阻塞等待 DM 批准（面板可见 approval_status=pending 并批准）
                Map<String, Object> resolve = resolveVote(sessionId);
                result.putAll(resolve);
                // resolveVote 响应不含 phase 键（既有 /resolve 契约）——advance 统一补当前阶段
                result.put("phase", game.phase.name().toLowerCase());
                result.put("advanced", "reveal");
            }
            case REVEAL -> {
                Map<String, Object> ended = confirmEnded(sessionId);
                result.putAll(ended);
                result.put("advanced", "ended");
            }
            case ENDED -> {
                result.put("phase", "ended");
                result.put("advanced", "ended");
                result.put("terminal", true);
                result.put("message", "对局已结束（幂等，不越界）");
                result.put("murderer", game.murderer);
                result.put("correct", game.correctVerdict);
            }
            default -> {
                result.put("error", "当前阶段不可推进: " + game.phase.name().toLowerCase());
                result.put("phase", game.phase.name().toLowerCase());
            }
        }
        return result;
    }

    /** C3: 由 playerKey 反查其所属对局 sessionId（仅内存对局；重启后请用 game_id/room_code 定位）。 */
    public String findSessionByPlayerKey(String playerKey) {
        if (playerKey == null || playerKey.isBlank()) return "";
        for (Map.Entry<String, ScriptGame> e : games.entrySet()) {
            if (e.getValue().playerKeys.containsValue(playerKey)) return e.getKey();
        }
        return "";
    }

    /** C3: 由 playerKey 反查对局内玩家名（重连后按 key 恢复个人视图用）。 */
    public String findPlayerByKey(String sessionId, String playerKey) {
        ScriptGame game = games.get(sessionId);
        if (game == null || playerKey == null || playerKey.isBlank()) return "";
        for (Map.Entry<String, String> e : game.playerKeys.entrySet()) {
            if (playerKey.equals(e.getValue())) return e.getKey();
        }
        return "";
    }

    /**
     * C3: 断线重连恢复入口 —— 内存对局存在直接返回该玩家视图；
     * 不存在则从持久化快照重建（重启后可用）；对局已 ENDED 返回终态结果。
     *
     * @return 玩家视图（含 restored/resumed/player 附加键；ENDED 含 terminal/murderer/correct）
     */
    public Map<String, Object> resumeGame(String sessionId, String playerKey) {
        if (sessionId == null || sessionId.isBlank()) return Map.of("error", "缺少对局标识");
        ScriptGame game = games.get(sessionId);
        boolean restored = false;
        if (game == null) {
            game = restoreFromSnapshot(sessionId);
            if (game == null) return Map.of("error", "对局不存在且无快照可恢复");
            restored = true;
        }
        // 由 roleKey 反查玩家（重连场景客户端可能只持 key）
        String player = findPlayerByKey(sessionId, playerKey);
        if (player == null || player.isBlank()) {
            return Map.of("error", "身份校验失败：player_key 不属于本对局任何玩家");
        }
        Map<String, Object> view = new LinkedHashMap<>(game.toMap(player));
        view.put("restored", restored);
        view.put("resumed", true);
        view.put("player", player);
        if (game.phase == Phase.ENDED) {
            view.put("terminal", true);
            view.put("murderer", game.murderer);
            view.put("correct", game.correctVerdict);
            // 终态结果补全（toMap 按既有契约不含 truth/votes —— 终态恢复时显式携带）
            view.put("truth", game.truth);
            view.put("votes", new LinkedHashMap<>(game.votes));
            view.put("winner", game.winner);
        }
        log.info("Script game {} resumed by player {} (restored={}, phase={})",
                sessionId, player, restored, game.phase.name().toLowerCase());
        return view;
    }

    /**
     * C3: 对局快照落库 —— 全量状态写 ScriptEntity（type=snapshot，name 前缀「对局快照:<sessionId>」），
     * 每次状态变更调用（对齐 Chronos“内存 Room 仅缓存，每次状态变更写持久化”）。
     * databaseService 为 null（直接构造的测试）时跳过。
     */
    private void saveSnapshot(ScriptGame game) {
        if (databaseService == null || game == null) return;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "snapshot");
        content.put("session_id", game.sessionId);
        content.put("saved_at", LocalDateTime.now().toString());
        content.put("phase", game.phase.name());
        content.put("name", game.name);
        content.put("background", game.background);
        content.put("truth", game.truth);
        content.put("killer_id", game.killerId);
        content.put("script_schema", game.scriptSchema);
        content.put("roles", new ArrayList<>(game.roles));
        content.put("players", new ArrayList<>(game.players));
        content.put("assignments", new LinkedHashMap<>(game.assignments));
        content.put("secrets", new LinkedHashMap<>(game.secrets));
        content.put("round", game.round);
        content.put("clues", new ArrayList<>(game.clues));
        content.put("player_clues", new LinkedHashMap<>(game.playerClues));
        content.put("player_ap", new LinkedHashMap<>(game.playerAp));
        content.put("player_ap_max", new LinkedHashMap<>(game.playerApMax));
        content.put("player_talkativeness", new LinkedHashMap<>(game.playerTalkativeness));
        content.put("player_keys", new LinkedHashMap<>(game.playerKeys));
        content.put("votes", new LinkedHashMap<>(game.votes));
        content.put("locations", new ArrayList<>(game.locations));
        content.put("winner", game.winner);
        content.put("simulation_started", game.simulationStarted);
        content.put("murderer", game.murderer);
        content.put("correct_verdict", game.correctVerdict);
        content.put("discussion_transcript", new ArrayList<>(game.discussionTranscript));
        content.put("discussion_contexts", new LinkedHashMap<>(game.discussionContexts));
        databaseService.saveScript("对局快照:" + game.sessionId, content);
    }

    /**
     * C3: 从持久化快照重建对局（重启后恢复）。无快照返回 null。
     * 重建后重新放入 games 缓存（内存仅作缓存，权威状态在快照）。
     */
    private ScriptGame restoreFromSnapshot(String sessionId) {
        if (databaseService == null) return null;
        Optional<Map<String, Object>> snap = databaseService.getLatestScriptSnapshot(sessionId);
        if (snap.isEmpty()) return null;
        Map<String, Object> c = snap.get();
        if (!sessionId.equals(str(c.get("session_id")))) return null;

        ScriptGame game = new ScriptGame();
        game.sessionId = sessionId;
        game.phase = parsePhase(c.get("phase"));
        game.name = str(c.get("name"));
        game.background = str(c.get("background"));
        game.truth = str(c.get("truth"));
        game.killerId = str(c.get("killer_id"));
        game.scriptSchema = mapOf(c.get("script_schema"));
        game.roles.addAll(strList(c.get("roles")));
        game.players.addAll(strList(c.get("players")));
        game.assignments.putAll(strMap(c.get("assignments")));
        game.secrets.putAll(strMap(c.get("secrets")));
        game.round = intOf(c.get("round"), 1);
        game.clues.addAll(mapList(c.get("clues")));
        for (Map.Entry<String, List<String>> e : strListMap(c.get("player_clues")).entrySet()) {
            game.playerClues.put(e.getKey(), e.getValue());
        }
        game.playerAp.putAll(intMap(c.get("player_ap")));
        game.playerApMax.putAll(intMap(c.get("player_ap_max")));
        // 批次 D: talkativeness 快照恢复（旧快照无此键 → 空 map，门控按缺省 0.5 兜底）
        game.playerTalkativeness.putAll(doubleMap(c.get("player_talkativeness")));
        // 批次 D: 人类玩家标记（全员真人，缺省 true；恢复后按玩家补齐）
        for (String p : game.players) {
            game.playerIsHuman.put(p, true);
        }
        game.playerKeys.putAll(strMap(c.get("player_keys")));
        game.votes.putAll(strMap(c.get("votes")));
        game.locations.addAll(strList(c.get("locations")));
        game.winner = str(c.get("winner"));
        game.simulationStarted = boolOf(c.get("simulation_started"), false);
        game.murderer = str(c.get("murderer"));
        game.correctVerdict = boolOf(c.get("correct_verdict"), false);
        for (Object o : mapList(c.get("discussion_transcript"))) {
            if (o instanceof Map<?, ?> mm) {
                Map<String, String> turn = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    if (e.getKey() != null) turn.put(str(e.getKey()), str(e.getValue()));
                }
                game.discussionTranscript.add(turn);
            }
        }
        game.discussionContexts.putAll(strMap(c.get("discussion_contexts")));

        games.put(sessionId, game);
        log.info("Script game {} restored from snapshot (phase={}, players={}, round={})",
                sessionId, game.phase.name(), game.players.size(), game.round);
        return game;
    }

    // ── C3: 快照恢复的宽容转换辅助（Jackson 反序列化后类型为 Map<String,Object>/List<Object>/Number 等） ──

    private static Phase parsePhase(Object o) {
        if (o == null) return Phase.SETUP;
        try {
            return Phase.valueOf(str(o));
        } catch (IllegalArgumentException e) {
            return Phase.SETUP;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
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

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> strListMap(Object o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(str(e.getKey()), strList(e.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(str(e.getKey()), e.getValue() == null ? "" : str(e.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> doubleMap(Object o) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                if (e.getValue() instanceof Number n) {
                    out.put(str(e.getKey()), n.doubleValue());
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> intMap(Object o) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(str(e.getKey()), intOf(e.getValue(), 0));
            }
        }
        return out;
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x != null) out.add(str(x));
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(str(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolOf(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        return def;
    }

    /** C3: 安全读取 playerKeys（快照恢复前 map 可能为空）。 */
    private static Map<String, String> playerKeysOf(ScriptGame game) {
        return game.playerKeys == null ? Map.of() : game.playerKeys;
    }
}
