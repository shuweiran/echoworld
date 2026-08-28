package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.Obstacle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationPathfinderTest {

    private final NavigationPathfinder pathfinder = new NavigationPathfinder();

    @Test
    void routesAroundObstacleInsteadOfWalkingThroughIt() {
        Obstacle wall = new Obstacle(Obstacle.Type.WALL, 128, 64, 64, 32, true, "wall");

        List<NavigationPathfinder.Point> path = pathfinder.findPath(
                32, 32, 288, 192, 320, 224, List.of(wall));

        assertFalse(path.isEmpty(), "可达目标必须生成路径");
        assertTrue(path.size() > 1, "绕障路径应至少包含一个中间航点");
        assertTrue(path.stream().noneMatch(p -> p.x() >= 116 && p.x() <= 204
                        && p.y() >= 52 && p.y() <= 108),
                "路径航点不得落入带角色半径的障碍膨胀区域");
        NavigationPathfinder.Point last = path.get(path.size() - 1);
        assertTrue(Math.abs(last.x() - 288) < 0.001 && Math.abs(last.y() - 192) < 0.001);
    }

    @Test
    void returnsEmptyWhenBarrierSplitsTheWorld() {
        Obstacle barrier = new Obstacle(Obstacle.Type.WALL, 0, 64, 320, 32, true, "barrier");

        List<NavigationPathfinder.Point> path = pathfinder.findPath(
                32, 32, 288, 192, 320, 224, List.of(barrier));

        assertTrue(path.isEmpty(), "完整横向障碍阻断时应明确返回不可达");
    }
}
