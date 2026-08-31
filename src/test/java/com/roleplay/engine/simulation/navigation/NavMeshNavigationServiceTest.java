package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.spatial.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NavMeshNavigationServiceTest {
    private final BakedNavMesh mesh = new BakedNavMesh(List.of(
            node(1, "floor-0", 0), node(2, "floor-0", 10), node(3, "floor-0", 20),
            node(4, "floor-1", 20)), List.of(
            new BakedNavMesh.Link(1, 2, 10, 20, true),
            new BakedNavMesh.Link(2, 3, 10, 20, true)));

    @Test
    void plansDeterministicallyAcrossBakedPolygonAdjacency() {
        PathPlan plan = new NavMeshNavigationService(mesh).plan(request(
                new NavLocation("main", "floor-0", new Vec3(1, 0, 0), 1),
                new NavLocation("main", "floor-0", new Vec3(19, 0, 0), 3),
                new NavProfile(12, true, true, true), ControlAuthority.AI_AUTONOMOUS));

        assertEquals(PathPlan.Status.READY, plan.status());
        assertEquals(NavMeshNavigationService.BACKEND, plan.backend());
        assertEquals(List.of(new Vec3(10, 0, 0), new Vec3(20, 0, 0), new Vec3(19, 0, 0)),
                plan.steps().stream().map(PathStep::target).toList());
    }

    @Test
    void rejectsInsufficientClearanceAndCrossFloorQueries() {
        NavMeshNavigationService service = new NavMeshNavigationService(mesh);
        assertEquals(PathPlan.Status.UNREACHABLE, service.plan(request(
                nav(1, "floor-0", 0), nav(3, "floor-0", 20),
                new NavProfile(21, true, true, true), ControlAuthority.AI_AUTONOMOUS)).status());
        PathPlan crossFloor = service.plan(request(nav(3, "floor-0", 20), nav(4, "floor-1", 20),
                NavProfile.humanoid(), ControlAuthority.AI_AUTONOMOUS));
        assertEquals(PathPlan.Status.UNREACHABLE, crossFloor.status());
        assertEquals("cross-floor route requires semantic portal planning", crossFloor.reason());
    }

    @Test
    void playerAuthorityNeverEntersServerAiPlanner() {
        PathPlan plan = new NavMeshNavigationService(mesh).plan(request(nav(1, "floor-0", 0),
                nav(2, "floor-0", 10), NavProfile.humanoid(), ControlAuthority.PLAYER_INPUT));
        assertEquals(PathPlan.Status.REJECTED, plan.status());
    }

    private BakedNavMesh.Node node(long ref, String floor, double x) {
        return new BakedNavMesh.Node(ref, "main", floor, new Vec3(x, 0, 0));
    }

    private NavLocation nav(long ref, String floor, double x) {
        return new NavLocation("main", floor, new Vec3(x, 0, 0), ref);
    }

    private PathRequest request(NavLocation from, NavLocation to, NavProfile profile, ControlAuthority authority) {
        return new PathRequest("npc", authority, from, to, profile, 100, 100, List.of());
    }
}
