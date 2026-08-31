package com.roleplay.engine.simulation.persistence;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.action.ActionType;
import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.action.ActionSource;
import com.roleplay.engine.simulation.spatial.Transform3D;
import com.roleplay.engine.simulation.worldobject.AffordanceDefinition;
import com.roleplay.engine.simulation.worldobject.WorldObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SimulationWorldCheckpointTest {
    @Test
    void restoresRegisteredAgentAndObjectMutableFacts() {
        SimulationWorld original = world();
        original.getState("alice").setX(90);
        original.getState("alice").setY(80);
        original.setObjectState("door", "open", "true");
        WorldCheckpoint checkpoint = original.createCheckpoint("world-1");

        SimulationWorld restored = world();
        restored.restoreCheckpoint(checkpoint);

        assertEquals(90, restored.getState("alice").getX());
        assertEquals(80, restored.getState("alice").getY());
        assertEquals("true", restored.objectState("door", "open"));
        assertEquals(checkpoint.worldVersion(), restored.worldVersion());
    }

    @Test
    void coordinatorPersistsCheckpointAndDurableActionWithoutBlockingWorldMutation() {
        SimulationWorld world = world();
        InMemoryWorldCheckpointStore store = new InMemoryWorldCheckpointStore();
        try (WorldPersistenceCoordinator coordinator = new WorldPersistenceCoordinator("world-2", world, store, 10)) {
            coordinator.checkpointNow();
            var future = world.enqueueAction(new ActionIntent("open-persisted", "alice", ActionSource.AI_PLANNER,
                    ActionType.OPEN, "door", 0, 0, Map.of()));
            world.getActionDispatcher().drain(world, System.currentTimeMillis());
            assertEquals("OPEN", future.join().code());
        }

        assertEquals("world-2", store.loadLatest("world-2").orElseThrow().worldId());
        assertFalse(store.eventsAfter("world-2", -1).isEmpty());
    }

    private SimulationWorld world() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("alice"), "", null), 1, 2, 200, 80);
        world.registerWorldObject(new WorldObject("door", "DOOR", Transform3D.ground(5, 5),
                Map.of(ActionType.OPEN, new AffordanceDefinition(ActionType.OPEN, 20, 0, 1, Map.of())), Set.of()));
        world.setObjectState("door", "open", "false");
        return world;
    }
}
