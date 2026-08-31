package com.roleplay.engine.simulation.agentruntime;

import java.util.Map;

/** Server-owned objective. LLMs may propose one, but cannot activate it directly. */
public record Goal(String id,
                   String type,
                   String targetId,
                   double basePriority,
                   Map<NeedType, Double> driveWeights,
                   long createdAtTick,
                   long expiresAtTick,
                   Map<String, Object> parameters) {
    public Goal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("goal id required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("goal type required");
        if (!Double.isFinite(basePriority)) throw new IllegalArgumentException("basePriority must be finite");
        if (createdAtTick < 0 || expiresAtTick < 0) throw new IllegalArgumentException("ticks must be non-negative");
        targetId = targetId == null ? "" : targetId;
        driveWeights = driveWeights == null ? Map.of() : Map.copyOf(driveWeights);
        for (var entry : driveWeights.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !Double.isFinite(entry.getValue()) || entry.getValue() < 0) {
                throw new IllegalArgumentException("drive weights must be finite and non-negative");
            }
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public boolean expiredAt(long tick) {
        return expiresAtTick > 0 && tick > expiresAtTick;
    }

    public double priority(Iterable<Need> needs) {
        double score = basePriority;
        for (Need need : needs) {
            score += need.drive().score() * driveWeights.getOrDefault(need.type(), 0.0);
        }
        return score;
    }
}
