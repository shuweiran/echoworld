package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionType;

/** Structured execution failure fed back into utility and replanning. */
public record FailureFeedback(String goalId,
                              String skillId,
                              ActionType action,
                              String code,
                              String message,
                              long worldVersion,
                              long perceptionVersion,
                              long tick) {
    public FailureFeedback {
        goalId = goalId == null ? "" : goalId;
        skillId = skillId == null ? "" : skillId;
        code = code == null ? "" : code;
        message = message == null ? "" : message;
        if (worldVersion < 0 || perceptionVersion < 0 || tick < 0) {
            throw new IllegalArgumentException("failure versions and tick must be non-negative");
        }
    }
}
