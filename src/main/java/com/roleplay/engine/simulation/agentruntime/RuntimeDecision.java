package com.roleplay.engine.simulation.agentruntime;

import java.util.List;

/** Explainable result of one non-blocking cognition tick. */
public record RuntimeDecision(Status status,
                              CognitiveLod lod,
                              String goalId,
                              String skillId,
                              String code,
                              boolean plannerUsed,
                              List<ScoredSkill> rankedCandidates) {
    public enum Status {
        DEFERRED,
        NO_GOAL,
        NO_CANDIDATE,
        MACRO_SIMULATED,
        ACTION_RUNNING,
        SKILL_SUCCEEDED,
        REPLAN_REQUIRED,
        RUNTIME_ERROR
    }

    public RuntimeDecision {
        if (status == null || lod == null) throw new IllegalArgumentException("status/lod required");
        goalId = goalId == null ? "" : goalId;
        skillId = skillId == null ? "" : skillId;
        code = code == null ? "" : code;
        rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
    }
}
