package com.roleplay.engine.simulation;

import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.db.service.DatabaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P-0814-I：玩家角色分速 —— playerControlled 用高速度（默认 90-105 px/s），AI 维持 45-80。
 *
 * <p>背景（docs/调研报告-移动与分组问题.md 1.3 R2）：P-0813-F 为降 AI 对话密度把全局速度调到
 * 45-80 px/s，玩家角色同样被拖慢 → 主观「几乎不动」。本批玩家/AI 分速：玩家角色提速，
 * AI 保持低速（AI 密度控制不受影响）。配置键 roleplay.pacing.move-speed-player-*（yml 双份）。
 */
class SimulationPlayerSpeedTest {

    private static final double PLAYER_BASE = 90.0;
    private static final double PLAYER_RANGE = 15.0;
    private static final double AI_BASE = 45.0;
    private static final double AI_RANGE = 35.0;

    private static SimulationService buildSim(SimulationWorld world) {
        InterruptManager im = new InterruptManager(new WorldEventBus());
        return new SimulationService(world, mock(LLMClient.class), mock(DatabaseService.class),
                im, new AgentTaskManager(im), new WorldEventBus(),
                mock(AnnouncementService.class), null, new AppConfig());
    }

    @Test
    @DisplayName("① 显式 playerName：玩家角色标记 playerControlled 且速度 90-105，AI 保持 45-80")
    void playerGetsHighSpeed_aiKeepsBaseSpeed() {
        SimulationWorld world = new SimulationWorld();
        SimulationService sim = buildSim(world);
        sim.initWithPersonas(
                List.of(new Persona("我", "主角"), new Persona("阿杰", "AI角色")),
                "park", "我");

        AgentState player = world.getState("我");
        AgentState ai = world.getState("阿杰");
        assertNotNull(player);
        assertNotNull(ai);
        assertTrue(player.isPlayerControlled(), "显式 playerName 的角色应标记玩家控制");
        assertTrue(player.getMoveSpeed() >= PLAYER_BASE && player.getMoveSpeed() <= PLAYER_BASE + PLAYER_RANGE,
                "玩家角色速度应落在 [" + PLAYER_BASE + ", " + (PLAYER_BASE + PLAYER_RANGE) + "]，实际 " + player.getMoveSpeed());
        assertFalse(ai.isPlayerControlled(), "AI 角色不应标记玩家控制");
        assertTrue(ai.getMoveSpeed() >= AI_BASE && ai.getMoveSpeed() <= AI_BASE + AI_RANGE,
                "AI 速度应保持 [" + AI_BASE + ", " + (AI_BASE + AI_RANGE) + "]，实际 " + ai.getMoveSpeed());
    }

    @Test
    @DisplayName("② 旧规则兼容：无显式 playerName 时名字为 'me' 的 agent 标记玩家控制并走玩家分速")
    void meAgent_getsPlayerControlledAndPlayerSpeed() {
        SimulationWorld world = new SimulationWorld();
        SimulationService sim = buildSim(world);
        sim.initWithPersonas(
                List.of(new Persona("me", "主角"), new Persona("阿杰", "AI角色")),
                "park");

        AgentState me = world.getState("me");
        assertNotNull(me);
        assertTrue(me.isPlayerControlled(), "'me' 角色应标记玩家控制（旧规则向后兼容）");
        assertTrue(me.getMoveSpeed() >= PLAYER_BASE, "'me' 角色应走玩家分速，实际 " + me.getMoveSpeed());
        assertFalse(world.getState("阿杰").isPlayerControlled());
    }

    @Test
    @DisplayName("③ 配置默认值：AppConfig 默认 90/15，与 yml 双份一致")
    void configDefaults_reflectPacingKeys() {
        AppConfig cfg = new AppConfig();
        assertEquals(PLAYER_BASE, cfg.getPacing().getMoveSpeedPlayerBase(), "move-speed-player-base 默认 90");
        assertEquals(PLAYER_RANGE, cfg.getPacing().getMoveSpeedPlayerRandomRange(), "move-speed-player-random-range 默认 15");
        assertEquals(45.0, cfg.getPacing().getMoveSpeedBase(), "AI 基准不变（45）");
        assertEquals(35.0, cfg.getPacing().getMoveSpeedRandomRange(), "AI 随机幅度不变（35）");
    }

    @Test
    @DisplayName("④ 服务接线：SimulationService 从 AppConfig 读取玩家分速配置")
    void serviceReadsPlayerSpeedConfig() {
        SimulationWorld world = new SimulationWorld();
        SimulationService sim = buildSim(world);
        assertEquals(PLAYER_BASE, sim.getMoveSpeedPlayerBase(), 1e-9);
        assertEquals(PLAYER_RANGE, sim.getMoveSpeedPlayerRandomRange(), 1e-9);
    }
}
