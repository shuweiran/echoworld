package com.roleplay.engine.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0814-I：MovementSystem 轻量寻路增强 —— 目标力 > 障碍斥力 + blocked 沿墙绕行（切向滑行）。
 *
 * <p>修复目标（docs/调研报告-移动与分组问题.md 1.3 R3）：TARGET_WEIGHT=50 仅障碍斥力 200 的 1/4，
 * LLM 瓦片地图墙/家具密集时角色在墙前被推回、抖动、无法绕行到达目标。修复后：
 * TARGET_WEIGHT=220（> 200）+ blocked 时切向滑行（TANGENT_SLIDE_WEIGHT=300）+ 保留 30% 目标力，
 * 角色滑过墙角后目标力直接牵引到目标。
 */
class MovementSystemNavigationTest {

    private static final double W = SimulationWorld.WORLD_WIDTH;   // 1000
    private static final double H = SimulationWorld.WORLD_HEIGHT;  // 600
    private static final double DT = SimulationWorld.TICK_INTERVAL_MS / 1000.0; // 0.2s/tick

    private MovementSystem ms(List<Obstacle> obs) {
        MovementSystem m = new MovementSystem(W, H, SimulationWorld.WORLD_MARGIN, new SpatialGrid(W, H, 100));
        m.setObstacles(obs);
        return m;
    }

    private AgentState agent(double x, double y) {
        AgentState a = new AgentState("测试角色", x, y);
        a.setMoveSpeed(80);
        return a;
    }

    @Test
    @DisplayName("① 常量：目标力已提升至 > 障碍斥力（220 > 200），切向滑行力 > 斥力")
    void targetWeightBeatsObstacleRepulsion() {
        assertTrue(MovementSystem.TARGET_WEIGHT > MovementSystem.OBSTACLE_REPULSION,
                "TARGET_WEIGHT=" + MovementSystem.TARGET_WEIGHT + " 应 > OBSTACLE_REPULSION=" + MovementSystem.OBSTACLE_REPULSION
                        + "（修复前 50 < 200，墙前被 4 倍斥力推回抖动）");
        assertTrue(MovementSystem.TANGENT_SLIDE_WEIGHT > MovementSystem.OBSTACLE_REPULSION,
                "TANGENT_SLIDE_WEIGHT=" + MovementSystem.TANGENT_SLIDE_WEIGHT + " 应 > 斥力（沿墙滑行不被钉死）");
        assertTrue(MovementSystem.TARGET_WEIGHT >= 150, "任务要求 TARGET_WEIGHT 50→150+");
    }

    @Test
    @DisplayName("② 竖墙挡路：角色沿墙绕行到达墙后目标（不再卡死抖动/原路弹回）")
    void agentNavigatesAroundVerticalWall() {
        // 竖墙横贯 y 100..500：直线路径必穿墙（修复前：目标力 50 vs 斥力 200 → 贴墙抖动不前进）
        Obstacle wall = new Obstacle(Obstacle.Type.WALL, 480, 100, 20, 400, true, "竖墙");
        MovementSystem movement = ms(List.of(wall));
        AgentState a = agent(300, 300);
        a.setTarget(700, 300);
        boolean reached = false;
        for (int i = 0; i < 500; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.getX() >= 0 && a.getX() <= W && a.getY() >= 0 && a.getY() <= H,
                    "tick#" + i + " 越界: (" + a.getX() + "," + a.getY() + ")");
            if (Math.hypot(a.getX() - 700, a.getY() - 300) < 40) { reached = true; break; }
        }
        assertTrue(reached,
                "角色应绕墙到达目标；最终 pos=(" + a.getX() + "," + a.getY() + ")");
        // 不卡死：最终位置显著离开起点（修复前会停在墙前抖动）
        assertTrue(a.getX() > 480 || Math.hypot(a.getX() - 700, a.getY() - 300) < 60,
                "角色应越过墙线 x=480 或已到目标，最终 pos=(" + a.getX() + "," + a.getY() + ")");
    }

    @Test
    @DisplayName("② 横墙+顶墙组合：角色绕行到达墙后目标（贴顶墙实况不卡死）")
    void agentNavigatesAroundHorizontalWall() {
        // 顶墙（LLM 地图贴边墙实况）+ 中段横墙挡在目标直线路径上
        Obstacle topWall = new Obstacle(Obstacle.Type.WALL, 0, 0, W, 30, true, "顶墙");
        Obstacle midWall = new Obstacle(Obstacle.Type.WALL, 280, 170, 140, 60, true, "横墙");
        MovementSystem movement = ms(List.of(topWall, midWall));
        AgentState a = agent(200, 100);
        a.setTarget(500, 400);
        boolean reached = false;
        for (int i = 0; i < 600; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.getX() >= 0 && a.getX() <= W && a.getY() >= 0 && a.getY() <= H,
                    "tick#" + i + " 越界: (" + a.getX() + "," + a.getY() + ")");
            if (Math.hypot(a.getX() - 500, a.getY() - 400) < 40) { reached = true; break; }
        }
        assertTrue(reached,
                "角色应绕行横墙到达目标；最终 pos=(" + a.getX() + "," + a.getY() + ")");
    }

    @Test
    @DisplayName("③ 无障碍自由移动回归：目标力提高不破坏普通移动（仍直线到达）")
    void noObstacle_normalMovementStillReaches() {
        MovementSystem movement = ms(List.of());
        AgentState a = agent(100, 100);
        a.setTarget(900, 500);
        boolean reached = false;
        for (int i = 0; i < 300; i++) {
            movement.update(List.of(a), DT);
            if (Math.hypot(a.getX() - 900, a.getY() - 500) < 40) { reached = true; break; }
        }
        assertTrue(reached, "无障碍时应直达目标；最终 pos=(" + a.getX() + "," + a.getY() + ")");
    }

    @Test
    @DisplayName("④ LLM 四边围墙内移动：目标力提高后角色仍不越界、能向墙内目标前进")
    void insideBorderWalls_agentMovesWithinWorld() {
        int w = 24, h = 16;
        int[][] grid = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (y == 0 || y == h - 1 || x == 0 || x == w - 1) grid[y][x] = 1;
            }
        }
        List<Obstacle> walls = Obstacle.fromCollisionGrid(grid, 32, "海边度假村");
        MovementSystem movement = ms(walls);
        AgentState a = agent(200, 200);
        a.setTarget(800, 400);
        boolean moved = false;
        for (int i = 0; i < 300; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.getX() >= 0 && a.getX() <= W && a.getY() >= 0 && a.getY() <= H,
                    "tick#" + i + " 越界: (" + a.getX() + "," + a.getY() + ")");
            if (a.getX() > 400 && a.getY() > 250) moved = true;
            if (Math.hypot(a.getX() - 800, a.getY() - 400) < 40) { moved = true; break; }
        }
        assertTrue(moved, "角色应向目标前进（不贴墙抖动）；最终 pos=(" + a.getX() + "," + a.getY() + ")");
    }
}
