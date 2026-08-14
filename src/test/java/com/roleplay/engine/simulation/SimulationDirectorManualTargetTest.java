package com.roleplay.engine.simulation;

import com.roleplay.engine.core.Persona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0813-E：③ 导演轮每 ~10s 覆盖玩家手动目标 —— 回归测试。
 *
 * <p>根因（tmp/调查-玩家控制与地图质量-20260813.md）：SimulationService.runDirectorRound 只跳过
 * playerControlled、不跳 manualTarget → 玩家点击的目标 ~10s 内被导演重设（实测 (200,300)→(560,·)）。
 * 修复：applyDirectorDecisions 对 manualTarget 角色与 playerControlled 同等对待——未超时（60s）
 * 不覆盖；超时后释放标记，导演恢复接管。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class SimulationDirectorManualTargetTest {

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private SimulationWorld world;

    @BeforeEach
    void reset() {
        world.clearAgents();
    }

    private void initTwoAgents() {
        simulationService.initWithPersonas(
                List.of(new Persona("甲", "性格直爽"), new Persona("乙", "性格温和")),
                "park");
    }

    private static Map<String, Object> decision(String agent, int x, int y) {
        return Map.of("agent", agent, "target_x", x, "target_y", y);
    }

    @Test
    @DisplayName("③ 未超时 manualTarget：导演决定不覆盖手动目标（玩家点击 (200,300) 不被导演改）")
    void freshManualTarget_notOverwrittenByDirector() {
        initTwoAgents();
        AgentState jia = world.getState("甲");
        jia.setTarget(200, 300);
        jia.setManualTarget(true); // /target 端点路径

        simulationService.applyDirectorDecisions(List.of(
                decision("甲", 900, 100),   // 导演想把甲改到 (900,100)
                decision("乙", 400, 500)));

        assertEquals(200, jia.getTargetX(), 1e-9, "手动目标 x 不应被导演覆盖");
        assertEquals(300, jia.getTargetY(), 1e-9, "手动目标 y 不应被导演覆盖");
        assertTrue(jia.isManualTarget(), "未超时手动目标标记应保留");
        assertTrue(jia.isHasTarget(), "手动目标应保持有效");

        // 对照：无手动目标的乙仍被导演正常接管
        AgentState yi = world.getState("乙");
        assertEquals(400, yi.getTargetX(), 1e-9, "无手动目标的角色应被导演正常重设");
        assertEquals(500, yi.getTargetY(), 1e-9);
        assertTrue(yi.isHasTarget());
    }

    @Test
    @DisplayName("③ manualTarget 超时（>60s）：释放标记，导演恢复接管（玩家点击新目标即重新计时）")
    void expiredManualTarget_directorTakesOver() {
        initTwoAgents();
        AgentState jia = world.getState("甲");
        jia.setTarget(200, 300);
        jia.setManualTarget(true);
        // 模拟超时：时间戳拨回 61s 前（测试支持方法，避免真实等待）
        jia.setManualTargetSinceForTest(System.currentTimeMillis() - 61_000);

        simulationService.applyDirectorDecisions(List.of(decision("甲", 900, 100)));

        assertEquals(900, jia.getTargetX(), 1e-9, "超时后导演应可重设目标");
        assertEquals(100, jia.getTargetY(), 1e-9);
        assertFalse(jia.isManualTarget(), "超时后应释放手动标记，导演恢复接管");
    }

    @Test
    @DisplayName("③ 手动目标到达后自动释放（clearTarget 路径）：导演可接管（回归）")
    void reachedManualTarget_releasesFlag() {
        initTwoAgents();
        AgentState jia = world.getState("甲");
        jia.setTarget(200, 300);
        jia.setManualTarget(true);
        // MovementSystem 到达目标（dist<5）→ clearTarget → manualTarget 一并释放
        jia.clearTarget();

        simulationService.applyDirectorDecisions(List.of(decision("甲", 900, 100)));
        assertEquals(900, jia.getTargetX(), 1e-9, "手动目标到达后导演应可接管");
        assertFalse(jia.isManualTarget());
    }

    @Test
    @DisplayName("③ 回归：playerControlled 角色仍被导演跳过（不被改目标）")
    void playerControlled_stillSkipped() {
        initTwoAgents();
        AgentState jia = world.getState("甲");
        jia.setPlayerControlled(true);
        jia.setTarget(150, 150);

        simulationService.applyDirectorDecisions(List.of(decision("甲", 800, 500)));

        assertEquals(150, jia.getTargetX(), 1e-9, "playerControlled 角色目标不应被导演覆盖（回归）");
    }

    @Test
    @DisplayName("③ 回归：对话中角色仍被导演跳过（不被改目标）")
    void inConversation_stillSkipped() {
        initTwoAgents();
        AgentState jia = world.getState("甲");
        jia.setInConversation(true);
        jia.setTarget(150, 150);

        simulationService.applyDirectorDecisions(List.of(decision("甲", 800, 500)));

        assertEquals(150, jia.getTargetX(), 1e-9, "对话中角色目标不应被导演覆盖（回归）");
    }
}
