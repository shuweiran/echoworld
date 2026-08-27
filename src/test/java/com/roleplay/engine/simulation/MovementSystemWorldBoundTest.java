package com.roleplay.engine.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0813-E：①碰撞推挤把角色钉出世界边界 + ②对话中角色移动/目标被清零 —— 回归测试。
 *
 * <p>根因（tmp/调查-玩家控制与地图质量-20260813.md）：MovementSystem.clampToWorld 先 clamp 到世界内、
 * 再沿障碍中心径向推挤 pushDist=max(w,h)/2+15 —— LLM 地图贴边墙（顶墙 (0,0,1000,30)）把角色弹射到
 * 世界外（实测苏婉_2 停 (-14.9, 25.8)、林杰/林浩 y&gt;600，8 次采样 Δ=0 永久卡死）。修复后：最小穿透
 * 轴推挤 + 推挤后二次 clamp，任何 tick 后坐标必须 ∈ [0, 1000]×[0, 600]。
 *
 * <p>②：对话中的角色 —— 玩家手动目标（manualTarget）保留且可移动；AI 自主目标仍清零（原行为）。
 */
class MovementSystemWorldBoundTest {

    private static final double W = SimulationWorld.DEFAULT_WORLD_WIDTH;
    private static final double H = SimulationWorld.DEFAULT_WORLD_HEIGHT;
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

    /** 多轮 tick 后断言：坐标始终在世界内（每轮都查，抓到任何越界 tick）。 */

