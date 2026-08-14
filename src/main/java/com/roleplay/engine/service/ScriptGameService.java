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
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.interact.MapInteractService;
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

    /** 阶段 2: 统一地图生成路径（LLM 生成 → 校验 → BSP 降级，见 docs/地图JSON契约-v1.md）。 */
    private final ScriptMapService mapService;

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

    /** P1（剧本杀可玩性修复，任务 1）：投票超时（毫秒）—— VOTE 阶段超过该时长后，
     *  resolveVote 将未投票玩家按弃票处理并转为托管（票作废）；默认 60s，可配
     *  roleplay.game.script.vote-timeout-ms。 */
    @Value("${roleplay.game.script.vote-timeout-ms:60000}")
    private long voteTimeoutMs = 60000;

    /** P1：投票超时总开关（默认开启；false=保持旧行为可无限等、无弃票处理）。 */
    @Value("${roleplay.game.script.vote-timeout-enabled:true}")
    private boolean voteTimeoutEnabled = true;

    /** P1：投票 quorum 门槛开关（默认开启；false=保持旧行为少数票即可定局）。 */
    @Value("${roleplay.game.script.quorum-enabled:true}")
    private boolean quorumEnabled = true;

    /** P-0805-A（B4）：平票/审批驳回重投次数上限（默认 3；超过则按「无人被定罪」结束投票，防无限循环）。 */
    @Value("${roleplay.game.script.max-revotes:3}")
    private int maxRevotes = 3;

    /** P1（测试钩子）：运行时切换投票超时（毫秒），对齐 RouterService.setSerialRound 先例。 */
    public void setVoteTimeoutMs(long ms) { this.voteTimeoutMs = ms; }

    /** P-0805-A（B4，测试钩子）：运行时切换重投次数上限。 */
    public void setMaxRevotes(int n) { this.maxRevotes = Math.max(0, n); }

    /** P-0805-B（WebSearch 搜证）：搜证时是否自动联网检索线索相关背景（默认关，避免每局都打 DuckDuckGo）。 */
    @Value("${roleplay.game.script.web-search-enabled:false}")
    private boolean webSearchEnabled = false;

    /** P-0805-B（WebSearch 搜证）：运行时切换开关（测试钩子）。 */
    public void setWebSearchEnabled(boolean on) { this.webSearchEnabled = on; }

    /** P-0805-C（阶段倒计时）：单阶段最长停留毫秒（默认 0=禁用；>0 时惰性推进 INVESTIGATION→DISCUSSION→VOTE，VOTE 复用投票超时/弃票）。 */
    @Value("${roleplay.game.script.phase-timeout-ms:0}")
    private long phaseTimeoutMs = 0L;

    /** P-0805-C（阶段倒计时，测试钩子）：运行时切换。 */
    public void setPhaseTimeoutMs(long ms) { this.phaseTimeoutMs = Math.max(0, ms); }

    /**
     * P-0805-C（阶段倒计时）：惰性超时推进 —— 任一玩家轮询状态时检测当前阶段停留时长，
     * 超过 phase-timeout-ms 自动推进（零定时器，对齐投票超时惰性判定先例）。
     * INVESTIGATION→DISCUSSION（自动启动讨论引擎）；DISCUSSION→VOTE（enterVotePhase 统一计时/quorum 重置）。
     * VOTE 阶段不在此推进（超时弃票已由 resolveVote 惰性处理，避免未经审批直接揭晓）。
     */
    private void maybeAdvanceOnTimeout(ScriptGame game) {
        if (game == null || game.phaseTimeoutMs <= 0) return;
        if (game.phase == Phase.ENDED || game.phase == Phase.REVEAL || game.phase == Phase.VOTE) return;
        long elapsed = System.currentTimeMillis() - game.phaseStartedAt;
        if (elapsed < game.phaseTimeoutMs) return;
        try {
            if (game.phase == Phase.INVESTIGATION) {
                log.info("Script game {} phase INVESTIGATION timed out ({}ms), auto advancing to DISCUSSION",
                        game.sessionId, game.phaseTimeoutMs);
                startDiscussion(game.sessionId);
            } else if (game.phase == Phase.DISCUSSION) {
                log.info("Script game {} phase DISCUSSION timed out ({}ms), auto advancing to VOTE",
                        game.sessionId, game.phaseTimeoutMs);
                enterVotePhase(game);
            }
        } catch (Exception e) {
            log.warn("Script game {} phase timeout advance failed: {}", game.sessionId, e.getMessage());
        }
    }

    /** P-0805-B：WebSearch 服务（无参构造的独立 @Service；懒创建，未开启时不消耗）。 */
    private WebSearchService webSearchService;

    private WebSearchService webSearch() {
        if (webSearchService == null) webSearchService = new WebSearchService();
        return webSearchService;
    }

    /** P1（测试钩子）：运行时切换投票超时开关。 */
    public void setVoteTimeoutEnabled(boolean enabled) { this.voteTimeoutEnabled = enabled; }

    /** P1（测试钩子）：运行时切换 quorum 开关。 */
    public void setQuorumEnabled(boolean enabled) { this.quorumEnabled = enabled; }

    /** P-0803-J（地图容量扩展）：剧本杀地图默认宽度（roleplay.game.map.default-width，默认 24 保持既有行为）。 */
    @Value("${roleplay.game.map.default-width:40}")
    private int mapDefaultWidth = 40;

    /** P-0803-J（地图容量扩展）：剧本杀地图默认高度（roleplay.game.map.default-height，默认 16 保持既有行为）。 */
    @Value("${roleplay.game.map.default-height:24}")
    private int mapDefaultHeight = 24;

    /** P-0810-21（P0-3，尺寸下限）：剧本杀地图最小宽度（roleplay.game.map.min-width，默认 32）。 */
    @Value("${roleplay.game.map.min-width:32}")
    private int mapMinWidth = 32;

    /** P-0810-21（P0-3，尺寸下限）：剧本杀地图最小高度（roleplay.game.map.min-height，默认 20）。 */
    @Value("${roleplay.game.map.min-height:20}")
    private int mapMinHeight = 20;

    /** P-0810-21（P0-3）：尺寸解析后 clamp 到下限（显式传参 / 对局已定尺寸 / 配置默认统一生效；低于下限提升并 log warning）。 */
    private int[] clampToMin(int effW, int effH, String logTag) {
        int cw = Math.max(effW, mapMinWidth);
        int ch = Math.max(effH, mapMinHeight);
        if (cw != effW || ch != effH) {
            log.warn("Script game {} map size clamped to min {}×{} (requested {}×{})", logTag, cw, ch, effW, effH);
        }
        return new int[] { cw, ch };
    }


    /** P-0803-K（多地图切换）：door 靠近校验容差（格）——玩家上报坐标距 door 中心曼哈顿距离
     *  ≤ radius + 容差 视为靠近（服务端不持有玩家权威位置，坐标由客户端上报，尽力校验；缺坐标跳过）。 */
    private static final int DOOR_PROXIMITY_SLACK = 2;

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

    // P-0802-J: 讨论引擎 per-game（按对局/会话隔离，多局并发互不覆盖；替代原 service 实例级共享）
    // D-012 已知限制「讨论引擎为 service 实例级共享（多局并发讨论会互覆世界）」的剧本杀侧落地：
    // 每局独立 SimulationWorld + ConversationManager + WorldDirectorService，互不覆盖/互不串状态
    // （对齐狼人杀侧 P-0802-I 三 Map 模式：discussionWorlds/discussionConversations/discussionDirectors）。
    private final Map<String, SimulationWorld> discussionWorlds = new ConcurrentHashMap<>();
    private final Map<String, ConversationManager> discussionConversations = new ConcurrentHashMap<>();
    private final Map<String, WorldDirectorService> discussionDirectors = new ConcurrentHashMap<>();
    /** GAP-3: 讨论轮次执行线程池（虚拟线程，讨论结束自动进 VOTE）。 */
    private final ExecutorService discussionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // P-0802-P3（改造方案 Phase 3）：本局 player_id 绑定（sessionId → playerId → 当前角色名）——
    // init 时 controller 经 registerPlayerBinding 登记；renamePlayer 同步更新值；saveSnapshot 随快照落库；
    // restoreFromSnapshot/resumeGame 按绑定重映射（旧存档含旧名 → 恢复到新名，解决“改完名再重连”）。
    private final Map<String, Map<String, String>> playerBindingsBySession = new ConcurrentHashMap<>();
    /** P-0802-P3：玩家身份解析器（可选注入；直接构造的旧测试为 null → 绑定重映射跳过，零行为变化）。 */
    private final PlayerIdentityService identityService;

    public enum Phase { SETUP, INVESTIGATION, DISCUSSION, VOTE, REVEAL, ENDED }

    public static class ScriptGame {
        String sessionId;
        volatile Phase phase = Phase.SETUP;
        /** P-0803-K（剧本杀双版本）：对局模式 —— "full"=真剧本杀（搜证+地图，默认）/ "chat"=简单对话版（无取证，直接多人对话讨论）。 */
        volatile String mode = "full";

        // Script data
        String name = "未命名剧本";
        String background = "";
        String truth = "";
        /** C1: 剧本 Schema v1 全量（generateScript 输出，落库与双生成器一致性验证用）。 */
        Map<String, Object> scriptSchema;
        /** C1: schema 中的凶手角色 id（role_x；兼容旧数据可为空，运行时判定仍走 truth 解析 D6）。 */
        String killerId = "";
        /** P-0805-A（B2）：schema 角色 id → 角色名（killerId 结构化判定用；随快照落库，兼容旧快照空 map）。 */
        final Map<String, String> roleNamesById = new LinkedHashMap<>();
        /** P-0805-B（私聊）：私聊历史 —— 会话键 "A|B"（字典序）→ 消息列表 {from,to,content,ts}；随快照落库，重启可恢复。 */
        final Map<String, List<Map<String, Object>>> privateChats = new LinkedHashMap<>();
        /** P-0805-B（时间推进）：对局开始时间戳（initGame 置位；前端可显示对局已进行时长/阶段倒计时基准）。 */
        long startedAt = System.currentTimeMillis();
        /** P-0805-C（阶段倒计时）：当前阶段进入时间戳（INVESTIGATION/DISCUSSION/VOTE 每次切换置位；超时自动推进基准）。 */
        long phaseStartedAt = System.currentTimeMillis();
        /** P-0805-C（阶段倒计时）：单阶段最长停留毫秒（initGame 从配置拷入；0=禁用；toMap 下发供前端倒计时 UI）。 */
        long phaseTimeoutMs = 0L;
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

        // P1（剧本杀可玩性修复）：
        // ① 投票超时 + quorum —— voteStartedAt=进入 VOTE 的时间戳（0=未进入，超时判定基准，随快照落库）；
        //    quorumFailCount=quorum 不足清票重投计数（0=首次，1=已重投过一轮；重投仍不足→按已投计+低参与度）；
        //    lowParticipation=低参与度判定标记（quorum 重投后仍不足时置 true）。
        // ② 玩家退出/挂机托管 —— trustees=托管玩家集合（退出/断线/投票超时无操作 → AI 代管但标记清楚；
        //    其票作废、不计入 quorum 在线数）；theme=剧本主题（restart 重开一局复用，随快照落库）。
        // ③ LLM 降级 —— llmDegraded=generateScript 走了 defaultScript 兜底（无 LLM key / LLM 失败），
        //    前端 ScriptStatePanel 据此显示「离线模板模式」提示条。
        long voteStartedAt = 0;
        int quorumFailCount = 0;
        /** P-0805-A（B4）：重投计数（平票清票 / 审批驳回回滚均累加；达 maxRevotes 后按「无人被定罪」终止）。 */
        int revoteCount = 0;
        volatile boolean lowParticipation = false;
        final java.util.Set<String> trustees = java.util.concurrent.ConcurrentHashMap.newKeySet();
        String theme = "";
        volatile boolean llmDegraded = false;

        // P-0810-17（阶段 1，两阶段生成）：概略剧本（第一阶段产物：locations/roles 一句话人设/
        // clues 标题/storyline/killer_hint）；完整剧本+地图由 POST /api/script/generate_full 异步补齐。
        Map<String, Object> outline;
        /** P-0810-17：完整剧本后台生成中标记（generate_full 异步期间 true；toMap.generating 暴露给前端 loading）。 */
        volatile boolean generating = false;
        /**
         * P-0810-17（B1）：已实时回显的发言去重键（speaker|message）——discussionSay 立即广播与
         * 讨论线程逐轮回调共用此集合，防止同一发言（人类在 discussionSay 广播 + 讨论组历史回放）重复推送。
         */
        final java.util.Set<String> speechEmitted = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // GAP-4b: 判定结果缓存（resolveVote 揭晓时写入，ENDED 落库/展示用）
        String murderer = "";
        boolean correctVerdict = false;

        // GAP-3: 讨论引擎产物 —— 发言记录 + 最后一轮各成员上下文（WEAK 隔离验证 A3-2）
        // P-0810-17（B5，D-034 登记项）：ArrayList → CopyOnWriteArrayList —— 讨论线程逐轮 append 与
        // saveSnapshot 拷贝（地图生成/切图/轮询快照路径）并发存在极小概率 CME，改并发安全容器根治。
        final List<Map<String, String>> discussionTranscript = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, String> discussionContexts = new LinkedHashMap<>();
        /** 讨论组已建且轮次进行中（A3-1/A3-3 验收）。 */
        volatile boolean discussionActive = false;

        // 阶段 2: 对局地图（LLM/BSP 生成，契约 v1；快照持久化，重启可恢复）
        Map<String, Object> mapData;
        /** 阶段 2: 地图生成溯源（generator/validation/fallback 原因）。 */
        List<String> mapFallbackReasons = new ArrayList<>();
        /** P-0803-J（地图容量扩展）：对局地图尺寸（0=未定；generateMap 首次生成后记录，
         *  regenerate 无显式尺寸时保持原尺寸；随快照持久化跨重启）。 */
        int mapWidth = 0;
        int mapHeight = 0;
        /** P-0803-K（多地图切换）：多图注册表 —— mapId → 地图数据（契约 v1）。
         *  首个地图 = init 自动生成（map_1）；generateMap 生成即注册并设为当前；
         *  door zone 切换在注册表内迁移 currentMapId。 */
        final Map<String, Map<String, Object>> maps = new LinkedHashMap<>();
        /** P-0803-K：每图生成溯源（mapId → fallback 原因列表；当前图镜像到 mapFallbackReasons 兼容旧字段）。 */
        final Map<String, List<String>> mapFallbacks = new LinkedHashMap<>();
        /** P-0803-K：每图搜证足迹（mapId → 已搜地点集合；切换时当前图足迹暂存/目标图足迹载入，
         *  searchedLocations 恒为当前图足迹视图 —— 前端绿点数据源不变）。 */
        final Map<String, java.util.Set<String>> searchedByMap = new LinkedHashMap<>();
        /** P-0803-K：当前地图 id（空串 = 未初始化；多图注册表键）。 */
        String currentMapId = "";
        /** P-0803-E 方案 B: 搜证足迹 —— 全局已搜过的地点（地图绿点恢复 + 面板/地图双通道同步，契约 §5 消费端）。 */
        final java.util.Set<String> searchedLocations = new java.util.LinkedHashSet<>();
        /** P-0814-H: 热点交互一次性 flag（对齐 searchedLocations 幂等标记范式 —— flag 动作写入、conditions.requireFlag 读取；随快照落库）。 */
        final java.util.Set<String> decorFlags = new java.util.LinkedHashSet<>();
        /** P-0814-H: decor 实例运行时状态（键 "mapId|decorId" → 状态 map；once 处理后含 processed=true；随快照落库，场景存热点实例状态）。 */
        final Map<String, Map<String, Object>> decorStates = new LinkedHashMap<>();

        public Map<String, Object> toMap(String playerName) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase.name().toLowerCase());
            // P-0803-K: 对局模式（"full"=真剧本杀 / "chat"=简单对话版）——前端据此隐藏搜证/地图/2D 讨论区
            m.put("mode", mode);
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
            m.put("started_at", startedAt);
            m.put("elapsed_ms", System.currentTimeMillis() - startedAt);
            m.put("phase_started_at", phaseStartedAt);
            m.put("phase_elapsed_ms", System.currentTimeMillis() - phaseStartedAt);
            m.put("phase_timeout_ms", phaseTimeoutMs);
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
            // P1: LLM 降级标记 —— 剧本走了 defaultScript 兜底（无 LLM key / LLM 失败）时为 true，
            // 前端 ScriptStatePanel 据此显示「当前为离线模板模式，内容为占位剧本」提示条
            m.put("llm_degraded", llmDegraded);
            // P1: 托管玩家列表 —— 退出/断线/投票超时无操作的玩家由 AI 代管（标记清楚，投票权作废），
            // 前端据此展示 🤖 托管标记与 quorum 在线人数感知
            m.put("trustees", new ArrayList<>(trustees));
            // P-0810-17（阶段 1）：概略剧本（两阶段生成第一阶段产物；SETUP 中间态时展示给玩家，
            // generate_full 完成后完整剧本生效，概略键保留供前端对照——附加键不破坏既有契约）
            if (outline != null) {
                m.put("outline", outline);
            }
            // P-0810-17：完整剧本后台生成中标记（前端 loading 依据；generate_full 异步期间 true）
            m.put("generating", generating);
            m.put("locations", new ArrayList<>(locations));
            // 阶段 2: 对局地图（已生成时附加；契约 v1，前端 Phaser 渲染）
            if (mapData != null) {
                m.put("map", mapData);
            }
            // P-0803-E 方案 B: 搜证足迹（地图绿点恢复数据源；附加键不破坏既有契约，旧对局为空列表）
            m.put("searched_locations", new ArrayList<>(searchedLocations));
            // P-0803-K: 多图注册表信息（当前图 id + 已注册图 id 列表；附加键不破坏既有契约，旧对局无此键）
            if (currentMapId != null && !currentMapId.isBlank()) {
                m.put("current_map_id", currentMapId);
            }
            if (!maps.isEmpty()) {
                m.put("map_ids", new ArrayList<>(maps.keySet()));
            }
            // P-0814-H: decor 实例运行时状态 + 一次性 flag（前端已处理置灰/条件展示数据源；附加键不破坏既有契约，旧对局无此键）
            if (!decorStates.isEmpty()) {
                m.put("decor_states", new LinkedHashMap<>(decorStates));
            }
            if (!decorFlags.isEmpty()) {
                m.put("decor_flags", new ArrayList<>(decorFlags));
            }
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

        /** P-0802-P3：测试/编排钩子 —— 玩家→角色表副本（局中改名断言用）。 */
        public Map<String, String> getAssignments() { return new LinkedHashMap<>(assignments); }

        /** P-0802-P3：测试/编排钩子 —— 玩家→roleKey 表副本。 */
        public Map<String, String> getPlayerKeys() { return new LinkedHashMap<>(playerKeys); }

        public boolean isSimulationStarted() {
            return simulationStarted;
        }
    }

    private final Map<String, ScriptGame> games = new ConcurrentHashMap<>();

    /** P-0805-A（B5）：对局 TTL —— 内存 games 表超时未访问的对局惰性清理（防长期运行内存泄漏；重启后由快照重建）。 */
    @Value("${roleplay.game.script.game-ttl-ms:43200000}")
    private long gameTtlMs = 43200000L; // 默认 12h

    /** P-0805-A（B5）：最近访问时间戳（sessionId → lastAccessMillis；getGame/init 时更新）。 */
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    /** P-0805-A（B5）：惰性清扫计数器（每 256 次访问触发一次全表扫描，摊薄开销）。 */
    private final java.util.concurrent.atomic.AtomicInteger sweepCounter = new java.util.concurrent.atomic.AtomicInteger();

    /** P-0805-A（B5，测试钩子）：运行时切换对局 TTL（毫秒；0=禁用清理，对齐 setVoteTimeoutMs 先例）。 */
    public void setGameTtlMs(long ms) { this.gameTtlMs = Math.max(0, ms); }

    /** P-0805-A（B5）：访问即刷新（getGame 公共读取点）；惰性清扫（周期性扫描过期对局移除）+ 阶段超时惰性推进。 */
    private ScriptGame touchGame(String sessionId) {
        if (sessionId == null) return null;
        ScriptGame g = games.get(sessionId);
        if (g != null) {
            lastAccess.put(sessionId, System.currentTimeMillis());
            lazySweep();
            // P-0805-C：轮询触发的阶段超时自动推进（惰性，零定时器）
            maybeAdvanceOnTimeout(g);
        }
        return g;
    }

    private void lazySweep() {
        if (gameTtlMs <= 0) return;
        int n = sweepCounter.incrementAndGet();
        if ((n & 0xFF) != 0) return; // 每 256 次访问
        sweepExpired();
    }

    /** P-0805-A（B5，测试钩子）：立即执行一次过期清扫（绕过节流）。 */
    public void sweepExpired() {
        if (gameTtlMs <= 0) return;
        long now = System.currentTimeMillis();
        long deadline = now - gameTtlMs;
        for (Map.Entry<String, Long> e : lastAccess.entrySet()) {
            if (e.getValue() != null && e.getValue() < deadline) {
                String sid = e.getKey();
                lastAccess.remove(sid);
                ScriptGame evicted = games.remove(sid);
                if (evicted != null) {
                    log.info("Script game {} evicted (idle > {}ms), snapshot persists state", sid, gameTtlMs);
                }
            }
        }
    }

    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService) {
        this(llmClient, approvalService, null, null, null);
    }

    /** 测试/兼容构造（GAP-4c/GAP-8 路径，无方案B announcement 注入）。 */
    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService,
                             DatabaseService databaseService, SSEController sse) {
        this(llmClient, approvalService, databaseService, sse, null);
    }

    /** Spring 注入路径（GAP-4c/GAP-8 + 方案B Step 3）：剧本落库 + script SSE 推送 + announcement SYSTEM 广播。 */
    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService,
                             DatabaseService databaseService, SSEController sse,
                             AnnouncementService announcementService) {
        this(llmClient, approvalService, databaseService, sse, announcementService, null);
    }

    /** P-0802-P3（改造方案 Phase 3）：六参 @Autowired 构造 —— 追加 PlayerIdentityService（快照 player_id_bindings 重映射用）；
     *  旧五参构造委托本构造 null（既有测试/调用点零改动）。 */
    @Autowired
    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService,
                             DatabaseService databaseService, SSEController sse,
                             AnnouncementService announcementService,
                             PlayerIdentityService playerIdentityService) {
        this.llmClient = llmClient;
        this.approvalService = approvalService;
        this.databaseService = databaseService;
        this.sse = sse;
        this.announcementService = announcementService;
        this.identityService = playerIdentityService;
        this.scriptService = new ScriptService(llmClient);
        this.mapService = new ScriptMapService(llmClient);
    }

    /** Phase 1: Generate script and assign roles. */
    public Map<String, Object> initGame(String sessionId, String theme, List<String> playerNames) {
        return initGame(sessionId, theme, playerNames, "full", false);
    }

    /**
     * P-0803-K（剧本杀双版本）：initGame 带模式重载。
     *
     * <p>mode="chat"（简单对话版）：跳过 INVESTIGATION 直接进入 DISCUSSION 并自动启动讨论引擎
     * （蓝图 v3 Step3v 降级路径「轮次发言」：无取证/无地图、只有多人对话），不自动生成地图、
     * 搜证/转交被阶段守卫天然拦截；角色/秘密/剧本 Schema v1 生成路径与真剧本杀完全一致。
     * mode="full"（默认，真剧本杀）：维持既有行为（INVESTIGATION + 自动地图串联）。
     */
    public Map<String, Object> initGame(String sessionId, String theme, List<String> playerNames, String mode) {
        return initGame(sessionId, theme, playerNames, mode, false);
    }

    /**
     * P-0810-17（阶段 1，两阶段生成）：initGame 五参重载 —— outlineOnly 分流。
     *
     * <p>{@code outlineOnly=true}（概略先行，POST /api/script/init 默认）：只生成概略剧本
     * （轻量 prompt，目标 &lt;10s，不再同步双 LLM 生成完整剧本+地图）→ {@code game.phase = Phase.SETUP}
     * （枚举现成未用，天然“概略已生成、完整待生成”中间态）→ toMap 附加 {@code outline} 键；
     * 完整剧本+地图由 {@link #generateFull}（POST /api/script/generate_full）后台异步补齐。
     *
     * <p>{@code outlineOnly=false}（缺省）：既有同步完整生成路径（完整剧本 + full 模式自动地图），
     * 3/4 参旧重载委托本参（既有调用方/测试零变化）。
     */
    public Map<String, Object> initGame(String sessionId, String theme, List<String> playerNames,
                                        String mode, boolean outlineOnly) {
        boolean chatMode = "chat".equalsIgnoreCase(mode);
        ScriptGame game = new ScriptGame();
        game.mode = chatMode ? "chat" : "full";
        game.sessionId = sessionId;
        game.players.addAll(playerNames);

        // P1: 剧本主题留档（restart 重开一局复用；随快照落库）
        game.theme = theme == null ? "" : theme;

        // ══════════════ P-0810-17 两阶段生成第一阶段：概略先行（outlineOnly） ══════════════
        if (outlineOnly) {
            // 只生成概略（轻量 LLM 调用，快 <10s）：locations / roles（名字+一句话人设）/
            // clues（标题+地点）/ storyline / killer_hint —— 完整剧本+地图由 generate_full 异步补齐。
            Map<String, Object> outline = scriptService.generateOutline(theme, playerNames);
            game.outline = outline;
            game.name = outlineName(outline, theme);
            game.background = outlineStoryline(outline);
            game.phase = Phase.SETUP;   // 中间态：概略已生成、完整剧本待生成
            game.round = 1;
            game.phaseStartedAt = System.currentTimeMillis();
            game.phaseTimeoutMs = this.phaseTimeoutMs;
            games.put(sessionId, game);

            // C3: 概略态也发放 roleKey（重连/身份认证概略期可用；generate_full 补齐完整剧本时保留）
            for (String player : game.players) {
                game.playerKeys.put(player, UUID.randomUUID().toString());
            }

            // 概略态快照（outline 随快照落库，重启不丢；scriptSchema 为空 → persistScript 跳过）
            saveSnapshot(game);
            broadcastPhase(game, "setup");
            broadcastStatus(game);
            log.info("Script game {} outline ready (SETUP): theme='{}', {} locations, {} roles, {} clues",
                    sessionId, game.theme,
                    outlineLocations(outline).size(), outlineRoles(outline).size(), outlineClues(outline).size());
            return game.toMap(playerNames.isEmpty() ? "" : playerNames.get(0));
        }

        // ══════════════ 完整生成路径（outlineOnly=false，既有行为） ══════════════
        // C1: 统一生成路径 —— 委托 ScriptService.generateScriptChecked（Schema v1 输出，宽容解析旧/新格式）
        ScriptService.ScriptGeneration generation = scriptService.generateScriptChecked(theme, playerNames);
        applyScript(game, generation.schema(), generation.degraded());

        // P-0803-K：简单对话版直接进入 DISCUSSION（无搜证阶段）；真剧本杀维持 INVESTIGATION
        game.phase = chatMode ? Phase.DISCUSSION : Phase.INVESTIGATION;
        game.round = 1;
        game.phaseTimeoutMs = this.phaseTimeoutMs;
        games.put(sessionId, game);

        // GAP-4c: 剧本生成即落库（对局重启不丢剧本；A4-3 验收依赖）
        persistScript(game);
        // C3: 初始快照落库（开房即有恢复点；断线重连/崩溃恢复基础）
        saveSnapshot(game);
        // P-0803-D（地图增强，调研项 1 方案 A 自动串联）：剧本生成即自动生成地图（仅真剧本杀；
        // 简单对话版无取证无地图，跳过 LLM 等待直接进入讨论）。失败不阻塞 init——generateMap 内部
        // 已有 LLM→BSP 兜底，此处仅防御性兜底；mapData 就位后 init 响应（toMap）自动附加 map 键。
        if (!chatMode) {
            try {
                generateMap(sessionId, "", 0L, false);
            } catch (Exception e) {
                log.warn("Script game {} auto map generation failed (non-blocking): {}", sessionId, e.getMessage());
            }
        }
        // GAP-8: 剧本生成完成，推送首阶段 + 状态；简单版直接推送 discussion 并自动启动讨论引擎
        if (chatMode) {
            if (game.players.size() < 2) {
                // P-0810-17（B2）：单人局无讨论对象 → 直接进 VOTE（跳过 discussion 广播，
                // 避免同一请求内 script_phase 连发 discussion→vote 快速翻转，前端只见 vote）
                enterVotePhase(game);
            } else {
                broadcastPhase(game, "discussion");
                try {
                    runDiscussionEngine(game);
                } catch (Exception e) {
                    log.warn("Script game {} chat-mode discussion engine setup failed, advancing to VOTE: {}",
                            sessionId, e.getMessage());
                    // P-0810-17（B2）：失败路径同样跳过 discussion 广播直接进 VOTE（同请求内不连发）
                    enterVotePhase(game);
                }
            }
        } else {
            broadcastPhase(game, "investigation");
        }
        broadcastStatus(game);

        log.info("Script game {}: {} players, {} locations, {} clues, {} secrets",
            sessionId, playerNames.size(), game.locations.size(), game.clues.size(), game.secrets.size());

        return game.toMap(playerNames.isEmpty() ? "" : playerNames.get(0));
    }

    /**
     * P-0810-17（阶段 1）：完整剧本 schema → 对局字段填充（initGame 完整路径与 generateFull 共用，
     * 双路径零漂移——两阶段二次生成 players 必须与概略一致，此处按 init 已登记的 players 分配角色）。
     */
    private void applyScript(ScriptGame game, Map<String, Object> script, boolean degraded) {
        game.scriptSchema = script;
        game.llmDegraded = degraded;
        game.name = ScriptSchemaV1.title(script);
        game.background = ScriptSchemaV1.background(script);
        game.truth = ScriptSchemaV1.truth(script);
        game.killerId = ScriptSchemaV1.killerId(script);
        // P-0805-A（B2）：角色 id → 角色名（结构化 killer 判定；空 killerId 不影响既有 truth 解析路径）
        game.roleNamesById.putAll(ScriptSchemaV1.roleNamesById(script));
        // roles: 规范角色名序列（secrets 键集合恒等于 roles，A1-3）
        game.roles.clear();
        game.roles.addAll(ScriptSchemaV1.roleNames(script));
        game.locations.clear();
        game.locations.addAll(ScriptSchemaV1.locations(script));
        // clues: 规范化线索（id/title/location/content/transferable/visible_to_owner_only + public 兼容键）
        game.clues.clear();
        game.clues.addAll(ScriptSchemaV1.clueList(script));
        // D5: secrets 发放 —— 从 schema 解析角色秘密（角色名 → 秘密），按角色存储
        game.secrets.clear();
        game.secrets.putAll(ScriptSchemaV1.secretsByRole(script));

        // Assign roles to players (shuffle)
        List<String> shuffledRoles = new ArrayList<>(game.roles);
        Collections.shuffle(shuffledRoles);
        game.assignments.clear();
        for (int i = 0; i < game.players.size() && i < shuffledRoles.size(); i++) {
            game.assignments.put(game.players.get(i), shuffledRoles.get(i));
        }
        // Leftover players get generic roles
        for (int i = shuffledRoles.size(); i < game.players.size(); i++) {
            game.assignments.put(game.players.get(i), "嫌疑人_" + (i - shuffledRoles.size() + 1));
        }

        // C2: 按角色分配初始 AP（基础值 + 角色 ap_bonus；侦探类角色行动点多，蓝图 P2 角色差异化搜证）
        Map<String, Integer> apBonusByName = ScriptSchemaV1.apBonusByRoleName(script);
        game.playerAp.clear();
        game.playerApMax.clear();
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, "");
            int bonus = role.isEmpty() ? 0 : apBonusByName.getOrDefault(role, 0);
            int ap = Math.max(1, apBase + bonus); // 至少 1 点，避免 0 AP 死局
            game.playerAp.put(player, ap);
            game.playerApMax.put(player, ap);
        }

        // 批次 D: 按角色分配 talkativeness（schema roles[].talkativeness，缺省 0.5；发言门控概率输入）
        Map<String, Double> talkativenessByName = ScriptSchemaV1.talkativenessByRoleName(script);
        game.playerTalkativeness.clear();
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
        // C3: 每玩家 roleKey —— 概略态 init 已发放则保留（generate_full 不覆盖，重连/认证连续）；
        // 完整路径（init outlineOnly=false）直接生成
        for (String player : game.players) {
            game.playerKeys.putIfAbsent(player, UUID.randomUUID().toString());
        }
    }

    /**
     * P-0810-17（阶段 1，新端点 POST /api/script/generate_full）：完整剧本 + 地图后台异步生成。
     *
     * <p>仅 {@code Phase.SETUP}（概略已生成、完整待生成）可调；异步虚拟线程执行，不阻塞调用方：
     * ① 完整剧本（概略作 prompt 约束，防两阶段矛盾）→ applyScript 填角色/秘密/线索/AP →
     * 落库 + 快照；② 阶段推进：full → INVESTIGATION / chat → DISCUSSION（与 initGame 完整路径同语义）；
     * ③ 推送 script_ready（决策点 6：新增结构化就绪事件）+ script_phase + script_status；
     * ④ full 模式异步 generateMap（LLM→7 项校验→BSP 降级，多图注册表；失败不阻塞）；
     * 完成后再次 script_ready（map_ready=true）+ script_status。
     *
     * @return 立即返回 {ok, generating, session_id, phase:"setup", message}；生成状态经 toMap.generating / script_ready 可查
     */
    public Map<String, Object> generateFull(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase != Phase.SETUP) {
            return Map.of("error", "当前不是概略待生成阶段（phase=" + game.phase.name().toLowerCase() + "）",
                    "phase", game.phase.name().toLowerCase());
        }
        if (game.generating) return Map.of("error", "完整剧本生成中，请稍候", "generating", true);
        game.generating = true;
        // 生成中状态先推一次（script_status 携带 generating=true，前端可显示 loading）
        broadcastStatus(game);
        final ScriptGame fg = game;
        discussionExecutor.submit(() -> {
            try {
                // ① 完整剧本（概略约束注入 prompt，防两阶段矛盾——方案 §7 决策点 3）
                ScriptService.ScriptGeneration generation =
                        scriptService.generateScriptChecked(fg.theme, fg.players, fg.outline);
                applyScript(fg, generation.schema(), generation.degraded());
                // 落库：剧本 type=script + 初始快照（与 initGame 完整路径双点一致）
                persistScript(fg);
                saveSnapshot(fg);

                // ② 阶段推进：full → INVESTIGATION / chat → DISCUSSION（守卫语义与 initGame 一致）
                boolean chat = "chat".equalsIgnoreCase(fg.mode);
                fg.phase = chat ? Phase.DISCUSSION : Phase.INVESTIGATION;
                fg.round = 1;
                fg.phaseStartedAt = System.currentTimeMillis();

                // ③ 就绪事件 + 阶段/状态推送（script_ready 携带结构化 payload，决策点 6 选新增事件）
                broadcastScriptReady(fg, false);
                if (chat) {
                    if (fg.players.size() < 2) {
                        // B2：单人局直接进 VOTE（跳过 discussion 广播，防同请求快速翻转）
                        enterVotePhase(fg);
                    } else {
                        broadcastPhase(fg, "discussion");
                        try {
                            runDiscussionEngine(fg);
                        } catch (Exception e) {
                            log.warn("Script game {} chat-mode discussion engine setup failed, advancing to VOTE: {}",
                                    sessionId, e.getMessage());
                            enterVotePhase(fg);
                        }
                    }
                } else {
                    broadcastPhase(fg, "investigation");
                }
                broadcastStatus(fg);

                // ④ full 模式异步地图（LLM→校验→BSP 降级；失败不阻塞，generateMap 内部已有兜底）
                if (!chat) {
                    try {
                        generateMap(sessionId, "", 0L, false);
                        broadcastScriptReady(fg, true);
                        broadcastStatus(fg);
                    } catch (Exception e) {
                        log.warn("Script game {} full map generation failed (non-blocking): {}",
                                sessionId, e.getMessage());
                    }
                }
                log.info("Script game {} full generation done: phase={}, roles={}, clues={}, map={}",
                        sessionId, fg.phase.name().toLowerCase(), fg.roles.size(), fg.clues.size(), fg.currentMapId);
            } catch (Exception e) {
                log.warn("Script game {} generate_full failed: {}", sessionId, e.getMessage());
            } finally {
                fg.generating = false;
                saveSnapshot(fg);
            }
        });
        return Map.of("ok", true, "generating", true, "session_id", sessionId,
                "phase", "setup", "message", "完整剧本生成已开始（后台异步），完成后推送 script_ready 事件");
    }

    // ── P-0810-17：概略字段访问器（toMap/initGame 用，null 安全） ──

    private static String outlineName(Map<String, Object> outline, String theme) {
        if (outline == null) return theme == null || theme.isBlank() ? "未命名剧本" : theme;
        Object t = outline.get("name");
        String s = t == null ? "" : String.valueOf(t).trim();
        return s.isBlank() ? (theme == null || theme.isBlank() ? "未命名剧本" : theme) : s;
    }

    private static String outlineStoryline(Map<String, Object> outline) {
        if (outline == null) return "";
        Object s = outline.get("storyline");
        return s == null ? "" : String.valueOf(s);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outlineRoles(Map<String, Object> outline) {
        if (outline == null) return List.of();
        Object r = outline.get("roles");
        return r instanceof List ? (List<Map<String, Object>>) r : List.of();
    }

    private static List<String> outlineLocations(Map<String, Object> outline) {
        if (outline == null) return List.of();
        Object l = outline.get("locations");
        return l instanceof List ? (List<String>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outlineClues(Map<String, Object> outline) {
        if (outline == null) return List.of();
        Object c = outline.get("clues");
        return c instanceof List ? (List<Map<String, Object>>) c : List.of();
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
            // P-0803-E 方案 B: 搜证过且无更多线索 → 记录足迹（地图该地点绿点态；已搜空不再重复提示）
            game.searchedLocations.add(location);
            // P-0803-K: 同步当前图足迹（切图后按图隔离恢复）
            game.searchedByMap.computeIfAbsent(game.currentMapId, k -> new java.util.LinkedHashSet<>()).add(location);
            saveSnapshot(game);
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
        // P-0803-E 方案 B: 搜证足迹（该地点已搜过，地图绿点同步数据源）
        game.searchedLocations.add(location);
        // P-0803-K: 同步当前图足迹（切图后按图隔离恢复）
        game.searchedByMap.computeIfAbsent(game.currentMapId, k -> new java.util.LinkedHashSet<>()).add(location);
        // C3: 状态变更 → 快照落库（搜证结果/AP 扣减可恢复）
        saveSnapshot(game);

        // P-0805-B（WebSearch 搜证）：开启时对首条线索做联网背景检索（物证联网检索玩法；
        // 失败静默降级——不影响搜证结果，仅追加 web_results 字段）
        List<Map<String, Object>> webResults = new ArrayList<>();
        if (webSearchEnabled && !found.isEmpty()) {
            try {
                Map<String, Object> first = found.get(0);
                String query = String.valueOf(first.get("title")) + " " + game.name + " 案件 线索 真相";
                for (Map<String, String> r : webSearch().search(query, 3)) {
                    webResults.add(new LinkedHashMap<>(r));
                }
            } catch (Exception e) {
                log.warn("Script game {} web search failed (non-blocking): {}", sessionId, e.getMessage());
            }
        }

        result.put("found", foundIds);
        result.put("clues", found.stream()
            .map(c -> Map.of("id", c.get("id"), "content", c.get("content"), "ap_cost", ScriptSchemaV1.apCost(c)))
            .collect(Collectors.toList()));
        if (!webResults.isEmpty()) result.put("web_results", webResults);
        result.put("result", "搜证成功：获得 " + foundIds.size() + " 条线索，消耗 " + cost + " AP");
        result.put("ap", game.playerAp.get(player));
        result.put("ap_cost", cost);
        return result;
    }

    /**
     * P-0814-H（热点/搜证点交互系统，核心借鉴清单 4/5 = I1 统一交互链 + I2 数据驱动 + I3 三层持久化）：
     * 玩家对地图交互点（decor 实体 / tileProps 瓦片动作 / 环境占位）执行统一动作键交互。
     *
     * <p>全链路逻辑（半径判定 Chebyshev / 优先级链 / once 幂等 / conditions 门 / 动作分发表）在
     * {@link com.roleplay.engine.simulation.map.interact.MapInteractService}（纯逻辑、单测直测）；
     * 本方法只做对局级校验 + 状态落地（GameContext 匿名实现）：
     * ① 热点实例状态（state 字段变更）→ game.decorStates（随快照落库，场景存热点实例状态）；
     * ② 一次性 flag → game.decorFlags（对齐 searchedLocations 幂等标记范式，不新造持久化体系）；
     * ③ 玩家持有（addItem 授予线索）→ game.playerClues（对齐既有线索持有机制）；
     * 快照恢复时三者一并恢复（restoreFromSnapshot）。
     *
     * @param mapId   目标地图 id（可空 —— 缺省当前图；多图注册表键）
     * @param decorId 显式目标 decor id（可空 —— 缺省走 tile 坐标解析）
     * @param tile    目标格坐标 "x,y"（可空 —— 与 decor_id 至少其一）
     * @param px, py  玩家瓦片坐标（可空 —— 缺省跳过靠近校验，客户端上报尽力而为，对齐 switchMap）
     */
    public Map<String, Object> interact(String sessionId, String player, String playerKey,
                                        String mapId, String decorId, String tile, Integer px, Integer py) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        String targetMapId = (mapId == null || mapId.isBlank()) ? game.currentMapId : mapId.trim();
        if (targetMapId.isBlank()) return Map.of("error", "地图尚未生成");
        Map<String, Object> data = game.maps.get(targetMapId);
        if (data == null) return Map.of("error", "地图不存在: " + targetMapId);
        if (player == null || player.isBlank()) return Map.of("error", "缺少玩家名");
        if (!game.players.contains(player)) return Map.of("error", "玩家不在本局中");
        Map<String, Object> access = checkPlayerAccess(sessionId, player, playerKey);
        if (access != null) return access;

        Map<String, Object> result = MapInteractService.interact(targetMapId, data, player, decorId, tile, px, py,
                new MapInteractService.GameContext() {
                    @Override
                    public boolean grantClue(String p, String clueId, Map<String, Object> clueData) {
                        List<String> mine = game.playerClues.computeIfAbsent(p, k -> new ArrayList<>());
                        if (mine.contains(clueId)) return false;
                        // 线索存在 → 直接授予；不存在且携带 title/content → 补建新线索（数据驱动 addItem）
                        Map<String, Object> existing = null;
                        for (Map<String, Object> c : game.clues) {
                            if (clueId.equals(c.get("id"))) {
                                existing = c;
                                break;
                            }
                        }
                        if (existing == null && clueData != null && !clueData.isEmpty()) {
                            Map<String, Object> nc = new LinkedHashMap<>();
                            nc.put("id", clueId);
                            nc.put("title", clueData.getOrDefault("title", clueId));
                            nc.put("content", clueData.getOrDefault("content", ""));
                            nc.put("location", clueData.getOrDefault("location", ""));
                            nc.put("public", false);
                            game.clues.add(nc);
                            existing = nc;
                        }
                        if (existing == null) return false; // 未知线索 id 且无数据 → 无法授予
                        mine.add(clueId);
                        return true;
                    }

                    @Override
                    public String clueTitle(String clueId) {
                        for (Map<String, Object> c : game.clues) {
                            if (clueId.equals(c.get("id"))) {
                                Object t = c.get("title");
                                return t == null ? null : String.valueOf(t);
                            }
                        }
                        return null;
                    }

                    @Override
                    public boolean hasFlag(String flag) {
                        return game.decorFlags.contains(flag);
                    }

                    @Override
                    public void writeFlag(String flag) {
                        game.decorFlags.add(flag);
                    }

                    @Override
                    public boolean isProcessed(String mid, String did) {
                        Map<String, Object> st = game.decorStates.get(mid + "|" + did);
                        return st != null && Boolean.TRUE.equals(st.get("processed"));
                    }

                    @Override
                    public void setProcessed(String mid, String did) {
                        game.decorStates.computeIfAbsent(mid + "|" + did, k -> new LinkedHashMap<>()).put("processed", true);
                    }

                    @Override
                    public Map<String, Object> runtimeState(String mid, String did) {
                        Map<String, Object> st = game.decorStates.get(mid + "|" + did);
                        return st == null ? Map.of() : new LinkedHashMap<>(st);
                    }

                    @Override
                    public void setRuntimeState(String mid, String did, Map<String, Object> merged) {
                        game.decorStates.put(mid + "|" + did, new LinkedHashMap<>(merged));
                    }
                });
        // P-0814-H: 交互状态变更随快照落库（三层持久化：实例状态 decorStates / 一次性 flag decorFlags /
        // 玩家持有 playerClues；databaseService 为 null 时 saveSnapshot 内部跳过）
        saveSnapshot(game);
        return result;
    }

    /**
     * 阶段 2: 生成对局地图（LLM 统一路径 → 校验 → BSP 降级，docs/地图JSON契约-v1.md）。
     *
     * <p>地图与对局绑定（ScriptGame.mapData），快照落库（重启可恢复）；已生成过且未显式
     * 请求重新生成（body 带 regenerate=true）时直接返回缓存地图，不重复调 LLM。
     * 返回：{map, generator, validation{ok,errors,warnings}, fallback[], cached}。
     *
     * @param sessionId 对局 id（必传；缺省由 controller 兜底当前对局）
     * @param theme     主题覆盖（可空——用剧本名）
     * @param seed      BSP 降级种子（0=默认）
     * @param regenerate 强制重新生成（默认 false）
     */
    public Map<String, Object> generateMap(String sessionId, String theme, long seed, boolean regenerate) {
        return generateMap(sessionId, theme, seed, regenerate, 0, 0);
    }

    /**
     * 阶段 2: 生成对局地图（P-0803-J 地图容量扩展：显式尺寸贯穿）。
     * 尺寸解析优先级：显式 width/height（&gt;0）→ 对局已定尺寸（regenerate 无显式尺寸时保持原尺寸）→ 配置默认。
     * 大图（超 LLM token 预算 40×24）由 ScriptMapService 直接走 BSP 确定性路径。
     *
     * @param width  显式地图宽度（≤0 = 不指定）
     * @param height 显式地图高度（≤0 = 不指定）
     */
    public Map<String, Object> generateMap(String sessionId, String theme, long seed, boolean regenerate,
                                           int width, int height) {
        return generateMap(sessionId, theme, seed, regenerate, width, height, null);
    }

    /**
     * 阶段 2: 生成对局地图（P-0803-K 多地图：生成即注册进多图注册表并设为当前）。
     * 尺寸解析优先级：显式 width/height（&gt;0）→ 对局已定尺寸（regenerate 无显式尺寸时保持原尺寸）→ 配置默认。
     * 大图（超 LLM token 预算 40×24）由 ScriptMapService 直接走 BSP 确定性路径。
     *
     * @param mapId 注册表键（可空——自动分配 map_&lt;n&gt;；LLM/BSP 输出的 map_id 统一归一为注册表键保证唯一）
     */
    public Map<String, Object> generateMap(String sessionId, String theme, long seed, boolean regenerate,
                                           int width, int height, String mapId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.mapData != null && !regenerate) {
            return mapResponse(game, Map.of("cached", true, "fallback", new ArrayList<>(game.mapFallbackReasons)));
        }
        int effW = width > 0 ? width : (game.mapWidth > 0 ? game.mapWidth : mapDefaultWidth);
        int effH = height > 0 ? height : (game.mapHeight > 0 ? game.mapHeight : mapDefaultHeight);
        // P-0810-21（P0-3）：尺寸下限 clamp（显式传参 / 对局已定尺寸 / 配置默认统一生效）
        int[] clamped = clampToMin(effW, effH, sessionId);
        effW = clamped[0];
        effH = clamped[1];
        String effTheme = (theme == null || theme.isBlank()) ? game.name : theme;
        String id = (mapId != null && !mapId.isBlank()) ? mapId.trim() : nextMapId(game);
        Map<String, Object> m = doGenerateAndRegister(game, id, effTheme, seed, effW, effH);
        if (m == null) return Map.of("error", "地图生成失败");
        // 设为当前图（镜像：mapData/mapFallbackReasons/mapWidth/mapHeight 兼容旧消费端）
        String prevId = game.currentMapId;
        game.mapData = m;
        game.currentMapId = id;
        game.mapFallbackReasons = new ArrayList<>(game.mapFallbacks.getOrDefault(id, List.of()));
        game.mapWidth = MapContract.intOf(m.get("width"), effW);
        game.mapHeight = MapContract.intOf(m.get("height"), effH);
        // P-0803-K: 足迹随切换迁移（与 switchMap 同规则——切走暂存 searchedByMap、目标图足迹载入；
        // 同图 regenerate 保持足迹；首个地图（prevId 空）无迁移必要）。缺此迁移时切换后
        // searchedLocations 仍残留旧图足迹（K5/K8 验收失败），且切回时会把污染足迹存进旧图。
        if (prevId != null && !prevId.isBlank() && !prevId.equals(id)) {
            game.searchedByMap.put(prevId, new java.util.LinkedHashSet<>(game.searchedLocations));
            game.searchedLocations.clear();
            java.util.Set<String> targetSearched = game.searchedByMap.get(id);
            if (targetSearched != null) game.searchedLocations.addAll(targetSearched);
        }
        // 状态变更 → 快照落库（地图可恢复）
        saveSnapshot(game);
        log.info("Script game {} map generated: id={}, generator={}, size={}x{}, fallback={}",
            sessionId, id,
            m.get("generator") instanceof Map<?, ?> g ? g.get("kind") : "?",
            game.mapWidth, game.mapHeight, game.mapFallbackReasons);
        return mapResponse(game, Map.of("cached", false, "fallback", new ArrayList<>(game.mapFallbackReasons)));
    }

    /**
     * P-0803-K: 生成地图并注册进多图注册表（generateMap / switchMap 自动生成目标图共用）。
     * 注册表键 = mapId（map 数据内 map_id 强制归一为键，保证唯一）；溯源按图存档。
     * 失败返回 null（仅 LLM 异常 + BSP 兜底也失败时）。
     */
    private Map<String, Object> doGenerateAndRegister(ScriptGame game, String mapId, String theme,
                                                      long seed, int width, int height) {
        try {
            int effW = width > 0 ? width : (game.mapWidth > 0 ? game.mapWidth : mapDefaultWidth);
            int effH = height > 0 ? height : (game.mapHeight > 0 ? game.mapHeight : mapDefaultHeight);
            // P-0810-21（P0-3）：尺寸下限 clamp（switchMap 自动生成目标图同样生效）
            int[] clamped = clampToMin(effW, effH, game.sessionId);
            effW = clamped[0];
            effH = clamped[1];
            String effTheme = (theme == null || theme.isBlank()) ? game.name : theme;
            // 线索地点（去重）：clues[].location —— zones[].clue_location 对齐目标（契约 §5）
            List<String> clueLocations = game.clues.stream()
                .map(c -> String.valueOf(c.getOrDefault("location", "")))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
            // P-0810-21（P0-1）：剧本上下文注入 —— background（概略态=outline.storyline，完整态=剧本 background）
            // + truth（完整剧本才有时，概略态为空）拼成 backgroundText 透传（仅氛围参考，不改变地点来源）
            String backgroundText = buildMapBackgroundText(game);
            ScriptMapService.MapResult result = mapService.generateMap(effTheme, backgroundText, game.locations, clueLocations, seed, effW, effH);
            Map<String, Object> m = new LinkedHashMap<>(result.map());
            m.put("map_id", mapId);
            game.maps.put(mapId, m);
            game.mapFallbacks.put(mapId, new ArrayList<>(result.fallbackReasons()));
            if (result.warnings() != null && !result.warnings().isEmpty()) {
                log.warn("Script game {} map {} quality warnings: {}", game.sessionId, mapId, result.warnings());
            }
            return m;
        } catch (Exception e) {
            log.warn("Script game {} map generation failed for {}: {}", game.sessionId, mapId, e.getMessage());
            return null;
        }
    }

    /** P-0810-21（P0-1）：拼地图 prompt 的剧本上下文文本（background + 案件真相；均空则空串）。 */
    private static String buildMapBackgroundText(ScriptGame game) {
        StringBuilder sb = new StringBuilder();
        if (game.background != null && !game.background.isBlank()) sb.append(game.background.trim());
        if (game.truth != null && !game.truth.isBlank()) {
            if (sb.length() > 0) sb.append("。");
            sb.append("案件真相：").append(game.truth.trim());
        }
        return sb.toString();
    }

    /** P-0803-K: 自动分配地图注册表键（map_1, map_2, …，注册表内唯一）。 */
    private static String nextMapId(ScriptGame game) {
        int n = game.maps.size() + 1;
        String id;
        do {
            id = "map_" + n++;
        } while (game.maps.containsKey(id));
        return id;
    }

    /** 阶段 2: 获取已生成的对局地图（未生成返回空 map 与 error）。 */
    public Map<String, Object> getMap(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.mapData == null) return Map.of("error", "地图尚未生成");
        return mapResponse(game, Map.of("cached", true));
    }

    /** P-0803-K: 当前地图 id（空串 = 未初始化；测试/状态查询）。 */
    public String getCurrentMapId(String sessionId) {
        ScriptGame game = games.get(sessionId);
        return game == null ? "" : game.currentMapId;
    }

    /** P-0803-K: 已注册地图 id 列表（对局多图注册表视图）。 */
    public List<String> getRegisteredMapIds(String sessionId) {
        ScriptGame game = games.get(sessionId);
        return game == null ? List.of() : new ArrayList<>(game.maps.keySet());
    }

    /**
     * P-0803-K（剧本杀模式多地图切换）：玩家进入/靠近 door 型 zone → 切换当前地图。
     *
     * <p>校验链：对局存在 → 阶段为 INVESTIGATION → 触发者在本局（+player_key 认证）→
     * door_zone_id 必须命中当前地图 type=door 的 zone（可选 x/y 靠近校验）→ 目标解析
     * （body target_map_id → door zone target/to/target_map_id 字段）→ 目标非当前地图 →
     * 目标已注册直接切换 / 未注册自动生成（BSP 兜底）→ 状态迁移（当前图足迹暂存、目标图
     * 足迹载入、mapWidth/mapHeight 随目标图尺寸联动）→ 快照 + script_status SSE 广播。
     * 非法 door 目标（无目标、目标=当前图、door 不存在/非 door 型、远离 door、阶段不符）→ 容错错误返回。
     * 角色/线索/AP/秘密/票型为对局级状态，切换天然保留。
     *
     * @param playerKey  可选身份校验（对齐 C3 玩家级端点，空串向后兼容）
     * @param doorZoneId 触发 door zone id（可空——缺省走显式 target_map_id 直切模式）
     * @param px, py     触发者瓦片坐标（可空——服务端不持有玩家权威位置，坐标由客户端上报，缺省跳过靠近校验）
     * @param targetMapId 目标地图 id（可空——缺省取 door zone 的 target/to/target_map_id 字段）
     */
    public Map<String, Object> switchMap(String sessionId, String player, String playerKey,
                                         String doorZoneId, Integer px, Integer py, String targetMapId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.mapData == null) return Map.of("error", "当前地图尚未生成");
        if (game.phase != Phase.INVESTIGATION) {
            return Map.of("error", "当前阶段不能切换地图（仅搜证阶段可探索多图）");
        }
        if (player == null || player.isBlank()) return Map.of("error", "缺少触发玩家名");
        if (!game.players.contains(player)) return Map.of("error", "玩家不在本局中");
        Map<String, Object> access = checkPlayerAccess(sessionId, player, playerKey);
        if (access != null) return access;

        // 1) door zone 解析（door_zone_id 缺省时要求显式 target_map_id 直切）
        String resolvedTarget = (targetMapId == null || targetMapId.isBlank()) ? "" : targetMapId.trim();
        Map<String, Object> door = null;
        if (doorZoneId != null && !doorZoneId.isBlank()) {
            door = findZone(game.mapData, doorZoneId);
            if (door == null) return Map.of("error", "door zone 不存在: " + doorZoneId);
            if (!"door".equals(String.valueOf(door.getOrDefault("type", "")))) {
                return Map.of("error", "zone 不是 door 类型（无法触发切图）: " + doorZoneId);
            }
            // 靠近校验：客户端上报瓦片坐标（尽力校验；缺坐标跳过）
            if (px != null && py != null) {
                int doorX = MapContract.intOf(door.get("x"), Integer.MIN_VALUE);
                int doorY = MapContract.intOf(door.get("y"), Integer.MIN_VALUE);
                if (doorX == Integer.MIN_VALUE || doorY == Integer.MIN_VALUE) {
                    return Map.of("error", "door zone 坐标缺失");
                }
                int dist = Math.abs(px - doorX) + Math.abs(py - doorY);
                int radius = Math.max(1, MapContract.intOf(door.get("radius"), 1));
                if (dist > radius + DOOR_PROXIMITY_SLACK) {
                    return Map.of("error", "玩家未靠近该 door（距离 " + dist
                            + " > 半径 " + radius + " + 容差 " + DOOR_PROXIMITY_SLACK + "）");
                }
            }
            if (resolvedTarget.isBlank()) {
                resolvedTarget = doorTargetOf(door);
            }
        } else if (resolvedTarget.isBlank()) {
            return Map.of("error", "缺少目标地图（door_zone_id 或 target_map_id 至少其一）");
        }
        if (resolvedTarget.isBlank()) {
            return Map.of("error", "该 door 未配置目标地图（缺 target/to/target_map_id 字段）");
        }
        if (resolvedTarget.equals(game.currentMapId)) {
            return Map.of("error", "目标地图就是当前地图: " + resolvedTarget);
        }

        // 2) 目标地图就绪：已注册直接取；未注册自动生成并注册（尺寸取 door 可选 width/height，缺省继承当前图尺寸）
        Map<String, Object> target = game.maps.get(resolvedTarget);
        if (target == null) {
            int tw = 0;
            int th = 0;
            if (door != null) {
                tw = MapContract.intOf(door.get("width"), 0);
                th = MapContract.intOf(door.get("height"), 0);
            }
            String doorTheme = door == null ? "" : String.valueOf(door.getOrDefault("prompt", door.getOrDefault("name", "")));
            target = doGenerateAndRegister(game, resolvedTarget,
                    doorTheme.isBlank() ? game.name : doorTheme, 0L, tw, th);
            if (target == null) return Map.of("error", "目标地图生成失败: " + resolvedTarget);
        }

        // 3) 切换 + 状态迁移（角色/线索/AP/秘密/票型为对局级状态，天然保留；足迹按图隔离迁移）
        String fromId = game.currentMapId;
        if (fromId != null && !fromId.isBlank()) {
            game.searchedByMap.put(fromId, new java.util.LinkedHashSet<>(game.searchedLocations));
        }
        game.currentMapId = resolvedTarget;
        game.mapData = target;
        game.mapFallbackReasons = new ArrayList<>(game.mapFallbacks.getOrDefault(resolvedTarget, List.of()));
        game.mapWidth = MapContract.intOf(target.get("width"), game.mapWidth);
        game.mapHeight = MapContract.intOf(target.get("height"), game.mapHeight);
        game.searchedLocations.clear();
        java.util.Set<String> targetSearched = game.searchedByMap.get(resolvedTarget);
        if (targetSearched != null) game.searchedLocations.addAll(targetSearched);

        saveSnapshot(game);
        // 全员同步：现有 script_status SSE 全量推送（含新 map，前端消费）+ announcement SYSTEM 横幅（现有通道）
        broadcastMapSwitch(game, fromId, resolvedTarget);
        broadcastStatus(game);
        log.info("Script game {} map switched {} → {} (size {}x{}) by {}",
                sessionId, fromId, resolvedTarget, game.mapWidth, game.mapHeight, player);
        return mapResponse(game, Map.of("switched", true, "from_map_id", fromId,
                "to_map_id", resolvedTarget, "trigger_player", player));
    }

    /**
     * P-0803-K: 在指定地图（缺省当前图）放置 door 型 zone（通往目标地图的门）。
     * 编排/测试/DM 接口 —— 生成的地图默认无 door，需要多图连通时经本方法布门；
     * LLM prompt 已预留 door 可选输出（target/to/target_map_id 字段）。
     * x/y 缺失或落在不可通行格 → 自动吸附最近可通行格（容错，防校验错误）。
     * door zone 可携带可选 width/height（自动生成目标图时按其尺寸生成，联动 P-0803-J 尺寸链路）。
     */
    public Map<String, Object> addDoorZone(String sessionId, String mapId, String zoneId, String name,
                                           int x, int y, int radius, String targetMapId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        String target = (mapId == null || mapId.isBlank()) ? game.currentMapId : mapId.trim();
        if (target == null || target.isBlank()) return Map.of("error", "地图尚未生成");
        Map<String, Object> data = game.maps.get(target);
        if (data == null) return Map.of("error", "地图不存在: " + target);
        if (zoneId == null || zoneId.isBlank()) return Map.of("error", "缺少 door zone id");
        if (targetMapId == null || targetMapId.isBlank()) return Map.of("error", "缺少目标地图（target_map_id 必填）");
        String tm = targetMapId.trim();
        if (tm.equals(target)) return Map.of("error", "door 不能指向自身所在地图");

        int[] pos = snapWalkable(data, x, y);
        if (pos == null) return Map.of("error", "地图无可用可通行格放置 door");

        Map<String, Object> door = new LinkedHashMap<>();
        door.put("id", zoneId.trim());
        door.put("name", name == null || name.isBlank() ? zoneId.trim() : name);
        door.put("type", "door");
        door.put("x", pos[0]);
        door.put("y", pos[1]);
        door.put("radius", Math.max(1, radius));
        door.put("target", tm);

        // zones 重建（同 id 替换；data 为注册表实例，当前图时与 game.mapData 同一对象，原地生效）
        List<Map<String, Object>> zones = new ArrayList<>();
        if (data.get("zones") instanceof List<?> zl) {
            for (Object o : zl) {
                if (o instanceof Map<?, ?> z) zones.add(new LinkedHashMap<>((Map<String, Object>) z));
            }
        }
        zones.removeIf(z -> zoneId.trim().equals(String.valueOf(z.get("id"))));
        zones.add(door);
        data.put("zones", zones);
        saveSnapshot(game);
        log.info("Script game {} door zone {} placed on {} → {} at ({},{})",
                sessionId, zoneId, target, tm, pos[0], pos[1]);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("map_id", target);
        r.put("zone", door);
        r.put("target_map_id", tm);
        return r;
    }

    /** P-0803-K: 在地图数据 zones[] 中按 id 找 zone（无则 null）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findZone(Map<String, Object> mapData, String zoneId) {
        if (mapData == null || zoneId == null || zoneId.isBlank()) return null;
        Object zonesObj = mapData.get("zones");
        if (!(zonesObj instanceof List<?> zones)) return null;
        for (Object o : zones) {
            if (o instanceof Map<?, ?> z && zoneId.equals(String.valueOf(z.get("id")))) {
                return new LinkedHashMap<>((Map<String, Object>) z);
            }
        }
        return null;
    }

    /** P-0803-K: door zone 目标地图解析（宽容字段：target / to / target_map_id，取首个非空）。 */
    private static String doorTargetOf(Map<String, Object> door) {
        for (String k : new String[]{"target", "to", "target_map_id"}) {
            Object v = door.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return "";
    }

    /**
     * P-0803-K: (x,y) 可通行则原样返回；否则在 map 内找距其最近的曼哈顿可通行格；找不到返回 null。
     * （x/y ≤ 0 视作未指定 → 全图最近可通行格）
     */
    private static int[] snapWalkable(Map<String, Object> mapData, int x, int y) {
        int W = MapContract.intOf(mapData.get("width"), 0);
        int H = MapContract.intOf(mapData.get("height"), 0);
        int[][] collision = null;
        if (mapData.get("layers") instanceof Map<?, ?> lm) {
            collision = MapContract.intGrid(lm.get("collision"));
        }
        if (collision == null) return null;
        if (x > 0 && y > 0 && y < collision.length && collision[y] != null
                && x < collision[y].length && collision[y][x] == 0) {
            return new int[]{x, y};
        }
        int bestD = Integer.MAX_VALUE;
        int[] best = null;
        for (int yy = 0; yy < collision.length && yy < H; yy++) {
            if (collision[yy] == null) continue;
            for (int xx = 0; xx < collision[yy].length && xx < W; xx++) {
                if (collision[yy][xx] == 0) {
                    int d = Math.abs(xx - (x > 0 ? x : 0)) + Math.abs(yy - (y > 0 ? y : 0));
                    if (d < bestD) {
                        bestD = d;
                        best = new int[]{xx, yy};
                    }
                }
            }
        }
        return best;
    }

    /**
     * P-0803-K: 切图全员同步 —— announcement SYSTEM 横幅（现有 AnnouncementService 通道，
     * 总开关 roleplay.broadcast.script-phase-broadcast 同门控；与 script_status SSE 通道并存）。
     * script_status 推送见 switchMap 内 broadcastStatus(game)（全量状态含新 map）。
     */
    private void broadcastMapSwitch(ScriptGame game, String fromId, String toId) {
        if (announcementService == null || !announcementService.isScriptPhaseBroadcast()) return;
        String title = game.name == null || game.name.isBlank() ? "剧本杀" : game.name;
        String text = "【" + title + "】地图切换：" + (fromId == null || fromId.isBlank() ? "初始" : fromId)
                + " → " + toId + " —— 请玩家们探索新场景！";
        announcementService.enqueue(new BroadcastMessage(
                UUID.randomUUID().toString(),
                BroadcastMessage.Level.SYSTEM, "system", "system",
                text, -1, -1, 0, BroadcastMessage.MODE_ANNOUNCEMENT,
                // 切图是离散横幅事件：coalesceKey 按目标图区分，避免同窗口内重复切图被合并成 ×N
                "script_map|" + toId,
                java.time.Instant.now().toEpochMilli()));
    }

    /** 组装地图响应（map + 溯源 + 校验信息）。 */
    private Map<String, Object> mapResponse(ScriptGame game, Map<String, Object> extra) {
        Map<String, Object> resp = new LinkedHashMap<>(extra);
        resp.put("map", game.mapData);
        Object gen = game.mapData == null ? null : game.mapData.get("generator");
        resp.put("generator", gen == null ? Map.of("kind", "unknown") : gen);
        resp.put("session_id", game.sessionId);
        return resp;
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
        // P1（任务 2a）：托管玩家（退出/断线/投票超时无操作 → AI 代管）票作废 —— 直接拒绝
        if (game.trustees.contains(voter)) return voter + " 已托管（AI 代管），投票作废";
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

        // ══ P1（任务 1）：投票超时 —— 超过 roleplay.game.script.vote-timeout-ms（默认 60s）后，
        //    未投票玩家按弃票处理并转为托管（AI 代管，票作废；任务 2a 的“超时无操作→托管”在此落地）。
        //    惰性判定：resolveVote 被调用时检查（无后台定时器，避免并发/测试 flaky）；
        //    开关 vote-timeout-enabled=false 时保持旧行为（可无限等）。
        List<String> abstained = new ArrayList<>();
        if (voteTimeoutEnabled && game.voteStartedAt > 0
                && System.currentTimeMillis() - game.voteStartedAt >= voteTimeoutMs) {
            for (String p : game.players) {
                if (!game.votes.containsKey(p) && !game.trustees.contains(p)) {
                    game.trustees.add(p);
                    abstained.add(p);
                }
            }
            if (!abstained.isEmpty()) {
                result.put("abstained", abstained);
                result.put("vote_timeout", true);
                // C3: 托管标记是状态变更 → 快照（重启恢复后仍保持托管）
                saveSnapshot(game);
            }
        }
        String timeoutNote = abstained.isEmpty()
                ? ""
                : "；投票超时，未投票玩家已按弃票处理并转为托管：" + String.join("、", abstained);

        // 1) 精确统计：只计合法票（票面嫌疑人归一为规范玩家名，非法票忽略；托管玩家票作废）
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
            result.put("result", "无人投票，无人被定罪，请先投票再揭晓" + timeoutNote);
            result.put("revote", true);
            return result;
        }
        // 票面全为无效值 → 同样不揭晓
        if (voteCount.isEmpty()) {
            result.put("result", "无有效投票（票面必须为本局玩家或角色名），请重新投票" + timeoutNote);
            result.put("revote", true);
            return result;
        }

        // 3) 平票 → 清空投票，复用 VOTE 阶段重投（D6：平票不再进入 REVEAL / 误设 winner；
        //    平票前置子 quorum 判定，既有平票语义零破坏）
        final int maxVotesFinal = maxVotes; // effectively-final 副本（lambda 引用要求）
        long ties = voteCount.values().stream().filter(c -> c == maxVotesFinal).count();
        if (ties > 1) {
            // P-0805-A（B4）：重投次数上限 —— 超过则按「无人被定罪」终止投票循环（不进入 REVEAL，返回 revote=false + tie_limit 标记）
            if (game.revoteCount >= maxRevotes) {
                game.revoteCount++;
                saveSnapshot(game);
                result.put("votes", new LinkedHashMap<>());
                result.put("most_voted", mostVoted);
                result.put("vote_count", maxVotes);
                result.put("result", "多次重投仍平票（已满重投上限 " + maxRevotes + " 次），本局无人被定罪" + timeoutNote);
                result.put("tie", true);
                result.put("tie_limit", true);
                result.put("revote", false);
                return result;
            }
            game.revoteCount++;
            game.votes.clear();
            // 重投重新计时（防“等满 60s 才被允许重投”）；quorumFailCount 不清（防平票↔quorum 乒乓死循环）
            game.voteStartedAt = System.currentTimeMillis();
            // C3: 清票也是状态变更 → 快照（避免重启后恢复出平票前的旧票型）
            saveSnapshot(game);
            result.put("votes", new LinkedHashMap<>());
            result.put("result", "平票，无人被定罪，已清空投票，请重新投票" + timeoutNote);
            result.put("tie", true);
            result.put("revote", true);
            return result;
        }

        // ══ P1（任务 1）：quorum 门槛 —— 有效票 < ceil(在线玩家数/2) 不判定：
        //    首次不足 → 清票重投一轮；重投仍不足 → 按已投计并标记「低参与度判定」。
        //    在线玩家数 = 本局玩家 − 托管玩家（退出/断线/超时的玩家不计入门槛，其票已作废）。
        if (quorumEnabled) {
            int online = 0;
            for (String p : game.players) {
                if (!game.trustees.contains(p)) online++;
            }
            int quorum = (online + 1) / 2; // ceil(n/2)
            result.put("online_players", online);
            result.put("quorum", quorum);
            if (maxVotes < quorum) {
                if (game.quorumFailCount == 0) {
                    game.quorumFailCount = 1;
                    game.votes.clear();
                    // 重投重新计时
                    game.voteStartedAt = System.currentTimeMillis();
                    saveSnapshot(game);
                    result.put("votes", new LinkedHashMap<>());
                    result.put("result", "投票人数不足（有效票 " + maxVotes + " / 需 ≥" + quorum
                            + "，在线 " + online + " 人），已清票重新投票一轮" + timeoutNote);
                    result.put("revote", true);
                    result.put("quorum_fail", true);
                    return result;
                }
                // 重投仍不足 → 按已投计，标记低参与度判定（随快照落库）
                game.lowParticipation = true;
                result.put("low_participation", true);
                saveSnapshot(game);
            }
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
        // P1: 超时弃票/低参与度提示追加到揭晓文案（前端 Reveal 面板可见）
        String verdictNote = timeoutNote;
        if (game.lowParticipation) {
            verdictNote += "；低参与度判定（投票人数不足门槛，按已投票计）";
        }
        result.put("result", verdict + verdictNote);
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
                // P-0805-A（B4）：审批驳回回滚同样受重投上限约束（防 DM 无限驳回-重投循环）
                game.revoteCount++;
                if (game.revoteCount > maxRevotes) {
                    game.votes.clear();
                    saveSnapshot(game);
                    Map<String, Object> rollback = new LinkedHashMap<>();
                    rollback.put("votes", new LinkedHashMap<>());
                    rollback.put("most_voted", mostVoted);
                    rollback.put("vote_count", maxVotes);
                    rollback.put("result", "审批多次驳回（已满上限 " + maxRevotes + " 次），本局无人被定罪");
                    rollback.put("tie_limit", true);
                    rollback.put("revote", false);
                    rollback.put("approval", "rejected");
                    return rollback;
                }
                log.warn("Script game {} reveal rejected/timeout, rollback to VOTE", game.sessionId);
                game.votes.clear();
                saveSnapshot(game);
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
            saveSnapshot(game);
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

    /** D6: 合法票精确统计 —— 嫌疑人归一为规范玩家名（角色名经 assignments 反查），非法票忽略；
     *  P1: 托管玩家（退出/断线/超时 → AI 代管）的票作废（castVote 已拒，此处防御性双保险）。 */
    private Map<String, Integer> countValidVotes(ScriptGame game) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Map.Entry<String, String> v : game.votes.entrySet()) {
            if (game.trustees.contains(v.getKey())) continue; // 托管玩家票作废
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
        // P-0805-A（B2）：结构化 killer 判定 —— killer_id（schema 角色 id）→ 角色名 → 玩家。
        // 消除 D6 文本解析不稳（LLM truth 用别名/不含凶手名 → 恒判"冤枉好人"）；killerId 为空（旧剧本/兜底）回退文本解析。
        String structural = resolveKillerByRoleId(game);
        if (!structural.isEmpty()) return structural;

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

    /**
     * P-0805-A（B2）：killerId（schema 角色 id）→ roleNamesById → 角色名 → assignments 反查玩家。
     * 三级容错：① id 直查 roleNamesById；② killerId 本身即角色名（部分旧格式）；③ killerId 是角色名时反查 assignments 值。
     * 均未命中返回空串（调用方回退 D6 文本解析）。
     */
    private String resolveKillerByRoleId(ScriptGame game) {
        String kid = game.killerId == null ? "" : game.killerId.trim();
        if (kid.isEmpty()) return "";

        // ① id → 角色名
        String roleName = game.roleNamesById.get(kid);
        // ② 兜底：killerId 直接是角色名
        if ((roleName == null || roleName.isBlank()) && game.roles.contains(kid)) roleName = kid;
        if (roleName == null || roleName.isBlank()) return "";

        // 角色名 → 玩家（先 assignments 值反查，再玩家名直配）
        for (Map.Entry<String, String> e : game.assignments.entrySet()) {
            if (roleName.equals(e.getValue())) return e.getKey();
        }
        return game.players.contains(roleName) ? roleName : "";
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
            enterVotePhase(game);
        }
    }

    /**
     * P1（任务 1）：统一进入投票阶段的入口 —— 置 phase=VOTE + 启动投票计时（voteStartedAt，
     * 超时判定基准）+ 重置 quorum 重投计数 + 快照 + 阶段推送。
     * 所有进入 VOTE 的路径（startVoting / 讨论引擎结束 / 讨论启动失败降级 / chat 模式讨论失败降级）
     * 统一走本方法，保证超时与 quorum 的计时基准一致。
     */
    private void enterVotePhase(ScriptGame game) {
        if (game == null) return;
        game.phase = Phase.VOTE;
        game.voteStartedAt = System.currentTimeMillis();
        game.phaseStartedAt = System.currentTimeMillis();
        game.quorumFailCount = 0;
        // C3: 阶段变更 → 快照
        saveSnapshot(game);
        broadcastPhase(game, "vote");
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
            game.phaseStartedAt = System.currentTimeMillis();
            // C3: 阶段变更 → 快照（恢复后若处于 DISCUSSION 但讨论线程已随重启丢失，DM 可调 start_voting 推进）
            saveSnapshot(game);
            broadcastPhase(game, "discussion");
            try {
                runDiscussionEngine(game);
            } catch (Exception e) {
                log.warn("Script game {} discussion engine setup failed, advancing to VOTE: {}",
                        sessionId, e.getMessage());
                // P-0810-17（B2）：失败路径改异步进 VOTE —— discussion 阶段广播已先行发送，
                // 若同步 enterVotePhase 会在同一请求内连发 discussion→vote（前端 setScriptPhase
                // 竞态只见 vote）；异步提交让 discussion 广播先落 SSE 通道，防快速翻转。
                final ScriptGame fg = game;
                discussionExecutor.submit(() -> enterVotePhase(fg));
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
        // P-0802-J: per-game 实例（世界/引擎/导演均按对局隔离，多局并发互不覆盖）
        ensureDiscussionEngine(game.sessionId);
        SimulationWorld world = discussionWorlds.get(game.sessionId);
        ConversationManager cm = discussionConversations.get(game.sessionId);
        WorldDirectorService director = discussionDirectors.get(game.sessionId);

        // P-0810-17（B1）：订阅讨论发言逐轮实时回调 —— ConversationManager 每轮结束后对新增发言
        // 逐条回调（SpeechTurn），此处转 script_speech SSE 实时回显（不再等全部轮次结束才落盘）；
        // human 发言已在 discussionSay 入口立即广播（同一对局 speechEmitted 去重，见 discussionSay），
        // 此处按 speaker|message 去重防双发。per-game 实例隔离：2D 世界/狼人杀各自 CM 不受影响。
        cm.setScriptSpeechListener(turn -> {
            try {
                if (turn == null || turn.speaker() == null || turn.text() == null || turn.text().isBlank()) return;
                if (!game.speechEmitted.add(turn.speaker() + "|" + turn.text())) return; // 已广播过（如 discussionSay 立即回显）
                if (sse != null) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("speaker", turn.speaker());
                    payload.put("message", turn.text());
                    payload.put("round", game.round);
                    payload.put("human", false);
                    payload.put("ts", System.currentTimeMillis());
                    sse.broadcastScriptSpeech(game.sessionId, payload);
                }
            } catch (Exception e) {
                log.warn("Script game {} speech SSE broadcast failed: {}", game.sessionId, e.getMessage());
            }
        });

        if (game.players.size() < 2) {
            // 单人局无讨论对象，直接进投票（统一入口：计时/quorum 重置/快照/推送）
            // P-0810-17（B2）：异步进 VOTE —— 调用方（initGame chat 单人局已跳过 discussion 广播；
            // startDiscussion 路径 discussion 广播已先行）同步 enterVotePhase 会同一请求内连发
            // discussion→vote 快速翻转，异步提交让前序广播先落 SSE 通道。
            final ScriptGame fg = game;
            discussionExecutor.submit(() -> enterVotePhase(fg));
            return;
        }

        world.clearAgents();
        world.setWorldNarration((game.background == null || game.background.isBlank())
                ? "你们正在针对这起案件进行讨论。"
                : game.background + "。你们正在针对这起案件进行讨论。");

        // 1) 注册讨论角色（A3-2：讨论 persona 不含秘密明文）
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, player);
            Agent agent = new Agent(buildDiscussionPersona(game, player, role), "npc", llmClient);
            double x = 100 + Math.random() * 800;
            double y = 100 + Math.random() * 400;
            world.registerAgent(agent, x, y, 220, 60);
            AgentState st = world.getState(player);
            if (st != null) st.setEmotion(Emotion.NEUTRAL);
        }

        // 2) 目标驱动：按角色秘密注入（A3-4）
        for (String player : game.players) {
            boolean hasSecret = !game.getSecretFor(player).isBlank();
            director.setGoal(player, hasSecret ? GOAL_HIDE_SECRET : GOAL_FIND_TRUTH);
        }

        // 3) 轨道分配：持秘密 → WEAK（只给摘要不给明文），未持 → MERGED（全文）
        List<AgentState> members = new ArrayList<>();
        Map<String, TrackAssignment> tracks = new LinkedHashMap<>();
        for (String player : game.players) {
            AgentState st = world.getState(player);
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
        ConversationGroup group = cm.createScriptDiscussionGroup(
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
                        cm.runScriptDiscussionRounds(group, discussionMaxRounds, gate);
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
                // P1: 统一进 VOTE 入口（投票计时/quorum 重置/快照/阶段推送；GAP-8 A3-3 自动进投票语义不变）
                enterVotePhase(game);
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
            // P-0802-J: per-game 实例（讨论引擎已按 sessionId 隔离，门控/导演/世界均取本局）
            WorldDirectorService director = discussionDirectors.get(game.sessionId);
            SimulationWorld world = discussionWorlds.get(game.sessionId);
            // 0) 临时应激目标每轮衰减（被质疑→辩解 pri100，N 轮后回落主动机）
            director.decayTemporaryGoals();

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
                        director.pushTemporaryGoal(member, GOAL_DEFEND, 100, priorityDecayRounds);
                    }
                }
            }

            // 3) 人类公开新线索 → 相关 AI 按动机触发发言（动机优先级≥50 的角色=高相关：凶手脱罪/平民隐藏）
            for (Map<String, Object> e : events) {
                if (Boolean.TRUE.equals(e.get("clue"))) {
                    for (String member : game.players) {
                        if (director.getGoalPriority(member) >= 50) {
                            triggers.add(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.HUMAN_CLUE, member));
                        }
                    }
                }
            }

            // 4) 情绪超阈值 → 必发言（ANGRY/SAD/CONFUSED/SURPRISED，对齐 demo emotion_threshold）
            for (String member : game.players) {
                AgentState st = world.getState(member);
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
                        iceBreaker = pickIceBreaker(game, director);
                    }
                }
            }

            // 7) 逐成员 SpeechGate 决策（talkativeness × 动机分 × wait_bias vs silence-floor）
            Map<String, Boolean> speakMap = new LinkedHashMap<>();
            Set<String> skip = new HashSet<>(humanSpokenPlayers);
            for (String member : game.players) {
                if (skip.contains(member)) continue; // 人类已发言 → AI 不代声（人类发言权豁免）
                double talk = game.playerTalkativeness.getOrDefault(member, ScriptSchemaV1.DEFAULT_TALKATIVENESS);
                int pri = director.getGoalPriority(member);
                SpeechGate.GateDecision d = speechGate.decide(member, talk, pri, triggers,
                        member.equals(iceBreaker), humanSpokeThisRound);
                speakMap.put(member, d.speak());
            }
            return new ConversationManager.RoundGateDecision(speakMap, skip);
        };
    }

    /** 冷场破冰者：按动机选择——查明真相（侦探位）优先，其次最高动机优先级、最健谈者兜底。 */
    private String pickIceBreaker(ScriptGame game, WorldDirectorService director) {
        for (String member : game.players) {
            if (GOAL_FIND_TRUTH.equals(director.getGoal(member))) return member;
        }
        String best = null;
        int bestPri = -1;
        double bestTalk = -1;
        for (String member : game.players) {
            int pri = director.getGoalPriority(member);
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

        // P-0810-17（B1）：人类发言立即实时回显（script_speech SSE，会话定向）——不再等讨论线程
        // 下轮排空后才可见；speechEmitted 去重（speaker|message）：讨论线程逐轮回调该发言时跳过，
        // 防同一发言重复推送（对齐狼人杀 werewolf_speech 实时回显形态）。
        if (sse != null && game.speechEmitted.add(player + "|" + message)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("speaker", player);
            payload.put("message", message);
            payload.put("round", game.round);
            payload.put("human", true);
            payload.put("ts", System.currentTimeMillis());
            sse.broadcastScriptSpeech(game.sessionId, payload);
        }

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

    /**
     * P-0805-B（私聊闭环）：剧本杀私聊 —— 玩家与 AI 角色一对一密聊。
     *
     * <p>语义：请求方把消息发给另一名玩家的角色（AI 代管），目标角色以本人 persona + 秘密 +
     * 已持线索生成回应（不泄露他人秘密、不直接认罪）；历史随快照落库。这是剧本杀核心玩法
     * （秘密结盟/套话/传递线索），后端此前只有空壳 PrivateChatService（纯内存无 AI 应答）。
     *
     * <p>秘密守卫：回应经 {@code guardPrivateSecret} 拦截「认罪/自曝」类输出（对齐讨论 demo 的
     * 修订机器人思想）；persona 只注入目标角色自己的秘密（WEAK 语义），不含他人秘密。
     *
     * @return {ok, from, to, message, reply, guarded, history}
     */
    public Map<String, Object> privateSay(String sessionId, String player, String target, String message) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (player == null || player.isBlank() || target == null || target.isBlank())
            return Map.of("error", "缺少发送方或接收方");
        if (message == null || message.isBlank()) return Map.of("error", "消息不能为空");
        if (!game.players.contains(player) || !game.players.contains(target))
            return Map.of("error", "私聊双方必须都在本局中");
        if (player.equals(target)) return Map.of("error", "不能和自己私聊");

        // 记录发送方消息
        String key = privateChatKey(player, target);
        List<Map<String, Object>> chat = game.privateChats.computeIfAbsent(key, k -> new ArrayList<>());
        Map<String, Object> fromMsg = new LinkedHashMap<>();
        fromMsg.put("from", player);
        fromMsg.put("to", target);
        fromMsg.put("content", message);
        fromMsg.put("ts", System.currentTimeMillis());
        chat.add(fromMsg);

        // 目标角色 AI 应答
        String reply = generatePrivateReply(game, player, target, message);
        boolean guarded = false;
        if (guardPrivateSecret(game, target, reply)) {
            reply = "（神色不变）这个话题我不便多说，你若真想知道，得先拿出证据来。";
            guarded = true;
        }
        Map<String, Object> toMsg = new LinkedHashMap<>();
        toMsg.put("from", target);
        toMsg.put("to", player);
        toMsg.put("content", reply);
        toMsg.put("ts", System.currentTimeMillis());
        chat.add(toMsg);

        // C3: 状态变更 → 快照
        saveSnapshot(game);

        // P-0805-C（私聊 SSE）：实时推送该私聊消息（script_private 事件；前端按本人 player 过滤展示）
        if (sse != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", player);
            payload.put("to", target);
            payload.put("message", message);
            payload.put("reply", reply);
            payload.put("guarded", guarded);
            payload.put("ts", System.currentTimeMillis());
            sse.broadcastScriptPrivate(game.sessionId, payload);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("from", player);
        r.put("to", target);
        r.put("message", message);
        r.put("reply", reply);
        r.put("guarded", guarded);
        r.put("history", new ArrayList<>(chat));
        return r;
    }

    /** P-0805-B：私聊历史（会话键字典序 "A|B" 或 "B|A" 均可取）。 */
    public List<Map<String, Object>> getPrivateChatHistory(String sessionId, String playerA, String playerB) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return List.of();
        return game.privateChats.getOrDefault(privateChatKey(playerA, playerB), List.of());
    }

    private static String privateChatKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * P-0805-B：目标角色私聊应答 —— persona（身份+本人秘密+已持线索）+ 会话历史 + 玩家消息 → LLM 生成。
     * LLM 失败/空输出 → 降级占位（不抛异常，保证私聊不中断）。
     */
    private String generatePrivateReply(ScriptGame game, String player, String target, String message) {
        try {
            Persona persona = buildDiscussionPersona(game, target, game.assignments.getOrDefault(target, target));
            String role = game.assignments.getOrDefault(target, target);
            String selfSecret = game.getSecretFor(target);
            StringBuilder prompt = new StringBuilder();
            prompt.append(persona.getPersonaDesc()).append("\n");
            if (!selfSecret.isBlank()) {
                prompt.append("你只知道自己「").append(role).append("」的秘密：").append(selfSecret)
                      .append("。其他人秘密一概不知，绝不能承认自己是凶手，也不要主动供出不利信息。\n");
            }
            List<Map<String, Object>> history = game.privateChats.getOrDefault(privateChatKey(player, target), List.of());
            prompt.append("以下是你们二人的私密对话历史（越靠后越新）：\n");
            for (Map<String, Object> m : history) {
                prompt.append(m.get("from")).append("：").append(m.get("content")).append("\n");
            }
            prompt.append(player).append("：").append(message).append("\n");
            prompt.append("请以").append(role).append("的口吻，用 1-3 句话私下回应。直接输出回应内容，不要任何前缀。");
            String reply = llmClient.callSync(java.util.List.of(
                    new com.roleplay.engine.core.Message(com.roleplay.engine.core.Message.Role.SYSTEM, "system",
                            "你是一个剧本杀角色，正在私下交谈。保持角色身份，谨慎作答，不主动泄露秘密，不直接认罪。"),
                    new com.roleplay.engine.core.Message(com.roleplay.engine.core.Message.Role.USER, player,
                            prompt.toString())), null, 200, 0.7);
            return reply == null || reply.isBlank() ? "嗯，我听着呢。你继续说。" : reply.trim();
        } catch (Exception e) {
            log.warn("Script game {} private reply failed, using fallback: {}", game.sessionId, e.getMessage());
            return "嗯，我听着呢。你继续说。";
        }
    }

    /** P-0805-B：私聊回应守卫 —— 命中「认罪/自曝/自己是凶手」类 → true（改写为遮掩）。 */
    private boolean guardPrivateSecret(ScriptGame game, String target, String reply) {
        if (reply == null) return false;
        String t = reply;
        String role = game.assignments.getOrDefault(target, target);
        boolean isKiller = !game.killerId.isBlank()
                && role.equals(game.roleNamesById.get(game.killerId));
        // 任何角色都不应自曝；凶手角色尤其不能认罪
        if (t.contains("我是凶手") || t.contains("我杀了") || t.contains("凶手是我")
                || t.contains("我下的毒") || t.contains("我捅的")) return true;
        // 无证据佐证时不应主动供出关键秘密（简化：含秘密标题关键词即拦截 —— 保守策略）
        String selfSecret = game.getSecretFor(target);
        if (!selfSecret.isBlank() && t.contains(selfSecret)) return true;
        return false;
    }

    /**
     * P-0802-J: per-game 懒创建讨论引擎 —— 每局独立 ConversationManager + SimulationWorld + WorldDirectorService
     * （替代原 service 实例级共享，多局并发互不覆盖/互不串状态；对齐狼人杀侧 P-0802-I 同款实现）。
     */
    private ConversationManager ensureDiscussionEngine(String sessionId) {
        return discussionConversations.computeIfAbsent(sessionId, k -> {
            SimulationWorld world = new SimulationWorld();
            ConversationManager cm = new ConversationManager();
            cm.init(world, llmClient,
                    name -> world.getAgent(name),
                    () -> world.getWorldNarration());
            discussionWorlds.put(k, world);
            discussionDirectors.put(k, new WorldDirectorService(llmClient));
            return cm;
        });
    }

    /** 测试钩子（同包）：指定对局的讨论引擎实例（断言 per-game 隔离用）。 */
    ConversationManager getDiscussionConversation(String sessionId) {
        return discussionConversations.get(sessionId);
    }

    /** 测试钩子（同包）：指定对局的讨论世界实例（断言 per-game 隔离用）。 */
    SimulationWorld getDiscussionWorld(String sessionId) {
        return discussionWorlds.get(sessionId);
    }

    /** 测试钩子（同包）：指定对局的讨论目标管理器（断言 per-game 隔离用）。 */
    WorldDirectorService getDiscussionDirector(String sessionId) {
        return discussionDirectors.get(sessionId);
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
        // P-0805-A（记忆检索接入讨论）：把该玩家已搜证持有的线索注入 persona ——
        //   「信息收集 ≠ 推理闭环」：AI 必须记得自己发现的证据，才能在讨论中引用/试探/推演，
        //   而不是只按秘密空泛发言。这是剧本杀语义下的"检索记忆"（对齐 MemoryRetrieval 的 additive 注入思想）。
        List<Map<String, Object>> held = game.heldCluesOf(player);
        if (!held.isEmpty()) {
            StringBuilder cluesDesc = new StringBuilder("你当前掌握的证据：");
            for (Map<String, Object> c : held) {
                cluesDesc.append("\n· ").append(c.get("title")).append("（").append(c.get("location")).append("）：")
                        .append(c.get("content"));
            }
            desc.append(cluesDesc).append("。讨论时可引用这些证据质询他人，但注意不要暴露你尚未掌握的线索。");
        } else {
            desc.append("你尚未掌握任何线索证据，主要通过他人发言获取信息。");
        }
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
        // P-0802-J: 目标管理器按对局隔离（未开过讨论的对局 director 为 null → 空串）
        WorldDirectorService director = discussionDirectors.get(sessionId);
        if (director == null) return "";
        String goal = director.getGoal(player);
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
        return touchGame(sessionId);
    }

    /**
     * P-0802-P3（改造方案 §4.2.4）：登记本局 player_id 绑定（playerId → 当前角色名）。
     * init 带 player_id 时由 controller 调用；renamePlayer 同步更新值；saveSnapshot 随快照落库。
     */
    public void registerPlayerBinding(String sessionId, String playerId, String characterName) {
        if (playerId == null || playerId.isBlank()) return;
        playerBindingsBySession.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(playerId, characterName);
    }

    /**
     * P-0802-P3（改造方案 §4.2.4）：局中改名 —— ScriptGame 名字键全量迁移（per-session 锁 synchronized(game)）：
     * players（:140）/assignments（:141）/playerAp（:149）/playerApMax（:151）/playerTalkativeness（:153）/
     * playerIsHuman（:155，虽只写不读仍同步保持一致）/playerKeys（:160）/线索归属（playerClues）/投票
     * （votes voter→suspect 双向）/discussionTranscript（speaker 字段）/discussionContexts（agent 键）。
     * checkPlayerAccess（:1428-1451）无需改——键同步后自然通过。
     * 讨论引擎 world（若本局讨论已建）同步改名；playerBindingsBySession 绑定值同步；状态变更 → 快照落库。
     * pendingHumanEvents 队列内既有事件不追溯（事件已在途，语义可接受，对齐 §4.2.3 同款决策）。
     */
    public void renamePlayer(String sessionId, String oldName, String newName) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return;
        synchronized (game) {
            // players 列表元素替换
            int pi = game.players.indexOf(oldName);
            if (pi >= 0) game.players.set(pi, newName);
            // assignments: 换键
            String role = game.assignments.remove(oldName);
            if (role != null) game.assignments.put(newName, role);
            // 各玩家键 Map 换键
            moveKey(game.playerAp, oldName, newName);
            moveKey(game.playerApMax, oldName, newName);
            moveKey(game.playerTalkativeness, oldName, newName);
            moveKey(game.playerIsHuman, oldName, newName);
            moveKey(game.playerKeys, oldName, newName);
            // 线索归属: 换键
            List<String> clues = game.playerClues.remove(oldName);
            if (clues != null) game.playerClues.put(newName, clues);
            // 投票: voter→suspect 双向替换
            Map<String, String> newVotes = new LinkedHashMap<>();
            game.votes.forEach((voter, suspect) -> newVotes.put(
                    voter.equals(oldName) ? newName : voter,
                    suspect.equals(oldName) ? newName : suspect));
            game.votes.clear();
            game.votes.putAll(newVotes);
            // discussionTranscript: speaker 字段替换
            for (Map<String, String> t : game.discussionTranscript) {
                if (oldName.equals(t.get("speaker"))) t.put("speaker", newName);
            }
            // discussionContexts: agent 键替换
            String ctx = game.discussionContexts.remove(oldName);
            if (ctx != null) game.discussionContexts.put(newName, ctx);
            // 绑定值同步（playerId → 角色名 的值 oldName → newName）
            Map<String, String> bindings = playerBindingsBySession.get(sessionId);
            if (bindings != null) {
                for (Map.Entry<String, String> e : bindings.entrySet()) {
                    if (oldName.equals(e.getValue())) e.setValue(newName);
                }
            }
        }
        // 讨论引擎 world（若本局讨论已建）：agent 名同步（世界为 per-game 隔离实例）
        SimulationWorld w = discussionWorlds.get(sessionId);
        if (w != null) w.renameAgent(oldName, newName);
        // 改名是状态变更 → 快照落库（重连按新名恢复，绑定随快照持久化）
        saveSnapshot(game);
        log.info("Script player renamed in {}: {} → {}", sessionId, oldName, newName);
    }

    /** P-0802-P3：含指定玩家的所有活跃对局 sessionId（局中改名会话收集用）。 */
    public java.util.Set<String> sessionsOfPlayer(String playerName) {
        Set<String> out = new java.util.HashSet<>();
        games.forEach((sid, g) -> {
            if (g.players.contains(playerName)) out.add(sid);
        });
        return out;
    }

    /** P-0802-P3：是否任一活跃对局含指定玩家（局中改名撞名检查用）。 */
    public boolean anyGameHasPlayer(String playerName) {
        return !sessionsOfPlayer(playerName).isEmpty();
    }

    /** 名字键 Map 换键工具（renamePlayer 用）。 */
    private static <V> void moveKey(Map<String, V> map, String oldName, String newName) {
        V v = map.remove(oldName);
        if (v != null) map.put(newName, v);
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
        log.info("Script game {} ended (winner={}, killer={}, correct={}, low_participation={})",
                sessionId, game.winner, game.murderer, game.correctVerdict, game.lowParticipation);
        return game.toMap(null);
    }

    /**
     * P1（任务 2a）：玩家退出对局 —— 角色标记为托管（AI 代管但标记清楚），其已投的票作废、
     * 不计入 quorum 在线数；此后 castVote 拒绝该玩家。
     * （断线重连仍可用 roleKey 恢复视图，但恢复后角色保持托管状态；对局结束则拒绝退出。）
     */
    public Map<String, Object> leaveGame(String sessionId, String player, String playerKey) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (player == null || player.isBlank()) return Map.of("error", "缺少玩家名");
        if (!game.players.contains(player)) return Map.of("error", "玩家不在本局中");
        if (game.phase == Phase.ENDED) return Map.of("error", "对局已结束，无法退出");
        // 身份校验（与玩家级端点一致：有 player_key 必须匹配；无 key 向后兼容按玩家名）
        Map<String, Object> denied = checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return denied;
        boolean newly = game.trustees.add(player);
        if (newly) {
            game.votes.remove(player); // 托管 → 已投的票作废
            // C3: 状态变更 → 快照（重启恢复后托管状态不丢）
            saveSnapshot(game);
            broadcastStatus(game);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("player", player);
        res.put("trusted", true);
        res.put("result", player + " 已退出对局，角色转为托管（AI 代管，投票权作废）");
        res.put("trustees", new ArrayList<>(game.trustees));
        return res;
    }

    /**
     * P1（任务 2b）：ENDED 后重开一局 —— 同剧本主题同玩家重开（复用 sessionId：
     * playerSessions/roomGames/currentSessionId 不变，前端轮询与 SSE 定位自动进入新局）；
     * 新对局生成全新剧本/角色分配/roleKey/票型，托管与降级标记重置。
     * 前端「再来一局（同剧本）」按钮调用；「回到剧本选择」为纯前端导航（goToView('scene')）。
     */
    public Map<String, Object> restartGame(String sessionId) {
        ScriptGame old = games.get(sessionId);
        if (old == null) return Map.of("error", "游戏不存在");
        if (old.phase != Phase.ENDED) {
            return Map.of("error", "仅已结束的对局可重开（当前阶段：" + old.phase.name().toLowerCase() + "）");
        }
        String theme = old.theme == null || old.theme.isBlank() ? "默认主题" : old.theme;
        String mode = old.mode;
        List<String> players = new ArrayList<>(old.players);
        log.info("Script game {} restart: theme='{}', players={}, mode={}", sessionId, theme, players, mode);
        return initGame(sessionId, theme, players, mode);
    }

    /** GAP-4c + C1: 剧本落库（initGame 剧本生成后调用；A4-3 剧本落库来源）—— 按 Schema v1 存取。
     *  P-0810-17（阶段 1）：概略态（scriptSchema 为空）跳过——完整剧本生成（generate_full）后才落库。 */
    private void persistScript(ScriptGame game) {
        if (databaseService == null) return;
        if (game.scriptSchema == null) {
            log.info("Script game {} script persist skipped (outline-only SETUP state, full script pending)",
                    game.sessionId);
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "script");
        content.put("schema_version", ScriptSchemaV1.CURRENT_VERSION);
        content.put("session_id", game.sessionId);
        content.put("metadata", ScriptSchemaV1.metadata(game.scriptSchema));
        content.put("name", game.name);
        content.put("background", game.background);
        content.put("truth", game.truth);
        content.put("killer_id", game.killerId);
        content.put("role_names_by_id", new LinkedHashMap<>(game.roleNamesById));
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
        // P1: 低参与度判定标记落对局结果（复盘/终态展示用；旧结果行无此键）
        content.put("low_participation", game.lowParticipation);
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

    /**
     * P-0810-17（阶段 1）：完整剧本/地图后台生成完成通知（script_ready 事件，会话定向）。
     * 决策点 6：新增结构化就绪事件承载「剧本就绪」（与 script_phase/script_status 并存——
     * script_phase 仍推阶段切换、script_status 仍推全量状态，script_ready 仅通知就绪时刻）。
     *
     * @param mapReady true = 地图也已就绪（full 模式第二阶段）；false = 仅完整剧本就绪
     */
    private void broadcastScriptReady(ScriptGame game, boolean mapReady) {
        if (sse == null || game == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ready", true);
        payload.put("phase", game.phase.name().toLowerCase());
        payload.put("name", game.name);
        payload.put("map_ready", mapReady);
        if (mapReady) {
            payload.put("current_map_id", game.currentMapId == null ? "" : game.currentMapId);
        }
        sse.broadcastScriptReady(game.sessionId, payload);
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
        } else {
            // P-0802-P3: 内存对局也按登记绑定重映射（改完名再重连：绑定值 = init/rename 时解析名，
            // 与当前角色名不一致 → 迁移到新名；renamePlayer 已同步过的对局绑定值已是新名 → 幂等跳过）
            remapByBindings(game, playerBindingsBySession.getOrDefault(sessionId, Map.of()));
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
        // P-0803-K: 对局模式随快照落库（简单对话版重连后前端仍按 chat 隐藏搜证 UI；旧快照无此键 → 恢复 full）
        content.put("mode", game.mode);
        // P1（剧本杀可玩性修复）：主题/LLM 降级标记/投票超时基准/quorum 重投计数/低参与度/托管玩家随快照落库
        // （旧快照无此键 → 恢复默认值：theme 空/llmDegraded false/voteStartedAt 0=超时判定跳过/quorumFailCount 0/lowParticipation false/trustees 空）
        content.put("theme", game.theme);
        content.put("llm_degraded", game.llmDegraded);
        // P-0810-17（阶段 1）：概略剧本随快照落库（重连/重启后概略不丢；旧快照无此键 → 恢复 null 零影响）
        content.put("outline", game.outline);
        content.put("vote_started_at", game.voteStartedAt);
        content.put("quorum_fail_count", game.quorumFailCount);
        content.put("revote_count", game.revoteCount);
        content.put("low_participation", game.lowParticipation);
        content.put("trustees", new ArrayList<>(game.trustees));
        content.put("name", game.name);
        content.put("background", game.background);
        content.put("truth", game.truth);
        content.put("killer_id", game.killerId);
        content.put("role_names_by_id", new LinkedHashMap<>(game.roleNamesById));
        content.put("script_schema", game.scriptSchema);
        content.put("started_at", game.startedAt);
        content.put("phase_started_at", game.phaseStartedAt);
        content.put("phase_timeout_ms", game.phaseTimeoutMs);
        content.put("private_chats", new LinkedHashMap<>(game.privateChats));
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
        // 阶段 2: 对局地图（LLM/BSP 生成，契约 v1；旧快照无此键 → 恢复时置 null）
        content.put("map_data", game.mapData);
        content.put("map_fallback_reasons", new ArrayList<>(game.mapFallbackReasons));
        // P-0803-J: 对局地图尺寸随快照落库（旧快照无此键 → 恢复为 0=未定，下次生成回退配置默认）
        content.put("map_width", game.mapWidth);
        content.put("map_height", game.mapHeight);
        // P-0803-E 方案 B: 搜证足迹落快照（旧快照无此键 → 恢复为空集合，前端零影响）
        content.put("searched_locations", new ArrayList<>(game.searchedLocations));
        // P-0814-H: 热点交互一次性 flag + decor 实例运行时状态随快照落库（旧快照无此键 → 恢复为空，零影响）
        content.put("decor_flags", new ArrayList<>(game.decorFlags));
        content.put("decor_states", new LinkedHashMap<>(game.decorStates));
        // P-0803-K: 多图注册表随快照落库（每图数据 + 溯源 + 足迹；旧快照无此键 → 恢复空注册表仅当前图）
        List<Map<String, Object>> mapsList = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : game.maps.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("map_id", e.getKey());
            entry.put("data", e.getValue());
            entry.put("fallback_reasons", new ArrayList<>(game.mapFallbacks.getOrDefault(e.getKey(), List.of())));
            entry.put("searched", new ArrayList<>(game.searchedByMap.getOrDefault(e.getKey(), java.util.Set.of())));
            mapsList.add(entry);
        }
        content.put("maps", mapsList);
        content.put("current_map_id", game.currentMapId);
        // P-0802-P3: player_id 绑定随快照落库（{playerId → characterName}）——
        // 恢复时按绑定重映射（改完名再重连：旧存档含旧名 → 恢复到新名）；无绑定回退旧名逻辑
        content.put("player_id_bindings", new LinkedHashMap<>(playerBindingsBySession.getOrDefault(game.sessionId, Map.of())));
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
        // P-0803-K: 对局模式快照恢复（旧快照无此键/非 chat → 真剧本杀，零行为变化）
        game.mode = "chat".equals(str(c.get("mode"))) ? "chat" : "full";
        // P1: 主题/LLM 降级/投票超时基准/quorum 重投/低参与度/托管恢复（旧快照无此键 → 默认值，零行为变化）
        game.theme = str(c.get("theme"));
        game.llmDegraded = boolOf(c.get("llm_degraded"), false);
        // P-0810-17（阶段 1）：概略剧本快照恢复（旧快照无此键 → null，零影响）
        Object ol = c.get("outline");
        game.outline = ol instanceof Map ? mapOf(ol) : null;
        game.voteStartedAt = c.get("vote_started_at") instanceof Number n ? n.longValue() : 0L;
        game.quorumFailCount = intOf(c.get("quorum_fail_count"), 0);
        game.revoteCount = intOf(c.get("revote_count"), 0);
        game.lowParticipation = boolOf(c.get("low_participation"), false);
        game.trustees.addAll(strList(c.get("trustees")));
        game.name = str(c.get("name"));
        game.background = str(c.get("background"));
        game.truth = str(c.get("truth"));
        game.killerId = str(c.get("killer_id"));
        game.roleNamesById.putAll(strMap(c.get("role_names_by_id")));
        game.scriptSchema = mapOf(c.get("script_schema"));
        game.startedAt = c.get("started_at") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        game.phaseStartedAt = c.get("phase_started_at") instanceof Number n2 ? n2.longValue() : System.currentTimeMillis();
        game.phaseTimeoutMs = c.get("phase_timeout_ms") instanceof Number n3 ? n3.longValue() : 0L;
        for (Object o : mapList(c.get("private_chats"))) {
            if (o instanceof Map<?, ?> mm) {
                String k = String.valueOf(mm.keySet().iterator().next());
                Object v = mm.get(k);
                if (v instanceof List<?> l) {
                    List<Map<String, Object>> msgs = new ArrayList<>();
                    for (Object x : l) {
                        if (x instanceof Map<?, ?> mx) {
                            Map<String, Object> msg = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> e : mx.entrySet()) {
                                if (e.getKey() != null) msg.put(String.valueOf(e.getKey()), e.getValue());
                            }
                            msgs.add(msg);
                        }
                    }
                    game.privateChats.put(k, msgs);
                }
            }
        }
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
        // 阶段 2: 地图快照恢复（旧快照无 map_data → null，前端提示尚未生成）
        Object md = c.get("map_data");
        if (md instanceof Map<?, ?> mm) {
            game.mapData = mapOf(mm);
        }
        // P-0803-J: 对局地图尺寸快照恢复（旧快照无此键 → 0=未定）
        game.mapWidth = intOf(c.get("map_width"), 0);
        game.mapHeight = intOf(c.get("map_height"), 0);
        Object mfr = c.get("map_fallback_reasons");
        if (mfr instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) game.mapFallbackReasons.add(str(o));
            }
        }
        // P-0803-E 方案 B: 搜证足迹恢复（旧快照无此键 → 空集合，地图绿点不重建但功能不受损）
        if (c.get("searched_locations") instanceof List<?> sl) {
            for (Object o : sl) {
                String s = str(o);
                if (s != null && !s.isBlank()) game.searchedLocations.add(s);
            }
        }
        // P-0814-H: 热点交互一次性 flag + decor 实例运行时状态恢复（旧快照无此键 → 空，零影响）
        if (c.get("decor_flags") instanceof List<?> dl) {
            for (Object o : dl) {
                String s = str(o);
                if (s != null && !s.isBlank()) game.decorFlags.add(s);
            }
        }
        if (c.get("decor_states") instanceof Map<?, ?> ds) {
            for (Map.Entry<?, ?> e : ds.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Map<?, ?> v) {
                    game.decorStates.put(str(e.getKey()), mapOf(v));
                }
            }
        }
        // P-0803-K: 多图注册表恢复（旧快照无 maps 键 → 空注册表；map_data 存在时以当前图为唯一项）
        game.currentMapId = str(c.get("current_map_id"));
        if (c.get("maps") instanceof List<?> ml) {
            for (Object o : ml) {
                if (!(o instanceof Map<?, ?> mm)) continue;
                String mid = str(mm.get("map_id"));
                if (mid.isBlank() || !(mm.get("data") instanceof Map<?, ?> dm)) continue;
                game.maps.put(mid, mapOf(dm));
                List<String> fr = new ArrayList<>();
                if (mm.get("fallback_reasons") instanceof List<?> fl) {
                    for (Object x : fl) if (x != null) fr.add(str(x));
                }
                game.mapFallbacks.put(mid, fr);
                java.util.Set<String> sd = new java.util.LinkedHashSet<>();
                if (mm.get("searched") instanceof List<?> sl2) {
                    for (Object x : sl2) {
                        String s = str(x);
                        if (!s.isBlank()) sd.add(s);
                    }
                }
                game.searchedByMap.put(mid, sd);
            }
        }
        // 兼容旧快照：无注册表但当前图存在 → 以当前图作为注册表唯一项（多图切换前向可用）
        if (game.maps.isEmpty() && game.mapData != null) {
            String mid = MapContract.str(game.mapData.get("map_id"), "");
            if (mid.isBlank()) mid = nextMapId(game);
            game.maps.put(mid, game.mapData);
            if (game.currentMapId.isBlank()) game.currentMapId = mid;
            game.mapFallbacks.put(mid, new ArrayList<>(game.mapFallbackReasons));
        }

        // P-0802-P3: 快照内 player_id 绑定恢复 + 按绑定重映射（旧存档含旧名 → 恢复到新名）
        Map<String, String> savedBindings = strMap(c.get("player_id_bindings"));
        playerBindingsBySession.put(sessionId, new LinkedHashMap<>(savedBindings));
        remapByBindings(game, savedBindings);

        games.put(sessionId, game);
        log.info("Script game {} restored from snapshot (phase={}, players={}, round={})",
                sessionId, game.phase.name(), game.players.size(), game.round);
        return game;
    }

    /**
     * P-0802-P3: 按 player_id 绑定重映射 —— 若绑定存在且角色名与快照内玩家名不一致，
     * 按绑定把快照内旧名迁移到绑定解析出的当前名（改完名再重连场景）。
     * identityService 为 null（直接构造的旧测试）→ 跳过，回退旧名逻辑（零行为变化）。
     *
     * @param bindings 绑定表 {playerId → characterName}（快照或内存登记）；
     *                 重映射目标 = identityService.resolveCharacterName(playerId)（当前角色名），
     *                 解析不到则保持快照名（无绑定回退旧名逻辑）。
     */
    private void remapByBindings(ScriptGame game, Map<String, String> bindings) {
        if (game == null || bindings == null || bindings.isEmpty()) return;
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            String playerId = e.getKey();
            String savedName = e.getValue();
            if (savedName == null || savedName.isBlank()) continue;
            // 玩家可能已在改名端点同步过（内存快照已用新名）→ 命中新名无需重映射
            if (game.players.contains(savedName)) {
                String currentName = (identityService != null)
                        ? identityService.resolveCharacterName(playerId).orElse(savedName)
                        : savedName;
                if (!currentName.equals(savedName)) {
                    migratePlayerNames(game, savedName, currentName);
                    log.info("Script game {} binding remap: {} → {} (player_id {})",
                            game.sessionId, savedName, currentName, playerId);
                }
            }
        }
    }

    /** 快照恢复路径的名字键迁移（不依赖 games map 已在位；renamePlayer 同款迁移逻辑复用）。 */
    private void migratePlayerNames(ScriptGame game, String oldName, String newName) {
        int pi = game.players.indexOf(oldName);
        if (pi >= 0) game.players.set(pi, newName);
        String role = game.assignments.remove(oldName);
        if (role != null) game.assignments.put(newName, role);
        moveKey(game.playerAp, oldName, newName);
        moveKey(game.playerApMax, oldName, newName);
        moveKey(game.playerTalkativeness, oldName, newName);
        moveKey(game.playerIsHuman, oldName, newName);
        moveKey(game.playerKeys, oldName, newName);
        List<String> clues = game.playerClues.remove(oldName);
        if (clues != null) game.playerClues.put(newName, clues);
        Map<String, String> newVotes = new LinkedHashMap<>();
        game.votes.forEach((voter, suspect) -> newVotes.put(
                voter.equals(oldName) ? newName : voter,
                suspect.equals(oldName) ? newName : suspect));
        game.votes.clear();
        game.votes.putAll(newVotes);
        for (Map<String, String> t : game.discussionTranscript) {
            if (oldName.equals(t.get("speaker"))) t.put("speaker", newName);
        }
        String ctx = game.discussionContexts.remove(oldName);
        if (ctx != null) game.discussionContexts.put(newName, ctx);
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
