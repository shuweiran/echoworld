package com.roleplay.engine.controller;

import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.WerewolfService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Werewolf game endpoints — now backed by full WerewolfService game engine.
 *
 * <p>P-0802-F（主人授权后端批次）改造：
 * <ul>
 *   <li>G0-1：init 返回 session_id + 注册 RouterService（身份进上下文）+ 登记人类玩家 + 自动推进开关</li>
 *   <li>新增 POST /api/werewolf/discussion_say（白天讨论人类发言，接对话引擎）</li>
 *   <li>status 响应附加 session_id / waiting_human</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/werewolf")
public class WerewolfController {

    private final WerewolfService werewolfService;
    private final RouterService router;
    /** P-0802-P2：玩家身份解析器 —— init 带 player_id 时按解析出的当前角色名登记人类玩家（角色库改名兜底）。 */
    private final PlayerIdentityService identityService;
    private final Map<String, String> playerSessions = new ConcurrentHashMap<>();
    /** P-0802-I：房间码 ? 对局 sessionId 映射（init 可选 room_code 登记；resume 可按房间码定位重连入口，对齐 C3 剧本杀轻量绑定）。 */
    private final Map<String, String> roomGames = new ConcurrentHashMap<>();
    private String currentSessionId = "";

    /** autoPlay 开关（roleplay.game.werewolf.auto-play，默认 true；直构测试无 Spring 注入 → false 保持手工驱动）。 */
    @Value("${roleplay.game.werewolf.auto-play:false}")
    private boolean autoPlay = false;

    /**
     * 主持人管理口令。狼人杀与剧本杀共用同一配置，避免出现一套功能安全、一套功能默认公开的分裂边界。
     * 空配置同样拒绝访问：管理接口不能以“未配置”为匿名放行条件。
     */
    @Value("${roleplay.game.dm.key:}")
    private String dmKey = "";

    /** P-0802-P3：测试钩子 —— 玩家 → 对局映射（局中改名后断言键已换名）。 */
    Map<String, String> playerSessions() {
        return playerSessions;
    }

    /**
     * P-0802-P3（改造方案 §4.2.3 rename 接线）：playerSessions 键 oldName→newName 同步。
     * 局中改名端点同步时由 PlayerIdentityService 编排调用；currentSessionId 不随玩家名变（会话级）。
     */
    public void renamePlayerSessionKey(String oldName, String newName) {
        String sid = playerSessions.remove(oldName);
        if (sid != null) playerSessions.put(newName, sid);
    }

    public WerewolfController(WerewolfService werewolfService) {
        this(werewolfService, null, null);
    }

    public WerewolfController(WerewolfService werewolfService, RouterService router) {
        this(werewolfService, router, null);
    }

