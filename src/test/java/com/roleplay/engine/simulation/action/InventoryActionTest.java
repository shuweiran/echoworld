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

class InventoryActionTest {
    @Test
    void pickUpIsExclusiveAndPutDownUsesActorPosition() {
        SimulationWorld world = worldWithAgents();
        world.registerWorldObject(item("key", Map.of()));

        var first = world.enqueueAction(intent("pick-a", "alice", ActionType.PICK_UP, "key"));
        var second = world.enqueueAction(intent("pick-b", "bob", ActionType.PICK_UP, "key"));
        world.getActionDispatcher().drain(world, 1_000);

        assertEquals(ActionResult.Status.SUCCEEDED, first.join().status());
        assertNotEquals(ActionResult.Status.SUCCEEDED, second.join().status());
        assertEquals("alice", world.carriedBy("key"));
        assertEquals(1, ((java.util.List<?>) world.gameplaySnapshot("alice").get("inventory")).size());
        assertEquals(world.getState("alice").getX(), world.transformOf("key").position().x(), 0.001);

        world.getState("alice").setX(45);
        world.getState("alice").setY(55);
        var drop = world.enqueueAction(intent("drop-a", "alice", ActionType.PUT_DOWN, "key"));
        world.getActionDispatcher().drain(world, 1_100);
        assertEquals(ActionResult.Status.SUCCEEDED, drop.join().status());
        assertEquals("", world.carriedBy("key"));
        assertEquals(45, world.transformOf("key").position().x(), 0.001);
        assertEquals(55, world.transformOf("key").position().z(), 0.001);
    }

    @Test
    void useAppliesWhitelistedMetricEffectsAndConsumesItem() {
        SimulationWorld world = worldWithAgents();
        world.getState("alice").gameplay().set("stamina", 20);
        world.registerWorldObject(item("drink", Map.of("useEffects", Map.of("stamina", 15), "consumable", true)));
        world.enqueueAction(intent("pick", "alice", ActionType.PICK_UP, "drink"));
        world.getActionDispatcher().drain(world, 1_000);

        var use = world.enqueueAction(intent("use", "alice", ActionType.USE, "drink"));
        world.getActionDispatcher().drain(world, 1_100);
        assertEquals(ActionResult.Status.SUCCEEDED, use.join().status());
        assertEquals(35, world.getState("alice").gameplay().value("stamina", -1));
        assertFalse((Boolean) world.snapshotNow().worldObjects().getFirst().get("active"));
        assertTrue(world.getWorldObjects().containsKey("drink"), "consumed tombstone must survive for checkpoint/clients");
    }

    @Test
    void otherActorCannotUseHeldItem() {
        SimulationWorld world = worldWithAgents();
        world.getState("bob").gameplay().set("stamina", 20);
        world.registerWorldObject(item("drink", Map.of("useEffects", Map.of("stamina", 15), "consumable", true)));
        world.enqueueAction(intent("pick-owner", "alice", ActionType.PICK_UP, "drink"));
        world.getActionDispatcher().drain(world, 1_000);

        var stealUse = world.enqueueAction(intent("use-other", "bob", ActionType.USE, "drink"));
        world.getActionDispatcher().drain(world, 1_100);
        assertNotEquals(ActionResult.Status.SUCCEEDED, stealUse.join().status());
        assertEquals(20, world.getState("bob").gameplay().value("stamina", -1));
        assertEquals("alice", world.carriedBy("drink"));
    }

    @Test
    void checkpointRestoresInventoryAndQuantitativeState() {
        SimulationWorld source = worldWithAgents();
        source.registerWorldObject(item("book", Map.of("useEffects", Map.of("insight", 1))));
        source.getState("alice").gameplay().set("focus", 42);
        source.enqueueAction(intent("pick-checkpoint", "alice", ActionType.PICK_UP, "book"));
        source.getActionDispatcher().drain(source, 1_000);
        var checkpoint = source.createCheckpoint("world-a");

        SimulationWorld restored = worldWithAgents();
        restored.registerWorldObject(item("book", Map.of("useEffects", Map.of("insight", 1))));
        restored.restoreCheckpoint(checkpoint);
        assertEquals("alice", restored.carriedBy("book"));
        assertEquals(42, restored.getState("alice").gameplay().value("focus", -1));
        assertEquals(1, ((java.util.List<?>) restored.gameplaySnapshot("alice").get("inventory")).size());
    }

    @Test
    void checkpointRestoresConsumedTombstoneWithoutResurrection() {
        SimulationWorld source = worldWithAgents();
        source.registerWorldObject(item("ration", Map.of("useEffects", Map.of("stamina", 1), "consumable", true)));
        source.enqueueAction(intent("pick-ration", "alice", ActionType.PICK_UP, "ration"));
        source.getActionDispatcher().drain(source, 1_000);
        source.enqueueAction(intent("use-ration", "alice", ActionType.USE, "ration"));
        source.getActionDispatcher().drain(source, 1_100);
        var checkpoint = source.createCheckpoint("world-consumed");

        SimulationWorld restored = worldWithAgents();
        restored.registerWorldObject(item("ration", Map.of("useEffects", Map.of("stamina", 1), "consumable", true)));
        restored.restoreCheckpoint(checkpoint);
        Map<String, Object> snapshot = restored.snapshotNow().worldObjects().getFirst();
        assertEquals(false, snapshot.get("active"));
        assertEquals("true", ((Map<?, ?>) snapshot.get("state")).get("consumed"));
        assertTrue(((java.util.List<?>) restored.gameplaySnapshot("alice").get("inventory")).isEmpty());
        assertFalse(restored.affordanceAvailable("alice", "ration", ActionType.USE));
    }

    private static WorldObject item(String id, Map<String, Object> extra) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>(extra);
        properties.put("portable", true);
        properties.put("displayName", id);
        properties.put("floorId", "ground");
        Map<ActionType, AffordanceDefinition> actions = Map.of(
                ActionType.PICK_UP, affordance(ActionType.PICK_UP),
                ActionType.PUT_DOWN, affordance(ActionType.PUT_DOWN),
                ActionType.USE, affordance(ActionType.USE));
        return new WorldObject(id, "ITEM", Transform3D.ground(12, 10), actions, Set.of("item"), properties);
    }

    private static AffordanceDefinition affordance(ActionType action) {
        return new AffordanceDefinition(action, 30, 0, 1, Map.of());
    }

    private static ActionIntent intent(String id, String actor, ActionType action, String target) {
        return new ActionIntent(id, actor, ActionSource.PLAYER_INPUT, action, target, 0, 0, Map.of());
    }

    private static SimulationWorld worldWithAgents() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("alice"), "", null), 10, 10, 200, 80);
        world.registerAgent(new Agent(new Persona("bob"), "", null), 11, 10, 200, 80);
        world.getState("alice").setPlayerControlled(true);
        world.getState("bob").setPlayerControlled(true);
        return world;
    }
}
