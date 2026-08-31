package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.Obstacle;
import com.roleplay.engine.simulation.navigation.portal.*;
import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.spatial.Vec3;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MultiFloorNavigationServiceTest {
    @Test
    void sameFloorAndF1ToF3ProduceFloorTaggedDeterministicSteps() {
        List<SemanticPortal> portals = List.of(stairs("s12", "f1", "f2", 20), stairs("s23", "f2", "f3", 20));
        MultiFloorNavigationService service = service(portals, available(portals));
        PathPlan same = service.plan(request(nav("f1", 5, 5), nav("f1", 15, 5), List.of(), NavProfile.humanoid()));
        assertEquals(PathPlan.Status.READY, same.status());
        assertTrue(same.steps().stream().allMatch(step -> step.floorId().equals("f1")));

        PathPlan cross = service.plan(request(nav("f1", 5, 5), nav("f3", 80, 80), List.of(), NavProfile.humanoid()));
        assertEquals(PathPlan.Status.READY, cross.status());
        assertEquals(List.of("s12", "s23"), cross.steps().stream()
                .filter(step -> step.type() == PathStep.Type.USE_PORTAL).map(PathStep::worldObjectId).toList());
        assertEquals("f3", cross.steps().getLast().floorId());
    }

    @Test
    void choosesShorterStaircaseAndReplansAroundBlockedOne() {
        SemanticPortal near = direct("near", 10);
        SemanticPortal far = direct("far", 90);
        List<SemanticPortal> portals = List.of(far, near);
        Map<String, PortalRuntimeState> states = new HashMap<>(available(portals));
        MultiFloorNavigationService service = service(portals, states);
        PathRequest request = request(nav("f1", 0, 10), nav("f2", 0, 10), List.of(), NavProfile.humanoid());
        assertEquals("near", firstPortal(service.plan(request)));
        states.put("near", new PortalRuntimeState("near", PortalRuntimeState.Availability.LOCKED, 1, "blocked"));
        assertEquals("far", firstPortal(service.plan(request)));
        states.put("far", new PortalRuntimeState("far", PortalRuntimeState.Availability.DISABLED, 1, "blocked"));
        assertEquals(PathPlan.Status.UNREACHABLE, service.plan(request).status());
    }

    @Test
    void floorLocalPlannerStillRespectsAgentRadiusAndNeverFallsThroughWalls() {
        List<Obstacle> wall = List.of(new Obstacle(Obstacle.Type.WALL, 45, 0, 10, 100, true, "wall", "f1"));
        PathPlan plan = service(List.of(), Map.of()).plan(request(nav("f1", 10, 50), nav("f1", 90, 50), wall,
                new NavProfile(12, true, true, true)));
        assertEquals(PathPlan.Status.UNREACHABLE, plan.status());
    }

    private static String firstPortal(PathPlan plan) {
        return plan.steps().stream().filter(step -> step.type() == PathStep.Type.USE_PORTAL)
                .findFirst().orElseThrow().worldObjectId();
    }

    private static MultiFloorNavigationService service(List<SemanticPortal> portals, Map<String, PortalRuntimeState> states) {
        return new MultiFloorNavigationService(definition(portals), new PortalRouter(), states);
    }

    private static Map<String, PortalRuntimeState> available(List<SemanticPortal> portals) {
        Map<String, PortalRuntimeState> states = new HashMap<>();
        portals.forEach(portal -> states.put(portal.id(), PortalRuntimeState.available(portal.id())));
        return states;
    }

    private static PathRequest request(NavLocation from, NavLocation to, List<Obstacle> obstacles, NavProfile profile) {
        return new PathRequest("npc", ControlAuthority.AI_AUTONOMOUS, from, to, profile, 100, 100, obstacles);
    }

    private static NavLocation nav(String floor, double x, double z) {
        return new NavLocation("s-" + floor, floor, new Vec3(x, 0, z), -1);
    }

    private static SemanticPortal stairs(String id, String from, String to, double x) {
        return new SemanticPortal(id, SemanticPortal.Kind.STAIRS,
                new PortalEndpoint(from, "s-" + from, new Vec3(x, 0, 20)),
                new PortalEndpoint(to, "s-" + to, new Vec3(x, 3, 20)), true, 10, "", Set.of("acoustic"));
    }

    private static SemanticPortal direct(String id, double x) { return stairs(id, "f1", "f2", x); }

    private static WorldDefinition definition(List<SemanticPortal> portals) {
        List<WorldDefinition.Floor> floors = new ArrayList<>();
        List<WorldDefinition.Surface> surfaces = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String id = "f" + i;
            WorldDefinition.Bounds3 bounds = new WorldDefinition.Bounds3(new Vec3(0, (i - 1) * 3, 0), new Vec3(100, (i - 1) * 3 + 1, 100));
            floors.add(new WorldDefinition.Floor(id, id, (i - 1) * 3, bounds));
            surfaces.add(new WorldDefinition.Surface("s-" + id, id, WorldDefinition.SurfaceKind.FLOOR, bounds, "", Set.of()));
        }
        return new WorldDefinition(new WorldDefinition.Metadata("test", 2, "test", 1), floors, surfaces,
                List.of(), List.of(), portals, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
