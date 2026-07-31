package com.roleplay.engine.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invite-code authentication endpoints.
 * Maps from Python api/routes_auth.py + services/invite_service.py.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Default admin key — parity with Python routes_auth.py. Override via env ROLEPLAY_ADMIN_KEY. */
    private static final String DEFAULT_ADMIN_KEY = "admin-secret-change-me";

    private final String adminKey;
    private final Map<String, Boolean> inviteCodes = new ConcurrentHashMap<>();
    private final Set<String> activeTokens = ConcurrentHashMap.newKeySet();

    public AuthController() {
        this.adminKey = System.getenv().getOrDefault("ROLEPLAY_ADMIN_KEY", DEFAULT_ADMIN_KEY);
        inviteCodes.put("DEFAULT2024", true);
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", "");
        if (inviteCodes.containsKey(code) && inviteCodes.get(code)) {
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            activeTokens.add(token);
            return ResponseEntity.ok(Map.of(
                "token", token, "user", "player",
                "message", "验证成功"
            ));
        }
        return ResponseEntity.status(401).body(Map.of("error", "无效的邀请码"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader("Authorization") String auth) {
        String token = auth.replace("Bearer ", "");
        if (activeTokens.contains(token)) {
            return ResponseEntity.ok(Map.of(
                "user", "player", "authenticated", true
            ));
        }
        return ResponseEntity.status(401).body(Map.of("error", "未认证"));
    }

    /**
     * Admin endpoints (host only) — require the admin key via X-Admin-Key header,
     * matching the Python backend's _require_admin (routes_auth.py).
     */
    private boolean checkAdminKey(String adminKeyHeader) {
        return adminKeyHeader != null && adminKey.equals(adminKeyHeader);
    }

    private static ResponseEntity<Map<String, String>> adminForbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限。请提供 X-Admin-Key 请求头"));
    }

    @PostMapping("/admin/generate")
    public ResponseEntity<?> generateCode(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKeyHeader) {
        if (!checkAdminKey(adminKeyHeader)) {
            return adminForbidden();
        }
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        inviteCodes.put(code, true);
        return ResponseEntity.ok(Map.of("code", code));
    }

    @GetMapping("/admin/list")
    public ResponseEntity<?> listCodes(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKeyHeader) {
        if (!checkAdminKey(adminKeyHeader)) {
            return adminForbidden();
        }
        List<Map<String, Object>> codes = new ArrayList<>();
        inviteCodes.forEach((code, active) ->
            codes.add(Map.of("code", code, "active", active, "uses", 0)));
        return ResponseEntity.ok(codes);
    }

    @PostMapping("/admin/deactivate")
    public ResponseEntity<?> deactivate(@RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKeyHeader) {
        if (!checkAdminKey(adminKeyHeader)) {
            return adminForbidden();
        }
        inviteCodes.put(body.getOrDefault("code", ""), false);
        return ResponseEntity.ok().build();
    }
}
