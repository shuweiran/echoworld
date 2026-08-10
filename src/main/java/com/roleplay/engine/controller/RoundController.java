package com.roleplay.engine.controller;

import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SessionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round control endpoints.
 * Maps from Python api/routes_round.py.
 *
 * <p>D11: 端点按 session_id 路由到对应会话实例（未传 → 默认单例，向后兼容）。
 * D13: /api/round/start 的 turns 参数真正生效 —— 按 turns 执行 N 轮
 * （每轮走 RouterService.runRound），支持中途 stop / 目标达成提前结束，
 * 响应报告实际执行轮数。
 */
@RestController
@RequestMapping("/api/round")
public class RoundController {

    private final SessionRegistry sessions;

    public RoundController(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startRound(@RequestBody Map<String, Object> body) {
        String userInput = (String) body.getOrDefault("message",
            body.getOrDefault("text", ""));
        int turns = ((Number) body.getOrDefault("turns", 1)).intValue();
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        RouterService target = sessions.get(sessionId);

        // D13: turns 真正生效 —— 按 turns 执行 N 轮，每轮走 runRound；
        // 停止条件：中途 stop / 目标达成 / 单轮错误（见 RouterService.runTurns）
        List<RouterService.RoundResult> results = target.runTurns(userInput, turns);
        RouterService.RoundResult last = results.isEmpty() ? null : results.get(results.size() - 1);

        // 汇总所有轮次的角色输出（前端逐条渲染）
        List<Map<String, Object>> allOutputs = new ArrayList<>();
        for (RouterService.RoundResult r : results) {
            if (r.agentOutputs != null) allOutputs.addAll(r.agentOutputs);
        }

        // 停止原因：completed 跑满 turns | error 单轮错误 | stopped 中途 /api/stop | goal_achieved 目标达成
        String stopReason = "completed";
        if (results.isEmpty() || (last != null && last.status != null && last.status.startsWith("error"))) {
            stopReason = "error";
        } else if (results.size() < Math.max(1, turns)) {
            stopReason = target.isRunning() ? "goal_achieved" : "stopped";
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", last != null ? last.status : "error: 无活动会话");
        resp.put("round", target.getRoundCount());
        resp.put("turns", turns);
        resp.put("rounds", results.size());
        resp.put("stop_reason", stopReason);
        resp.put("agent_outputs", allOutputs);
        resp.put("narration", last != null && last.integration != null
            ? last.integration.getOrDefault("narration", "") : "");
        resp.put("metrics", last != null && last.metrics != null ? last.metrics : Map.of());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@RequestBody Map<String, Object> body) {
        int round = ((Number) body.getOrDefault("round", 0)).intValue();
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        String result = sessions.get(sessionId).rollbackToRound(sessionId, round);
        return ResponseEntity.ok(Map.of("status", result));
    }

    /**
     * P-0810-21-D：玩家发言候选话术（一般模式玩家回合可选项）——
     * 前端在 AI 回合结束后拉取 2-4 条候选，点击即发言；LLM 失败恒返回规则兜底候选。
     * <pre>{@code
     * POST /api/round/suggest
     * {"session_id": "xxx", "count": 3} → {"session_id": "xxx", "suggestions": ["...", "...", "..."]}
     * }</pre>
     */
    @PostMapping("/suggest")
    public ResponseEntity<Map<String, Object>> suggest(@RequestBody Map<String, Object> body) {
        int count = ((Number) body.getOrDefault("count", 3)).intValue();
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        RouterService target = sessions.get(sessionId);
        List<String> suggestions = target.suggestPlayerLines(count);
        return ResponseEntity.ok(Map.of(
            "session_id", sessionId,
            "suggestions", suggestions
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getRoundStatus(@RequestParam(required = false) String session_id) {
        RouterService target = sessions.get(session_id);
        return ResponseEntity.ok(Map.of(
            "running", target.isRunning(),
            "round", target.getRoundCount()
        ));
    }
}
