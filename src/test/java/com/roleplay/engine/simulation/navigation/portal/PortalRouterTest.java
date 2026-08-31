package com.roleplay.engine.simulation.navigation.portal;

import com.roleplay.engine.simulation.navigation.NavProfile;
import com.roleplay.engine.simulation.navigation.portal.PortalRoute.Leg;
import com.roleplay.engine.simulation.spatial.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRouterTest {
    private final PortalRouter router = new PortalRouter();

    @Test
    void sameSurfaceProducesOnlyBackendHandoffLeg() {
        PortalRoute route = route(endpoint("f1", "s1", 0, 0, 0),
                endpoint("f1", "s1", 3, 4, 0), NavProfile.humanoid(), List.of(), Map.of());

        assertEquals(PortalRoute.Status.READY, route.status());
        assertEquals(1, route.legs().size());
        assertEquals(PortalRoute.Type.FLOOR_TRANSIT, route.legs().getFirst().type());
        assertEquals(5.0, route.totalCost(), 1.0e-9);
    }

    @Test
    void choosesLexicalPortalForEqualCostRegardlessOfInputOrder() {
        SemanticPortal alpha = portal("alpha", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 10, 0, 0), endpoint("f2", "s2", 10, 3, 0), true, 5, "");
        SemanticPortal beta = portal("beta", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 10, 0, 2), endpoint("f2", "s2", 10, 3, 2), true, 5, "");
        PortalEndpoint from = endpoint("f1", "s1", 0, 0, 1);
        PortalEndpoint to = endpoint("f2", "s2", 20, 3, 1);

        PortalRoute expected = route(from, to, NavProfile.humanoid(), List.of(beta, alpha), Map.of());
        assertEquals("alpha", traversedPortal(expected));

        for (int seed = 0; seed < 50; seed++) {
            List<SemanticPortal> shuffled = new ArrayList<>(List.of(alpha, beta));
            Collections.rotate(shuffled, seed % 2);
            assertEquals(expected, route(from, to, NavProfile.humanoid(), shuffled, Map.of()));
        }
    }

    @Test
    void oneWayPortalCannotBeTraversedBackwards() {
        SemanticPortal stairs = portal("stairs", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 0, 0, 0), endpoint("f2", "s2", 0, 3, 0),
                false, 1, "");

        PortalRoute forward = route(endpoint("f1", "s1", 0, 0, 0),
                endpoint("f2", "s2", 0, 3, 0), NavProfile.humanoid(), List.of(stairs), Map.of());
        PortalRoute reverse = route(endpoint("f2", "s2", 0, 3, 0),
                endpoint("f1", "s1", 0, 0, 0), NavProfile.humanoid(), List.of(stairs), Map.of());

        assertEquals(PortalRoute.Status.READY, forward.status());
        assertEquals(PortalRoute.Status.UNREACHABLE, reverse.status());
    }

    @Test
    void lockedPortalIsExcludedAndAvailableAlternativeWins() {
        SemanticPortal shortStairs = portal("short", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 1, 0, 0), endpoint("f2", "s2", 1, 3, 0), true, 1, "");
        SemanticPortal longStairs = portal("long", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 5, 0, 0), endpoint("f2", "s2", 5, 3, 0), true, 2, "");
        Map<String, PortalRuntimeState> states = Map.of("short", new PortalRuntimeState(
                "short", PortalRuntimeState.Availability.LOCKED, 2, "requires key"));

        PortalRoute route = route(endpoint("f1", "s1", 0, 0, 0),
                endpoint("f2", "s2", 6, 3, 0), NavProfile.humanoid(),
                List.of(shortStairs, longStairs), states);

        assertEquals(PortalRoute.Status.READY, route.status());
        assertEquals("long", traversedPortal(route));
    }

    @Test
    void profileCapabilitiesSelectElevatorInsteadOfStairs() {
        SemanticPortal stairs = portal("stairs", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 1, 0, 0), endpoint("f2", "s2", 1, 3, 0), true, 1, "");
        SemanticPortal lift = portal("lift", SemanticPortal.Kind.ELEVATOR,
                endpoint("f1", "s1", 3, 0, 0), endpoint("f2", "s2", 3, 3, 0), true, 1, "CALL");
        NavProfile liftOnly = new NavProfile(0.5, true, false, true);

        PortalRoute route = route(endpoint("f1", "s1", 0, 0, 0),
                endpoint("f2", "s2", 4, 3, 0), liftOnly, List.of(stairs, lift), Map.of());

        assertEquals(PortalRoute.Status.READY, route.status());
        assertEquals("lift", traversedPortal(route));
    }

    @Test
    void closedDoorInsertsExplicitInteractionBeforeTraversal() {
        SemanticPortal door = portal("door-12", SemanticPortal.Kind.DOOR,
                endpoint("f1", "hall", 2, 0, 0), endpoint("f1", "room", 2, 0, 0),
                true, 1, "OPEN");
        Map<String, PortalRuntimeState> states = Map.of("door-12", new PortalRuntimeState(
                "door-12", PortalRuntimeState.Availability.CLOSED, 4, ""));

        PortalRoute route = route(endpoint("f1", "hall", 0, 0, 0),
                endpoint("f1", "room", 4, 0, 0), NavProfile.humanoid(), List.of(door), states);
        List<Leg> semanticLegs = route.legs().stream()
                .filter(leg -> leg.type() != PortalRoute.Type.FLOOR_TRANSIT)
                .toList();

        assertEquals(PortalRoute.Status.READY, route.status());
        assertEquals(List.of(PortalRoute.Type.INTERACT, PortalRoute.Type.PORTAL_TRAVERSAL),
                semanticLegs.stream().map(Leg::type).toList());
        assertEquals("OPEN", semanticLegs.getFirst().interaction());
    }

    @Test
    void routeCanTraverseMultipleFloorsInStableOrder() {
        SemanticPortal p12 = portal("stairs-12", SemanticPortal.Kind.STAIRS,
                endpoint("f1", "s1", 1, 0, 0), endpoint("f2", "s2", 1, 3, 0), true, 1, "");
        SemanticPortal p23 = portal("stairs-23", SemanticPortal.Kind.STAIRS,
                endpoint("f2", "s2", 5, 3, 0), endpoint("f3", "s3", 5, 6, 0), true, 1, "");

        PortalRoute route = route(endpoint("f1", "s1", 0, 0, 0),
                endpoint("f3", "s3", 6, 6, 0), NavProfile.humanoid(), List.of(p23, p12), Map.of());

        assertEquals(PortalRoute.Status.READY, route.status());
        assertEquals(List.of("stairs-12", "stairs-23"), route.legs().stream()
                .filter(leg -> leg.type() == PortalRoute.Type.PORTAL_TRAVERSAL)
                .map(Leg::portalId)
                .toList());
        assertTrue(route.legs().stream().anyMatch(leg -> leg.type() == PortalRoute.Type.FLOOR_TRANSIT
                && leg.from().floorId().equals("f2")));
    }

    private PortalRoute route(PortalEndpoint from,
                              PortalEndpoint to,
                              NavProfile profile,
                              List<SemanticPortal> portals,
                              Map<String, PortalRuntimeState> states) {
        return router.route(new PortalRouteRequest(from, to, profile, portals, states));
    }

    private static SemanticPortal portal(String id,
                                         SemanticPortal.Kind kind,
                                         PortalEndpoint a,
                                         PortalEndpoint b,
                                         boolean bidirectional,
                                         double cost,
                                         String action) {
        return new SemanticPortal(id, kind, a, b, bidirectional, cost, action, Set.of());
    }

    private static PortalEndpoint endpoint(String floor, String surface,
                                           double x, double y, double z) {
        return new PortalEndpoint(floor, surface, new Vec3(x, y, z));
    }

    private static String traversedPortal(PortalRoute route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == PortalRoute.Type.PORTAL_TRAVERSAL)
                .findFirst()
                .orElseThrow()
                .portalId();
    }
}
