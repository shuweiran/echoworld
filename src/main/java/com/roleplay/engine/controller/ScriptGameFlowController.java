package com.roleplay.engine.controller;

import com.roleplay.engine.service.ScriptGameFlowService;
import com.roleplay.engine.service.ScriptGameService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 剧本杀高层流程 API。
 *
 * <p>与 ScriptController 的底层动作 API 分工：
 * <ul>
 *   <li>ScriptController：search/discussion/vote 等领域动作；</li>
 *   <li>本控制器：opening -> investigation 的生命周期编排，以及开场 NPC 响应。</li>
 * </ul>
 * 所有写入口仍复用 ScriptGameService.checkPlayerAccess 做 roleKey 鉴权。
 */
@RestController
@RequestMapping("/api/script/flow")
public class ScriptGameFlowController {

    private final ScriptGameFlowService flow;
    private final ScriptGameService games;

    public ScriptGameFlowController(ScriptGameFlowService flow, ScriptGameService games) {
        this.flow = flow;
        this.games = games;
    }

    /**
     * 生成完整剧本并在完成后进入可交互 opening，而不是直接开始搜证。
     * body: {session_id, player, player_key}
     */
    @PostMapping("/generate_full")
    public ResponseEntity<Map<String, Object>> generateFull(@RequestBody Map<String, String> body) {
        String sessionId = trim(body.get("session_id"));
        String player = trim(body.get("player"));
        String playerKey = trim(body.get("player_key"));
        if (sessionId.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 session_id"));
        Map<String, Object> denied = games.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(flow.generateFullToOpening(sessionId));
    }

    /**
     * 已经通过旧 generate_full 完成的局可调用此接口归一到 opening；有搜证进度后拒绝回退。
     */
    @PostMapping("/opening/ensure")
    public ResponseEntity<Map<String, Object>> ensureOpening(@RequestBody Map<String, String> body) {
        String sessionId = trim(body.get("session_id"));
        String player = trim(body.get("player"));
        String playerKey = trim(body.get("player_key"));
        if (sessionId.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 session_id"));
        Map<String, Object> denied = games.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(flow.ensureOpening(sessionId, player));
    }

    /** body: {session_id, player, player_key, message} */
    @PostMapping("/opening/say")
    public ResponseEntity<Map<String, Object>> openingSay(@RequestBody Map<String, String> body) {
        String sessionId = trim(body.get("session_id"));
        String player = trim(body.get("player"));
        String playerKey = trim(body.get("player_key"));
        if (sessionId.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 session_id"));
        Map<String, Object> denied = games.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(flow.openingSay(sessionId, player, body.getOrDefault("message", "")));
    }

    /** body: {session_id, player, player_key}；玩家确认后才启动 AI 自主搜证。 */
    @PostMapping("/investigation/start")
    public ResponseEntity<Map<String, Object>> startInvestigation(@RequestBody Map<String, String> body) {
        String sessionId = trim(body.get("session_id"));
        String player = trim(body.get("player"));
        String playerKey = trim(body.get("player_key"));
        if (sessionId.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 session_id"));
        Map<String, Object> denied = games.checkPlayerAccess(sessionId, player, playerKey);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(flow.startInvestigation(sessionId, player));
    }

    /**
     * 编排状态只返回阶段/AI 调度状态/当前玩家自己的 clue ids，不返回其他角色私有知识。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam("session_id") String sessionId,
                                                       @RequestParam("player") String player,
                                                       @RequestParam(value = "player_key", required = false, defaultValue = "") String playerKey) {
        String sid = trim(sessionId);
        String p = trim(player);
        String key = trim(playerKey);
        Map<String, Object> denied = games.checkPlayerAccess(sid, p, key);
        if (denied != null) return ResponseEntity.status(403).body(denied);
        return ResponseEntity.ok(flow.status(sid, p));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
