package com.roleplay.engine.simulation.agentruntime;

/** A registry-backed skill grounded to a currently valid target. */
public record SkillCandidate(SkillDefinition definition, String targetId) {
    public SkillCandidate {
        if (definition == null) throw new IllegalArgumentException("definition required");
        targetId = targetId == null ? "" : targetId;
    }

    public String skillId() {
        return definition.id();
    }
}
