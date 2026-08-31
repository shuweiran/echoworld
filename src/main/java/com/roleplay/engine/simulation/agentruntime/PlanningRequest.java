package com.roleplay.engine.simulation.agentruntime;

import java.util.List;

/** Provider-neutral semantic selection request containing only legal candidates. */
public record PlanningRequest(String agentId,
                              Goal goal,
                              List<ScoredSkill> candidates,
                              long worldVersion,
                              long perceptionVersion,
                              PlannerTrigger trigger) {
    public PlanningRequest {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        if (goal == null || trigger == null) throw new IllegalArgumentException("goal/trigger required");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (candidates.isEmpty()) throw new IllegalArgumentException("planner candidates required");
        if (worldVersion < 0 || perceptionVersion < 0) {
            throw new IllegalArgumentException("planner versions must be non-negative");
        }
    }
}
