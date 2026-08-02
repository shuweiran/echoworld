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
        // 开局夜：AI 角色立即自动行动；autoPlay 下全员行动完毕自动结算（真人行动经 night_action 提交）
        werewolfService.startNight(sessionId);
        return ResponseEntity.ok(state);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WerewolfController.class);
    private void logRouter(String sessionId) {
        log.info("Werewolf game {} registered to router", sessionId);
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

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(defaultValue = "") String player,
                                                          @RequestParam(defaultValue = "") String player_name) {
        String p = !player.isEmpty() ? player : player_name;
        String sessionId = playerSessions.getOrDefault(p, currentSessionId);
        if (sessionId.isEmpty()) {
            return ResponseEntity.ok(Map.of("game_over", true, "phase", "idle"));
        }
        return ResponseEntity.ok(werewolfService.statusMap(sessionId, p));
    }
}
