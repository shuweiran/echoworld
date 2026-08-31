package com.roleplay.engine.simulation.agentruntime;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Owns goal activation and deterministic need-driven ordering. */
public final class GoalManager {
    public enum Status { AVAILABLE, ACTIVE, COMPLETED, FAILED }

    private final Map<String, GoalEntry> goals = new LinkedHashMap<>();
    private String activeGoalId;

    public void add(Goal goal) {
        if (goal == null) throw new IllegalArgumentException("goal required");
        if (goals.putIfAbsent(goal.id(), new GoalEntry(goal, Status.AVAILABLE, "")) != null) {
            throw new IllegalArgumentException("duplicate goal id: " + goal.id());
        }
    }

    public Optional<Goal> select(Collection<Need> needs, long tick) {
        if (activeGoalId != null) {
            GoalEntry active = goals.get(activeGoalId);
            if (active != null && active.status == Status.ACTIVE && !active.goal.expiredAt(tick)) {
                return Optional.of(active.goal);
            }
            activeGoalId = null;
        }
        Comparator<Goal> order = Comparator
                .comparingDouble((Goal goal) -> goal.priority(needs)).reversed()
                .thenComparingLong(Goal::createdAtTick)
                .thenComparing(Goal::id);
        Optional<Goal> selected = goals.values().stream()
                .filter(entry -> entry.status == Status.AVAILABLE)
                .map(entry -> entry.goal)
                .filter(goal -> !goal.expiredAt(tick))
                .sorted(order)
                .findFirst();
        selected.ifPresent(goal -> {
            activeGoalId = goal.id();
            goals.computeIfPresent(goal.id(), (id, entry) -> entry.withStatus(Status.ACTIVE, ""));
        });
        return selected;
    }

    public Optional<Goal> activeGoal() {
        GoalEntry entry = activeGoalId == null ? null : goals.get(activeGoalId);
        return entry == null || entry.status != Status.ACTIVE ? Optional.empty() : Optional.of(entry.goal);
    }

    public void completeActive() {
        finishActive(Status.COMPLETED, "");
    }

    public void failActive(String reason) {
        finishActive(Status.FAILED, reason == null ? "" : reason);
    }

    public Status status(String goalId) {
        GoalEntry entry = goals.get(goalId);
        return entry == null ? null : entry.status;
    }

    public String terminalReason(String goalId) {
        GoalEntry entry = goals.get(goalId);
        return entry == null ? "" : entry.reason;
    }

    private void finishActive(Status status, String reason) {
        if (activeGoalId == null) return;
        goals.computeIfPresent(activeGoalId, (id, entry) -> entry.withStatus(status, reason));
        activeGoalId = null;
    }

    private record GoalEntry(Goal goal, Status status, String reason) {
        private GoalEntry withStatus(Status next, String nextReason) {
            return new GoalEntry(goal, next, nextReason);
        }
    }
}
