package com.roleplay.engine.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0815-H：玩家角色从 AI 行为中隔离 —— MovementSystem.computeForce 不再对
 * isPlayerControlled 角色施加 flocking（分离/聚合/对齐）与 wander（随机漫步）。
 *
 * <p>主人反馈（2026-08-15 23:28）：「玩家控制角色会有明显的其他方向的速度，当你不去控制时
 * 也会运动」——玩家角色无输入时自己朝别的方向移动（漂移/被推），手感失控。根因：
 * computeForce 对所有角色一视同仁——①无目标时随机漫步（每 tick ±7.5px 漂移）；
 * ②站在 AI 附近被分离力推开/聚合/对齐力牵引；③点墙后切向滑行沿墙滑离目标点
 * （P-0815-G 实测 36.6px/400ms 持续滑行 = 方向漂移）。
 *
 * <p>修复：玩家角色跳过 flocking（邻居循环）+ 跳过 wander（无目标无外力时速度恒 0），
 * blocked 切向滑行对玩家禁用（保留 30% 目标力走到墙边停下）。保留：目标力（manualTarget
 * 点击移动）、障碍斥力（防穿墙）、clampToWorld（边界约束）、对话冻结逻辑。AI 行为零变化。
 */
class MovementSystemPlayerIsolationTest {

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

    // ── ① 玩家无目标无外力 → 完全静止（无 wander）──────────────

    @Test
    @DisplayName("① 玩家无目标无外力：连续 100 tick 位置不变、速度恒 0（随机漫步对玩家失效）")
    void playerNoTargetNoForce_staysPerfectlyStill() {
        MovementSystem movement = ms(List.of());
        AgentState player = agent(100, 100);
        player.setPlayerControlled(true);
        for (int i = 0; i < 100; i++) {
            movement.update(List.of(player), DT);
            assertEquals(100.0, player.getX(), 1e-9, "tick#" + i + " 玩家 x 不应漂移");
            assertEquals(100.0, player.getY(), 1e-9, "tick#" + i + " 玩家 y 不应漂移");
            assertEquals(0.0, player.getVx(), 1e-9, "tick#" + i + " 玩家 vx 应恒 0");
            assertEquals(0.0, player.getVy(), 1e-9, "tick#" + i + " 玩家 vy 应恒 0");
        }
    }

    // ── ② 玩家旁有 AI 靠近 → 不被推开（无 separation）──────────

    @Test
    @DisplayName("② 玩家旁有 AI（距离 20 < MIN_SEPARATION 35）：玩家不被分离力推开，AI 正常避让")
    void playerNearAi_notPushedBySeparation() {
        MovementSystem movement = ms(List.of());
        AgentState player = agent(100, 100);
        player.setPlayerControlled(true);
        AgentState ai = agent(120, 100);   // 距离 20，必触发 separation
        List<AgentState> agents = List.of(player, ai);

        double maxDist = 20;
        for (int i = 0; i < 60; i++) {
            movement.update(agents, DT);
            // 玩家：不被任何 flocking 力推挤，位置逐字节不动
            assertEquals(100.0, player.getX(), 1e-9, "tick#" + i + " 玩家 x 不应被 AI 推开");
            assertEquals(100.0, player.getY(), 1e-9, "tick#" + i + " 玩家 y 不应被 AI 推开");
            maxDist = Math.max(maxDist, Math.hypot(player.getX() - ai.getX(), player.getY() - ai.getY()));
        }
        // 对照：AI 仍把玩家当邻居避让（分离力真实存在，只是不作用于玩家）
        assertTrue(maxDist > 30, "AI 应被 separation 推开（距离应显著大于 20），maxDist=" + maxDist);
    }

    // ── ③ 玩家 manualTarget → 正常走向目标（目标力保留）────────

