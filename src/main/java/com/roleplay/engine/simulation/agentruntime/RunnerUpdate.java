package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionResult;
import com.roleplay.engine.simulation.action.ActionType;

/** Non-blocking SkillRunner progress observed by AgentRuntime each tick. */
public record RunnerUpdate(Status status,
                           String goalId,
                           String skillId,
                           ActionType action,
                           String code,
                           ActionResult actionResult) {
    public enum Status { IDLE, RUNNING, SUCCEEDED, FAILED }

    public RunnerUpdate {
        if (status == null) throw new IllegalArgumentException("status required");
        goalId = goalId == null ? "" : goalId;
        skillId = skillId == null ? "" : skillId;
        code = code == null ? "" : code;
    }

    public static RunnerUpdate idle() {
        return new RunnerUpdate(Status.IDLE, "", "", null, "IDLE", null);
    }
}
