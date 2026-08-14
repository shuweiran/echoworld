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
 * P-0813-H v3（需求更正）：多轨道并行 + 节奏拉长 + 气泡参数下发。
 *
 * <p>主人澄清修正（取代此前两版串行队列设计）：①多轨道并行聊天保持——多个对话群同时存在、
 * 同时推进（不做 RUNNING/WAITING 全局队列、不做「同一时刻只跑一个群」）；②轨道内串行
 * （成员按顺序轮流发言）为既有回合制机制，保持并确认；③玩家所在轨道全速，其他轨道轮次
 * 间隔大幅拉长（inactive 倍率默认 ×4，比 F 的 ×2 更强，可调 ×3~×5）；④非玩家轨道发言的
 * 气泡停留/展示时长参数暴露（pacing.speechBubbleHoldMs 下发，前端可配或后端下发）。
 *
 * <p>覆盖：多群并行推进（无串行队列残留）+ 非玩家轨道间隔拉长（×4 默认 / ×5 自定义）+
 * 玩家轨道全速 + idle 全轨道 ×1.5 保持 + 轨道内回合制轮流记账保持 + 气泡参数下发 +
 * 模式守卫（剧本杀/狼人杀各局实例不注入 pacing → 原行为零变化）。
 */
class ConversationTrackPacingTest {

    // ── 基建（同 ConversationPacingTest 风格，零 Spring）────────────

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