    @Test
    @DisplayName("③ 玩家有 manualTarget：目标力保留，正常直线走向目标并到达")
    void playerManualTarget_walksToTarget() {
        MovementSystem movement = ms(List.of());
        AgentState player = agent(100, 100);
        player.setPlayerControlled(true);
        player.setTarget(800, 400);
        player.setManualTarget(true);
        boolean reached = false;
        for (int i = 0; i < 300; i++) {
            movement.update(List.of(player), DT);
            if (Math.hypot(player.getX() - 800, player.getY() - 400) < 40) { reached = true; break; }
        }
        assertTrue(reached, "玩家应正常走向 manualTarget（目标力保留），最终 pos=("
                + player.getX() + "," + player.getY() + ")");
    }

    @Test
    @DisplayName("③b 玩家方向输入：速度方向与输入一致，连续换向不反弹不打转")
    void playerManualDirection_isDeterministic() {
        MovementSystem movement = ms(List.of());
        AgentState player = agent(300, 300);
        player.setPlayerControlled(true);
        player.setManualDirection(1, 0);
        player.setManualTarget(true);
        movement.update(List.of(player), DT);
        assertTrue(player.getX() > 300 && Math.abs(player.getY() - 300) < 1e-9);
        player.setManualDirection(-1, 0);
        for (int i = 0; i < 4; i++) movement.update(List.of(player), DT);
        assertTrue(player.getX() < 300, "换向后应向左，不应沿旧惯性继续向右或原地打转：x=" + player.getX());
        assertEquals(0.0, player.getVy(), 1e-9);
    }

    // ── ④ AI 行为不受影响（仍 wander / 仍 flocking）────────────

    @Test
    @DisplayName("④a AI 无目标无外力：仍随机漫步（有速度、累积位移），与玩家静止形成对照")
    void aiStillWanders_withoutTarget() {
        MovementSystem movement = ms(List.of());
        AgentState ai = agent(200, 200);   // 未标记 playerControlled = AI
        double maxSpeed = 0;
        double totalPath = 0;
        double prevX = ai.getX(), prevY = ai.getY();
        for (int i = 0; i < 60; i++) {
            movement.update(List.of(ai), DT);
            maxSpeed = Math.max(maxSpeed, Math.hypot(ai.getVx(), ai.getVy()));
            totalPath += Math.hypot(ai.getX() - prevX, ai.getY() - prevY);
            prevX = ai.getX();
            prevY = ai.getY();
        }
        assertTrue(maxSpeed > 1.0, "AI 应持续有 wander 速度（maxSpeed=" + maxSpeed + "）");
        assertTrue(totalPath > 2.0, "AI 应累积位移（totalPath=" + totalPath + "）");
    }

    @Test
    @DisplayName("④b AI 之间仍分离（flocking 保留）：两 AI 距离 20 会互相推开")
    void aiStillSeparates_fromEachOther() {
        MovementSystem movement = ms(List.of());
        AgentState ai1 = agent(200, 200);
        AgentState ai2 = agent(220, 200);  // 距离 20 < MIN_SEPARATION 35
        List<AgentState> agents = List.of(ai1, ai2);
        double dist = 0;
        for (int i = 0; i < 10; i++) {
            movement.update(agents, DT);
            dist = Math.hypot(ai1.getX() - ai2.getX(), ai1.getY() - ai2.getY());
        }
        assertTrue(dist > 30, "两 AI 应被 separation 推开（dist=" + dist + "）");
    }

    // ── ⑤ blocked：玩家停墙不滑行（决定：禁用切向滑行）vs AI 仍绕行 ──

