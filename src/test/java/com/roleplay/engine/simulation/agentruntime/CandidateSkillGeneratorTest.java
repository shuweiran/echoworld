package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateSkillGeneratorTest {
    @Test
    void onlyPerceivedAndCurrentlyAffordedSkillsBecomeCandidates() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("open", ActionType.OPEN, Set.of(CognitiveLod.FULL, CognitiveLod.REDUCED)));
        registry.register(skill("speak", ActionType.SPEAK, Set.of(CognitiveLod.FULL, CognitiveLod.REDUCED)));
        Goal goal = new Goal("talk", "INTERACT", "bob", 1, Map.of(), 0, 0, Map.of());
        PerceptionSnapshot perception = new PerceptionSnapshot("alice", 3, 7, 1,
                Set.of("bob"), Map.of("bob", Set.of(ActionType.SPEAK)), Map.of());

        List<SkillCandidate> candidates = new CandidateSkillGenerator().generate(goal, perception,
                WorkingMemory.empty(), registry, CognitiveLod.FULL);

        assertEquals(List.of("speak"), candidates.stream().map(SkillCandidate::skillId).toList());
    }

    @Test
    void rememberedTargetNeverBypassesCurrentPerception() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("speak", ActionType.SPEAK, Set.of(CognitiveLod.FULL)));
        Goal goal = new Goal("talk", "INTERACT", "", 1, Map.of(), 0, 0, Map.of());
        WorkingMemory memory = new WorkingMemory(1, Map.of("goal.target.INTERACT", "bob"), List.of());
        PerceptionSnapshot perception = new PerceptionSnapshot("alice", 3, 7, 1,
                Set.of(), Map.of("bob", Set.of(ActionType.SPEAK)), Map.of());

        assertEquals(0, new CandidateSkillGenerator().generate(goal, perception, memory, registry,
                CognitiveLod.FULL).size());
    }

    @Test
    void reducedLodUsesOnlySkillsExplicitlyAllowedThere() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("full-only", ActionType.SPEAK, Set.of(CognitiveLod.FULL)));
        registry.register(skill("simple", ActionType.SPEAK,
                Set.of(CognitiveLod.FULL, CognitiveLod.REDUCED)));
        Goal goal = new Goal("talk", "INTERACT", "bob", 1, Map.of(), 0, 0, Map.of());
        PerceptionSnapshot perception = new PerceptionSnapshot("alice", 3, 7, 1,
                Set.of("bob"), Map.of("bob", Set.of(ActionType.SPEAK)), Map.of());

        assertEquals(List.of("simple"), new CandidateSkillGenerator().generate(goal, perception,
                WorkingMemory.empty(), registry, CognitiveLod.REDUCED).stream()
                .map(SkillCandidate::skillId).toList());
    }

    @Test
    void registryRejectsDuplicateSkillIds() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("speak", ActionType.SPEAK, Set.of(CognitiveLod.FULL)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(skill("speak", ActionType.SPEAK, Set.of(CognitiveLod.FULL))));
    }

    private SkillDefinition skill(String id, ActionType affordance, Set<CognitiveLod> lods) {
        return new SkillDefinition(id, Set.of("INTERACT"), affordance, true, 1, Map.of(), lods,
                List.of(new SkillStep(affordance, SkillStep.TargetSource.GOAL_TARGET, "", Map.of())));
    }
}
