package com.roleplay.engine.approval;

import com.roleplay.engine.service.RouterService.RoundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;

/**
 * Tests for the ApprovalService gate mechanism.
 *
 * <p>Verifies the full lifecycle: submit → await → approve/reject/timeout.
 * Uses a separate thread to simulate the DM action since submitForApproval
 * blocks the calling thread.
 */
class ApprovalServiceTest {

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService();
    }

    @Test
    @DisplayName("No pending actions return 'none' status")
    void testNoPendingStatus() {
        assertEquals("none", approvalService.getStatus("nonexistent-session"));
    }

    @Test
    @DisplayName("Approve unblocks the pending round")
    void testApproveFlow() throws Exception {
        RoundResult result = createTestResult("3 agents done");

        // Submit in a background thread
        CompletableFuture<RoundResult> futureResult = CompletableFuture.supplyAsync(() -> {
            try {
                return approvalService.submitForApproval(result, "session-1", 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        // Wait briefly to ensure the submission is pending
        Thread.sleep(100);
        assertEquals("pending", approvalService.getStatus("session-1"));

        // Approve
        boolean approved = approvalService.approve("session-1");
        assertTrue(approved);

        // The future should complete with the approved result
        RoundResult approvedResult = futureResult.get(3, TimeUnit.SECONDS);
        assertNotNull(approvedResult);
        assertEquals("3 agents done", approvedResult.status);
        assertEquals("none", approvalService.getStatus("session-1"));
    }

    @Test
    @DisplayName("Reject unblocks and returns null")
    void testRejectFlow() throws Exception {
        RoundResult result = createTestResult("test round");

        CompletableFuture<RoundResult> futureResult = CompletableFuture.supplyAsync(() -> {
            try {
                return approvalService.submitForApproval(result, "session-reject", 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        Thread.sleep(100);
        assertEquals("pending", approvalService.getStatus("session-reject"));

        // Reject with reason
        boolean rejected = approvalService.reject("session-reject", "角色反应不符合设定");
        assertTrue(rejected);

        RoundResult rejectedResult = futureResult.get(3, TimeUnit.SECONDS);
        assertNull(rejectedResult);
        assertEquals("none", approvalService.getStatus("session-reject"));
    }

    @Test
    @DisplayName("Timeout auto-rejects after specified duration")
    void testTimeout() throws Exception {
        RoundResult result = createTestResult("timeout test");

        // Very short timeout (1 second)
        long start = System.currentTimeMillis();
        CompletableFuture<RoundResult> futureResult = CompletableFuture.supplyAsync(() -> {
            try {
                return approvalService.submitForApproval(result, "session-timeout", 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        RoundResult timeoutResult = futureResult.get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(timeoutResult, "Should return null on timeout");
        assertTrue(elapsed >= 900, "Should wait at least close to timeout duration");
        assertEquals("none", approvalService.getStatus("session-timeout"));
    }

    @Test
    @DisplayName("Approve on non-existent session returns false")
    void testApproveNonexistent() {
        assertFalse(approvalService.approve("no-such-session"));
    }

    @Test
    @DisplayName("Reject on non-existent session returns false")
    void testRejectNonexistent() {
        assertFalse(approvalService.reject("no-such-session", "reason"));
    }

    @Test
    @DisplayName("Multiple rejections on same session show last reason")
    void testDoubleReject() throws Exception {
        RoundResult result = createTestResult("double reject");

        CompletableFuture<RoundResult> futureResult = CompletableFuture.supplyAsync(() -> {
            try {
                return approvalService.submitForApproval(result, "session-double", 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        Thread.sleep(100);

        // First rejection
        approvalService.reject("session-double", "first reason");
        RoundResult rejectedResult = futureResult.get(3, TimeUnit.SECONDS);
        assertNull(rejectedResult);

        // Second reject on same (already consumed) session returns false
        assertFalse(approvalService.reject("session-double", "second reason"));
    }

    @Test
    @DisplayName("getDetailedStatus returns elapsed time")
    void testDetailedStatus() throws Exception {
        RoundResult result = createTestResult("detailed");

        CompletableFuture<RoundResult> futureResult = CompletableFuture.supplyAsync(() -> {
            try {
                return approvalService.submitForApproval(result, "session-detailed", 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        Thread.sleep(200);

        Map<String, Object> detail = approvalService.getDetailedStatus("session-detailed");
        assertEquals("session-detailed", detail.get("session_id"));
        assertEquals("pending", detail.get("status"));
        assertTrue(((Number) detail.get("elapsed_seconds")).longValue() >= 0);

        approvalService.approve("session-detailed");
        futureResult.get(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("getDetailedStatus on unknown session returns 'none'")
    void testDetailedStatusNonexistent() {
        Map<String, Object> detail = approvalService.getDetailedStatus("unknown");
        assertEquals("unknown", detail.get("session_id"));
        assertEquals("none", detail.get("status"));
    }

    @Test
    @DisplayName("getPendingResult returns the submitted result")
    void testGetPendingResult() throws Exception {
        RoundResult result = createTestResult("pending access");

        CompletableFuture<RoundResult> futureResult = CompletableFuture.supplyAsync(() -> {
            try {
                return approvalService.submitForApproval(result, "session-pending", 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        Thread.sleep(100);

        RoundResult pending = approvalService.getPendingResult("session-pending");
        assertNotNull(pending);
        assertEquals("pending access", pending.status);

        approvalService.approve("session-pending");
        futureResult.get(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("getPendingResult on consumed session returns null")
    void testGetPendingResultAfterConsumed() {
        assertNull(approvalService.getPendingResult("consumed"));
    }

    // ════════════════════════════════════════════════════════════

    private static RoundResult createTestResult(String status) {
        return new RoundResult(
            status,
            List.of(Map.of("agent_name", "Alice", "content", "Hello")),
            Map.of("narration", "Alice speaks"),
            "test reasoning",
            Map.of("total_round_time_ms", 1000)
        );
    }
}
