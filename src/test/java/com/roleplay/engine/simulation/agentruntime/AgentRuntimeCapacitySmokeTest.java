package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.SimulationWorld;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeCapacitySmokeTest {
    @ParameterizedTest(name = "macro cognitive LOD keeps {0} agents within tick budget")
    @ValueSource(ints = {50, 100, 200})
    void macroCognitiveLodScalesAtTargetProfiles(int count) {
        SimulationWorld world = new SimulationWorld();
        for (int index = 0; index < count; index++) {
            String id = "npc-" + index;
            world.registerAgent(new Agent(new Persona(id), "", null),
                    10 + index % 20, 10 + index / 20, 200, 80);
            GoalManager goals = new GoalManager();
            goals.add(new Goal("ambient-" + index, "AMBIENT", "", 1,
                    Map.of(), 0, 0, Map.of()));
            AgentRuntime runtime = new AgentRuntime(id, goals, new SkillRegistry(),
                    PlannerGateway.NONE, world::enqueueAction, CognitiveLodPolicy.defaults());
            world.registerAgentRuntime(id, runtime, (ignoredWorld, state, tick, version, now) ->
                    new RuntimeInput(tick, now, 1_000, false, List.of(),
                            new PerceptionSnapshot(state.getAgentName(), version, version, tick,
                                    Set.of(), Map.of(), Map.of()),
                            WorkingMemory.empty(), PlannerTrigger.NONE));
        }

        assertTimeout(Duration.ofSeconds(5), world::advanceOneTick);

        assertEquals(count, world.getAgentRuntimeSystem().lastDecisions().size());
        assertTrue(world.getAgentRuntimeSystem().lastDecisions().values().stream().allMatch(decision ->
                decision.lod() == CognitiveLod.MACRO
                        && decision.status() == RuntimeDecision.Status.MACRO_SIMULATED));
    }
}
