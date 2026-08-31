package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.navigation.PathStep;
import com.roleplay.engine.simulation.navigation.portal.*;
import com.roleplay.engine.simulation.spatial.Vec3;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MultiFloorMovementIntegrationTest {
    @Test
    void authoritativeRouteMovesF1ToF3OneConnectorPerTickAndResetsVelocity() {
        SimulationWorld world = world(List.of(stairs("s12", "f1", "f2", 30), stairs("s23", "f2", "f3", 30)));
        AgentState state = spawn(world, "walker", "f1", 20, 20);
        assertTrue(state.setAutonomousTarget(70, 20, "f3", "s-f3"));

        String previousFloor = state.navLocation().floorId();
        int transitions = 0;
        for (int tick = 0; tick < 300 && state.isHasTarget(); tick++) {
            world.advanceOneTick();
            String floor = state.navLocation().floorId();
            if (!floor.equals(previousFloor)) {
                transitions++;
                assertEquals(0.0, state.getVx(), 0.0001);
                assertEquals(0.0, state.getVy(), 0.0001);
                assertFalse(previousFloor.equals("f1") && floor.equals("f3"), "one tick cannot skip a floor");
                previousFloor = floor;
            }
        }

        assertEquals("f3", state.navLocation().floorId());
        assertEquals(2, transitions);
        assertTrue(Math.hypot(state.getX() - 70, state.getY() - 20) < 10);
    }

    @Test
    void closingPlannedStaircaseInvalidatesAndReplansThroughAlternative() {
        SemanticPortal near = stairs("near", "f1", "f2", 45);
        SemanticPortal far = stairs("far", "f1", "f2", 75);
        SimulationWorld world = world(List.of(near, far));
        AgentState state = spawn(world, "walker", "f1", 20, 20);
        state.setAutonomousTarget(25, 20, "f2", "s-f2");
        world.advanceOneTick();
        assertTrue(usesConnector(state, "near"));

        world.setPortalState(new PortalRuntimeState("near", PortalRuntimeState.Availability.CLOSED, 2, "test"));
        assertFalse(state.hasNavigationPlan());
        world.advanceOneTick();
        assertFalse(usesConnector(state, "near"));
        assertTrue(usesConnector(state, "far"));

        for (int tick = 0; tick < 300 && state.isHasTarget(); tick++) world.advanceOneTick();
        assertEquals("f2", state.navLocation().floorId());
        assertTrue(Math.hypot(state.getX() - 25, state.getY() - 20) < 10);
    }

    @Test
    void unavailableAllConnectorsFailsExplicitlyWithoutChangingFloor() {
        SemanticPortal stairs = stairs("only", "f1", "f2", 30);
        SimulationWorld world = world(List.of(stairs));
        AgentState state = spawn(world, "walker", "f1", 20, 20);
        world.setPortalState(new PortalRuntimeState("only", PortalRuntimeState.Availability.LOCKED, 1, "test"));
        state.setAutonomousTarget(70, 20, "f2", "s-f2");

        for (int tick = 0; tick < 20; tick++) world.advanceOneTick();
        assertEquals("f1", state.navLocation().floorId());
        assertTrue(state.hasNavigationPlan(), "unreachable is an explicit attempted plan, not wall-clipping fallback");
        assertTrue(state.getNavigationSteps().isEmpty());
        assertEquals(0.0, state.getVx(), 0.0001);
        assertEquals(0.0, state.getVy(), 0.0001);
    }

    private static boolean usesConnector(AgentState state, String id) {
        return state.getNavigationSteps().stream()
                .anyMatch(step -> step.type() == PathStep.Type.USE_PORTAL && id.equals(step.worldObjectId()));
    }

    static SimulationWorld world(List<SemanticPortal> portals) {
        SimulationWorld world = new SimulationWorld();
        world.loadWorldDefinition(definition(portals));
        world.setCustomObstacles(List.of(), "multi-floor-test");
        return world;
    }

    static AgentState spawn(SimulationWorld world, String name, String floor, double x, double y) {
        String spawn = "spawn-" + floor + "-" + name;
        // Tests register directly so one reusable definition can support arbitrary agent counts.
        world.registerAgent(new Agent(new Persona(name), "", null), x, y, 200, 90);
        AgentState state = world.getState(name);
        state.getSpatial().setNavLocation(new com.roleplay.engine.simulation.spatial.NavLocation(
                "s-" + floor, floor, new Vec3(x, elevation(floor), y), -1));
        return state;
    }

    static SemanticPortal stairs(String id, String from, String to, double x) {
        return new SemanticPortal(id, SemanticPortal.Kind.STAIRS,
                new PortalEndpoint(from, "s-" + from, new Vec3(x, elevation(from), 20)),
                new PortalEndpoint(to, "s-" + to, new Vec3(x, elevation(to), 20)),
                true, 10, "", Set.of("acoustic"));
    }

    static WorldDefinition definition(List<SemanticPortal> portals) {
        List<WorldDefinition.Floor> floors = new ArrayList<>();
        List<WorldDefinition.Surface> surfaces = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String id = "f" + i;
            double elevation = elevation(id);
            WorldDefinition.Bounds3 bounds = new WorldDefinition.Bounds3(
                    new Vec3(0, elevation, 0), new Vec3(100, elevation + 1, 100));
            floors.add(new WorldDefinition.Floor(id, id, elevation, bounds));
            surfaces.add(new WorldDefinition.Surface("s-" + id, id, WorldDefinition.SurfaceKind.FLOOR,
                    bounds, "", Set.of()));
        }
        return new WorldDefinition(new WorldDefinition.Metadata("multi-floor-test", 2, "test", 1),
                floors, surfaces, List.of(), List.of(), portals, List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    static double elevation(String floor) { return (Integer.parseInt(floor.substring(1)) - 1) * 3.0; }
}
