package com.roleplay.engine.simulation.director;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationOrchestrator;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0815-E（主人需求：ASimulationOrchestrator 不得控制用户角色）：
 * 编排器只管 AI 角色——玩家角色（playerControlled）的行为/移动/发言由玩家自己决定：
 * ① World Director 不再为玩家生成导演目标；② 玩家不参与自由 agent 轨道分配；
 * ③ 玩家在对话组内也无轨道条目（组内发言直通，AI 成员轨道上下文不受影响）；
 * ④ 玩家自己的手动目标（玩家指令）粘性保留，不被规则更新清除；⑤ 纯 AI 场景零回归。
 */
class SimulationOrchestratorPlayerSkipTest {

    private static final long NOW = 1_000_000L;

    private SimulationWorld worldWithAgents(double[][] coords, String... names) {
        SimulationWorld world = new SimulationWorld();
        for (int i = 0; i < names.length; i++) {
            Persona persona = new Persona(names[i], "测试人格" + names[i]);
            Agent agent = new Agent(persona, "test", null);
            world.registerAgent(agent, coords[i][0], coords[i][1], 200.0, 50.0);
        }
        return world;
    }

    private ConversationManager conversationManager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        return cm;
    }

    @Test
    @DisplayName("① 玩家角色无导演目标：tick 后 getGoals 不含玩家，AI 角色正常有规则目标")
    void playerGetsNoDirectorGoal() {
        // A(0,0)/B(100,0) AI 近距；P(300,0) 玩家（playerControlled，与 AI 分开以便聚焦目标判定）
        SimulationWorld world = worldWithAgents(new double[][]{{0, 0}, {100, 0}, {300, 0}}, "A", "B", "P");
        world.getState("P").setPlayerControlled(true);
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        orchestrator.tick(NOW);

        Map<String, String> goals = orchestrator.getGoals();
        assertFalse(goals.containsKey("P"), "编排器不得为玩家角色生成导演目标（行为由玩家自己决定）");
        assertTrue(goals.containsKey("A"), "AI 角色正常有目标");
        assertTrue(goals.containsKey("B"), "AI 角色正常有目标");
    }

    @Test
    @DisplayName("② 玩家角色无轨道分配（自由态）：tick assignments 不含玩家，AI 正常分配")
    void playerGetsNoTrackAssignmentWhenFree() {
        SimulationWorld world = worldWithAgents(new double[][]{{0, 0}, {100, 0}, {300, 0}}, "A", "B", "P");
        world.getState("P").setPlayerControlled(true);
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        Map<String, TrackAssignment> assignments = orchestrator.tick(NOW);

        assertFalse(assignments.containsKey("P"), "编排器不得为玩家角色分配轨道（谁知道什么不由编排器决定）");
        assertEquals(Track.Mode.MERGED, assignments.get("A").type(), "AI 正常分配");
        assertEquals(Track.Mode.MERGED, assignments.get("B").type(), "AI 正常分配");
    }

    @Test
    @DisplayName("③ 玩家在对话组内：组轨道分配不含玩家条目，AI 成员仍有轨道上下文")
    void playerInGroup_getsNoGroupTrackEntry_aiMembersKeepTracks() {
        // 组 g1 = {A（AI）, P（玩家）}：applyToGroup 只对 AI 成员分配 → 组轨道含 A、不含 P
        SimulationWorld world = worldWithAgents(new double[][]{{0, 0}, {5, 0}}, "A", "P");
        world.getState("P").setPlayerControlled(true);
        ConversationManager cm = conversationManager(world);
        cm.createScriptDiscussionGroup("g1",
                List.of(world.getState("A"), world.getState("P")), Map.of());
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(), cm);

        orchestrator.tick(NOW);

        var group = cm.getActiveGroups().iterator().next();
        assertNotNull(group.getTrackAssignment("A"), "AI 成员 A 应有轨道分配（TrackStrategy 消费）");
        assertNull(group.getTrackAssignment("P"), "玩家在组内也无轨道条目（编排器不决定玩家的谁知道什么）");
        assertFalse(group.getTrackAssignments().isEmpty(), "组轨道非空（TrackStrategy 仍走轨道模式）");
        // tick 返回值同样不含玩家
        Map<String, TrackAssignment> assignments = orchestrator.tick(NOW);
        assertFalse(assignments.containsKey("P"));
    }

    @Test
    @DisplayName("④ 玩家自己的手动目标（玩家指令）粘性保留：tick 不被规则更新清除")
    void playerManualGoalIsPreserved() {
        SimulationWorld world = worldWithAgents(new double[][]{{0, 0}, {100, 0}, {300, 0}}, "A", "B", "P");
        world.getState("P").setPlayerControlled(true);
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        orchestrator.setGoal("P", "调查"); // 玩家/外部指令显式设定（粘性）
        orchestrator.tick(NOW);

        assertEquals("调查", orchestrator.getGoals().get("P"),
                "玩家手动目标粘性保留（外部注入不被规则更新清除）");
        // 同时确认 updateGoals 跳过玩家不会误清其手动目标
        assertTrue(orchestrator.getGoals().containsKey("A"));
    }

    @Test
    @DisplayName("⑤ 纯 AI 场景零回归：无玩家时目标与轨道分配与既有行为一致")
    void pureAiWorld_unchanged() {
        SimulationWorld world = worldWithAgents(new double[][]{{0, 0}, {100, 0}}, "A", "B");
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        Map<String, TrackAssignment> assignments = orchestrator.tick(NOW);

        assertEquals(2, assignments.size());
        assertEquals(Track.Mode.MERGED, assignments.get("A").type());
        assertEquals(Track.Mode.MERGED, assignments.get("B").type());
        assertEquals(2, orchestrator.getGoals().size(), "AI 全量目标正常");
        assertTrue(assignments.get("A").visibleAgents().contains("B"), "同分量两两互见");
    }
}
