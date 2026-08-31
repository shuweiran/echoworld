package com.roleplay.engine.simulation.agentruntime;

import java.util.Map;

/** Validated registry skill selected for one agent and one goal. */
public record SkillPlan(String id,
                        String agentId,
                        Goal goal,
                        SkillDefinition skill,
                        String groundedTargetId,
                        long worldVersion,
                        long perceptionVersion,
                        Map<String, Object> parameterOverrides) {
    public SkillPlan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("plan id required");
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        if (goal == null || skill == null) throw new IllegalArgumentException("goal/skill required");
        if (worldVersion < 0 || perceptionVersion < 0) {
            throw new IllegalArgumentException("plan versions must be non-negative");
        }
        groundedTargetId = groundedTargetId == null ? "" : groundedTargetId;
        parameterOverrides = parameterOverrides == null ? Map.of() : Map.copyOf(parameterOverrides);
    }
}
