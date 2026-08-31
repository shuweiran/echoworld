package com.roleplay.engine.simulation.worlddefinition;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.navigation.NavProfile;
import com.roleplay.engine.simulation.navigation.portal.*;
import com.roleplay.engine.simulation.spatial.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimulationWorldDefinitionIntegrationTest {
    @Test
    void validatedDefinitionControlsBoundsSpawnMembershipAndPortalRouting() {
        WorldDefinition definition = definition();
        SimulationWorld world = new SimulationWorld();
        world.loadWorldDefinition(definition);
        world.registerAgentAtSpawn(new Agent(new Persona("alice"), "", null), "spawn-f1", 200, 80);

        assertSame(definition, world.getWorldDefinition());
        assertEquals(100, world.getWorldWidth());
        assertEquals("f1", world.getState("alice").navLocation().floorId());
        PortalRoute route = world.routeAcrossFloors(
                new PortalEndpoint("f1", "s1", new Vec3(10, 0, 10)),
                new PortalEndpoint("f2", "s2", new Vec3(90, 10, 90)), NavProfile.humanoid());
        assertEquals(PortalRoute.Status.READY, route.status());
        assertTrue(route.legs().stream().anyMatch(leg -> leg.type() == PortalRoute.Type.PORTAL_TRAVERSAL));

        world.setPortalState(new PortalRuntimeState("stairs", PortalRuntimeState.Availability.LOCKED, 1, "blocked"));
        assertEquals(PortalRoute.Status.UNREACHABLE, world.routeAcrossFloors(
                new PortalEndpoint("f1", "s1", new Vec3(10, 0, 10)),
                new PortalEndpoint("f2", "s2", new Vec3(90, 10, 90)), NavProfile.humanoid()).status());
    }

    private WorldDefinition definition() {
        WorldDefinition.Bounds3 floor1 = new WorldDefinition.Bounds3(new Vec3(0, 0, 0), new Vec3(100, 1, 100));
        WorldDefinition.Bounds3 floor2 = new WorldDefinition.Bounds3(new Vec3(0, 10, 0), new Vec3(100, 11, 100));
        WorldDefinition.Surface s1 = new WorldDefinition.Surface("s1", "f1", WorldDefinition.SurfaceKind.FLOOR,
                floor1, "", Set.of());
        WorldDefinition.Surface s2 = new WorldDefinition.Surface("s2", "f2", WorldDefinition.SurfaceKind.FLOOR,
                floor2, "", Set.of());
        SemanticPortal stairs = new SemanticPortal("stairs", SemanticPortal.Kind.STAIRS,
                new PortalEndpoint("f1", "s1", new Vec3(50, 0, 50)),
                new PortalEndpoint("f2", "s2", new Vec3(50, 10, 50)), true, 10, "", Set.of());
        return new WorldDefinition(new WorldDefinition.Metadata("test-world", 2, "test", 1),
                List.of(new WorldDefinition.Floor("f1", "first", 0, floor1),
                        new WorldDefinition.Floor("f2", "second", 10, floor2)),
                List.of(s1, s2), List.of(), List.of(), List.of(stairs), List.of(),
                List.of(new WorldDefinition.SpawnPoint("spawn-f1", "f1", "s1", new Vec3(10, 0, 10), Set.of())),
                List.of(), List.of(), List.of());
    }
}
