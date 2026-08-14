package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0813-F：对话态降频逻辑单测（不依赖真实轮次循环时序，直调包可见 computeRoundCooldownMs）。
 *
 * <p>覆盖（任务要求②③）：未进入对话（无玩家轨道）→ 全轨道 ×idle 倍率；进入对话后
 * 当前轨道全速、其余轨道 ×inactive 倍率；模式守卫（剧本杀/狼人杀各局实例不注入 pacing
 * → 原硬编码间隔 2000/3000 零变化）；conversation-status 状态可查询。
 *
 * <p>P-0813-H 注（需求更正）：不做串行队列——本类继续测 F 的降频语义（多轨道并行 + 倍率），
 * 其中 inactive 倍率默认由 ×2 提升为 ×4（其他轨道间隔大幅拉长）。并行/拉长/气泡参数专项
 * 用例见 ConversationTrackPacingTest。
 */
class ConversationPacingTest {

    // ── 基建（同 ConversationJoinTest 风格，零 Spring）────────────

    private void register(SimulationWorld world, String name, double x, double y) {
        Persona persona = new Persona(name, "测试人格" + name);
        Agent agent = new Agent(persona, "test", null);   // null LLM: test-only
        world.registerAgent(agent, x, y, 200.0, 50.0);
    }

    private ConversationManager manager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        return cm;
    }

    /** 同步注册群组（createScriptDiscussionGroup：无后台轮次循环，确定性）。 */
    private ConversationGroup createGroup(ConversationManager cm, SimulationWorld world,
                                          String gid, String... members) {
        List<AgentState> states = java.util.Arrays.stream(members).map(world::getState).toList();
        return cm.createScriptDiscussionGroup(gid, states, Map.of());
    }

    /** 场景：A、B、C 普通 NPC；P 玩家（playerControlled）。 */
    private SimulationWorld worldWithPlayer() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "C", 6, 0);
        register(world, "P", 2, 0);
        world.getState("P").setPlayerControlled(true);
        return world;
    }

    /** 注入默认节奏参数（2D 世界接线同款：2000/3000/8000/1.5/×4.0——P-0813-H 默认 inactive ×4）。 */
    private void wireDefaultPacing(ConversationManager cm) {
        cm.setPacing(true, 2_000, 3_000, 8_000, 1.5, 4.0);
    }

    // ── ② 未进入对话：全轨道放慢 ──────────────────────────────

    @Test
    @DisplayName("② 未进入对话（无玩家轨道）：所有轨道轮次间隔 ×idle 倍率（DYAD 2000→3000 / 群聊 3000→4500）")
    void noPlayerGroup_allTracksSlowedByIdleMultiplier() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");   // NPC 组
        ConversationGroup g2 = createGroup(cm, world, "g2", "B", "C");   // NPC 组（P 不在任何组）

        assertEquals(3_000, cm.computeRoundCooldownMs(g1, ConversationMode.DYAD),
                "未进入对话：DYAD 轮次间隔 = 2000×1.5 = 3000ms");
        assertEquals(4_500, cm.computeRoundCooldownMs(g1, ConversationMode.GROUP_DISCUSSION),
                "未进入对话：群聊轮次间隔 = 3000×1.5 = 4500ms");
        assertEquals(cm.computeRoundCooldownMs(g1, ConversationMode.DYAD),
                cm.computeRoundCooldownMs(g2, ConversationMode.DYAD),
                "未进入对话：所有轨道一视同仁");
    }

    // ── ③ 进入对话：当前轨道全速、其余降频 ─────────────────────

    @Test
    @DisplayName("③ 进入对话：玩家所在轨道全速（2000/3000），其余轨道 ×inactive 倍率（4000/6000）")
    void playerGroup_fullSpeed_othersSlowed() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        ConversationGroup current = createGroup(cm, world, "cur", "P", "A");  // 玩家轨道
        ConversationGroup other = createGroup(cm, world, "oth", "B", "C");    // 非当前轨道

        assertEquals(2_000, cm.computeRoundCooldownMs(current, ConversationMode.DYAD),
                "当前轨道全速：2000ms（与注入前原行为一致）");
        assertEquals(3_000, cm.computeRoundCooldownMs(current, ConversationMode.GROUP_DISCUSSION),
                "当前轨道群聊全速：3000ms");
        assertEquals(8_000, cm.computeRoundCooldownMs(other, ConversationMode.DYAD),
                "非当前轨道降频：2000×4.0 = 8000ms（P-0813-H 默认 ×4 拉长）");
        assertEquals(12_000, cm.computeRoundCooldownMs(other, ConversationMode.GROUP_DISCUSSION),
                "非当前轨道群聊降频：3000×4.0 = 12000ms");
    }

    @Test
    @DisplayName("③b 当前轨道判定：getCurrentPlayerGroup 返回含玩家的活跃群组，无玩家轨道返回 null")
    void currentPlayerGroup_detection() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        assertNull(cm.getCurrentPlayerGroup(), "无群组时当前轨道为 null");

        ConversationGroup npc = createGroup(cm, world, "npc", "A", "B");
        assertNull(cm.getCurrentPlayerGroup(), "玩家不在任何组 → 当前轨道仍为 null（未进入对话）");

        ConversationGroup current = createGroup(cm, world, "cur", "P", "C");
        assertEquals("cur", cm.getCurrentPlayerGroup().getGroupId(), "玩家所在组即当前对话轨道");
        // npc 组不受影响
        assertEquals(8_000, cm.computeRoundCooldownMs(npc, ConversationMode.DYAD),
                "进入对话后 NPC 组间隔拉长 ×4");
    }

    // ── 模式守卫：剧本杀/狼人杀各局实例不注入 → 原行为零变化 ────

    @Test
    @DisplayName("④ 模式守卫：不注入 pacing（剧本杀/狼人杀各局实例）→ 原硬编码间隔 2000/3000 零变化")
    void notWired_pacingDisabled_originalCooldowns() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);   // 未调用 setPacing
        ConversationGroup g1 = createGroup(cm, world, "g1", "P", "A");

        assertFalse(cm.isPacingEnabled(), "未注入 → pacing 禁用");
        assertEquals(2_000, cm.computeRoundCooldownMs(g1, ConversationMode.DYAD),
                "原行为：DYAD 2000ms 不变");
        assertEquals(3_000, cm.computeRoundCooldownMs(g1, ConversationMode.GROUP_DISCUSSION),
                "原行为：群聊 3000ms 不变");
    }

    @Test
    @DisplayName("④b 模式守卫：显式 setPacing(false)（enabled=false）→ 同样回退原间隔")
    void explicitlyDisabled_originalCooldowns() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        cm.setPacing(false, 2_000, 3_000, 8_000, 1.5, 4.0);
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");

        assertFalse(cm.isPacingEnabled());
        assertEquals(2_000, cm.computeRoundCooldownMs(g1, ConversationMode.DYAD));
        assertEquals(3_000, cm.computeRoundCooldownMs(g1, ConversationMode.GROUP_DISCUSSION));
    }

    // ── 自定义参数生效 ─────────────────────────────────────────

    @Test
    @DisplayName("⑤ 自定义节奏参数：setPacing 传入值精确生效（round=4000 idle=2.0 → 8000）")
    void customPacingValues_applied() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        cm.setPacing(true, 4_000, 5_000, 10_000, 2.0, 3.0);
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");

        assertEquals(8_000, cm.computeRoundCooldownMs(g1, ConversationMode.DYAD),
                "未对话态：4000×2.0 = 8000ms");
        assertEquals(10_000, cm.computeRoundCooldownMs(g1, ConversationMode.GROUP_DISCUSSION),
                "未对话态：5000×2.0 = 10000ms");
    }

    // ── 对话状态可查询（前端配合）──────────────────────────────

    @Test
    @DisplayName("⑥ conversation-status：pacing 段状态/currentTrack 随对话建立切换（idle ↔ in-dialogue）")
    void status_reportsPacingStateTransitions() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);

        Map<String, Object> statusBefore = cm.getStatus();
        assertEquals("", statusBefore.get("currentTrack"), "未进入对话 → currentTrack 空");
        @SuppressWarnings("unchecked")
        Map<String, Object> pacingBefore = (Map<String, Object>) statusBefore.get("pacing");
        assertEquals("idle", pacingBefore.get("state"));

        ConversationGroup current = createGroup(cm, world, "cur", "P", "A");   // 玩家进入对话
        Map<String, Object> statusIn = cm.getStatus();
        assertEquals("cur", statusIn.get("currentTrack"), "进入对话 → currentTrack=cur");
        @SuppressWarnings("unchecked")
        Map<String, Object> pacingIn = (Map<String, Object>) statusIn.get("pacing");
        assertEquals("in-dialogue", pacingIn.get("state"));
        assertEquals(4.0, ((Number) pacingIn.get("inactiveMultiplier")).doubleValue(), 1e-9, "P-0813-H 默认 ×4 拉长");

        // 玩家离开 → 恢复 idle
        cm.leaveGroup("cur", "P");
        assertEquals("", cm.getStatus().get("currentTrack"), "对话结束 → 恢复未对话态（currentTrack 空）");
        @SuppressWarnings("unchecked")
        Map<String, Object> pacingAfter = (Map<String, Object>) cm.getStatus().get("pacing");
        assertEquals("idle", pacingAfter.get("state"), "对话结束 → 节奏恢复未对话态");
    }
}
