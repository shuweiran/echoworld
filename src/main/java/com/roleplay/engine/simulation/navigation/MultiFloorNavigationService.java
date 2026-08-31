package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.navigation.portal.*;
import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.Vec3;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;

import java.util.*;

/**
 * Deterministic floor graph + floor-local A* composition. The server owns the
 * route; a client can only render the resulting walk/interaction/transition steps.
 */
public final class MultiFloorNavigationService implements NavigationService {
    private final GridNavigationService local = new GridNavigationService();
    private final PortalRouter portalRouter;
    private final WorldDefinition definition;
    private final Map<String, PortalRuntimeState> portalStates;

    public MultiFloorNavigationService(WorldDefinition definition,
                                       PortalRouter portalRouter,
                                       Map<String, PortalRuntimeState> portalStates) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.portalRouter = Objects.requireNonNull(portalRouter, "portalRouter");
        this.portalStates = portalStates == null ? Map.of() : portalStates;
    }

    @Override
    public PathPlan plan(PathRequest request) {
        if (request == null || request.from() == null || request.to() == null)
            return PathPlan.unreachable("multi-floor", "missing navigation request");
        if (request.authority() != ControlAuthority.AI_AUTONOMOUS)
            return new PathPlan(PathPlan.Status.REJECTED, "multi-floor", List.of(), "player input is never planned");
        if (request.from().floorId().equals(request.to().floorId())) {
            PathPlan sameFloor = local.plan(request);
            return sameFloor.status() == PathPlan.Status.READY
                    ? PathPlan.ready("multi-floor", withFloor(sameFloor.steps(), request.to().floorId(), request.to().surfaceId()))
                    : sameFloor;
        }

        PortalRoute route = portalRouter.route(new PortalRouteRequest(
                new PortalEndpoint(request.from().floorId(), request.from().surfaceId(), request.from().worldPosition()),
                new PortalEndpoint(request.to().floorId(), request.to().surfaceId(), request.to().worldPosition()),
                request.profile(), definition.portals(), portalStates));
        if (route.status() != PortalRoute.Status.READY)
            return PathPlan.unreachable("multi-floor", route.reason());

        List<PathStep> steps = new ArrayList<>();
        var cursor = request.from();
        for (PortalRoute.Leg leg : route.legs()) {
            if (leg.type() == PortalRoute.Type.FLOOR_TRANSIT) continue;
            PathPlan localPlan = local.plan(new PathRequest(request.entityId(), request.authority(), cursor,
                    new com.roleplay.engine.simulation.spatial.NavLocation(leg.from().surfaceId(), leg.from().floorId(), leg.from().worldPosition(), -1L),
                    request.profile(), request.worldWidth(), request.worldHeight(), request.obstacles()));
            if (localPlan.status() != PathPlan.Status.READY)
                return PathPlan.unreachable("multi-floor", "connector entry unreachable:" + leg.portalId());
            steps.addAll(withFloor(localPlan.steps(), leg.from().floorId(), leg.from().surfaceId()));
            if (leg.type() == PortalRoute.Type.INTERACT) {
                steps.add(new PathStep(PathStep.Type.INTERACT, leg.from().worldPosition(), leg.portalId(), leg.interaction(), leg.from().floorId()));
            } else if (leg.type() == PortalRoute.Type.PORTAL_TRAVERSAL) {
                steps.add(PathStep.usePortal(leg.portalId(), leg.to().worldPosition(), leg.to().floorId(), leg.to().surfaceId()));
            }
            cursor = new com.roleplay.engine.simulation.spatial.NavLocation(leg.to().surfaceId(), leg.to().floorId(), leg.to().worldPosition(), -1L);
        }
        PathPlan tail = local.plan(new PathRequest(request.entityId(), request.authority(), cursor, request.to(),
                request.profile(), request.worldWidth(), request.worldHeight(), request.obstacles()));
        if (tail.status() != PathPlan.Status.READY) return PathPlan.unreachable("multi-floor", "target floor unreachable");
        steps.addAll(withFloor(tail.steps(), request.to().floorId(), request.to().surfaceId()));
        return PathPlan.ready("multi-floor", steps);
    }

    private static List<PathStep> withFloor(List<PathStep> steps, String floor, String surface) {
        return steps.stream().map(s -> new PathStep(s.type(), s.target(), s.worldObjectId(), s.interaction(), floor, surface)).toList();
    }
}
