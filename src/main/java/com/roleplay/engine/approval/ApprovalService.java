package com.roleplay.engine.approval;

import com.roleplay.engine.service.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Approval gate mechanism for RouterService rounds.
 *
 * <p>In script and werewolf modes, the round pipeline pauses after Arbiter
 * integration and waits for DM (human) approval before persisting. This
 * gives the DM a chance to review, modify, or reject the round output.
 *
 * <p>Features:
 * <ul>
 *   <li>Submit a round result for approval → blocks the calling thread</li>
 *   <li>Approve → round continues to compression and persistence</li>
 *   <li>Reject → roll back to previous round snapshot</li>
 *   <li>Automatic timeout → rejection with "timeout" reason</li>
 * </ul>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** Default timeout before auto-rejection (seconds). */
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    /** Map of session ID → pending approval state. */
    private final ConcurrentHashMap<String, ApprovalState> pendingApprovals = new ConcurrentHashMap<>();

    /**
     * Submit a round result for DM approval.
     * Blocks the calling thread until approved, rejected, or timed out.
     *
     * @param result    the RoundResult to submit
     * @param sessionId the session identifier
     * @return the approved (possibly modified) RoundResult, or null if rejected
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public RouterService.RoundResult submitForApproval(
            RouterService.RoundResult result, String sessionId) throws InterruptedException {
        return submitForApproval(result, sessionId, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Submit with custom timeout.
     */
    public RouterService.RoundResult submitForApproval(
            RouterService.RoundResult result, String sessionId, long timeoutSeconds) throws InterruptedException {
        ApprovalState state = new ApprovalState(result, sessionId);
        ApprovalState existing = pendingApprovals.putIfAbsent(sessionId, state);
        if (existing != null) {
            log.warn("Session {} already has a pending approval, replacing", sessionId);
            pendingApprovals.put(sessionId, state);
        }

        log.info("Approval pending for session {}, round {}. Timeout={}s",
            sessionId, "?", timeoutSeconds);

        // Wait for approval/rejection with timeout
        boolean decided = state.latch.await(timeoutSeconds, TimeUnit.SECONDS);

        if (!decided) {
            // Timeout — auto reject
            log.warn("Approval timed out for session {} after {}s", sessionId, timeoutSeconds);
            pendingApprovals.remove(sessionId);
            state.rejected = true;
            state.rejectReason = "timeout";
            return null;
        }

        pendingApprovals.remove(sessionId);

        if (state.rejected) {
            log.info("Approval rejected for session {}: {}", sessionId, state.rejectReason);
            return null;
        }

        log.info("Approval granted for session {}", sessionId);
        return state.result;
    }

    /**
     * Approve the pending round for a session.
     *
     * @param sessionId the session to approve
     * @return true if there was a pending approval for this session
     */
    public boolean approve(String sessionId) {
        ApprovalState state = pendingApprovals.get(sessionId);
        if (state == null) {
            log.warn("No pending approval for session {}", sessionId);
            return false;
        }
        state.approved = true;
        state.latch.countDown();
        return true;
    }

    /**
     * Reject the pending round for a session.
     *
     * @param sessionId the session to reject
     * @param reason    the reason for rejection
     * @return true if there was a pending approval for this session
     */
    public boolean reject(String sessionId, String reason) {
        ApprovalState state = pendingApprovals.get(sessionId);
        if (state == null) {
            log.warn("No pending approval for session {}", sessionId);
            return false;
        }
        state.rejected = true;
        state.rejectReason = reason != null ? reason : "rejected by DM";
        state.latch.countDown();
        return true;
    }

    /**
     * Get the current phase for a session.
     *
     * @param sessionId the session to query
     * @return "pending" if awaiting approval, or "none" if no pending gateway
     */
    public String getStatus(String sessionId) {
        ApprovalState state = pendingApprovals.get(sessionId);
        if (state == null) {
            return "none";
        }
        if (state.approved) return "approved";
        if (state.rejected) return "rejected";
        return "pending";
    }

    /**
     * Get detailed status for a session.
     */
    public Map<String, Object> getDetailedStatus(String sessionId) {
        ApprovalState state = pendingApprovals.get(sessionId);
        if (state == null) {
            return Map.of("session_id", sessionId, "status", "none");
        }
        return Map.of(
            "session_id", sessionId,
            "status", getStatus(sessionId),
            "elapsed_seconds", Duration.between(state.createdAt, Instant.now()).getSeconds(),
            "reject_reason", state.rejectReason != null ? state.rejectReason : "",
            "approved", state.approved,
            "rejected", state.rejected
        );
    }

    /**
     * Get the pending RoundResult for a session (read-only).
     */
    public RouterService.RoundResult getPendingResult(String sessionId) {
        ApprovalState state = pendingApprovals.get(sessionId);
        return state != null ? state.result : null;
    }

    // ════════════════════════════════════════════════════════════

    /**
     * Internal state for a pending approval.
     */
    private static class ApprovalState {
        final RouterService.RoundResult result;
        final String sessionId;
        final Instant createdAt = Instant.now();
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean approved = false;
        volatile boolean rejected = false;
        volatile String rejectReason = null;

        ApprovalState(RouterService.RoundResult result, String sessionId) {
            this.result = result;
            this.sessionId = sessionId;
        }
    }
}
