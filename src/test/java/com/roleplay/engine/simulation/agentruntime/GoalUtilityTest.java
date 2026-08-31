package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalUtilityTest {
    @Test
    void needPressureDeterministicallyActivatesHighestValueGoal() {
        GoalManager manager = new GoalManager();
        Goal explore = goal("explore", "EXPLORE", 1.0, Map.of(NeedType.CURIOSITY, 1.0));
        Goal socialize = goal("socialize", "TALK", 0.2, Map.of(NeedType.SOCIAL, 2.0));
        manager.add(explore);
        manager.add(socialize);

        Goal selected = manager.select(List.of(
                new Need(NeedType.SOCIAL, 0.9, 0.5, 1.0),
                new Need(NeedType.CURIOSITY, 0.2, 0.5, 1.0)), 2).orElseThrow();

        assertEquals("socialize", selected.id());
        assertEquals(GoalManager.Status.ACTIVE, manager.status("socialize"));
    }

    @Test
    void utilityTieBreaksBySkillIdAndFailurePenaltyChangesNextChoice() {
        Goal goal = goal("g", "WAIT", 0.5, Map.of());
        SkillCandidate beta = new SkillCandidate(skill("beta", 1.0), "");
        SkillCandidate alpha = new SkillCandidate(skill("alpha", 1.0), "");
        PerceptionSnapshot perception = perception();
        FailureRecovery recovery = new FailureRecovery(8, 1.0);
        UtilityScorer scorer = new UtilityScorer();

        assertEquals("alpha", scorer.rank(List.of(beta, alpha), goal, List.of(), perception,
                WorkingMemory.empty(), recovery).getFirst().skillId());

        recovery.record(new FailureFeedback("g", "alpha", ActionType.WAIT, "BLOCKED", "",
                1, 1, 1));
        assertEquals("beta", scorer.rank(List.of(beta, alpha), goal, List.of(), perception,
                WorkingMemory.empty(), recovery).getFirst().skillId());
    }

    private Goal goal(String id, String type, double priority, Map<NeedType, Double> weights) {
        return new Goal(id, type, "", priority, weights, 0, 0, Map.of());
    }

    private SkillDefinition skill(String id, double utility) {
        return new SkillDefinition(id, Set.of("WAIT"), null, false, utility, Map.of(),
                Set.of(CognitiveLod.FULL),
                List.of(new SkillStep(ActionType.WAIT, SkillStep.TargetSource.NONE, "", Map.of())));
    }

    private PerceptionSnapshot perception() {
        return new PerceptionSnapshot("agent", 1, 1, 1, Set.of(), Map.of(), Map.of());
    }
}
