package com.roleplay.engine.simulation.agentruntime;

import java.util.Map;

/** Auditable utility result; breakdown is stable for tests and metrics. */
public record ScoredSkill(SkillCandidate candidate, double score, Map<String, Double> breakdown) {
    public ScoredSkill {
        if (candidate == null) throw new IllegalArgumentException("candidate required");
        if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
        breakdown = breakdown == null ? Map.of() : Map.copyOf(breakdown);
    }

    public String skillId() {
        return candidate.skillId();
    }
}
