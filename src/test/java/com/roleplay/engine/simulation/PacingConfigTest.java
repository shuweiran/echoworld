package com.roleplay.engine.simulation;

import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Persona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0813-F：节奏参数默认值断言 + 2D 世界接线断言（roleplay.pacing.*）。
 *
 * <p>覆盖（任务要求①）：yml 双份默认值经 AppConfig 绑定正确；SimulationService 构造注入
 * 生效（导演轮间隔 / 移动速度基准）；initWithPersonas 实际移动速度落在 [base, base+range]；
 * ConversationManager 经 2D 世界接线 pacing 启用（conversation-status pacing.state=idle）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class PacingConfigTest {

    @Autowired
    private AppConfig appConfig;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private SimulationWorld world;

    @BeforeEach
    void reset() {
        world.clearAgents();
    }

    @Test
    @DisplayName("① 节奏参数默认值：yml 双份绑定 AppConfig（enabled/间隔/倍率/移动速度）")
    void pacingDefaults_boundFromYml() {
        AppConfig.PacingConfig pacing = appConfig.getPacing();
        assertNotNull(pacing, "roleplay.pacing.* 应绑定到 AppConfig");
        assertTrue(pacing.isEnabled(), "enabled 默认 true");
        assertEquals(15_000, pacing.getDirectorIntervalMs(), "导演轮间隔默认 15000ms（原 10000 降低）");
        assertEquals(8_000, pacing.getConversationCooldownMs(), "组对再建冷却默认 8000ms（原 5000 降低）");
        assertEquals(2_000, pacing.getRoundCooldownMs(), "DYAD 轮次基础间隔默认 2000ms");
        assertEquals(3_000, pacing.getGroupRoundCooldownMs(), "群聊轮次基础间隔默认 3000ms");
        assertEquals(1.5, pacing.getIdleRoundMultiplier(), 1e-9, "未对话态轮次倍率默认 1.5");
        assertEquals(4.0, pacing.getInactiveTrackMultiplier(), 1e-9, "非玩家轨道倍率默认 4.0（P-0813-H：比 F 的 ×2 更强，间隔大幅拉长）");
        assertEquals(4_000L, pacing.getSpeechBubbleHoldMs(), "非玩家轨道发言气泡停留/展示时长默认 4000ms");
        assertEquals(45, pacing.getMoveSpeedBase(), 1e-9, "移动速度基准默认 45");
        assertEquals(35, pacing.getMoveSpeedRandomRange(), 1e-9, "移动速度随机幅度默认 35");
    }

    @Test
    @DisplayName("①b SimulationService 接线：导演轮间隔与移动速度参数从 AppConfig 注入生效")
    void simulationService_wiredFromPacingConfig() {
        assertEquals(15_000, simulationService.getDirectorIntervalMs(), "导演轮间隔应取配置 15000ms");
        assertEquals(45, simulationService.getMoveSpeedBase(), 1e-9, "移动速度基准应取配置 45");
        assertEquals(35, simulationService.getMoveSpeedRandomRange(), 1e-9, "移动速度幅度应取配置 35");
    }

    @Test
    @DisplayName("①c 实际移动速度：initWithPersonas 落点 [45, 80)（base + rand×range）")
    void moveSpeed_fallsInConfiguredBand() {
        simulationService.initWithPersonas(
                List.of(new Persona("甲", "性格直爽"), new Persona("乙", "性格温和"),
                        new Persona("丙", "沉默寡言"), new Persona("丁", "活泼开朗")),
                "park");
        assertEquals(4, world.getAgentCount());
        for (String name : world.getAgentNames()) {
            double speed = world.getState(name).getMoveSpeed();
            assertTrue(speed >= 45 - 1e-9, name + " 速度应 ≥ base 45，实际 " + speed);
            assertTrue(speed < 80 + 1e-9, name + " 速度应 < base+range 80，实际 " + speed);
        }
    }

    @Test
    @DisplayName("①d 对话状态可查询：2D 世界 pacing 已启用且无玩家轨道时 state=idle")
    void conversationStatus_reportsPacingIdle() {
        simulationService.initWithPersonas(
                List.of(new Persona("甲", "性格直爽"), new Persona("乙", "性格温和")),
                "park");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> status = simulationService.getConversationStatus();
        assertTrue((Boolean) status.get("pacingEnabled"), "2D 世界 ConversationManager 应注入 pacing");
        // P-0813-H：2D 世界节奏参数下发——气泡停留时长 + 非玩家轨道 ×4 拉长
        assertNotNull(status.get("pacing"), "conversation-status 应含 pacing 段");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> pacing = (java.util.Map<String, Object>) status.get("pacing");
        assertEquals(4_000L, ((Number) pacing.get("speechBubbleHoldMs")).longValue(),
                "气泡停留/展示时长 4000ms 下发（前端可作气泡展示时长/展示间隔）");
        assertEquals(4.0, ((Number) pacing.get("inactiveMultiplier")).doubleValue(), 1e-9,
                "非玩家轨道 ×4 拉长下发");
        assertEquals("", status.get("currentTrack"), "无玩家轨道时 currentTrack 应为空");
        assertEquals("idle", pacing.get("state"), "未进入对话 → pacing.state=idle");
        assertEquals(1.5, ((Number) pacing.get("idleMultiplier")).doubleValue(), 1e-9);
        assertEquals(8_000L, ((Number) pacing.get("conversationCooldownMs")).longValue());
    }
}
