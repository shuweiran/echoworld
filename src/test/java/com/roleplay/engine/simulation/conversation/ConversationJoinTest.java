package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 方案A（轨道系统用户加入调研-20260803 §5 方案A 后端原语）——玩家加入他人对话组。
 *
 * <p>覆盖（任务书 P-0803 批次测试清单）：
 * <ul>
 *   <li>加入成功（在场状态 + 组内轨道重算 + status 反映新参与者）</li>
 *   <li>重复加入拒绝 / 目标组不存在 / 玩家角色不存在（不在场）/ 玩家已在组</li>
 *   <li>加入后 tick 保持不被踢出 + 不误伤自动建组排除逻辑（无多余 DYAD 双路径）</li>
 *   <li>玩家加入后的发言复用 executeRound 玩家分支（与既有 DYAD 路径一致）</li>
 *   <li>（设上限）满员拒绝：ConversationGroup 上限原语 + DYAD 对偶组上限 2 接线</li>
 *   <li>离开：状态还原 / 非成员拒绝 / 最后一名成员离开自动解散</li>
 * </ul>
 *
 * <p>直构世界 + ConversationManager（与 SimulationOrchestratorTest 同风格，零 Spring）；
 * 群组经 createScriptDiscussionGroup 注册（同步、无后台轮次循环，确定性）；DYAD 满员用例
 * 走真实 tick 建组（mock LLM 保证轮次循环不提前解散，测试尾 stopAll 收尾）。
 */
class ConversationJoinTest {

    private static final long NOW = 1_000_000L;

    // ── 基建 ──────────────────────────────────────────────────

    private void register(SimulationWorld world, String name, double x, double y) {
        Persona persona = new Persona(name, "测试人格" + name);
        Agent agent = new Agent(persona, "test", null);   // null LLM: test-only
        world.registerAgent(agent, x, y, 200.0, 50.0);
    }

