package com.roleplay.engine.simulation.agentruntime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic and explainable scoring before any optional semantic planner. */
public final class UtilityScorer {
    public ScoredSkill score(SkillCandidate candidate,
                             Goal goal,
                             Collection<Need> needs,
                             PerceptionSnapshot perception,
                             WorkingMemory memory,
                             FailureRecovery failures) {
        if (candidate == null || goal == null || needs == null || perception == null
                || memory == null || failures == null) {
            throw new IllegalArgumentException("utility context required");
        }
        SkillDefinition skill = candidate.definition();
        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("base", skill.baseUtility());
        parts.put("goal", goal.basePriority());

        double drives = 0;
        for (Need need : needs) {
            drives += need.drive().score() * skill.driveWeights().getOrDefault(need.type(), 0.0);
        }
        parts.put("drives", drives);

        double grounding = candidate.targetId().isBlank() ? 0
                : perception.perceives(candidate.targetId()) ? 0.15 : 0;
        if (skill.requiredAffordance() != null
                && perception.offers(candidate.targetId(), skill.requiredAffordance())) {
            grounding += 0.25;
        }
        parts.put("grounding", grounding);

        double failurePenalty = failures.penalty(goal.id(), skill.id());
        parts.put("failurePenalty", -failurePenalty);
        double total = parts.values().stream().mapToDouble(Double::doubleValue).sum();
        return new ScoredSkill(candidate, total, parts);
    }

    public List<ScoredSkill> rank(Collection<SkillCandidate> candidates,
                                  Goal goal,
                                  Collection<Need> needs,
                                  PerceptionSnapshot perception,
                                  WorkingMemory memory,
                                  FailureRecovery failures) {
        List<ScoredSkill> ranked = new ArrayList<>();
        for (SkillCandidate candidate : candidates) {
            ranked.add(score(candidate, goal, needs, perception, memory, failures));
        }
        ranked.sort(Comparator.comparingDouble(ScoredSkill::score).reversed()
                .thenComparing(ScoredSkill::skillId)
                .thenComparing(item -> item.candidate().targetId()));
        return List.copyOf(ranked);
    }
}
