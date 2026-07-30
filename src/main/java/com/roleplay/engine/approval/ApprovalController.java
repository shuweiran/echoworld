package com.roleplay.engine.approval;

import com.roleplay.engine.service.RouterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for round approval gate management.
 *
 * <p>In script and werewolf modes, the round pipeline pauses after Arbiter
 * integration and waits for DM (human) approval. These endpoints allow the
 * DM to review and decide on pending rounds.
 *
 * <h3>Curl examples:</h3>
 * <pre>{@code
 * # Approve the current pending round
 * curl -X POST http://localhost:8000/api/approval/approve \
 *   -H "Content-Type: application/json" \
 *   -d '{"session_id": "abc123"}'
 *
 * # Reject the current pending round
 * curl -X POST http://localhost:8000/api/approval/reject \
 *   -H "Content-Type: application/json" \
 *   -d '{"session_id": "abc123", "reason": "角色反应不符合设定"}'
 *
 * # Check approval status
 * curl "http://localhost:8000/api/approval/status?session_id=abc123"
 *
 * # Get detailed status with elapsed time
 * curl "http://localhost:8000/api/approval/status/detail?session_id=abc123"
 * }</pre>
 */
@RestController
@RequestMapping("/api/approval")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final RouterService routerService;

    public ApprovalController(ApprovalService approvalService, RouterService routerService) {
        this.approvalService = approvalService;
        this.routerService = routerService;
    }

    /**
     * Approve the pending round for a session.
     * The round pipeline will continue to compression and persistence.
     *
     * <p>Request body:
     * <pre>{@code
     * {"session_id": "abc123"}
     * }</pre>
     */
    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.getOrDefault("session_id", routerService.getState().getOrDefault("session_id", ""));
        if (sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "session_id is required"));
        }

        boolean result = approvalService.approve(sessionId);
        if (result) {
            return ResponseEntity.ok(Map.of(
                "status", "approved",
                "session_id", sessionId
            ));
        } else {
            return ResponseEntity.status(404).body(Map.of(
                "error", "No pending approval for session '" + sessionId + "'"
            ));
        }
    }

    /**
     * Reject the pending round for a session.
     * The round pipeline will roll back to the previous round snapshot.
     *
     * <p>Request body:
     * <pre>{@code
     * {"session_id": "abc123", "reason": "角色反应不符合设定"}
     * }</pre>
     */
    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.getOrDefault("session_id", routerService.getState().getOrDefault("session_id", ""));
        String reason = (String) body.getOrDefault("reason", "rejected by DM");

        if (sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "session_id is required"));
        }

        boolean result = approvalService.reject(sessionId, reason);
        if (result) {
            return ResponseEntity.ok(Map.of(
                "status", "rejected",
                "session_id", sessionId,
                "reason", reason,
                "rolled_back", true
            ));
        } else {
            return ResponseEntity.status(404).body(Map.of(
                "error", "No pending approval for session '" + sessionId + "'"
            ));
        }
    }

    /**
     * Check approval status for a session.
     *
     * <p>Query parameters:
     * <pre>{@code
     * ?session_id=abc123
     * }</pre>
     *
     * <p>Response:
     * <pre>{@code
     * {"session_id": "abc123", "status": "pending|approved|rejected|none"}
     * }</pre>
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // Use current session from RouterService
            sessionId = (String) routerService.getState().getOrDefault("session_id", "");
        }
        String status = approvalService.getStatus(sessionId);
        return ResponseEntity.ok(Map.of(
            "session_id", sessionId,
            "status", status
        ));
    }

    /**
     * Get detailed approval status with elapsed time.
     */
    @GetMapping("/status/detail")
    public ResponseEntity<Map<String, Object>> getDetailedStatus(@RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = (String) routerService.getState().getOrDefault("session_id", "");
        }
        return ResponseEntity.ok(approvalService.getDetailedStatus(sessionId));
    }
}