    @Test
    @DisplayName("① 顶墙 (0,0,1000,30)：角色贴顶墙卡死位 (25,25.8) 多轮 tick 始终在世界内且被推到墙下")
    void topWall_agentNeverLeavesWorld() {
        Obstacle topWall = new Obstacle(Obstacle.Type.WALL, 0, 0, W, 30, true, "顶墙");
        MovementSystem movement = ms(List.of(topWall));
        AgentState a = agent(25, 25.8); // 实测卡死位：clamp 后仍在墙内
        a.setTarget(500, 400);          // 目标在墙下方
        for (int i = 0; i < 60; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.getX() >= 0 && a.getX() <= W, "tick#" + i + " x=" + a.getX() + " 越界");
            assertTrue(a.getY() >= 0 && a.getY() <= H, "tick#" + i + " y=" + a.getY() + " 越界");
            assertTrue(a.getY() >= 30 - 1e-9, "tick#" + i + " 角色应被推到顶墙下方而非墙内/墙外，y=" + a.getY());
        }
        // 应真正走向目标（不再被钉死）：最终位置应显著离开起点
        assertTrue(a.getX() > 100, "角色应能向目标移动，最终 x=" + a.getX());
        assertTrue(a.getY() > 50, "角色应能向目标移动，最终 y=" + a.getY());
    }

    @Test
    @DisplayName("① 右墙 (970,0,30,600)：角色贴右墙 (975,300) 多轮 tick 始终在世界内并被推出墙外")
    void rightWall_agentNeverLeavesWorld() {
        Obstacle rightWall = new Obstacle(Obstacle.Type.WALL, W - 30, 0, 30, H, true, "右墙");
        MovementSystem movement = ms(List.of(rightWall));
        AgentState a = agent(975, 300);
        a.setTarget(100, 100); // 向左走
        for (int i = 0; i < 60; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.getX() >= 0 && a.getX() <= W, "tick#" + i + " x=" + a.getX() + " 越界");
            assertTrue(a.getY() >= 0 && a.getY() <= H, "tick#" + i + " y=" + a.getY() + " 越界");
            assertTrue(a.getX() <= W - 30 + 1e-9, "tick#" + i + " 角色应在右墙左侧，x=" + a.getX());
        }
        assertTrue(a.getX() < 500, "角色应能向左移动，最终 x=" + a.getX());
    }

    @Test
    @DisplayName("① 顶+左双墙直角：贴角 (25,25) 多轮 tick 始终在世界内")
    void cornerWalls_agentNeverLeavesWorld() {
        List<Obstacle> walls = List.of(
                new Obstacle(Obstacle.Type.WALL, 0, 0, W, 30, true, "顶墙"),
                new Obstacle(Obstacle.Type.WALL, 0, 0, 30, H, true, "左墙"));
        MovementSystem movement = ms(walls);
        AgentState a = agent(25, 25); // 同时卡进两面墙
        a.setTarget(500, 300);
        for (int i = 0; i < 60; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.getX() >= 0 && a.getX() <= W, "tick#" + i + " x=" + a.getX() + " 越界");
            assertTrue(a.getY() >= 0 && a.getY() <= H, "tick#" + i + " y=" + a.getY() + " 越界");
        }
        // 顶墙横贯世界无法穿越：角色应被推出墙内并沿墙底滑动（x 增长、y 稳定在墙下），始终在世界内
        assertTrue(a.getX() > 60, "角色应被推离左墙并沿顶墙滑动，最终 x=" + a.getX());
        assertTrue(a.getY() >= 30 - 1e-9, "角色应在顶墙下方（不被钉在墙内/墙外），最终 y=" + a.getY());
    }

    @Test
    @DisplayName("① LLM 地图四边围墙（fromCollisionGrid 24×16 实况）：多角色多轮 tick 全部在世界内")
    void llmBorderWalls_multiAgentNeverLeaveWorld() {
        // 复现生产 LLM 地图：24×16 网格，四边 1 瓦片厚围墙 → fromCollisionGrid 合并为 4 个贴边障碍
        int w = 24, h = 16;
        int[][] grid = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (y == 0 || y == h - 1 || x == 0 || x == w - 1) grid[y][x] = 1;
            }
        }
        List<Obstacle> walls = Obstacle.fromCollisionGrid(grid, 32, "海边度假村");
        assertEquals(4, walls.size(), "四边墙应合并为 4 个障碍");
        MovementSystem movement = ms(walls);

        // 多角色从墙内/贴边等危险起点出发（clamp 位都落在墙体内）
        List<AgentState> agents = new ArrayList<>();
        agents.add(spawn(movement, agents, 25, 25, 500, 300));
        agents.add(spawn(movement, agents, 975, 575, 100, 100));
        agents.add(spawn(movement, agents, 500, 25, 400, 500));
        agents.add(spawn(movement, agents, 25, 400, 900, 200));
        agents.add(spawn(movement, agents, 900, 300, 50, 400));

        for (int i = 0; i < 80; i++) {
            movement.update(agents, DT);
            for (AgentState a : agents) {
                assertTrue(a.getX() >= 0 && a.getX() <= W, "tick#" + i + " " + a.getAgentName() + " x=" + a.getX() + " 越界");
                assertTrue(a.getY() >= 0 && a.getY() <= H, "tick#" + i + " " + a.getAgentName() + " y=" + a.getY() + " 越界");
            }
        }
    }

    private AgentState spawn(MovementSystem movement, List<AgentState> existing, double x, double y, double tx, double ty) {
        AgentState a = agent(x, y);
        a.setTarget(tx, ty);
        return a;
    }

    @Test
    @DisplayName("① 无障碍自由移动（回归）：普通移动链路不受影响，坐标始终在世界内")
    void noObstacle_normalMovementStillWorks() {
        MovementSystem movement = ms(List.of());
        AgentState a = agent(100, 100);
        a.setTarget(900, 500);
        for (int i = 0; i < 100; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.getX() >= 0 && a.getX() <= W, "tick#" + i + " x=" + a.getX());
            assertTrue(a.getY() >= 0 && a.getY() <= H, "tick#" + i + " y=" + a.getY());
        }
        // 应明显向目标移动（到达后触发 wander 不再贴点，用宽断言）
        assertTrue(a.getX() > 700, "应明显向目标移动，最终 x=" + a.getX());
        assertTrue(a.getY() > 400, "应明显向目标移动，最终 y=" + a.getY());
    }

    // ── ② 对话中的角色：玩家手动目标保留可移动，AI 自主目标仍清 ──

    @Test
    @DisplayName("② 对话中 + 玩家手动目标（manualTarget）：目标保留、角色继续走向目标（不再被清零）")
    void inConversation_manualTarget_keptAndMoves() {
        MovementSystem movement = ms(List.of());
        AgentState a = agent(100, 100);
        a.setInConversation(true);
        a.setTarget(800, 100);      // /target 端点路径：manualTarget=true
        a.setManualTarget(true);
        double startX = a.getX();
        for (int i = 0; i < 30; i++) {
            movement.update(List.of(a), DT);
            assertTrue(a.isHasTarget(), "tick#" + i + " 手动目标不应被对话逻辑清零");
        }
        assertTrue(a.getX() > startX + 20, "对话中带手动目标的角色应继续移动，x " + startX + " → " + a.getX());
    }

    @Test
    @DisplayName("② 对话中 + AI 自主目标（非手动）：目标被清、速度冻结（原行为保留）")
    void inConversation_aiTarget_clearedAndFrozen() {
        MovementSystem movement = ms(List.of());
        AgentState a = agent(100, 100);
        a.setInConversation(true);
        a.setTarget(800, 100);      // AI 目标（导演/约束层设置，manualTarget=false）
        double startX = a.getX();
        movement.update(List.of(a), DT);
        assertFalse(a.isHasTarget(), "AI 自主目标在对话中应被清零（原行为）");
        assertEquals(0.0, a.getVx(), "对话中应冻结速度");
        assertEquals(0.0, a.getVy(), "对话中应冻结速度");
        assertEquals(startX, a.getX(), "对话中位置不应移动");
    }

    @Test
    @DisplayName("② 对话结束（inConversation=false）后：保留的手动目标继续执行")
    void manualTarget_survivesConversation_andContinuesAfter() {
        MovementSystem movement = ms(List.of());
        AgentState a = agent(100, 100);
        a.setTarget(800, 100);
        a.setManualTarget(true);
        // 对话期间：目标保留、位置移动
        a.setInConversation(true);
        for (int i = 0; i < 10; i++) movement.update(List.of(a), DT);
        assertTrue(a.isHasTarget(), "对话中手动目标应保留");
        double midX = a.getX();
        assertTrue(midX > 100, "对话中手动目标角色应已移动，x=" + midX);
        // 对话结束：继续走向同一目标
        a.setInConversation(false);
        for (int i = 0; i < 30; i++) movement.update(List.of(a), DT);
        assertTrue(a.getX() > midX + 20, "对话结束后应继续走向目标，x " + midX + " → " + a.getX());
    }
}
