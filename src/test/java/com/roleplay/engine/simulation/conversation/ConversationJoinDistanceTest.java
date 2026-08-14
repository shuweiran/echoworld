package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0815-A（调研报告-移动与分组问题.md 2.4 #3）：joinGroup 距离校验 + 玩家自动 DYAD 距离上限。
 *
 * <p>① joinGroup：玩家距组最近成员 ≥ {@link ConversationManager#JOIN_GROUP_MAX_DISTANCE}（120px）
 * 拒绝（前端 findApproachableGroups 只做展示层限制，后端校验是本原）；
 * ② 拒绝无副作用（成员表不变）；③ 玩家消息自动 DYAD：最近 AI 超过
 * {@link ConversationManager#MAX_AUTO_DYAD_DISTANCE}（200px）不建组。
 */
class ConversationJoinDistanceTest {

    private static final long NOW = 1_000_000L;

    private void register(SimulationWorld world, String name, double x, double y) {
        Persona persona = new Persona(name, "测试人格" + name);
        Agent agent = new Agent(persona, "test", null);
        world.registerAgent(agent, x, y, 200.0, 50.0);
    }

    private ConversationManager manager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        cm.setTrackDirector(new TrackDirectorService());
        cm.setGoalSupplier(() -> Map.of());
        return cm;
    }

    private ConversationGroup createGroup(ConversationManager cm, SimulationWorld world,
                                          String gid, String... members) {
        List<com.roleplay.engine.simulation.AgentState> states =
                java.util.Arrays.stream(members).map(world::getState).toList();
        return cm.createScriptDiscussionGroup(gid, states, Map.of());
    }

    // ── ① joinGroup 距离校验 ──────────────────────────────────

    @Test
    @DisplayName("① 玩家距组最近成员 < 120px → 加入成功")
    void joinWithinDistance_succeeds() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "P", 100, 0);   // 距 A 100px / 距 B 97px → 均在 120px 内
        world.getState("P").setPlayerControlled(true);
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        ConversationManager.JoinResult r = cm.joinGroup("g1", "P");

        assertTrue(r.success(), "100px 内应可加入：" + r.message());
        assertTrue(r.group().containsAgent("P"));
    }

    @Test
    @DisplayName("①b 玩家距组最近成员 ≥ 120px → 拒绝（明确错误信息，无成员表副作用）")
    void joinBeyondDistance_rejected_noSideEffect() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "P", 400, 0);   // 距 A 400px / 距 B 397px → 超 120px
        world.getState("P").setPlayerControlled(true);
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "A", "B");

        ConversationManager.JoinResult r = cm.joinGroup("g1", "P");

        assertFalse(r.success(), "400px 外应拒绝加入");
        assertTrue(r.message().contains("too far"), "错误信息应说明距离问题：" + r.message());
        assertFalse(g.containsAgent("P"), "拒绝后成员表不应含玩家（无副作用）");
        assertEquals(2, g.getParticipantCount(), "拒绝后组内成员数不变");
        assertFalse(world.getState("P").isInConversation(), "拒绝后玩家不应置 inConversation");
    }

    @Test
    @DisplayName("①c 距离校验是最后一道闸：组不存在/不在场/已在组/满员错误信息优先（顺序兼容既有契约）")
    void joinErrorOrder_unchanged() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "P", 400, 0);   // 超距玩家
        world.getState("P").setPlayerControlled(true);
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        assertTrue(cm.joinGroup("ghost-group", "P").message().contains("not found"),
                "组不存在优先于距离校验");
        assertTrue(cm.joinGroup("g1", "ghost").message().contains("agent not found"),
                "角色不在场优先于距离校验");
        // 已在组 → already（在距离校验之前命中）
        assertTrue(cm.joinGroup("g1", "A").message().contains("already in group"),
                "已在组优先于距离校验");
    }

    // ── ② 玩家自动 DYAD 距离上限 ──────────────────────────────

    @Test
    @DisplayName("② 最近 AI 在 200px 内 → 玩家消息自动建 DYAD")
    void autoDyad_withinRange_created() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "P", 150, 0);   // 距 A 150px < 200px
        world.getState("P").setPlayerControlled(true);
        world.getState("P").setCurrentMessage("你好");
        ConversationManager cm = manager(world);

        try {
            cm.tick(NOW);
            List<ConversationGroup> groups = new java.util.ArrayList<>(cm.getActiveGroups());
            assertEquals(1, groups.size(), "150px 内应自动建 DYAD 组，实际=" + groups);
        } finally {
            cm.stopAll();
        }
    }

    @Test
    @DisplayName("②b 最近 AI 超过 200px → 玩家消息不自动建 DYAD（组数 0）")
    void autoDyad_beyondRange_notCreated() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "P", 500, 0);   // 距 A 500px > 200px
        world.getState("P").setPlayerControlled(true);
        world.getState("P").setCurrentMessage("你好");
        ConversationManager cm = manager(world);

        try {
            cm.tick(NOW);
            assertEquals(0, cm.getActiveGroupCount(), "超 200px 不应自动建 DYAD 组");
            assertFalse(world.getState("P").isInConversation(), "未建组 → 玩家不应置 inConversation");
        } finally {
            cm.stopAll();
        }
    }
}
