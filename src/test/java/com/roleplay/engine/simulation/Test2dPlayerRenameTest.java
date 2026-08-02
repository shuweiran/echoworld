package com.roleplay.engine.simulation;

import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.entity.CharacterEntity;
import com.roleplay.engine.db.repository.CharacterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改造方案《玩家角色改名与 AI 识别》Phase 2 判定测试（方案 §8 用例 1，P-0802-P2）：
 * 2D 世界 playerControlled —— initWithPersonas 带 player_id 解析式标记玩家控制。
 *
 * <p>场景：角色库中「小明」已改名为「大明」（playerId 绑定随新名迁移，Phase 1 特性），
 * 前端仍传旧名 playerName=小明 + player_id → 判定解析出「大明」→ 标记 playerControlled
 * （主控导演/AI 不再替玩家角色说话）。
 *
 * <p>关键回归断言：无 player_id 请求行为与现状逐字节一致（显式 playerName 规则 / 旧规则 me）。
 * 走 application-test.yml（H2 mem create-drop + mock LLM + RANDOM_PORT，D-008 基建）。
 * 角色绑定直接经 CharacterRepository 落库（本批不测角色库接口，那是 Phase 1 范围）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class Test2dPlayerRenameTest {

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private SimulationWorld world;

    @Autowired
    private CharacterRepository characterRepo;

    /** 绑定：player_id → 角色名（直接落库；改名后绑定随新名，Phase 1 特性）。 */
    private void bind(String playerId, String name) {
        characterRepo.save(new CharacterEntity(name, "性格", "声音", "背景", playerId));
    }

    // ── ① playerId 解析式标记：角色已改名，旧名 playerName + player_id → 解析名被标记 ──

    @Test
    @DisplayName("① initWithPersonas 带 playerId：角色改名后旧名 playerName + player_id → 解析名被标记 playerControlled")
    void initWithPersonas_playerId_marksResolvedName() {
        bind("pid-2d", "大明");

        simulationService.initWithPersonas(
                List.of(new Persona("大明", "性格"), new Persona("小红", "性格")),
                "park", "小明", "pid-2d");

        assertTrue(world.getState("大明").isPlayerControlled(), "解析名「大明」应被标记 playerControlled");
        assertFalse(world.getState("小红").isPlayerControlled(), "其他角色不应被标记");
    }

    // ── ② 无 playerId 三参旧路径：显式 playerName 行为不变（零变化回归） ──

    @Test
    @DisplayName("② 无 playerId：三参显式 playerName 行为不变（回归）")
    void initWithPersonas_noPlayerId_explicitNameUnchanged() {
        simulationService.initWithPersonas(
                List.of(new Persona("小明", "性格"), new Persona("小红", "性格")),
                "park", "小红");
        assertTrue(world.getState("小红").isPlayerControlled(), "显式 playerName 应被标记（旧行为）");
        assertFalse(world.getState("小明").isPlayerControlled());
    }

    // ── ③ 无 playerId 三参旧路径：未传 playerName → 旧规则名字 me 标记（零变化回归） ──

    @Test
    @DisplayName("③ 无 playerId：未传 playerName → 旧规则名字 me 标记（回归）")
    void initWithPersonas_noPlayerId_legacyMeRule() {
        simulationService.initWithPersonas(
                List.of(new Persona("me", "性格"), new Persona("小红", "性格")),
                "park");
        assertTrue(world.getState("me").isPlayerControlled(), "旧规则：名字 me 应被标记（回归）");
        assertFalse(world.getState("小红").isPlayerControlled());
    }

    // ── ④ player_id 未绑定 → 回退 playerName 字符串逻辑（零变化回归） ──

    @Test
    @DisplayName("④ player_id 未绑定（解析空）：回退 playerName 字符串标记（零变化）")
    void initWithPersonas_unboundPlayerId_fallsBack() {
        simulationService.initWithPersonas(
                List.of(new Persona("小明", "性格"), new Persona("小红", "性格")),
                "park", "小明", "pid-unknown");
        assertTrue(world.getState("小明").isPlayerControlled(), "解析空应回退 playerName 标记（旧行为）");
        assertFalse(world.getState("小红").isPlayerControlled());
    }
}
