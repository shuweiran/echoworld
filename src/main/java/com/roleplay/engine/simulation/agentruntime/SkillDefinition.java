package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Data-oriented reusable behavior that may only reference approved ActionType values. */
public record SkillDefinition(String id,
                              Set<String> goalTypes,
                              ActionType requiredAffordance,
                              boolean targetMustBePerceived,
                              double baseUtility,
                              Map<NeedType, Double> driveWeights,
                              Set<CognitiveLod> allowedLods,
                              List<SkillStep> steps) {
    public SkillDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("skill id required");
        goalTypes = goalTypes == null ? Set.of() : Set.copyOf(goalTypes);
        if (goalTypes.isEmpty() || goalTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
            throw new IllegalArgumentException("at least one goal type required");
        }
        if (!Double.isFinite(baseUtility)) throw new IllegalArgumentException("baseUtility must be finite");
        driveWeights = driveWeights == null ? Map.of() : Map.copyOf(driveWeights);
        for (var entry : driveWeights.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !Double.isFinite(entry.getValue()) || entry.getValue() < 0) {
                throw new IllegalArgumentException("drive weights must be finite and non-negative");
            }
        }
        allowedLods = allowedLods == null ? Set.of() : Set.copyOf(allowedLods);
        if (allowedLods.isEmpty() || allowedLods.contains(CognitiveLod.MACRO)) {
            throw new IllegalArgumentException("skills must allow FULL and/or REDUCED cognition only");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("skill must contain at least one action step");
    }

    public boolean supports(Goal goal, CognitiveLod lod) {
        return goalTypes.contains(goal.type()) && allowedLods.contains(lod);
    }
}
