package com.roleplay.engine.controller;

import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptGameService;
import com.roleplay.engine.simulation.SimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Script murder mystery game endpoints.
 */
@RestController
@RequestMapping("/api/script")
public class ScriptController {

    private static final Logger log = LoggerFactory.getLogger(ScriptController.class);

    private final ScriptGameService scriptGameService;
    private final RouterService router;
    private final SimulationService simulationService;
    /** P-0802-P2：玩家身份解析器 —— init 带 player_id 时按解析出的当前角色名登记（角色库改名兜底）。 */
    private final PlayerIdentityService identityService;
    /** P-0802-P2：玩家身份登记 —— 解析名 → player_id（init 带 player_id 时写入；Phase 3 局中改名/重连恢复用）。 */
    private final Map<String, String> playerIdBindings = new ConcurrentHashMap<>();
    private final Map<String, String> playerSessions = new ConcurrentHashMap<>();
    /** C3: 房间码 ↔ 对局 sessionId 映射（init 可选 room_code 登记；resume 可用房间码定位重连入口）。 */
    private final Map<String, String> roomGames = new ConcurrentHashMap<>();
    private String currentSessionId = "";

    /** C4: DM 面板 key（越权保护）—— roleplay.game.dm.key；空（默认）= 放开（与审批门 D7 同开放模式），
     *  非空时 DM 端点（dm/status、advance）要求请求头 X-DM-Key 匹配，否则 403。 */
    @Value("${roleplay.game.dm.key:}")
    private String dmKey = "";

    /**
     * P-0802-P3（改造方案 §4.2.4 rename 接线）：playerSessions 键 + playerIdBindings 键 oldName→newName 同步。
     * 局中改名端点同步时由 PlayerIdentityService 编排调用。
     */
    public void renamePlayerSessionKey(String oldName, String newName) {
        String sid = playerSessions.remove(oldName);
        if (sid != null) playerSessions.put(newName, sid);
        String pid = playerIdBindings.remove(oldName);
        if (pid != null) playerIdBindings.put(newName, pid);
    }

    public ScriptController(ScriptGameService scriptGameService, RouterService router,
                            SimulationService simulationService) {
        this(scriptGameService, router, simulationService, null);
    }

    /** P-0802-P2：四参构造（Spring 注入路径，@Autowired 显式指定）。identityService 可为 null（旧测试直构路径，init 内 null 守卫）。 */
    @Autowired
    public ScriptController(ScriptGameService scriptGameService, RouterService router,
                            SimulationService simulationService, PlayerIdentityService playerIdentityService) {
        this.scriptGameService = scriptGameService;
        this.router = router;
        this.simulationService = simulationService;
        this.identityService = playerIdentityService;
    }

    /** P-0802-P2：测试钩子 —— player_id 登记映射（解析名 → player_id）。 */
    Map<String, String> playerIdBindings() {
        return playerIdBindings;
    }

    /** P-0802-P3：测试钩子 —— 玩家 → 对局映射（局中改名后断言键已换名）。 */
    Map<String, String> playerSessions() {
        return playerSessions;
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> init(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> players = (List<String>) body.getOrDefault("players", List.of());
        String theme = (String) body.getOrDefault("theme", "默认主题");
        // P-0803-K（剧本杀双版本）：对局模式 —— 缺省 "full"=真剧本杀（搜证+地图）/ "chat"=简单对话版（无取证，直接多人对话）
        String mode = (String) body.getOrDefault("mode", "full");
        // 单人前端对局：仅扮演者需实际表态；其余点亮角色由讨论引擎驱动为 NPC。
        // 未传保持旧行为（全员真人），不影响联机/既有调用方。
        String humanPlayer = String.valueOf(body.getOrDefault("human_player", "")).trim();
        // P-0810-17（阶段 1，两阶段生成）：outline_only 缺省 true —— init 只生成概略（快 <10s，
        // 不再同步双 LLM 生成完整剧本+地图）；完整剧本+地图由 POST /api/script/generate_full 后台异步补齐。
        // outline_only=false 保留既有同步完整生成路径（向后兼容旧调用方/测试）。
        boolean outlineOnly = body.get("outline_only") == null
                || Boolean.parseBoolean(String.valueOf(body.get("outline_only")));
        // C3: 可选房间码绑定（/api/rooms 6 位码 → 对局，重连入口；不传则跳过）
        String roomCode = (String) body.getOrDefault("room_code", "");
        // P-0802-P2：可选 player_id —— 按解析出的当前角色名登记绑定（角色库改名后即使 players 仍传旧名，
        // 也登记到新名；无 player_id / 未绑定 / identityService 缺失 → 不登记，零行为变化）
        String playerId = body.get("player_id") != null ? String.valueOf(body.get("player_id")) : "";
        if (!playerId.isBlank() && identityService != null) {
            String resolved = identityService.resolveCharacterName(playerId).orElse(null);
            if (resolved != null) {
                playerIdBindings.put(resolved, playerId);
            }
        }
        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        currentSessionId = sessionId;
        players.forEach(p -> playerSessions.put(p, sessionId));
        if (roomCode != null && !roomCode.isBlank()) roomGames.put(roomCode.trim().toUpperCase(), sessionId);
        // P-0802-P3：登记绑定到服务层（initGame 的初始快照即携带，改名后重连按绑定重映射恢复新名）
        if (!playerId.isBlank() && identityService != null) {
            String resolved = identityService.resolveCharacterName(playerId).orElse(null);
            if (resolved != null) {
                scriptGameService.registerPlayerBinding(sessionId, playerId, resolved);
            }
        }
        Map<String, Object> state = scriptGameService.initGame(sessionId, theme, players, mode, outlineOnly);
        if (!humanPlayer.isBlank()) {
            scriptGameService.designateHumanPlayer(sessionId, humanPlayer);
            ScriptGameService.ScriptGame initialized = scriptGameService.getGame(sessionId);
            if (initialized != null) state = initialized.toMap(humanPlayer);
        }
        // D5: 将剧本局注册到 RouterService，secrets 随对话注入对应角色上下文
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game != null) {
            router.setScriptGame(game);
            log.info("Script game {} registered to router, {} secrets issued", sessionId, game.getSecrets().size());
        }
        // C3: 响应附加 room_code（若绑定）与 session_id（重连定位用）
        Map<String, Object> resp = new LinkedHashMap<>(state);
        resp.put("session_id", sessionId);
        if (roomCode != null && !roomCode.isBlank()) resp.put("room_code", roomCode.trim().toUpperCase());
        return ResponseEntity.ok(resp);
    }

