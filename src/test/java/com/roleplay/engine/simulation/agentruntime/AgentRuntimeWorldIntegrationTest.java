package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.action.ActionPhase;
import com.roleplay.engine.simulation.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AgentRuntimeWorldIntegrationTest {
    @Test
    void utilitySkillFlowsThroughAuthoritativeWorldTickAndActionQueue() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("alice"), "", null), 10, 10, 200, 80);

        GoalManager goals = new GoalManager();
        goals.add(new Goal("idle", "IDLE", "", 1, Map.of(), 0, 0, Map.of()));
        SkillRegistry skills = new SkillRegistry();
        skills.register(new SkillDefinition("wait", Set.of("IDLE"), null, false, 1,
                Map.of(), Set.of(CognitiveLod.FULL, CognitiveLod.REDUCED),
                List.of(new SkillStep(ActionType.WAIT, SkillStep.TargetSource.NONE, "", Map.of()))));
        AgentRuntime runtime = new AgentRuntime("alice", goals, skills, PlannerGateway.NONE,
                world::enqueueAction, CognitiveLodPolicy.defaults());

        world.registerAgentRuntime("alice", runtime, (ignoredWorld, state, tick, version, now) ->
                new RuntimeInput(tick, now, 0, false, List.of(),
                        new PerceptionSnapshot("alice", version, version, tick,
                                Set.of(), Map.of(), Map.of()),
                        WorkingMemory.empty(), PlannerTrigger.NONE));

        world.advanceOneTick();

        RuntimeDecision submitted = world.getAgentRuntimeSystem().lastDecisions().get("alice");
        assertEquals(RuntimeDecision.Status.ACTION_RUNNING, submitted.status());
        assertEquals("wait", submitted.skillId());
        assertEquals(ActionPhase.SUCCESS, world.getActionDispatcher().states().getFirst().phase());

        world.advanceOneTick();

        assertEquals(RuntimeDecision.Status.SKILL_SUCCEEDED,
                world.getAgentRuntimeSystem().lastDecisions().get("alice").status());
        assertEquals(GoalManager.Status.COMPLETED, goals.status("idle"));
    }

    @Test
    void oneBrokenRuntimeCannotAbortOtherAgentsOrTheWorldTick() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("broken"), "", null), 1, 1, 200, 80);
        world.registerAgent(new Agent(new Persona("healthy"), "", null), 2, 2, 200, 80);
        world.registerAgentRuntime("broken", macroRuntime("broken"), (ignored, state, tick, version, now) ->
                macroInput("another-agent", tick, version, now));
        world.registerAgentRuntime("healthy", macroRuntime("healthy"), (ignored, state, tick, version, now) ->
                macroInput("healthy", tick, version, now));

        assertDoesNotThrow(world::advanceOneTick);
        assertEquals(RuntimeDecision.Status.RUNTIME_ERROR,
                world.getAgentRuntimeSystem().lastDecisions().get("broken").status());
        assertEquals(RuntimeDecision.Status.MACRO_SIMULATED,
                world.getAgentRuntimeSystem().lastDecisions().get("healthy").status());
    }

    private AgentRuntime macroRuntime(String id) {
        GoalManager goals = new GoalManager();
        goals.add(new Goal("ambient-" + id, "AMBIENT", "", 1, Map.of(), 0, 0, Map.of()));
        return new AgentRuntime(id, goals, new SkillRegistry(), PlannerGateway.NONE,
                intent -> java.util.concurrent.CompletableFuture.failedFuture(
                        new AssertionError("macro LOD must not submit actions")), CognitiveLodPolicy.defaults());
    }

    private RuntimeInput macroInput(String perceptionAgentId, long tick, long version, long now) {
        return new RuntimeInput(tick, now, 1_000, false, List.of(),
                new PerceptionSnapshot(perceptionAgentId, version, version, tick,
                        Set.of(), Map.of(), Map.of()), WorkingMemory.empty(), PlannerTrigger.NONE);
    }
}
