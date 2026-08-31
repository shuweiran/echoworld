package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.Vec3;

import java.util.List;

/** Legacy collision raster backend behind the permanent NavigationService API. */
public final class GridNavigationService implements NavigationService {
    private final NavigationPathfinder pathfinder;

    public GridNavigationService() { this(new NavigationPathfinder()); }

    GridNavigationService(NavigationPathfinder pathfinder) { this.pathfinder = pathfinder; }

    @Override
    public PathPlan plan(PathRequest request) {
        if (request == null || request.from() == null || request.to() == null) {
            return new PathPlan(PathPlan.Status.REJECTED, "grid-a-star", List.of(), "missing navigation request");
        }
        if (request.authority() != ControlAuthority.AI_AUTONOMOUS) {
            return new PathPlan(PathPlan.Status.REJECTED, "grid-a-star", List.of(),
                    "player input is never planned by the AI navigation service");
        }
        Vec3 from = request.from().worldPosition();
        Vec3 to = request.to().worldPosition();
        NavProfile profile = request.profile() == null ? NavProfile.humanoid() : request.profile();
        List<NavigationPathfinder.Point> points = pathfinder.findPath(
                from.x(), from.z(), to.x(), to.z(),
                request.worldWidth(), request.worldHeight(), request.obstacles().stream()
                        .filter(obstacle -> obstacle.belongsToFloor(request.from().floorId())).toList(), profile.radius());
        if (points.isEmpty() && from.groundDistance(to) > 5.0) {
            return PathPlan.unreachable("grid-a-star", "no walkable route");
        }
        return PathPlan.ready("grid-a-star", points.stream()
                .map(point -> PathStep.walk(new Vec3(point.x(), to.y(), point.y())))
                .toList());
    }
}
