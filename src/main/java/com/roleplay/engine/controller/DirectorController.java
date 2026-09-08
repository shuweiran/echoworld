package com.roleplay.engine.controller;

import com.roleplay.engine.service.director.DirectorAgentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Player-facing authoritative Director Agent endpoints. */
@RestController
@RequestMapping("/api/director")
public class DirectorController {
    private final DirectorAgentService director;

    public DirectorController(DirectorAgentService director) {
        this.director = director;
    }

    @PostMapping("/preflight")
    public ResponseEntity<Map<String, Object>> createPreflight(@RequestBody Map<String, Object> body) {
        return call(() -> director.createPreflight(body == null ? Map.of() : body));
    }

    @GetMapping("/preflight/{preflightId}")
    public ResponseEntity<Map<String, Object>> getPreflight(@PathVariable String preflightId) {
        return call(() -> director.getPreflight(preflightId));
    }

    @PostMapping("/preflight/{preflightId}/chat")
    public ResponseEntity<Map<String, Object>> preflightChat(@PathVariable String preflightId,
                                                              @RequestBody Map<String, Object> body) {
        return call(() -> director.chatPreflight(preflightId, string(body, "message")));
    }

    /** The only route that turns a confirmed preflight into a live roleplay session. */
    @PostMapping("/preflight/{preflightId}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String preflightId) {
        return call(() -> director.start(preflightId));
    }

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> runtimeState(@RequestParam("session_id") String sessionId) {
        return call(() -> director.runtimeState(sessionId));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> runtimeChat(@RequestBody Map<String, Object> body) {
        return call(() -> director.chatRuntime(string(body, "session_id"), string(body, "message")));
    }

    private ResponseEntity<Map<String, Object>> call(DirectorCall action) {
        try {
            return ResponseEntity.ok(action.run());
        } catch (IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage() == null ? "主控请求无效" : e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    private static String string(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return "";
        return String.valueOf(body.get(key));
    }

    @FunctionalInterface
    private interface DirectorCall {
        Map<String, Object> run();
    }
}