    @Autowired
    public WerewolfController(WerewolfService werewolfService, RouterService router,
                              PlayerIdentityService playerIdentityService) {
        this.werewolfService = werewolfService;
        this.router = router;
        this.identityService = playerIdentityService;
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> init(@RequestParam(defaultValue = "") String player_name,
                                                     @RequestParam(defaultValue = "") String human_players,
                                                     @RequestParam(defaultValue = "") String player_id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        // Support both query param and JSON body formats
        List<String> players = new ArrayList<>();
        String playerName = trim(player_name);
        if (body != null && body.containsKey("players")) {
            @SuppressWarnings("unchecked")
            List<String> bodyPlayers = (List<String>) body.get("players");
            players.addAll(bodyPlayers);
        } else if (!human_players.isEmpty()) {
            players.addAll(Arrays.asList(human_players.split(",")));
        }

        // P-0824-C：initGame 固定按 players[0] 生成私密视图，因此必须先解析真正的人类身份，
        // 再把它规范到首位。旧逻辑只在 player_name 不在列表时置顶，真人在后位会收到首个 AI 的
        // your_role/role_key；player_id 解析到改名后的角色时还会把一个不存在于本局的名字登记为真人。
        String pid = !trim(player_id).isBlank() ? trim(player_id)
                : (body != null ? trim(body.get("player_id")) : "");
        String resolvedName = (!pid.isBlank() && identityService != null)
                ? identityService.resolveCharacterName(pid).map(String::trim).filter(n -> !n.isBlank()).orElse(null)
                : null;
        String humanPlayerName = resolvedName != null ? resolvedName : playerName;

        if (!humanPlayerName.isBlank()) {
            // player_id 解析名优先：从名单移除旧名，防止改名前后两个身份同时进入同一局。
            if (resolvedName != null && !playerName.isBlank() && !playerName.equals(resolvedName)) {
                players.removeIf(playerName::equals);
            }
            // 已在列表后位也必须移动到首位；同时消除重复项，保证令牌只归属一个玩家身份。
            players.removeIf(humanPlayerName::equals);
            players.add(0, humanPlayerName);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> requestedRoles = body != null
            ? (Map<String, String>) body.get("roles") : null;
        Map<String, String> customRoles = requestedRoles == null ? null : new LinkedHashMap<>(requestedRoles);
        if (customRoles != null && resolvedName != null && !playerName.isBlank()
                && !playerName.equals(resolvedName)) {
            String oldRole = customRoles.remove(playerName);
            if (oldRole != null) customRoles.putIfAbsent(resolvedName, oldRole);
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        currentSessionId = sessionId;
        players.forEach(p -> playerSessions.put(p, sessionId));
        // P-0802-I：联机房绑定（轻量，对齐 C3）—— init 可选 room_code，resume 可按房间码定位
        String roomCode = body != null ? String.valueOf(body.getOrDefault("room_code", "")).trim() : "";
        if (!roomCode.isBlank()) {
            roomGames.put(roomCode.toUpperCase(Locale.ROOT), sessionId);
        }

        Map<String, Object> state = werewolfService.initGame(sessionId, players, customRoles);
        // P-0802-F：登记人类玩家（AI = 存活玩家中非人类）→ 注册 router（身份进上下文）→ 自动推进
        // 无 player_id / 未绑定 / identityService 缺失时仍回退 player_name；解析成功时名单和视图均使用当前名。
        Set<String> humans = humanPlayerName.isBlank() ? Set.of() : Set.of(humanPlayerName);
        werewolfService.setHumanPlayers(sessionId, humans);
        WerewolfService.GameState game = werewolfService.getGame(sessionId);
        if (router != null) {
            router.setWerewolfGame(game);
            logRouter(sessionId);
        }
        werewolfService.setAutoPlay(sessionId, autoPlay);
        werewolfService.notifyGameInit(sessionId, humanPlayerName);
        // G0-1：响应携带 session_id（前端可拿到）
        state.put("session_id", sessionId);
        // P-0802-I：响应回显 room_code（若绑定）
        if (!roomCode.isBlank()) state.put("room_code", roomCode.toUpperCase(Locale.ROOT));
        // 开局夜：AI 角色立即自动行动；autoPlay 下全员行动完毕自动结算（真人行动经 night_action 提交）
        werewolfService.startNight(sessionId);
        return ResponseEntity.ok(state);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WerewolfController.class);
    private void logRouter(String sessionId) {
        log.info("Werewolf game {} registered to router", sessionId);
    }

    /**
     * P-0802-I：断线重连恢复 —— body: session_id 或 room_code + player（按 session_id 拉取当前对局状态，
     * 内存命中直接返回 / 快照重建 / 终局返回终态；重连后重新登记玩家会话映射）。
     * P-0802-J：增加 roleKey 防冒充 —— body 必含 player_key（本人 roleKey，init/status 响应 role_key 字段发放，
     * 或 GET /api/werewolf/keys 主持人分发）；校验不匹配 → 拒绝恢复（对齐剧本杀 C3 roleKey 体系）。
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", "");
        String roomCode = body.getOrDefault("room_code", "");
        String player = body.getOrDefault("player", "");
        String playerKey = body.getOrDefault("player_key", "");
        if ((sessionId == null || sessionId.isBlank()) && (roomCode == null || roomCode.isBlank())) {
            return ResponseEntity.ok(Map.of("error", "缺少对局标识（session_id / room_code 至少其一）"));
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = roomGames.getOrDefault(roomCode.trim().toUpperCase(Locale.ROOT), "");
            if (sessionId.isBlank()) {
                return ResponseEntity.ok(Map.of("error", "房间码未绑定任何对局"));
            }
        }
        Map<String, Object> result = werewolfService.resumeGame(sessionId.trim(), player == null ? "" : player,
                playerKey == null ? "" : playerKey);
        if (result.containsKey("error")) {
            return ResponseEntity.ok(result); // 身份校验失败：不登记玩家会话映射
        }
        // 重连后登记玩家会话映射（后续 night_action/vote/status 可定位本局）
        if (player != null && !player.isBlank()) {
            playerSessions.put(player, sessionId.trim());
        }
        currentSessionId = sessionId.trim();
        return ResponseEntity.ok(result);
    }

    /** P-0802-J: 全员 roleKey 一览（主持人/DM 分发令牌用，对齐剧本杀 GET /api/script/keys）。 */
    @GetMapping("/keys")
    public ResponseEntity<Map<String, Object>> getKeys(
            @RequestParam(defaultValue = "") String session_id,
            @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        if (!dmKeyOk(dmKeyHeader)) return dmDenied();
        String sid = trim(session_id);
        if (sid.isBlank()) return badRequest("缺少 session_id");
        Map<String, String> keys = werewolfService.getPlayerKeys(sid);
        return ResponseEntity.ok(Map.of("session_id", sid, "player_keys", keys));
    }

    /** 仅保留给旧的直接控制器测试编译；HTTP 路由始终进入带 X-DM-Key 的重载。 */
    @Deprecated
    public ResponseEntity<Map<String, Object>> getKeys(String sessionId) {
        return getKeys(sessionId, "");
    }

    @PostMapping("/night_action")
    public ResponseEntity<Map<String, Object>> nightAction(@RequestBody Map<String, String> body) {
        ResponseEntity<Map<String, Object>> denied = requirePlayer(body);
        if (denied != null) return denied;
        String player = trim(body.get("player"));
        String action = trim(body.get("action"));
        String target = trim(body.get("target"));
        String sessionId = trim(body.get("session_id"));
        String result = werewolfService.recordNightAction(sessionId, player, action, target);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/hunter_shoot")
    public ResponseEntity<Map<String, Object>> hunterShoot(@RequestBody Map<String, String> body) {
        ResponseEntity<Map<String, Object>> denied = requirePlayer(body);
        if (denied != null) return denied;
        String player = trim(body.get("player"));
        String target = trim(body.get("target"));
        String sessionId = trim(body.get("session_id"));
        String result = werewolfService.hunterShoot(sessionId, player, target);
        return ResponseEntity.ok(Map.of("result", result));
    }

    /** P-0802-F：白天讨论人类发言（接入讨论引擎，下轮被排空入发言记录）。
     *  P-0810-17（B4）：防御性拷贝 —— service 返回 map 后需追加 session_id，不可变 map（Map.of）
     *  再 put 会 500（service 侧已改 LinkedHashMap 根治，此处兜底防其他返回路径/未来回归）。 */
    @PostMapping("/discussion_say")
    public ResponseEntity<Map<String, Object>> discussionSay(@RequestBody Map<String, String> body) {
        ResponseEntity<Map<String, Object>> denied = requirePlayer(body);
        if (denied != null) return denied;
        String player = trim(body.get("player"));
        String message = body.getOrDefault("message", "");
        String sessionId = trim(body.get("session_id"));
        Map<String, Object> result = new LinkedHashMap<>(werewolfService.discussionSay(sessionId, player, message));
        result.put("session_id", sessionId);
        return ResponseEntity.ok(result);
    }

    /** Admin: resolve night phase and transition to day. */
    @PostMapping("/resolve_night")
    public ResponseEntity<Map<String, Object>> resolveNight(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        if (!dmKeyOk(dmKeyHeader)) return dmDenied();
        String sessionId = trim(body.get("session_id"));
        if (sessionId.isBlank()) return badRequest("缺少 session_id");
        return ResponseEntity.ok(werewolfService.resolveNight(sessionId));
    }

    @PostMapping("/vote")
    public ResponseEntity<Map<String, Object>> vote(@RequestBody Map<String, Object> body) {
        Map<String, String> credentials = stringMap(body);
        ResponseEntity<Map<String, Object>> denied = requirePlayer(credentials);
        if (denied != null) return denied;
        String player = credentials.get("player");
        String target = trim(body.get("target"));
        String sessionId = credentials.get("session_id");
        String result = werewolfService.castVote(sessionId, player, target);
        return ResponseEntity.ok(Map.of("result", result, "phase", "day_vote"));
    }

    /** Admin: resolve votes and transition to night. */
    @PostMapping("/resolve_vote")
    public ResponseEntity<Map<String, Object>> resolveVote(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        if (!dmKeyOk(dmKeyHeader)) return dmDenied();
        String sessionId = trim(body.get("session_id"));
        if (sessionId.isBlank()) return badRequest("缺少 session_id");
        return ResponseEntity.ok(werewolfService.resolveVote(sessionId));
    }

    @PostMapping("/start_voting")
    public ResponseEntity<Map<String, Object>> startVoting(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-DM-Key", defaultValue = "") String dmKeyHeader) {
        if (!dmKeyOk(dmKeyHeader)) return dmDenied();
        String sessionId = trim(body.get("session_id"));
        if (sessionId.isBlank()) return badRequest("缺少 session_id");
        werewolfService.startVoting(sessionId);
        return ResponseEntity.ok(Map.of("phase", "day_vote"));
    }

    /**
     * HTTP 玩家状态视图：角色、夜间可见信息等均属私密数据，因此与行动接口使用同一 roleKey 边界。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestParam(defaultValue = "") String player,
            @RequestParam(defaultValue = "") String player_name,
            @RequestParam(defaultValue = "") String session_id,
            @RequestParam(defaultValue = "") String player_key) {
        String p = !trim(player).isBlank() ? trim(player) : trim(player_name);
        Map<String, String> credentials = Map.of(
                "session_id", trim(session_id), "player", p, "player_key", trim(player_key));
        ResponseEntity<Map<String, Object>> denied = requirePlayer(credentials);
        if (denied != null) return denied;
        return ResponseEntity.ok(werewolfService.statusMap(trim(session_id), p));
    }

    private ResponseEntity<Map<String, Object>> requirePlayer(Map<String, String> body) {
        String sessionId = trim(body.get("session_id"));
        String player = trim(body.get("player"));
        String playerKey = trim(body.get("player_key"));
        if (sessionId.isBlank() || player.isBlank() || playerKey.isBlank()) {
            return badRequest("缺少身份字段：session_id、player、player_key 均为必填");
        }
        if (!werewolfService.isPlayerKeyValid(sessionId, player, playerKey)) {
            return ResponseEntity.status(403).body(Map.of("error", "身份校验失败：player_key 与对局玩家不匹配"));
        }
        playerSessions.put(player, sessionId);
        return null;
    }

    private boolean dmKeyOk(String providedKey) {
        return dmKey != null && !dmKey.isBlank() && providedKey != null && dmKey.equals(providedKey);
    }

    private ResponseEntity<Map<String, Object>> dmDenied() {
        return ResponseEntity.status(403).body(Map.of("error", "DM 权限校验失败：请配置并提供 X-DM-Key"));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private Map<String, String> stringMap(Map<String, Object> body) {
        Map<String, String> result = new HashMap<>();
        result.put("session_id", trim(body.get("session_id")));
        result.put("player", trim(body.get("player")));
        result.put("player_key", trim(body.get("player_key")));
        return result;
    }

    private static String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