    /**
     * P-0810-17（阶段 1，新端点）：完整剧本 + 地图后台异步生成（两阶段生成第二阶段）。
     * body: session_id（可选，缺省当前对局）。返回 {ok, generating, session_id, phase:"setup", message}；
     * 生成完成推送 script_ready（map_ready 分两段：完整剧本就绪 → 地图就绪）+ script_phase + script_status。
     * 仅 SETUP（概略已生成）可调；重复调用/非概略态返回 error（含 phase）。
     */
    @PostMapping("/generate_full")
    public ResponseEntity<Map<String, Object>> generateFull(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        }
        return ResponseEntity.ok(scriptGameService.generateFull(sessionId));
    }

    /**
     * 阶段 2: 生成/获取对局地图（LLM 统一路径 → 校验 → BSP 降级，docs/地图JSON契约-v1.md）。
     * body: session_id（可选，缺省用当前对局）/ theme（可选主题覆盖）/ seed（BSP 降级种子）/
     *       width·height（可选显式尺寸，P-0803-J：≤0/缺失=默认 24×16；超 LLM token 预算 40×24 直接 BSP）/
     *       regenerate（强制重生成）。
     * 返回 {map, generator, validation{ok,errors,warnings}, fallback[], cached}。
     */
    @PostMapping("/map")
    public ResponseEntity<Map<String, Object>> map(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        }
        String theme = body.getOrDefault("theme", "");
        long seed = 0;
        try {
            seed = Long.parseLong(body.getOrDefault("seed", "0"));
        } catch (NumberFormatException ignored) {
            // 非法 seed → 用默认
        }
        // P-0803-J（地图容量扩展）：显式尺寸可选透传（非法/负数按 0=不指定处理 → 默认 24×16）
        int width = 0;
        int height = 0;
        try {
            width = Integer.parseInt(body.getOrDefault("width", "0"));
        } catch (NumberFormatException ignored) {
            // 非法 width → 用默认
        }
        try {
            height = Integer.parseInt(body.getOrDefault("height", "0"));
        } catch (NumberFormatException ignored) {
            // 非法 height → 用默认
        }
        boolean regenerate = Boolean.parseBoolean(body.getOrDefault("regenerate", "false"));
        // P-0803-K（多地图切换）：可选注册表键（缺省自动分配 map_<n>；显式指定可预生成命名地图）
        String mapId = body.getOrDefault("map_id", "");
        return ResponseEntity.ok(scriptGameService.generateMap(sessionId, theme, seed, regenerate, width, height, mapId));
    }

    /**
     * P-0803-K（剧本杀模式多地图切换）：door zone 触发切图。
     * body: session_id（可选，缺省当前对局）/ player（必填，触发者）/ player_key（可选，身份校验）/
     *       door_zone_id（可选——给定必须为当前地图 type=door 的 zone；缺省走显式 target_map_id 直切）/
     *       x·y（可选——触发者瓦片坐标，靠近校验；缺省跳过）/ target_map_id（可选——目标地图 id，
     *       缺省取 door zone 的 target/to/target_map_id 字段；目标未注册自动生成）。
     * 返回 {switched, from_map_id, to_map_id, map, generator, fallback, session_id}；非法 door 目标容错 error。
     */
    @PostMapping("/map/switch")
    public ResponseEntity<Map<String, Object>> mapSwitch(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        }
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        String doorZoneId = body.getOrDefault("door_zone_id", "");
        String targetMapId = body.getOrDefault("target_map_id", "");
        Integer px = parseIntOrNull(body.get("x"));
        Integer py = parseIntOrNull(body.get("y"));
        return ResponseEntity.ok(scriptGameService.switchMap(sessionId, player, playerKey, doorZoneId, px, py, targetMapId));
    }

    /**
     * P-0803-K：在地图上放置 door 型 zone（多图连通的布门接口；生成的地图默认无 door，
     * LLM prompt 已预留 door 可选输出）。
     * body: session_id / map_id（可选，缺省当前图）/ zone_id（必填，唯一）/ name（可选）/
     *       x·y（可选，瓦片坐标；缺失或不可通行自动吸附最近可通行格）/ radius（可选，默认 1）/
     *       target_map_id（必填，该门通往的地图）。
     * 返回 {ok, map_id, zone, target_map_id}。
     */
    @PostMapping("/map/door")
    public ResponseEntity<Map<String, Object>> mapDoor(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        }
        String mapId = body.getOrDefault("map_id", "");
        String zoneId = body.getOrDefault("zone_id", "");
        String name = body.getOrDefault("name", "");
        String targetMapId = body.getOrDefault("target_map_id", "");
        Integer xi = parseIntOrNull(body.get("x"));
        Integer yi = parseIntOrNull(body.get("y"));
        Integer ri = parseIntOrNull(body.get("radius"));
        int x = xi == null ? -1 : xi;
        int y = yi == null ? -1 : yi;
        int radius = ri == null ? 1 : ri;
        return ResponseEntity.ok(scriptGameService.addDoorZone(sessionId, mapId, zoneId, name, x, y, radius, targetMapId));
    }

    /** 宽容整数解析（非法/缺失 → null）。 */
    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * C3: 断线重连恢复 —— body: game_id 或 room_code + player_key。
     * 内存对局存在直接返回该玩家视图；不存在则从持久化快照重建（重启后可用）；ENDED 返回终态。
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(@RequestBody Map<String, String> body) {
        String gameId = body.getOrDefault("game_id", "");
        String roomCode = body.getOrDefault("room_code", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = "";
        if (gameId != null && !gameId.isBlank()) {
            sessionId = gameId;
        } else if (roomCode != null && !roomCode.isBlank()) {
            sessionId = roomGames.getOrDefault(roomCode.trim().toUpperCase(), "");
        } else if (playerKey != null && !playerKey.isBlank()) {
            sessionId = scriptGameService.findSessionByPlayerKey(playerKey);
        }
        if (sessionId.isBlank()) {
            return ResponseEntity.ok(Map.of("error", "缺少对局标识（game_id / room_code / player_key 至少其一）"));
        }
        // P-0803-H2：恢复成功后将对局设为当前对局（status/SSE/keys 按 currentSessionId 定位；
        // 否则重启后 status 恒 idle，前端 scriptState 无法恢复）
        this.currentSessionId = sessionId;
        return ResponseEntity.ok(scriptGameService.resumeGame(sessionId, playerKey == null ? "" : playerKey));
    }

    /** C3: DM 面板分发 roleKey 用（全员令牌一览；仅 DM 侧调用，配合重连/顶号 UI）。
     *  P-0810-17（B3）：可选 player_key —— 有 key 时优先按 key 反查对局（防多局并发
     *  currentSessionId 回退错位，role_key 串局 403）；无 key 保持 session_id/current 解析。 */
    @GetMapping("/keys")
    public ResponseEntity<Map<String, Object>> getKeys(@RequestParam(defaultValue = "") String session_id,
                                                       @RequestParam(defaultValue = "") String player_key,
                                                       @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        if (!dmKeyOk(dmKeyHeader)) {
            return ResponseEntity.status(403).body(Map.of("error", "DM 权限校验失败：请配置并提供 X-DM-Key"));
        }
        String sid = session_id.isBlank() ? currentSessionId : session_id;
        // P-0810-17（B3）：优先 player_key 反查（该 key 属于哪个对局以服务层为准，key 与对局一一对应）
        if (player_key != null && !player_key.isBlank()) {
            String sidByKey = scriptGameService.findSessionByPlayerKey(player_key);
            if (!sidByKey.isBlank()) sid = sidByKey;
        }
        if (sid.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        Map<String, String> keys = scriptGameService.getPlayerKeys(sid);
        return ResponseEntity.ok(Map.of("session_id", sid, "player_keys", keys));
    }

    /** 仅供既有直接控制器测试调用；与 HTTP 路由同样按空 DM key 拒绝。 */
    @Deprecated
    public ResponseEntity<Map<String, Object>> getKeys(String sessionId, String playerKey) {
        return getKeys(sessionId, playerKey, "");
    }

    /**
     * C4: DM 全量视图（对齐 Chronos state:dm_dashboard）—— 所有玩家角色/AP/线索/投票/秘密/roleKey
     * + 对局元数据（真相/killer_id/判定/审批状态），供主持人面板使用。
     * 越权保护：roleplay.game.dm.key 配置非空时要求 X-DM-Key 请求头匹配（默认放开，与审批门同模式）。
     */
    @GetMapping("/dm/status")
    public ResponseEntity<Map<String, Object>> dmStatus(@RequestParam(defaultValue = "") String session_id,
                                                        @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        if (!dmKeyOk(dmKeyHeader)) {
            return ResponseEntity.status(403).body(Map.of("error", "DM 权限校验失败：缺少或错误的 X-DM-Key"));
        }
        String sid = session_id.isBlank() ? currentSessionId : session_id;
        if (sid.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        return ResponseEntity.ok(scriptGameService.dmStatus(sid));
    }

    /**
     * C4: DM 手动推进阶段（对齐 Chronos dm:advance）—— 状态机逐级推进：
     * INVESTIGATION→DISCUSSION→VOTE→REVEAL→ENDED；VOTE 步经 D7 审批门（阻塞等待批准）。
     * 供 C3 已知限制“恢复后 DM 手动推进”与主持人面板使用。
     * 越权保护同上（X-DM-Key）。
     */
    @PostMapping("/advance")
    public ResponseEntity<Map<String, Object>> advance(@RequestBody Map<String, String> body,
                                                       @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        if (!dmKeyOk(dmKeyHeader)) {
            return ResponseEntity.status(403).body(Map.of("error", "DM 权限校验失败：缺少或错误的 X-DM-Key"));
        }
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        return ResponseEntity.ok(scriptGameService.advancePhase(sessionId));
    }

    /** C4: DM key 校验 —— 安全默认拒绝；未配置口令时不得开放真相或全员令牌。 */
    private boolean dmKeyOk(String providedKey) {
        return dmKey != null && !dmKey.isBlank() && providedKey != null && dmKey.equals(providedKey);
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String location = body.getOrDefault("location", "");
        String playerKey = body.getOrDefault("player_key", "");
        // P-0819-Q：显式 session_id 优先，避免同名玩家/多局并发时搜证落到旧会话。
        String sessionId = body.getOrDefault("session_id", "");
        if (sessionId == null || sessionId.isBlank()) sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.search(sessionId, player, location));
    }

    /**
     * P-0814-H（热点/搜证点交互系统）：对地图交互点执行统一动作键交互。
     * body: session_id（可选，缺省按 player 回退 currentSessionId）/ player / player_key（可选，C3 身份认证）/
     *       map_id（可选，缺省当前图）/ decor_id（可选 —— 与 tile 至少其一，显式目标）/
     *       tile（可选，"x,y" 目标格坐标 —— 无 decor_id 时按优先级链解析 decor 实体 &gt; tileProps.action &gt; 环境占位）/
     *       x·y（可选，玩家瓦片坐标 —— 靠近校验 Chebyshev 半径，缺省跳过）。
     * 响应：动作执行结果（dialog 文本 / menu 数据 / items 线索授予 / flags / sounds·anims 占位 / state 实例状态 /
     *       processed 已处理 / result 汇总 / error 失败原因）。
     */
    @PostMapping("/interact")
    public ResponseEntity<Map<String, Object>> interact(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        String mapId = body.getOrDefault("map_id", "");
        String decorId = body.getOrDefault("decor_id", "");
        String tile = body.getOrDefault("tile", "");
        // 玩家瓦片坐标（可选；非法/缺失 → null = 跳过靠近校验）
        Integer px = parseIntOrNull(body.get("x"));
        Integer py = parseIntOrNull(body.get("y"));
        String sessionId = body.getOrDefault("session_id", playerSessions.getOrDefault(player, currentSessionId));
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.interact(sessionId, player, playerKey, mapId, decorId, tile, px, py));
    }

    /**
     * 批次 D: 人类发言入口（人机混合讨论）—— 人类发言权豁免（不过门控直接注入讨论流）；
     * 消息中 @角色名 → 目标 AI 强制发言；clue=true → 公开新线索，相关 AI 按动机触发。
     */
    @PostMapping("/discussion_say")
    public ResponseEntity<Map<String, Object>> discussionSay(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String message = body.getOrDefault("message", "");
        String playerKey = body.getOrDefault("player_key", "");
        boolean clue = Boolean.parseBoolean(body.getOrDefault("clue", "false"));
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.discussionSay(sessionId, player, message, clue));
    }

    /** C2: 线索转交（body: player, target_player, clue_id；C3: +player_key）—— 转交后 ownership 变更，接收方 status 可见。 */
    @PostMapping("/transfer_clue")
    public ResponseEntity<Map<String, Object>> transferClue(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String targetPlayer = body.getOrDefault("target_player", "");
        String clueId = body.getOrDefault("clue_id", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.transferClue(sessionId, player, targetPlayer, clueId));
    }

    /**
     * P-0805-B（私聊闭环）：剧本杀私聊 —— 玩家与 AI 角色一对一密聊（秘密结盟/套话）。
     * body: { player, target, message, player_key? } → { ok, from, to, message, reply, guarded, history }
     */
    @PostMapping("/private")
    public ResponseEntity<Map<String, Object>> privateSay(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String target = body.getOrDefault("target", "");
        String message = body.getOrDefault("message", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.privateSay(sessionId, player, target, message));
    }

    /** P-0805-B：私聊历史 —— { player, other, player_key? } → 该二人私聊记录。 */
    @GetMapping("/private/history")
    public ResponseEntity<Map<String, Object>> privateHistory(@RequestParam String player,
                                                               @RequestParam String other,
                                                               @RequestParam(required = false) String player_key) {
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "history", scriptGameService.getPrivateChatHistory(sessionId, player, other)));
    }

    @PostMapping("/start_discussion")
    public ResponseEntity<Map<String, Object>> startDiscussion(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = resolveSessionId(player, playerKey, body.getOrDefault("session_id", ""));
        if (playerKey.isBlank()) return ResponseEntity.status(403).body(Map.of("error", "缺少玩家 roleKey"));
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        ScriptGameService.ScriptGame beforeGame = scriptGameService.getGame(sessionId);
        if (beforeGame != null) {
            Map<String, Object> playerView = beforeGame.toMap(player);
            Object locations = playerView.get("locations");
            Object searched = playerView.get("searched_locations");
            if (locations instanceof List<?> locs && !locs.isEmpty()
                    && searched instanceof List<?> searchedLocs && searchedLocs.isEmpty()) {
                return ResponseEntity.ok(Map.of("phase", "investigation", "transitioned", false,
                        "error", "请至少完成一次搜证后再进入讨论"));
            }
        }
        boolean transitioned = scriptGameService.startDiscussion(sessionId);
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game == null) {
            return ResponseEntity.ok(Map.of("phase", "not_found", "simulation_started", false));
        }

        boolean simulationStarted = game.isSimulationStarted();
        if (transitioned && !simulationStarted) {
            simulationService.initWithPersonas(scriptGameService.buildSimulationPersonas(sessionId), "cafe");
            simulationService.setSecretAgents(scriptGameService.getSecretPlayers(sessionId));
            scriptGameService.buildDiscussionGoals(sessionId)
                .forEach(simulationService::setTrackGoal);
            simulationService.start();
            scriptGameService.markSimulationStarted(sessionId);
            simulationStarted = true;
            log.info("Script game {} bridged into 2D simulation: {} players, secretAgents={}",
                sessionId, game.getPlayers().size(), scriptGameService.getSecretPlayers(sessionId));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", game.getPhase().name().toLowerCase());
        result.put("transitioned", transitioned);
        if (!transitioned) {
            result.put("error", "当前阶段不是搜证阶段，无法进入讨论");
            return ResponseEntity.ok(result);
        }
        result.put("simulation_started", simulationStarted);
        result.put("simulation_url", "/simulation.html");
        result.put("simulation_state_url", "/api/simulation/state");
        result.put("track_state_url", "/api/simulation/track/state");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start_voting")
    public ResponseEntity<Map<String, Object>> startVoting(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = resolveSessionId(player, playerKey, body.getOrDefault("session_id", ""));
        if (playerKey.isBlank()) return ResponseEntity.status(403).body(Map.of("error", "缺少玩家 roleKey"));
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game == null) return ResponseEntity.ok(Map.of("phase", "not_found", "error", "游戏不存在"));
        String before = game.getPhase().name().toLowerCase();
        scriptGameService.startVoting(sessionId);
        String after = game.getPhase().name().toLowerCase();
        if (!"vote".equals(after)) {
            return ResponseEntity.ok(Map.of("phase", after, "transitioned", false,
                    "error", "当前阶段不是讨论阶段，无法进入投票"));
        }
        return ResponseEntity.ok(Map.of("phase", after, "transitioned", !"vote".equals(before)));
    }

    @PostMapping("/vote")
    public ResponseEntity<Map<String, Object>> vote(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String suspect = body.getOrDefault("suspect", "");
        String playerKey = body.getOrDefault("player_key", "");
        // P-0816-G（API-11 弃票扩展，决策 U8）：abstain=true 时弃票（suspect 可空）→ 独立 abstainedVoters 集合；
        // 缺省/false 走既有 3 参路径逐字节不变（向后兼容：现有投票调用不变）
        boolean abstain = Boolean.parseBoolean(body.getOrDefault("abstain", "false"));
        String sessionId = resolveSessionId(player, playerKey, body.getOrDefault("session_id", ""));
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        String result = abstain
                ? scriptGameService.castVote(sessionId, player, "", true)
                : scriptGameService.castVote(sessionId, player, suspect);
        return ResponseEntity.ok(Map.of("result", result));
    }

    /**
     * P-0816-G（UI 重设计阶段一，§3.4）+ P-0816-P1（403 修复）：sessionId 解析 ——
     * 优先级：① 显式 session_id（query/body，P-0816-P1 新增——原实现忽略显式 session_id，
     * 重启后旧对局仅剩快照时 player_key 反查失效、回退 currentSessionId 导致 403）；
     * ② player_key 反查对局（P-0810-17 B3：key 与对局一一对应，反查是唯一可靠归属，防多局并发 role_key 错位）；
     * ③ playerSessions/currentSessionId（旧行为兜底）。新增端点（actions/action/vote-status/goal）统一走此解析。
     */
    private String resolveSessionId(String player, String playerKey, String explicitSessionId) {
        if (explicitSessionId != null && !explicitSessionId.isBlank()) return explicitSessionId;
        if (playerKey != null && !playerKey.isBlank()) {
            String sidByKey = scriptGameService.findSessionByPlayerKey(playerKey);
            if (!sidByKey.isBlank()) return sidByKey;
        }
        return playerSessions.getOrDefault(player, currentSessionId);
    }

    /** P-0816-P1：鉴权前确保对局在内存（重启后旧对局按快照恢复，否则 checkPlayerAccess 403「游戏不存在」）。 */
    private void ensureGameLoaded(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        scriptGameService.ensureGameLoaded(sessionId);
    }

    /**
     * P-0816-G（UI 重设计阶段一 API-1，§3.2）：行动建议集 —— 搜证阶段主区「行动选择条」数据源
     * （去问人 / 去搜地点 / 出示线索，服务端权威生成，阈值配置化 roleplay.game.script.action.*，决策 U6）。
     * query: session_id? / player / player_key? → {ok, phase, actions[], ap, ap_max}
     */
    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> actions(@RequestParam(defaultValue = "") String session_id,
                                                       @RequestParam(defaultValue = "") String player,
                                                       @RequestParam(defaultValue = "") String player_key) {
        String sessionId = resolveSessionId(player, player_key, session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.listActions(sessionId, player));
    }

    /**
     * P-0816-G（UI 重设计阶段一 API-2，§3.2）：行动执行统一入口 —— 按 action_id 分派并内部委托既有
     * search / privateSay / discussionSay（不重复造执行逻辑，决策 U7/D3）；
     * research 已搜地点回看不扣 AP（响应含 {replayed:true, clues:[...]}），扣 AP 仅首次搜证。
     * P-0816-P1：支持显式 session_id（query 或 body，优先于 player_key 反查）——
     * 重启后旧对局快照恢复定位（修复 403）。
     * body: {player, action_id, player_key?, session_id?}；query: ?session_id=
     */
    @PostMapping("/action")
    public ResponseEntity<Map<String, Object>> action(@RequestBody(required = false) Map<String, String> body,
                                                      @RequestParam(defaultValue = "") String session_id) {
        Map<String, String> b = body == null ? Map.of() : body;
        String player = b.getOrDefault("player", "");
        String actionId = b.getOrDefault("action_id", "");
        String playerKey = b.getOrDefault("player_key", "");
        String bodySid = b.getOrDefault("session_id", "");
        String sessionId = resolveSessionId(player, playerKey, session_id.isBlank() ? bodySid : session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.executeAction(sessionId, player, actionId));
    }

    /**
     * P-0816-G（UI 重设计阶段一 API-10，§3.2）：投票进度聚合 —— 投票页「已投票 x/y」+ 右栏统计条
     * （只出聚合不出投票人，决策 C13）；非 VOTE 阶段返回 {phase}（前端隐藏统计区）。
     * query: session_id? / player / player_key? → {ok, total, voted, abstained, pending[], candidates[], trustees[]}
     */
    @GetMapping("/vote/status")
    public ResponseEntity<Map<String, Object>> voteStatus(@RequestParam(defaultValue = "") String session_id,
                                                          @RequestParam(defaultValue = "") String player,
                                                          @RequestParam(defaultValue = "") String player_key) {
        String sessionId = resolveSessionId(player, player_key, session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.voteStatus(sessionId));
    }

    /**
     * P-0816-G（UI 重设计阶段一 API-13，§3.2）：目标 HUD 规则模板 —— 顶栏 🎯 目标徽章数据源
     * （按 phase 返回 {title, progress, detail}，搜证 x/y、质询计数、投票 x/y；零新状态，决策 U4/U14）。
     * query: session_id? / player / player_key? → {ok, phase, goal}
     */
    @GetMapping("/goal")
    public ResponseEntity<Map<String, Object>> goal(@RequestParam(defaultValue = "") String session_id,
                                                    @RequestParam(defaultValue = "") String player,
                                                    @RequestParam(defaultValue = "") String player_key) {
        String sessionId = resolveSessionId(player, player_key, session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.getGoal(sessionId));
    }

    /**
     * P-0816-R（UI 重设计阶段二 API-3，§3.2，决策 U1）：心锁列表 —— 左栏角色 🔒 标记数据源。
     * 数据源：二期前规则推导过渡（线索 content/title 提及角色名 → 该角色 1 锁）；终态 LLM 标注
     * clues[].unlock_role 预留字段（宽容解析缺省走推导）。roleLocks 状态随快照落库、不加表。
     * query: session_id? / player / player_key? → {ok, locks:[{role, lock_count, unlock_clue_ids, unlocked}]}
     */
    @GetMapping("/locks")
    public ResponseEntity<Map<String, Object>> locks(@RequestParam(defaultValue = "") String session_id,
                                                     @RequestParam(defaultValue = "") String player,
                                                     @RequestParam(defaultValue = "") String player_key) {
        String sessionId = resolveSessionId(player, player_key, session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.getLocks(sessionId));
    }

    /**
     * P-0816-R（UI 重设计阶段二 API-4，§3.2，决策 U1）：出示证据破锁。
     * 校验链：阶段（investigation/discussion）→ 玩家持有 clue_id → 线索是否该角色解锁线索 → 破锁
     * （lock_count 归零 + unlockedLocks 记录随快照；成功后 SSE script_locks 广播）；幂等（重复出示提示已解锁）。
     * body: {player, target_role, clue_id, player_key?, session_id?}
     */
    @PostMapping("/unlock")
    public ResponseEntity<Map<String, Object>> unlock(@RequestBody(required = false) Map<String, String> body,
                                                      @RequestParam(defaultValue = "") String session_id) {
        Map<String, String> b = body == null ? Map.of() : body;
        String player = b.getOrDefault("player", "");
        String targetRole = b.getOrDefault("target_role", "");
        String clueId = b.getOrDefault("clue_id", "");
        String playerKey = b.getOrDefault("player_key", "");
        String bodySid = b.getOrDefault("session_id", "");
        String sessionId = resolveSessionId(player, playerKey, session_id.isBlank() ? bodySid : session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.unlockLock(sessionId, player, targetRole, clueId));
    }

    /**
     * P-0816-R（UI 重设计阶段二 API-5，§3.2）：质询发言。
     * pressed 标记写 discussionTranscript（并发安全容器，随快照落库）；同一发言可被多人质询但幂等；
     * 讨论引擎运行中向被质询角色注入「辩解」临时目标；成功后 SSE script_press 广播。
     * body: {player, target, message_id?, player_key?, session_id?}
     */
    @PostMapping("/press")
    public ResponseEntity<Map<String, Object>> press(@RequestBody(required = false) Map<String, String> body,
                                                     @RequestParam(defaultValue = "") String session_id) {
        Map<String, String> b = body == null ? Map.of() : body;
        String player = b.getOrDefault("player", "");
        String target = b.getOrDefault("target", "");
        String messageId = b.getOrDefault("message_id", "");
        String playerKey = b.getOrDefault("player_key", "");
        String bodySid = b.getOrDefault("session_id", "");
        String sessionId = resolveSessionId(player, playerKey, session_id.isBlank() ? bodySid : session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.press(sessionId, player, target, messageId));
    }

    /**
     * P-0816-T（UI 重设计阶段三 API-9，§3.2，决策 C8）：出示证据到对话流。
     * 以「🃏 出示：CL-xx 线索名」system 行插入 discussionTranscript（全员可见）+ SSE script_present
     * 定向广播（player + clue_id + 摘要）；出示前校验线索存在且属于本局 + 持有（公开线索可直接出示）；
     * 幂等（presentedClues 记录，重复出示提示已出示）。阶段守卫：仅 DISCUSSION 阶段可出示。
     * body: {player, clue_id, player_key?, session_id?}
     */
    @PostMapping("/present")
    public ResponseEntity<Map<String, Object>> present(@RequestBody(required = false) Map<String, String> body,
                                                       @RequestParam(defaultValue = "") String session_id) {
        Map<String, String> b = body == null ? Map.of() : body;
        String player = b.getOrDefault("player", "");
        String clueId = b.getOrDefault("clue_id", "");
        String playerKey = b.getOrDefault("player_key", "");
        String bodySid = b.getOrDefault("session_id", "");
        String sessionId = resolveSessionId(player, playerKey, session_id.isBlank() ? bodySid : session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.present(sessionId, player, clueId));
    }

    /**
     * P-0816-R（UI 重设计阶段二 API-8，§3.2，决策 U2 MVP 内容推导）：关系矩阵 —— 右栏逻辑链 Tab 数据源。
     * 服务端内容推导（零生成器改动）：线索 content/title 提及角色名 → ★ 直接关联；线索持有者所扮演角色
     * → ◯ 持有；其余 –。二期切 LLM 标注 clues[].related_roles[]。phase 无关，不泄 secret。
     * query: session_id? / player / player_key? → {ok, roles[], clues[], matrix, relations[]}
     */
    @GetMapping("/relations")
    public ResponseEntity<Map<String, Object>> relations(@RequestParam(defaultValue = "") String session_id,
                                                         @RequestParam(defaultValue = "") String player,
                                                         @RequestParam(defaultValue = "") String player_key) {
        String sessionId = resolveSessionId(player, player_key, session_id);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        ensureGameLoaded(sessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.getRelations(sessionId));
    }

    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = resolveSessionId(player, playerKey, body.getOrDefault("session_id", ""));
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        // D-079：揭晓按钮必须推进 VOTE -> REVEAL；仅计算 resolveVote 不会改变阶段。
        return ResponseEntity.ok(scriptGameService.advancePhase(sessionId));
    }

    /** GAP-4b: REVEAL 展示后由前端确认结束对局 → ENDED（终态，含结果落库 GAP-4c）。 */
    @PostMapping("/finish")
    public ResponseEntity<Map<String, Object>> finish(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = resolveSessionId(player, playerKey, body.getOrDefault("session_id", ""));
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.confirmEnded(sessionId));
    }

    /**
     * P1（任务 2a）：玩家退出对局 —— 角色标记为托管（AI 代管但标记清楚），其已投的票作废、
     * 不计入 quorum 在线数。body: player（必填）/ player_key（可选，身份校验）/ session_id（可选）。
     */
    @PostMapping("/leave")
    public ResponseEntity<Map<String, Object>> leave(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = body.getOrDefault("session_id", playerSessions.getOrDefault(player, currentSessionId));
        return ResponseEntity.ok(scriptGameService.leaveGame(sessionId, player, playerKey));
    }

    /**
     * P1（任务 2b）/D-078：ENDED 后重开一局 —— 同剧本主题同玩家重开（复用 sessionId，
     * 前端轮询/SSE 定位不变）；新对局生成全新剧本/角色分配/roleKey/票型，托管与降级标记重置。
     * 重置会令旧 roleKey 失效，因此必须先用旧局 player + player_key 或 X-DM-Key 鉴权；
     * 玩家成功后仅返回本人新视图，DM 成功后仅返回公共视图，禁止透传 initGame 默认首位玩家视图。
     */
    @PostMapping("/restart")
    public ResponseEntity<Map<String, Object>> restart(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        if (sessionId.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));

        boolean dmAuthorized = dmKeyOk(dmKeyHeader);
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        if (!dmAuthorized) {
            if (player.isBlank()) {
                return ResponseEntity.status(403).body(Map.of("error", "身份校验失败：缺少 player"));
            }
            Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
            if (denied != null) return ResponseEntity.status(403).body(denied);
        }

        Map<String, Object> result = scriptGameService.restartGame(sessionId);
        if (result.containsKey("error")) return ResponseEntity.ok(result);
        ScriptGameService.ScriptGame restarted = scriptGameService.getGame(sessionId);
        if (restarted == null) {
            return ResponseEntity.ok(Map.of("error", "重开后对局状态不可用"));
        }
        return ResponseEntity.ok(restarted.toMap(dmAuthorized ? "" : player));
    }

    /**
     * C3/D-078: 状态查询 —— 空 player + 空 player_key 返回不含本人秘密/令牌的公共视图；
     * 请求指定玩家的本人视图必须通过 player_key 认证。仅传 player_key 时可由 key 反查玩家（重连场景）。
     * P-0810-17（B3）：sessionId 解析优先 findSessionByPlayerKey(player_key)（有 key 时）——
     * 修复 role_key 错位：playerSessions 回退 currentSessionId 在并发对局下会拿到全局最后对局，
     * 玩家 1..N 误用玩家 0 的 key → 403；key 与对局一一对应，反查是唯一可靠归属。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(defaultValue = "") String player,
                                                         @RequestParam(defaultValue = "") String player_key) {
        // P-0810-17（B3）：有 player_key 时优先按 key 反查对局（key 唯一归属本局）；
        // 反查失败再回退 playerSessions/currentSessionId（旧行为兜底）
        String sessionId = "";
        String resolvedPlayer = player == null ? "" : player;
        if (player_key != null && !player_key.isBlank()) {
            String sidByKey = scriptGameService.findSessionByPlayerKey(player_key);
            if (!sidByKey.isBlank()) {
                sessionId = sidByKey;
                if (resolvedPlayer.isBlank()) {
                    resolvedPlayer = scriptGameService.findPlayerByKey(sessionId, player_key);
                }
            }
        }
        if (sessionId.isBlank()) {
            sessionId = playerSessions.getOrDefault(resolvedPlayer, currentSessionId);
        }
        if (sessionId.isEmpty()) {
            return ResponseEntity.ok(Map.of("phase", "idle"));
        }
        boolean publicView = resolvedPlayer.isBlank() && (player_key == null || player_key.isBlank());
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game == null) return ResponseEntity.ok(Map.of("phase", "not_found"));
        if (publicView) {
            return ResponseEntity.ok(game.toMap(""));
        }
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, resolvedPlayer, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(game.toMap(resolvedPlayer));
    }
}
