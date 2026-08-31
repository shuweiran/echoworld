package com.roleplay.engine.simulation.agentruntime;

import java.util.List;

/** Enumerates only registry skills grounded by current perception and affordances. */
public final class CandidateSkillGenerator {
    public List<SkillCandidate> generate(Goal goal,
                                         PerceptionSnapshot perception,
                                         WorkingMemory memory,
                                         SkillRegistry registry,
                                         CognitiveLod lod) {
        if (goal == null || perception == null || memory == null || registry == null || lod == null) {
            throw new IllegalArgumentException("candidate generation context required");
        }
        if (lod == CognitiveLod.MACRO) return List.of();

        String rememberedKey = "goal.target." + goal.type();
        String targetId = goal.targetId().isBlank()
                ? memory.stringFact(rememberedKey).orElse("")
                : goal.targetId();

        return registry.definitions().stream()
                .filter(skill -> skill.supports(goal, lod))
                .filter(skill -> !skill.targetMustBePerceived() || perception.perceives(targetId))
                .filter(skill -> skill.requiredAffordance() == null
                        || perception.offers(targetId, skill.requiredAffordance()))
                .map(skill -> new SkillCandidate(skill, targetId))
                .toList();
    }
}
