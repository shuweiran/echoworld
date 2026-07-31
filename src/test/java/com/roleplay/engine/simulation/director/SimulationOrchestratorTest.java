package com.roleplay.engine.simulation.director;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.SimulationOrchestrator;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.conversation.ConversationGroup;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.conversation.ConversationMode;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SimulationOrchestrator} — Phase 3 dual-director tick pipeline.
 *
 * <p>Core scenarios:
 * <ul>
 *   <li>tick: World Director 更新目标 + Track Director 产出全场景分配</li>
 *   <li>applyToGroup: ConversationGroup.trackAssignments 被填充（Phase 2 TrackStrategy 消费）</li>
 *   <li>秘密任务注入经 orchestrator 直达 Track Director → 群组内该成员 ISOLATED</li>
 * </ul>
 */
class SimulationOrchestratorTest {

    private static final long NOW = 1_000_000L;

    private SimulationWorld worldWithAgents(String... names) {
        SimulationWorld world = new SimulationWorld();
        double[][] coords = {{0, 0}, {3, 0}, {50, 0}, {500, 0}};
        for (int i = 0; i < names.length; i++) {
            Persona persona = new Persona(names[i], "测试人格" + names[i]);
            Agent agent = new Agent(persona, "test", null);   // null LLM: test-only
            world.registerAgent(agent, coords[i][0], coords[i][1], 200.0, 50.0);
        }
        return world;
    }

    private ConversationManager conversationManager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        return cm;
    }

    // ── tick 编排 ──────────────────────────────────────────────

    @Test
    @DisplayName("tick：World Director 更新目标，Track Director 产出全场景分配")
    void tickProducesAssignmentsAndGoals() {
        SimulationWorld world = worldWithAgents("A", "B");
        world.getState("A").setEmotion(Emotion.ANGRY);   // → 目标"平静情绪"

        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        Map<String, TrackAssignment> assignments = orchestrator.tick(NOW);

        // TrackScore=0（2人+无敏感）→ 公开聊天：全部 MERGED。
        assertEquals(2, assignments.size());
        assertEquals(Track.Mode.MERGED, assignments.get("A").type());
        assertEquals(Track.Mode.MERGED, assignments.get("B").type());

        // World Director 目标已更新（情绪异常 → 平静情绪）。
        assertEquals(WorldDirectorService.GOAL_CALM, orchestrator.getGoals().get("A"));
    }

    // ── applyToGroup 接线 ──────────────────────────────────────

    @Test
    @DisplayName("applyToGroup：ConversationGroup.trackAssignments 被填充（TrackStrategy 消费入口）")
    void applyToGroupFillsTrackAssignments() {
        SimulationWorld world = worldWithAgents("A", "B", "C");
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        List<AgentState> members = List.of(world.getState("A"), world.getState("B"), world.getState("C"));
        ConversationGroup group = new ConversationGroup(
                "abc", ConversationMode.GROUP_DISCUSSION, members);
        assertTrue(group.getTrackAssignments().isEmpty(), "初始无轨道信息");

        Map<String, TrackAssignment> assignments = orchestrator.applyToGroup(group);

        assertFalse(group.getTrackAssignments().isEmpty(), "tick 后 trackAssignments 必须被填充");
        assertEquals(3, group.getTrackAssignments().size());
        for (String name : List.of("A", "B", "C")) {
            TrackAssignment ta = group.getTrackAssignment(name);
            assertNotNull(ta, name + " 应有轨道分配");
            assertEquals(name, ta.agentId());
        }
        assertEquals(assignments, group.getTrackAssignments());
    }

    @Test
    @DisplayName("applyToGroup：秘密任务注入 → 群组内该成员 ISOLATED，其余按空间分配")
    void applyToGroupWithSecretAgent() {
        SimulationWorld world = worldWithAgents("A", "B", "C");
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));
        orchestrator.addSecretAgent("B");

        List<AgentState> members = List.of(world.getState("A"), world.getState("B"), world.getState("C"));
        ConversationGroup group = new ConversationGroup(
                "abc", ConversationMode.GROUP_DISCUSSION, members);

        orchestrator.applyToGroup(group);

        assertEquals(Track.Mode.ISOLATED, group.getTrackAssignment("B").type());
        assertTrue(group.getTrackAssignment("B").contextNote().contains("秘密任务"));
        // A(0,0)/B(3,0) 近距 MERGED（B 被秘密覆盖），C(50,0) 旁观 WEAK。
        assertEquals(Track.Mode.MERGED, group.getTrackAssignment("A").type());
        assertEquals(Track.Mode.WEAK, group.getTrackAssignment("C").type());
    }

    @Test
    @DisplayName("tick 无角色（空世界）→ 空分配不抛异常")
    void tickEmptyWorldIsSafe() {
        SimulationWorld world = new SimulationWorld();
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        assertTrue(orchestrator.tick(NOW).isEmpty());
    }
}
