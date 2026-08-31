package com.roleplay.engine.simulation.action;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.spatial.Transform3D;
import com.roleplay.engine.simulation.worldobject.AffordanceDefinition;
import com.roleplay.engine.simulation.worldobject.WorldObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActionLifecycleTest {
    @Test
    void waitAdvancesAcrossTicksThroughAuthoritativeLifecycle() {
        SimulationWorld world = worldWithAgent();
        ActionIntent intent = new ActionIntent("wait-fsm", "alice", ActionSource.AI_PLANNER,
                ActionType.WAIT, "", 0, 0, Map.of("durationMillis", 100));
        var future = world.enqueueAction(intent);

        world.getActionDispatcher().drain(world, 1_000);
        assertFalse(future.isDone());
        assertEquals(ActionPhase.EXECUTING, world.getActionDispatcher().state("wait-fsm").phase());

        world.getActionDispatcher().drain(world, 1_101);
        assertEquals(ActionResult.Status.SUCCEEDED, future.join().status());
        assertEquals(ActionPhase.SUCCESS, world.getActionDispatcher().state("wait-fsm").phase());
    }

    @Test
    void openActionUsesAffordanceAndMutatesWorldObjectState() {
        SimulationWorld world = worldWithAgent();
        world.registerWorldObject(new WorldObject("door", "DOOR", Transform3D.ground(20, 10),
                Map.of(ActionType.OPEN, new AffordanceDefinition(ActionType.OPEN, 30, 0, 1,
                        Map.of("open", "false"))), Set.of("portal")));
        world.setObjectState("door", "open", "false");
        ActionIntent intent = new ActionIntent("open-door", "alice", ActionSource.AI_PLANNER,
                ActionType.OPEN, "door", 0, 0, Map.of());

        var future = world.enqueueAction(intent);
        world.getActionDispatcher().drain(world, 2_000);

        assertEquals(ActionResult.Status.SUCCEEDED, future.join().status());
        assertEquals("true", world.objectState("door", "open"));
        assertEquals(ActionPhase.SUCCESS, world.getActionDispatcher().state("open-door").phase());
        assertFalse(world.getRecentActionEvents().isEmpty());
    }

    @Test
    void unavailableAffordanceStopsBeforeExecutorMutation() {
        SimulationWorld world = worldWithAgent();
        world.registerWorldObject(new WorldObject("far-door", "DOOR", Transform3D.ground(500, 500),
                Map.of(ActionType.OPEN, new AffordanceDefinition(ActionType.OPEN, 20, 0, 1, Map.of())), Set.of()));
        var future = world.enqueueAction(new ActionIntent("far", "alice", ActionSource.AI_PLANNER,
                ActionType.OPEN, "far-door", 0, 0, Map.of()));

        world.getActionDispatcher().drain(world, 3_000);

        assertEquals("AFFORDANCE_UNAVAILABLE", future.join().code());
        assertEquals(ActionPhase.BLOCKED, world.getActionDispatcher().state("far").phase());
        assertNull(world.objectState("far-door", "open"));
    }

    @Test
    void activeActionRevalidatesAndWorldResetCompletesOutstandingFutures() {
        SimulationWorld world = worldWithAgent();
        var active = world.enqueueAction(new ActionIntent("active-wait", "alice", ActionSource.AI_PLANNER,
                ActionType.WAIT, "", 0, 0, Map.of("durationMillis", 10_000)));
        world.getActionDispatcher().drain(world, 1_000);
        world.removeAgent("alice");
        assertEquals("INTERRUPTED", active.join().code());

        world.registerAgent(new Agent(new Persona("alice"), "", null), 10, 10, 200, 80);
        var queued = world.enqueueAction(new ActionIntent("queued-wait", "alice", ActionSource.AI_PLANNER,
                ActionType.WAIT, "", 0, 0, Map.of("durationMillis", 10_000)));
        world.clearAgents();
        assertEquals("INTERRUPTED", queued.join().code());
        assertTrue(world.getActionDispatcher().states().isEmpty());
    }

    private SimulationWorld worldWithAgent() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("alice"), "", null), 10, 10, 200, 80);
        return world;
    }
}