    /** 场景：A、B、C、D、E、F 普通 NPC；P 玩家（playerControlled）。 */
    private SimulationWorld worldWithPlayer() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "C", 6, 0);
        register(world, "D", 9, 0);
        register(world, "E", 12, 0);
        register(world, "F", 15, 0);
        register(world, "P", 2, 0);
        world.getState("P").setPlayerControlled(true);
        return world;
    }

    /** 注入默认节奏参数（2D 世界接线同款：2000/3000/8000/1.5/×4.0，气泡 4000）。 */
    private void wireDefaultPacing(ConversationManager cm) {
        cm.setPacing(true, 2_000, 3_000, 8_000, 1.5, 4.0);
        cm.setSpeechBubbleHoldMs(4_000);
    }

    // ── ① 多轨道并行推进（无串行队列残留）────────────────────────

    @Test
    @DisplayName("① 多群并行推进：多个对话群同时存在、同时推进（无 RUNNING/WAITING 队列、无闸门、无挂起）")
    void multipleGroups_runInParallel_noSerialQueue() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");
        ConversationGroup g2 = createGroup(cm, world, "g2", "C", "D");
        ConversationGroup g3 = createGroup(cm, world, "g3", "E", "F");

        assertEquals(3, cm.getActiveGroupCount(), "三个对话群同时存在");
        assertTrue(g1.isActive() && g2.isActive() && g3.isActive(), "所有群均活跃（并行推进）");
        // 每群都可计算轮次间隔（并行推进的节奏输入），无任何群被挂起
        for (ConversationGroup g : List.of(g1, g2, g3)) {
            assertTrue(cm.computeRoundCooldownMs(g, ConversationMode.DYAD) > 0, g.getGroupId() + " 可推进");
        }
        // 串行队列残留检查：状态暴露不含 queue / serial / 群调度状态
        Map<String, Object> status = cm.getStatus();
        assertFalse(status.containsKey("serialEnabled"), "串行队列概念已废弃：无 serialEnabled");
        assertFalse(status.containsKey("queue"), "串行队列概念已废弃：无 queue");
        assertEquals(3, status.get("activeGroups"), "并行：三群同存");
    }

    // ── ② 非玩家轨道间隔大幅拉长（×4 默认，比 F 的 ×2 更强）──────

    @Test
    @DisplayName("② 玩家进对话：玩家轨道全速（2000/3000），其他轨道 ×4 大幅拉长（8000/12000）")
    void playerTrack_fullSpeed_otherTracksStretchedBy4() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        ConversationGroup current = createGroup(cm, world, "cur", "P", "A");
        ConversationGroup other = createGroup(cm, world, "oth", "B", "C");

        assertEquals(2_000, cm.computeRoundCooldownMs(current, ConversationMode.DYAD),
                "玩家轨道全速：2000ms（与 F 一致）");
        assertEquals(3_000, cm.computeRoundCooldownMs(current, ConversationMode.GROUP_DISCUSSION),
                "玩家轨道群聊全速：3000ms");
        assertEquals(8_000, cm.computeRoundCooldownMs(other, ConversationMode.DYAD),
                "其他轨道大幅拉长：2000×4.0 = 8000ms（F 的 ×2=4000 更强）");
        assertEquals(12_000, cm.computeRoundCooldownMs(other, ConversationMode.GROUP_DISCUSSION),
                "其他轨道群聊拉长：3000×4.0 = 12000ms");
        // 其他轨道仍并行推进（可计算间隔、未挂起）
        assertTrue(other.isActive(), "其他轨道保持并行推进（不挂起）");
    }

    @Test
    @DisplayName("②b 自定义 ×5：inactive 倍率可调（×5 → 10000/15000）")
    void customInactiveMultiplier_x5() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        cm.setPacing(true, 2_000, 3_000, 8_000, 1.5, 5.0);
        ConversationGroup current = createGroup(cm, world, "cur", "P", "A");
        ConversationGroup other = createGroup(cm, world, "oth", "B", "C");

        assertEquals(10_000, cm.computeRoundCooldownMs(other, ConversationMode.DYAD), "2000×5.0 = 10000ms");
        assertEquals(15_000, cm.computeRoundCooldownMs(other, ConversationMode.GROUP_DISCUSSION), "3000×5.0 = 15000ms");
        assertEquals(2_000, cm.computeRoundCooldownMs(current, ConversationMode.DYAD), "玩家轨道仍全速");
    }

    // ── ③ idle 全轨道 ×1.5 保持（F 行为不变）────────────────────

    @Test
    @DisplayName("③ 未进入对话：全轨道 ×idle 倍率（DYAD 2000→3000 / 群聊 3000→4500，F 行为保持）")
    void noPlayerGroup_allTracksSlowedByIdleMultiplier() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");
        ConversationGroup g2 = createGroup(cm, world, "g2", "C", "D");

        assertEquals(3_000, cm.computeRoundCooldownMs(g1, ConversationMode.DYAD), "2000×1.5 = 3000ms");
        assertEquals(4_500, cm.computeRoundCooldownMs(g1, ConversationMode.GROUP_DISCUSSION), "3000×1.5 = 4500ms");
        assertEquals(cm.computeRoundCooldownMs(g1, ConversationMode.DYAD),
                cm.computeRoundCooldownMs(g2, ConversationMode.DYAD), "idle 时所有轨道一视同仁");
    }

    // ── ④ 轨道内串行（成员轮流发言）保持 ─────────────────────────

    @Test
    @DisplayName("④ 轨道内串行保持：成员按顺序轮流发言的回合制记账（每成员一轮、round 递增一次）")
    void intraTrackSerial_turnBasedAccountingKept() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        // DYAD：两名成员一轮 = 2 次发言；recordTurn 是策略结算的记账点（成员轮流入史）
        ConversationGroup dyad = createGroup(cm, world, "g1", "A", "B");
        assertEquals(0, dyad.getRoundCount());

        dyad.recordTurn("A", "第一条");
        assertEquals(1, dyad.getRoundCount(), "成员 A 发言 → 第 1 轮");
        dyad.recordTurn("B", "第二条");
        assertEquals(1, dyad.getRoundCount(), "成员 B 发言完成第 1 轮（两人一轮）");
        dyad.recordTurn("A", "第三条");
        assertEquals(2, dyad.getRoundCount(), "A 再次发言 → 第 2 轮（轮流推进）");
        dyad.recordTurn("B", "第四条");
        assertEquals(2, dyad.getRoundCount());

        // 三人群聊：每 3 条发言一轮
        ConversationGroup trio = createGroup(cm, world, "g2", "C", "D", "E");
        for (int r = 1; r <= 2; r++) {
            for (int m = 0; m < 3; m++) {
                trio.recordTurn("成员" + m, "发言");
            }
            assertEquals(r, trio.getRoundCount(), "三人轮流一圈 = 1 轮");
        }
        assertEquals(6, trio.getTurnCount());
    }

    // ── ⑤ 气泡停留时长参数下发（前端可配/后端下发）──────────────

    @Test
    @DisplayName("⑤ 气泡参数下发：conversation-status 的 pacing.speechBubbleHoldMs 暴露（默认 4000 / 自定义 6000）")
    void bubbleHoldMs_exposedViaStatus() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);

        @SuppressWarnings("unchecked")
        Map<String, Object> pacing = (Map<String, Object>) cm.getStatus().get("pacing");
        assertEquals(4_000L, ((Number) pacing.get("speechBubbleHoldMs")).longValue(),
                "默认气泡停留/展示时长 4000ms 下发");

        cm.setSpeechBubbleHoldMs(6_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> pacing2 = (Map<String, Object>) cm.getStatus().get("pacing");
        assertEquals(6_000L, ((Number) pacing2.get("speechBubbleHoldMs")).longValue(),
                "自定义气泡停留时长 6000ms 下发（前端可配/后端下发）");
    }

    // ── ⑥ 模式守卫：剧本杀/狼人杀各局实例不注入 → 原行为零变化 ────

    @Test
    @DisplayName("⑥ 不注入 pacing：轮次间隔回退原硬编码 2000/3000、pacing.state=disabled、无气泡参数")
    void notWired_originalBehavior() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);   // 未调用 setPacing
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");

        assertFalse(cm.isPacingEnabled(), "未注入 → pacing 禁用");
        assertEquals(2_000, cm.computeRoundCooldownMs(g1, ConversationMode.DYAD), "原行为：DYAD 2000ms");
        assertEquals(3_000, cm.computeRoundCooldownMs(g1, ConversationMode.GROUP_DISCUSSION), "原行为：群聊 3000ms");
        @SuppressWarnings("unchecked")
        Map<String, Object> pacing = (Map<String, Object>) cm.getStatus().get("pacing");
        assertEquals("disabled", pacing.get("state"));
        assertFalse(pacing.containsKey("speechBubbleHoldMs"), "未注入：不下发气泡参数");
    }

    @Test
    @DisplayName("⑥b 显式 setPacing(false)：同样回退原间隔")
    void explicitlyDisabled_originalBehavior() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        cm.setPacing(false, 2_000, 3_000, 8_000, 1.5, 4.0);
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");

        assertEquals(2_000, cm.computeRoundCooldownMs(g1, ConversationMode.DYAD));
        assertEquals(3_000, cm.computeRoundCooldownMs(g1, ConversationMode.GROUP_DISCUSSION));
    }

    // ── ⑦ 玩家进出不影响并行（无队列语义残留）────────────────────

    @Test
    @DisplayName("⑦ 玩家进/出对话：其他群始终并行推进（无挂起），仅节奏变化")
    void playerJoinLeave_othersStayParallel() {
        SimulationWorld world = worldWithPlayer();
        ConversationManager cm = manager(world);
        wireDefaultPacing(cm);
        ConversationGroup g1 = createGroup(cm, world, "g1", "A", "B");
        ConversationGroup g2 = createGroup(cm, world, "g2", "C", "D");

        cm.joinGroup("g1", "P");
        assertEquals(8_000, cm.computeRoundCooldownMs(g2, ConversationMode.DYAD),
                "玩家进对话：g2 间隔拉长 ×4 但仍在并行推进");
        assertTrue(g2.isActive(), "g2 未被挂起");
        assertEquals("g1", cm.getStatus().get("currentTrack"), "currentTrack=g1（玩家轨道）");

        cm.leaveGroup("g1", "P");
        assertEquals(3_000, cm.computeRoundCooldownMs(g2, ConversationMode.DYAD),
                "玩家退出：g2 恢复 idle ×1.5 节奏（并行不变）");
        assertEquals("", cm.getStatus().get("currentTrack"));
    }
}