    @Test
    @DisplayName("⑤a 玩家点墙（目标被竖墙挡住）：走到墙边停下，不沿墙滑离目标点（切向滑行禁用）")
    void playerBlocked_stopsAtWall_noTangentSlide() {
        Obstacle wall = new Obstacle(Obstacle.Type.WALL, 480, 100, 20, 400, true, "竖墙");
        MovementSystem movement = ms(List.of(wall));
        AgentState player = agent(300, 300);
        player.setPlayerControlled(true);
        player.setTarget(700, 300);   // 直线路径必穿墙 → blocked
        player.setManualTarget(true);

        double startY = player.getY();
        for (int i = 0; i < 300; i++) {
            movement.update(List.of(player), DT);
            // 不沿墙垂直滑行（修复前切向滑行会把角色沿 -y 方向滑离，实测 36.6px/400ms）
            assertTrue(Math.abs(player.getY() - startY) < 2.0,
                    "tick#" + i + " 玩家不应沿墙滑行，y=" + player.getY() + "（起点 y=" + startY + "）");
            // 不穿墙
            assertTrue(player.getX() < 470, "tick#" + i + " 玩家不应穿墙，x=" + player.getX());
        }
        // 目标未达但已停住（保留 30% 目标力走到墙边，斥力抵消后静止）
        assertTrue(Math.hypot(player.getX() - 700, player.getY() - 300) > 100,
                "玩家不应到达墙后目标点，pos=(" + player.getX() + "," + player.getY() + ")");
        assertTrue(player.isHasTarget(), "blocked 未到达时目标应保留（交由 60s 手动目标超时释放）");

        // 稳定：最后 100 tick 累计位移趋近 0（不再滑动）
        double prevX = player.getX(), prevY = player.getY();
        double tailPath = 0;
        for (int i = 0; i < 100; i++) {
            movement.update(List.of(player), DT);
            tailPath += Math.hypot(player.getX() - prevX, player.getY() - prevY);
            prevX = player.getX();
            prevY = player.getY();
        }
        assertTrue(tailPath < 2.0, "玩家停在墙边后应完全静止（最后 100 tick 累计位移=" + tailPath + "）");
    }

    @Test
    @DisplayName("⑤b 对照：AI 点墙仍切向滑行绕行（P-0814-I 行为保留，AI 零变化）")
    void aiBlocked_tangentSlidePreserved() {
        Obstacle wall = new Obstacle(Obstacle.Type.WALL, 480, 100, 20, 400, true, "竖墙");
        MovementSystem movement = ms(List.of(wall));
        AgentState ai = agent(300, 300);
        ai.setTarget(700, 300);   // 直线路径必穿墙 → blocked
        double startY = ai.getY();
        for (int i = 0; i < 15; i++) {
            movement.update(List.of(ai), DT);
        }
        assertTrue(Math.abs(ai.getY() - startY) > 15,
                "AI 应沿墙切向滑行绕行（y " + startY + " → " + ai.getY() + "，与玩家停墙形成对照）");
    }

    // ── ⑥ P-0816-A：玩家无目标 + 障碍斥力作用范围 → 仍完全静止（自走根治）──

    @Test
    @DisplayName("⑥ 玩家无目标且处于障碍斥力作用范围：100 tick 速度恒 0、位置逐字节不变（障碍斥力不再推玩家）")
    void playerNoTargetNearObstacle_staysPerfectlyStill() {
        // 小障碍 (390,290,20,20) → 中心 (400,300)；斥力范围 r = OBSTACLE_RANGE 80 + 半宽 10 = 90
        Obstacle box = new Obstacle(Obstacle.Type.WALL, 390, 290, 20, 20, true, "小障碍");
        MovementSystem movement = ms(List.of(box));
        AgentState player = agent(340, 300);   // 距障碍中心 60px < 90，必落入斥力作用区
        player.setPlayerControlled(true);
        for (int i = 0; i < 100; i++) {
            movement.update(List.of(player), DT);
            assertEquals(340.0, player.getX(), 1e-9, "tick#" + i + " 玩家 x 不应被障碍斥力推动");
            assertEquals(300.0, player.getY(), 1e-9, "tick#" + i + " 玩家 y 不应被障碍斥力推动");
            assertEquals(0.0, player.getVx(), 1e-9, "tick#" + i + " 玩家 vx 应恒 0（障碍斥力对玩家禁用）");
            assertEquals(0.0, player.getVy(), 1e-9, "tick#" + i + " 玩家 vy 应恒 0（障碍斥力对玩家禁用）");
        }
    }

