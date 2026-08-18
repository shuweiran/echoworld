package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.core.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P-0814-I：POST /api/simulation/move-dir/{agentName} —— WASD/方向键持续移动端点。
 *
 * <p>语义：目标点 = 服务端权威坐标 + 归一化方向 × 步长（默认 90px，上限 200px），
 * 置位 manualTarget（每次调用刷新时间戳 → 持续按住时不会 60s 后被导演接管）。
 */
class SimulationMoveDirTest {

    private static final double W = SimulationWorld.WORLD_WIDTH;

    private SimulationWorld worldWith(String name, double x, double y) {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona(name, "测试角色"), "npc", null), x, y, 200, 80);
        return world;
    }

    private SimulationController controller(SimulationWorld world) {
        return new SimulationController(mock(SimulationService.class), world,
                mock(CharacterController.class));
    }

    @Test
    @DisplayName("① 基本：目标点 = 当前坐标 + 归一化方向 × 默认步长 90，置位 manualTarget")
    void moveDir_setsTargetAheadWithDefaultStep() {
        SimulationWorld world = worldWith("我", 100, 100);
        SimulationController c = controller(world);
        Map<String, Object> r = c.moveDir("我", Map.of("dx", 1.0, "dy", 0.0));
        assertEquals("ok", r.get("status"));
        AgentState st = world.getState("我");
        assertEquals(190.0, st.getTargetX(), 1e-6, "目标 x = 当前 100 + 90");
        assertEquals(100.0, st.getTargetY(), 1e-6);
        assertTrue(st.isHasTarget());
        assertTrue(st.isManualTarget(), "move-dir 应置位 manualTarget（导演/约束/日程跳过）");
    }

    @Test
    @DisplayName("② 对角方向归一化：斜向不超步长")
    void moveDir_normalizesDiagonal() {
        SimulationWorld world = worldWith("我", 100, 100);
        SimulationController c = controller(world);
        c.moveDir("我", Map.of("dx", 1.0, "dy", 1.0));
        AgentState st = world.getState("我");
        double step = Math.hypot(st.getTargetX() - 100, st.getTargetY() - 100);
        assertEquals(90.0, step, 1e-6, "斜向步长应为 90（归一化后）");
    }

    @Test
    @DisplayName("③ 显式 step + 世界边界 clamp")
    void moveDir_honorsStepAndClamps() {
        SimulationWorld world = worldWith("我", 990, 100);
        SimulationController c = controller(world);
        c.moveDir("我", Map.of("dx", 1.0, "dy", 0.0, "step", 500.0));
        AgentState st = world.getState("我");
        assertTrue(st.getTargetX() <= W - 10 + 1e-9, "目标应被 clamp 到世界内，targetX=" + st.getTargetX());
        assertEquals(W - 10, st.getTargetX(), 1e-6);
    }

    @Test
    @DisplayName("④ 持续按住刷新 manualTarget 时间戳：两次调用 since 单调递增（60s 不被导演接管）")
    void moveDir_refreshesManualTargetTimestamp() {
        SimulationWorld world = worldWith("我", 100, 100);
        SimulationController c = controller(world);
        c.moveDir("我", Map.of("dx", 1.0, "dy", 0.0));
        AgentState st = world.getState("我");
        long since1 = st.getManualTargetSince();
        assertTrue(since1 > 0);
        try { Thread.sleep(5); } catch (InterruptedException ignored) { }
        c.moveDir("我", Map.of("dx", 1.0, "dy", 0.0));
        long since2 = st.getManualTargetSince();
        assertTrue(since2 > since1, "时间戳应刷新（since1=" + since1 + " since2=" + since2 + "）");
    }

    @Test
    @DisplayName("⑤ 零方向：清除目标 + 停止（P-0816-C 新语义：松开 WASD 角色立即静止，不再滑向最后目标点）")
    void moveDir_zeroDirection_stops() {
        SimulationWorld world = worldWith("我", 100, 100);
        SimulationController c = controller(world);
        AgentState st = world.getState("我");
        st.setTarget(500, 300);
        c.moveDir("我", Map.of("dx", 0.0, "dy", 0.0));
        assertFalse(st.isHasTarget(), "零方向应清除目标（hasTarget=false）");
        assertFalse(st.isManualTarget(), "零方向应清除 manualTarget");
        assertTrue(st.getManualTargetSince() < 0, "manualTargetSince 应重置为 -1");
    }

    @Test
    @DisplayName("⑥ 未知角色 → error")
    void moveDir_unknownAgent_returnsError() {
        SimulationWorld world = worldWith("我", 100, 100);
        SimulationController c = controller(world);
        Map<String, Object> r = c.moveDir("不存在", Map.of("dx", 1.0, "dy", 0.0));
        assertEquals("error", r.get("status"));
    }
}
