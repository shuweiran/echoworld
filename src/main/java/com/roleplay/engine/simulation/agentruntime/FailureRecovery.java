package com.roleplay.engine.simulation.agentruntime;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded deterministic failure memory used to penalize repeated dead ends. */
public final class FailureRecovery {
    private final int capacity;
    private final double penaltyPerFailure;
    private final ArrayDeque<FailureFeedback> recent = new ArrayDeque<>();

    public FailureRecovery(int capacity, double penaltyPerFailure) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        if (!Double.isFinite(penaltyPerFailure) || penaltyPerFailure < 0) {
            throw new IllegalArgumentException("penalty must be finite and non-negative");
        }
        this.capacity = capacity;
        this.penaltyPerFailure = penaltyPerFailure;
    }

    public static FailureRecovery defaults() {
        return new FailureRecovery(32, 1.0);
    }

    public void record(FailureFeedback feedback) {
        if (feedback == null) throw new IllegalArgumentException("feedback required");
        if (recent.size() == capacity) recent.removeFirst();
        recent.addLast(feedback);
    }

    public double penalty(String goalId, String skillId) {
        long count = recent.stream()
                .filter(item -> item.goalId().equals(goalId) && item.skillId().equals(skillId))
                .count();
        return count * penaltyPerFailure;
    }

    public List<FailureFeedback> recent() {
        return List.copyOf(recent);
    }
}