    @Test
    @DisplayName("⑦ 玩家卡入障碍内部：clampToWorld 仍生效推出防卡墙，推挤后速度归零完全静止")
    void playerInsideObstacle_pushedOutByClamp_thenStill() {
        Obstacle box = new Obstacle(Obstacle.Type.WALL, 390, 290, 20, 20, true, "小障碍");
        MovementSystem movement = ms(List.of(box));
        AgentState player = agent(398, 300);   // 障碍内部（rect 390-410 × 290-310），最小穿透边=左（8px）
        player.setPlayerControlled(true);
        for (int i = 0; i < 50; i++) {
            movement.update(List.of(player), DT);
        }
        // 第一 tick 即被 clampToWorld 沿最小穿透边推出到障碍左缘外 14px（PUSH_CLEARANCE）
        assertEquals(376.0, player.getX(), 1e-9, "玩家应从障碍内部被推出到左缘外（x=" + player.getX() + "）");
        assertEquals(300.0, player.getY(), 1e-9);
        assertEquals(0.0, player.getVx(), 1e-9, "推出后玩家 vx 应恒 0（速度归零，不反弹）");
        assertEquals(0.0, player.getVy(), 1e-9, "推出后玩家 vy 应恒 0");
    }

    // ── ⑧ 对话中玩家：无 manualTarget 仍冻结 / 有 manualTarget 仍移动（既有语义零破坏）──

    @Test
    @DisplayName("⑧ 玩家对话中且无 manualTarget：仍完全冻结（vx/vy=0、位置不变，对话冻结语义保留）")
    void playerInConversation_noManualTarget_stillFrozen() {
        MovementSystem movement = ms(List.of());
        AgentState player = agent(100, 100);
        player.setPlayerControlled(true);
        player.setInConversation(true);
        for (int i = 0; i < 100; i++) {
            movement.update(List.of(player), DT);
            assertEquals(100.0, player.getX(), 1e-9, "tick#" + i + " 对话冻结：玩家 x 不应移动");
            assertEquals(100.0, player.getY(), 1e-9, "tick#" + i + " 对话冻结：玩家 y 不应移动");
            assertEquals(0.0, player.getVx(), 1e-9, "tick#" + i + " 对话冻结：vx 恒 0");
            assertEquals(0.0, player.getVy(), 1e-9, "tick#" + i + " 对话冻结：vy 恒 0");
        }
    }

    @Test
    @DisplayName("⑨ 玩家对话中且有 manualTarget：仍走向目标（对话中手动目标特判保留，P-0813-E）")
    void playerInConversation_withManualTarget_stillWalks() {
        MovementSystem movement = ms(List.of());
        AgentState player = agent(100, 100);
        player.setPlayerControlled(true);
        player.setInConversation(true);
        player.setTarget(500, 300);
        player.setManualTarget(true);
        boolean moved = false;
        for (int i = 0; i < 60; i++) {
            movement.update(List.of(player), DT);
            if (player.getX() > 150) { moved = true; break; }
        }
        assertTrue(moved, "对话中玩家手动目标应继续走向目标（最终 x=" + player.getX() + "）");
    }

    // ── ⑩ AI 对照：AI 靠墙仍被障碍斥力推（AI 行为逐字节不变）──

    @Test
    @DisplayName("⑩ 对照：AI 无目标处于障碍斥力范围 → 仍被斥力推开（障碍斥力对 AI 保留）")
    void aiNearObstacle_stillPushedByRepulsion() {
        Obstacle box = new Obstacle(Obstacle.Type.WALL, 390, 290, 20, 20, true, "小障碍");
        MovementSystem movement = ms(List.of(box));
        AgentState ai = agent(340, 300);   // 障碍在右（中心 x=400），斥力应把 AI 推离（向左/远离）
        for (int i = 0; i < 5; i++) {
            movement.update(List.of(ai), DT);
        }
        assertTrue(ai.getX() < 330, "AI 应被障碍斥力推离障碍（x 340 → " + ai.getX() + "，应减小）");
        assertTrue(ai.getVx() < -0.5, "AI 应有指向远离障碍的斥力速度（vx=" + ai.getVx() + "）");
    }
}