    private ConversationManager manager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        // 生产接线：Track Director 决定组内轨道（join 后重算用，玩家与 NPC 同判）。
        cm.setTrackDirector(new TrackDirectorService());
        cm.setGoalSupplier(() -> Map.of());
        return cm;
    }

    /** 注册一个 GROUP_DISCUSSION 群组（同步、无后台轮次循环）。 */
    private ConversationGroup createGroup(ConversationManager cm, SimulationWorld world,
                                          String gid, String... members) {
        List<AgentState> states = java.util.Arrays.stream(members).map(world::getState).toList();
        return cm.createScriptDiscussionGroup(gid, states, Map.of());
    }

    /** 场景：A(0,0) B(3,0) 组内；P(2,0) 玩家（playerControlled）；C(6,0) 空闲 NPC。 */
    private SimulationWorld worldThreePlusPlayer() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "P", 2, 0);
        register(world, "C", 6, 0);
        world.getState("P").setPlayerControlled(true);
        return world;
    }

    // ── ① 加入成功 ────────────────────────────────────────────

    @Test
    @DisplayName("① 加入成功：成员表 + 在场状态（inConversation/冻结/速度清零）+ 轨道重算含玩家")
    void joinSuccess_addsPlayer_presenceAndTracksRecomputed() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        ConversationManager.JoinResult r = cm.joinGroup("g1", "P");

        assertTrue(r.success(), "加入应成功：" + r.message());
        assertEquals("g1", r.group().getGroupId());
        assertEquals(3, r.group().getParticipantCount());
        assertTrue(r.group().containsAgent("P"), "成员表应含玩家");
        AgentState p = world.getState("P");
        assertTrue(p.isInConversation(), "玩家应置 inConversation=true（在场）");
        assertTrue(r.group().isFrozen("P"), "玩家应冻结（对齐 startGroup 成员语义）");
        assertEquals(0, p.getVx());
        assertEquals(0, p.getVy());
        // 组内轨道重算：玩家与 NPC 同等判定（近距 → MERGED）
        TrackAssignment pTrack = r.group().getTrackAssignment("P");
        assertNotNull(pTrack, "join 后玩家应有轨道分配");
        assertEquals(com.roleplay.engine.core.Track.Mode.MERGED, pTrack.type(),
                "P(2,0) 距 A/B < 5 格应 MERGED");
    }

    @Test
    @DisplayName("①b status 反映新参与者：conversation-status participants 含玩家")
    void statusReflectsJoinedParticipant() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        cm.joinGroup("g1", "P");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) cm.getStatus().get("groups");
        assertEquals(1, groups.size());
        @SuppressWarnings("unchecked")
        List<String> participants = (List<String>) groups.get(0).get("participants");
        assertTrue(participants.contains("P"), "status participants 应反映加入结果：" + participants);
        assertEquals(List.of("A", "B", "P"), participants, "插入序：A、B（建组）→ P（加入）");
    }

    // ── ② 异常/边界 ───────────────────────────────────────────

    @Test
    @DisplayName("② 重复加入拒绝：二次 join 失败且成员表不变")
    void joinDuplicate_rejected() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "A", "B");

        assertTrue(cm.joinGroup("g1", "P").success());
        ConversationManager.JoinResult dup = cm.joinGroup("g1", "P");

        assertFalse(dup.success(), "重复加入应拒绝");
        assertTrue(dup.message().contains("already"), dup.message());
        assertEquals(3, g.getParticipantCount(), "成员表不应变化");
    }

    @Test
    @DisplayName("③ 目标组不存在：joinGroup 返回 not found")
    void joinGroupNotFound_rejected() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        ConversationManager.JoinResult r = cm.joinGroup("ghost-group", "P");

        assertFalse(r.success());
        assertTrue(r.message().contains("not found"), r.message());
    }

    @Test
    @DisplayName("④ 玩家角色不存在/不在场：2D 世界无该 agent → 拒绝")
    void joinPlayerNotInWorld_rejected() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        ConversationManager.JoinResult r = cm.joinGroup("g1", "ghost");

        assertFalse(r.success());
        assertTrue(r.message().contains("agent not found"), r.message());
        assertEquals(2, cm.getActiveGroups().iterator().next().getParticipantCount(), "成员表不变");
    }

    @Test
    @DisplayName("⑤ 玩家已在组（建组即成员）→ 拒绝")
    void joinAlreadyMember_rejected() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "P");

        ConversationManager.JoinResult r = cm.joinGroup("g1", "P");

        assertFalse(r.success());
        assertTrue(r.message().contains("already in group"), r.message());
    }

    @Test
    @DisplayName("⑥ 加入后 tick 保持不被踢出：组存活、玩家在组、且不新建多余 DYAD 双路径")
    void joinThenTick_keepsPlayer_noSpuriousDyad() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        cm.joinGroup("g1", "P");
        // 玩家在组内发消息 → tick：应被所在组消费，而不是再自动建一个 DYAD 组（调研 §4.2 #8 双路径互斥）
        world.getState("P").setCurrentMessage("我来了");

        cm.tick(NOW);

        assertEquals(1, cm.getActiveGroupCount(), "tick 后不应新建多余群组（玩家已 busy 跳过 DYAD 自动建组）");
        ConversationGroup g = cm.getActiveGroups().iterator().next();
        assertEquals("g1", g.getGroupId());
        assertTrue(g.isActive(), "组不应被 tick 解散");
        assertTrue(g.containsAgent("P"), "玩家应仍在组内（tick 不得踢出）");
        assertTrue(world.getState("P").isInConversation(), "玩家在场状态保持");
    }

    // ── ③ 玩家发言语义（复用 executeRound 玩家分支，与 DYAD 一致） ──

    @Test
    @DisplayName("⑦ 加入后玩家发言：runScriptDiscussionRounds 消费 currentMessage 入轮次（与 DYAD 路径同链路）")
    void joinedPlayerSpeech_consumedLikeDyadPath() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "A", "B");
        assertTrue(cm.joinGroup("g1", "P").success());
        world.getState("P").setCurrentMessage("大家好，我加入讨论");

        ConversationManager.ScriptDiscussionResult result = cm.runScriptDiscussionRounds(g, 1);

        // NPC（A/B）LLM 为 null 生成失败被跳过；玩家分支直接用自己的消息（executeRound L346-354 同款）
        List<Map<String, String>> transcript = result.transcript();
        assertFalse(transcript.isEmpty(), "应有轮次记录");
        assertEquals("P", transcript.get(0).get("speaker"), "玩家发言应入轮次");
        assertEquals("大家好，我加入讨论", transcript.get(0).get("message"));
        assertTrue(transcript.stream().noneMatch(e -> "A".equals(e.get("speaker")) || "B".equals(e.get("speaker"))),
                "NPC 无 LLM 不应产生占位发言：" + transcript);
    }

    // ── ④ 离开 ────────────────────────────────────────────────

    @Test
    @DisplayName("⑧ 离开：在场状态还原、成员表移除、组与剩余成员存活、轨道重算")
    void leaveGroup_restoresPlayerState_groupSurvives() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "A", "B");
        cm.joinGroup("g1", "P");

        ConversationManager.JoinResult r = cm.leaveGroup("g1", "P");

        assertTrue(r.success(), r.message());
        AgentState p = world.getState("P");
        assertFalse(p.isInConversation(), "离开后 inConversation 应还原 false");
        assertFalse(g.containsAgent("P"), "成员表应移除玩家");
        assertFalse(g.isFrozen("P"), "离开后应解冻");
        assertEquals(2, g.getParticipantCount(), "组与剩余成员存活");
        assertTrue(cm.getActiveGroups().stream().anyMatch(x -> "g1".equals(x.getGroupId())), "组不应解散");
        assertNotNull(g.getTrackAssignment("A"), "离开后组内轨道应重算保留");
    }

    @Test
    @DisplayName("⑨ 离开非成员 / 离开不存在组 → 拒绝")
    void leaveGroup_notMember_rejected() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        ConversationManager.JoinResult r = cm.leaveGroup("g1", "P");
        assertFalse(r.success());
        assertTrue(r.message().contains("not in group"), r.message());

        ConversationManager.JoinResult missing = cm.leaveGroup("ghost-group", "P");
        assertFalse(missing.success());
        assertTrue(missing.message().contains("not found"), missing.message());
    }

    @Test
    @DisplayName("⑩ 最后一名成员离开 → 组自动解散")
    void leaveLastMember_dissolvesGroup() {
        SimulationWorld world = worldThreePlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "C", "P");
        assertTrue(cm.leaveGroup("g1", "P").success(), "玩家先离开");
        assertEquals(1, cm.getActiveGroupCount(), "还剩 C，组存活");
        assertTrue(cm.leaveGroup("g1", "C").success(), "最后一名成员离开");
        assertEquals(0, cm.getActiveGroupCount(), "组内无人 → 自动解散");
        assertFalse(world.getState("C").isInConversation());
        assertFalse(world.getState("P").isInConversation());
    }

    // ── ⑤ 满员拒绝（设上限时加入校验） ────────────────────────

    @Test
    @DisplayName("⑪ ConversationGroup 上限原语：满员拒绝 / 重复拒绝 / 移除后可再加入")
    void groupCap_blocksOverflow() {
        SimulationWorld world = worldThreePlusPlayer();
        AgentState a = world.getState("A");
        AgentState b = world.getState("B");
        AgentState p = world.getState("P");
        ConversationGroup dyad = new ConversationGroup("dyad", ConversationMode.DYAD, List.of(a, b), 2);

        assertEquals(2, dyad.getMaxParticipants());
        assertFalse(dyad.addParticipant(p), "满员（上限 2）加入应拒绝");
        assertEquals(2, dyad.getParticipantCount());
        assertFalse(dyad.addParticipant(a), "重复加入应拒绝");
        assertTrue(dyad.removeParticipant("B"), "移除应在组内成员");
        assertFalse(dyad.removeParticipant("B"), "重复移除应 false");
        assertTrue(dyad.addParticipant(p), "移除后空位可再加入");
        assertEquals(2, dyad.getParticipantCount());
    }

    @Test
    @DisplayName("⑫ DYAD 组上限 2 接线：真实 tick 建组后第三方加入被拒（调研 §4.2 #5：DYAD 禁 join）")
    void dyadGroupFull_rejectsThirdMember() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "P", 6, 0);
        world.getState("P").setPlayerControlled(true);
        world.getState("P").setCurrentMessage("你好");

        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList())).thenReturn("你好【情绪：平静】");
        ConversationManager cm = new ConversationManager();
        cm.init(world, llm, world::getAgent, world::getWorldNarration);

        try {
            cm.tick(NOW);   // 玩家消息 → DYAD 组（P+最近空闲 NPC B）
            assertTrue(cm.getActiveGroups().stream().anyMatch(g -> "P+B".equals(g.getGroupId())),
                    "tick 应为玩家建 DYAD 组 P+B");

            ConversationManager.JoinResult full = cm.joinGroup("P+B", "A");
            assertFalse(full.success(), "DYAD 组满员（上限 2）第三方加入应被拒");
            assertTrue(full.message().contains("full"), full.message());

            ConversationManager.JoinResult dup = cm.joinGroup("P+B", "P");
            assertFalse(dup.success(), "玩家本人在 DYAD 组内 → 重复加入应被拒");
            assertTrue(dup.message().contains("already"), dup.message());
        } finally {
            cm.stopAll();   // 停掉后台轮次循环，避免残留虚拟线程
        }
    }
}
