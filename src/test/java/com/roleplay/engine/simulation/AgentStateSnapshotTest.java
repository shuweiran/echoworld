package com.roleplay.engine.simulation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P-0815-G（玩家地图运动控制深度调研）：AgentState.toMap 快照字段契约。
 *
 * <p>根因链：SSE 全量快照广播已被节流为每 2 tick（400ms）一次（P-0815-E/F），
 * 前端 SimulationScene.update 的速度外推（P-0813-G）依赖快照携带 vx/vy（sp>1 才激活），
 * 但 toMap 此前不输出 vx/vy → 外推恒不生效 → 角色每 400ms「突进+冻结」循环（移动卡顿）。
 * 本批为 toMap 补 vx/vy 键（附加键，零破坏），激活既有外推。</p>
 */
class AgentStateSnapshotTest {

    @Test
    void toMapIncludesVelocity() {
        AgentState st = new AgentState("测试角色", 100, 200);
        st.setVx(90.0);
        st.setVy(-45.5);
        Map<String, Object> m = st.toMap();
        assertEquals("测试角色", m.get("agentName"));
        assertEquals(100.0, m.get("x"));
        assertEquals(200.0, m.get("y"));
        assertEquals(90.0, m.get("vx"));
        assertEquals(-45.5, m.get("vy"));
    }

    @Test
    void toMapRoundingAndZero() {
        AgentState st = new AgentState("静止角色", 10, 20);
        Map<String, Object> m = st.toMap();
        assertNotNull(m.get("vx"));
        assertNotNull(m.get("vy"));
        assertEquals(0.0, m.get("vx"));
        assertEquals(0.0, m.get("vy"));
        // 精度：与 x/y 相同的 2 位小数截断
        st.setVx(1.23456);
        st.setVy(-9.8765);
        assertEquals(1.23, st.toMap().get("vx"));
        assertEquals(-9.88, st.toMap().get("vy"));
    }

    @Test
    void movingStateKeepsExistingKeys() {
        AgentState st = new AgentState("移动角色", 300, 150);
        st.setMoveSpeed(95.0);
        st.setPlayerControlled(true);
        st.setTarget(500, 400);
        Map<String, Object> m = st.toMap();
        // 既有契约键全部保留（附加键零破坏）
        assertEquals(300.0, m.get("x"));
        assertEquals(150.0, m.get("y"));
        assertEquals(95.0, m.get("moveSpeed"));
        assertEquals(true, m.get("playerControlled"));
        assertEquals(true, m.get("hasTarget"));
        assertEquals(500.0, m.get("targetX"));
        assertEquals(400.0, m.get("targetY"));
        // 新键
        assertEquals(0.0, m.get("vx"));
        assertEquals(0.0, m.get("vy"));
    }
}
