package com.roleplay.engine.simulation.agentruntime;

import java.util.Map;

/** Schema-constrained planner output; it can select a skill but cannot invent actions. */
public record PlannerProposal(String skillId,
                              long basedOnWorldVersion,
                              long basedOnPerceptionVersion,
                              Map<String, Object> parameterOverrides,
                              String rationale) {
    public PlannerProposal {
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId required");
        if (basedOnWorldVersion < 0 || basedOnPerceptionVersion < 0) {
            throw new IllegalArgumentException("proposal versions must be non-negative");
        }
        parameterOverrides = parameterOverrides == null ? Map.of() : Map.copyOf(parameterOverrides);
        rationale = rationale == null ? "" : rationale;
    }
}
