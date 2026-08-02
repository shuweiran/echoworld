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
        Map<String, Object> state = scriptGameService.initGame(sessionId, theme, players);
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
     * 阶段 2: 生成/获取对局地图（LLM 统一路径 → 校验 → BSP 降级，docs/地图JSON契约-v1.md）。
     * body: session_id（可选，缺省用当前对局）/ theme（可选主题覆盖）/ seed（BSP 降级种子）/ regenerate（强制重生成）。
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
        boolean regenerate = Boolean.parseBoolean(body.getOrDefault("regenerate", "false"));
        return ResponseEntity.ok(scriptGameService.generateMap(sessionId, theme, seed, regenerate));
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
        return ResponseEntity.ok(scriptGameService.resumeGame(sessionId, playerKey == null ? "" : playerKey));
    }

    /** C3: DM 面板分发 roleKey 用（全员令牌一览；仅 DM 侧调用，配合重连/顶号 UI）。 */
    @GetMapping("/keys")
    public ResponseEntity<Map<String, Object>> getKeys(@RequestParam(defaultValue = "") String session_id) {
        String sid = session_id.isBlank() ? currentSessionId : session_id;
        if (sid.isBlank()) return ResponseEntity.ok(Map.of("error", "缺少 session_id"));
        Map<String, String> keys = scriptGameService.getPlayerKeys(sid);
        return ResponseEntity.ok(Map.of("session_id", sid, "player_keys", keys));
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

    /** C4: DM key 校验 —— 未配置（空）时放开（与审批门同开放模式），配置后严格匹配。 */
    private boolean dmKeyOk(String providedKey) {
        if (dmKey == null || dmKey.isBlank()) return true;
        return providedKey != null && dmKey.equals(providedKey);
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String location = body.getOrDefault("location", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(scriptGameService.search(sessionId, player, location));
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

    @PostMapping("/start_discussion")
    public ResponseEntity<Map<String, Object>> startDiscussion(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
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
        result.put("phase", "discussion");
        result.put("simulation_started", simulationStarted);
        result.put("simulation_url", "/simulation.html");
        result.put("simulation_state_url", "/api/simulation/state");
        result.put("track_state_url", "/api/simulation/track/state");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start_voting")
    public ResponseEntity<Map<String, Object>> startVoting(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        scriptGameService.startVoting(sessionId);
        return ResponseEntity.ok(Map.of("phase", "vote"));
    }

    @PostMapping("/vote")
    public ResponseEntity<Map<String, Object>> vote(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String suspect = body.getOrDefault("suspect", "");
        String playerKey = body.getOrDefault("player_key", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        String result = scriptGameService.castVote(sessionId, player, suspect);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        return ResponseEntity.ok(scriptGameService.resolveVote(sessionId));
    }

    /** GAP-4b: REVEAL 展示后由前端确认结束对局 → ENDED（终态，含结果落库 GAP-4c）。 */
    @PostMapping("/finish")
    public ResponseEntity<Map<String, Object>> finish(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        return ResponseEntity.ok(scriptGameService.confirmEnded(sessionId));
    }

    /**
     * C3: 状态查询 —— 支持 player_key 认证（有 key 校验匹配，无 key 向后兼容）；
     * 仅传 player_key 时可由 key 反查玩家（重连场景）。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(defaultValue = "") String player,
                                                         @RequestParam(defaultValue = "") String player_key) {
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        // C3: 只传 player_key（未传玩家名）→ 由 key 反查对局与玩家（重连后个人视图）
        if ((player == null || player.isBlank()) && player_key != null && !player_key.isBlank()) {
            String sidByKey = scriptGameService.findSessionByPlayerKey(player_key);
            if (!sidByKey.isBlank()) {
                sessionId = sidByKey;
                player = scriptGameService.findPlayerByKey(sessionId, player_key);
            }
        }
        if (sessionId.isEmpty()) {
            return ResponseEntity.ok(Map.of("phase", "idle"));
        }
        Map<String, Object> denied = scriptGameService.checkPlayerAccess(sessionId, player, player_key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game == null) return ResponseEntity.ok(Map.of("phase", "not_found"));
        return ResponseEntity.ok(game.toMap(player));
    }
}
