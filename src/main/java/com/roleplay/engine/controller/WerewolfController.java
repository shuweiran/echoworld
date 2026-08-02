package com.roleplay.engine.controller;

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
    private final Map<String, String> playerSessions = new ConcurrentHashMap<>();
    /** P-0802-I：房间码 ? 对局 sessionId 映射（init 可选 room_code 登记；resume 可按房间码定位重连入口，对齐 C3 剧本杀轻量绑定）。 */
    private final Map<String, String> roomGames = new ConcurrentHashMap<>();
    private String currentSessionId = "";

    /** autoPlay 开关（roleplay.game.werewolf.auto-play，默认 true；直构测试无 Spring 注入 → false 保持手工驱动）。 */
    @Value("${roleplay.game.werewolf.auto-play:false}")
    private boolean autoPlay = false;

    public WerewolfController(WerewolfService werewolfService) {
        this(werewolfService, null);
    }

    @Autowired
    public WerewolfController(WerewolfService werewolfService, RouterService router) {
        this.werewolfService = werewolfService;
        this.router = router;
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> init(@RequestParam(defaultValue = "") String player_name,
                                                     @RequestParam(defaultValue = "") String human_players,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        // Support both query param and JSON body formats
        List<String> players = new ArrayList<>();
        String playerName = player_name;
        if (body != null && body.containsKey("players")) {
            @SuppressWarnings("unchecked")
            List<String> bodyPlayers = (List<String>) body.get("players");
            players.addAll(bodyPlayers);
        } else if (!human_players.isEmpty()) {
            players.addAll(Arrays.asList(human_players.split(",")));
        }
        if (!playerName.isEmpty() && !players.contains(playerName)) {
            players.add(0, playerName);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> customRoles = body != null
            ? (Map<String, String>) body.get("roles") : null;

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
        Set<String> humans = playerName.isEmpty() ? Set.of() : Set.of(playerName);
        werewolfService.setHumanPlayers(sessionId, humans);
        WerewolfService.GameState game = werewolfService.getGame(sessionId);
        if (router != null) {
            router.setWerewolfGame(game);
            logRouter(sessionId);
        }
        werewolfService.setAutoPlay(sessionId, autoPlay);
        werewolfService.notifyGameInit(sessionId, playerName);
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
    public ResponseEntity<Map<String, Object>> getKeys(@RequestParam(defaultValue = "") String session_id) {
        String sid = session_id.isBlank() ? currentSessionId : session_id;
        if (sid.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        Map<String, String> keys = werewolfService.getPlayerKeys(sid);
        return ResponseEntity.ok(Map.of("session_id", sid, "player_keys", keys));
    }

    @PostMapping("/night_action")
    public ResponseEntity<Map<String, Object>> nightAction(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String action = body.getOrDefault("action", "");
        String target = body.getOrDefault("target", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        String result = werewolfService.recordNightAction(sessionId, player, action, target);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/hunter_shoot")
    public ResponseEntity<Map<String, Object>> hunterShoot(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String target = body.getOrDefault("target", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        String result = werewolfService.hunterShoot(sessionId, player, target);
        return ResponseEntity.ok(Map.of("result", result));
    }

    /** P-0802-F：白天讨论人类发言（接入讨论引擎，下轮被排空入发言记录）。 */
    @PostMapping("/discussion_say")
    public ResponseEntity<Map<String, Object>> discussionSay(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String message = body.getOrDefault("message", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> result = werewolfService.discussionSay(sessionId, player, message);
        result.put("session_id", sessionId);
        return ResponseEntity.ok(result);
    }

    /** Admin: resolve night phase and transition to day. */
    @PostMapping("/resolve_night")
    public ResponseEntity<Map<String, Object>> resolveNight(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        return ResponseEntity.ok(werewolfService.resolveNight(sessionId));
    }

    @PostMapping("/vote")
    public ResponseEntity<Map<String, Object>> vote(@RequestBody Map<String, Object> body) {
        String player = (String) body.getOrDefault("player", "");
        String target = (String) body.getOrDefault("target", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        String result = werewolfService.castVote(sessionId, player, target);
        return ResponseEntity.ok(Map.of("result", result, "phase", "day_vote"));
    }

    /** Admin: resolve votes and transition to night. */
    @PostMapping("/resolve_vote")
    public ResponseEntity<Map<String, Object>> resolveVote(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        return ResponseEntity.ok(werewolfService.resolveVote(sessionId));
    }

    @PostMapping("/start_voting")
    public ResponseEntity<Map<String, Object>> startVoting(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        werewolfService.startVoting(sessionId);
        return ResponseEntity.ok(Map.of("phase", "day_vote"));
    }

    /** 向后兼容重载：不传 session_id（旧测试/旧客户端）。 */
    public ResponseEntity<Map<String, Object>> getStatus(String player, String player_name) {
        return getStatus(player, player_name, "");
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(defaultValue = "") String player,
                                                          @RequestParam(defaultValue = "") String player_name,
                                                          @RequestParam(defaultValue = "") String session_id) {
        String p = !player.isEmpty() ? player : player_name;
        // P-0802-I：显式 session_id 优先（重连/多局场景前端可直接按对局定位）
        String sid = !session_id.isBlank() ? session_id.trim() : playerSessions.getOrDefault(p, currentSessionId);
        if (sid.isEmpty()) {
            return ResponseEntity.ok(Map.of("game_over", true, "phase", "idle"));
        }
        return ResponseEntity.ok(werewolfService.statusMap(sid, p));
    }
}
