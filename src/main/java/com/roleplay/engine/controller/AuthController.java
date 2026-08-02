package com.roleplay.engine.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invite-code authentication endpoints.
 * Maps from Python api/routes_auth.py + services/invite_service.py.
 *
 * <p>P-0801-G: 邀请码功能显式开关 {@code roleplay.auth.invite-enabled}（默认 false=关闭）——
 * 关闭时 /verify 返回 403「邀请码功能未启用」（不暴露邀请码是否正确），其余端点与游戏功能不受影响；
 * 开启时校验配置的持久化邀请码 {@code roleplay.auth.invite-code}（默认 DEFAULT2024 兼容旧行为）。
 * 改配置后重启服务生效；功能代码/端点全部保留。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Default admin key — parity with Python routes_auth.py. Override via env ROLEPLAY_ADMIN_KEY. */
    private static final String DEFAULT_ADMIN_KEY = "admin-secret-change-me";

    private final String adminKey;

    /** 邀请码功能显式开关（roleplay.auth.invite-enabled，默认 false=关闭）。 */
    private final boolean inviteEnabled;

    /** 持久化初始邀请码（roleplay.auth.invite-code，默认 DEFAULT2024 兼容旧行为；服务重启不丢）。 */
    private final String inviteCode;

    private final Map<String, Boolean> inviteCodes = new ConcurrentHashMap<>();
    private final Set<String> activeTokens = ConcurrentHashMap.newKeySet();

    public AuthController(
            @Value("${roleplay.auth.invite-enabled:false}") boolean inviteEnabled,
            @Value("${roleplay.auth.invite-code:DEFAULT2024}") String inviteCode) {
        this.inviteEnabled = inviteEnabled;
        this.inviteCode = inviteCode;
        this.adminKey = System.getenv().getOrDefault("ROLEPLAY_ADMIN_KEY", DEFAULT_ADMIN_KEY);
        // 兼容旧行为：DEFAULT2024 恒在；配置的持久化邀请码（如 B3283A78）启用后立即可用
        inviteCodes.put("DEFAULT2024", true);
        if (inviteCode != null && !inviteCode.isBlank()) {
            inviteCodes.put(inviteCode.trim(), true);
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body) {
        // 显式开关（roleplay.auth.invite-enabled，默认关闭）：关闭时明确 403，
        // 不暴露邀请码是否正确；仅此端点被门控，不影响其余游戏功能。
        if (!inviteEnabled) {
            return ResponseEntity.status(403).body(Map.of("error", "邀请码功能未启用"));
        }
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
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        // D23: 缺失 Authorization 头 → 401 而非 400（Spring 必填参数缺失会抛
        // MissingRequestHeaderException → 400；契约要求未认证一律 401 且带错误 JSON）
        if (auth == null || auth.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "缺少 Authorization 头"));
        }
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        if (!token.isBlank() && activeTokens.contains(token)) {
            return ResponseEntity.ok(Map.of(
                "user", "player", "authenticated", true
            ));
        }
        // 无效/空 token → 401，与缺失头区分（错误信息不同）
        return ResponseEntity.status(401).body(Map.of("error", "无效的 token"));
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
