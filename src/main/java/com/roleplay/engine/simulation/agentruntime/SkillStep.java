package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionType;

import java.util.Map;

/** One registered atomic action inside a reusable skill. */
public record SkillStep(ActionType action,
                        TargetSource targetSource,
                        String targetParameter,
                        Map<String, Object> parameters) {
    public enum TargetSource { NONE, GOAL_TARGET, GOAL_PARAMETER }

    public SkillStep {
        if (action == null || targetSource == null) throw new IllegalArgumentException("action/targetSource required");
        targetParameter = targetParameter == null ? "" : targetParameter;
        if (targetSource == TargetSource.GOAL_PARAMETER && targetParameter.isBlank()) {
            throw new IllegalArgumentException("targetParameter required for GOAL_PARAMETER");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public String resolveTarget(Goal goal, Map<String, Object> overrides) {
        return switch (targetSource) {
            case NONE -> "";
            case GOAL_TARGET -> goal.targetId();
            case GOAL_PARAMETER -> {
                Object value = overrides.getOrDefault(targetParameter, goal.parameters().get(targetParameter));
                yield value instanceof String text ? text : "";
            }
        };
    }
}
