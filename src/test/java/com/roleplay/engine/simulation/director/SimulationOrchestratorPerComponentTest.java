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
 * P-0815-A（调研报告-移动与分组问题.md 2.4 #4）：Track 分配改按听觉组——
 * SimulationOrchestrator.tick 不再把全场景 agents 一次性喂给 assign 做 allMerged；
 * 改为「每个活动对话组 + 每个听力连通分量」分别 assign：无敏感触发时 MERGED 只覆盖
 * 同分量成员，单人分量（无听力接触）→ ISOLATED，不再全员两两互见。
 */
class SimulationOrchestratorPerComponentTest {

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
    @DisplayName("① 两个听力分量：A/B 近距 MERGED（仅同分量互见），C 单人分量 ISOLATED——不再全场景 allMerged")
    void twoComponents_mergedOnlyWithinComponent() {
        // A(0,0)/B(100,0) 链式可听（100px 内），C(500,0) 完全超出听觉 → 单人分量。
        // 旧行为：无触发 → 全场景 allMerged（C 也 MERGED 且两两互见）。
        SimulationWorld world = worldWithAgents(new double[][]{{0, 0}, {100, 0}, {500, 0}}, "A", "B", "C");
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        Map<String, TrackAssignment> assignments = orchestrator.tick(NOW);

        assertEquals(3, assignments.size());
        assertEquals(Track.Mode.MERGED, assignments.get("A").type(), "A 在分量内 MERGED");
        assertEquals(Track.Mode.MERGED, assignments.get("B").type(), "B 在分量内 MERGED");
        assertEquals(Track.Mode.ISOLATED, assignments.get("C").type(),
                "C 无听力接触 → ISOLATED（不再全场景 allMerged）");
        // MERGED 只覆盖同分量成员：A/B 互见，均不见 C
        assertTrue(assignments.get("A").visibleAgents().contains("B"));
        assertFalse(assignments.get("A").visibleAgents().contains("C"), "A 不应看见远处 C");
        assertFalse(assignments.get("B").visibleAgents().contains("C"), "B 不应看见远处 C");
        assertTrue(assignments.get("C").visibleAgents().isEmpty());
        assertTrue(assignments.get("C").contextNote().contains("隔离"), assignments.get("C").contextNote());
    }

    @Test
    @DisplayName("② 链式分量（A↔B↔C 传递闭包）整组 MERGED，D 超听觉 ISOLATED")
    void chainComponent_mergedWithinChain() {
        // A(0)-B(100)-C(200)：A↔B、B↔C 可听 → 传递闭包同分量；A-C 不直接可听但经 B 连通。
        // D(500) 完全隔离。无敏感触发 → 分量 {A,B,C} 全部 MERGED（同分量两两互见），D ISOLATED。
        SimulationWorld world = worldWithAgents(
                new double[][]{{0, 0}, {100, 0}, {200, 0}, {500, 0}}, "A", "B", "C", "D");
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));

        Map<String, TrackAssignment> assignments = orchestrator.tick(NOW);

        assertEquals(Track.Mode.MERGED, assignments.get("A").type());
        assertEquals(Track.Mode.MERGED, assignments.get("B").type());
        assertEquals(Track.Mode.MERGED, assignments.get("C").type());
        assertEquals(Track.Mode.ISOLATED, assignments.get("D").type());
        assertTrue(assignments.get("A").visibleAgents().containsAll(List.of("B", "C")),
                "链式分量内两两互见（同分量成员），实际=" + assignments.get("A").visibleAgents());
        assertFalse(assignments.get("A").visibleAgents().contains("D"), "分量外不可见");
    }

    @Test
    @DisplayName("③ 活动对话组独立分配：组内成员走 applyToGroup，组外按分量——组内组外不互见")
    void activeGroup_assignedIndependentlyFromFreeComponents() {
        SimulationWorld world = worldWithAgents(
                new double[][]{{0, 0}, {3, 0}, {500, 0}, {505, 0}}, "A", "B", "C", "D");
        ConversationManager cm = conversationManager(world);
        // 活动组 g1 = {A, B}（近距）；C/D 为组外另一分量（500/505）
        cm.createScriptDiscussionGroup("g1",
                List.of(world.getState("A"), world.getState("B")), Map.of());
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(), cm);

        Map<String, TrackAssignment> assignments = orchestrator.tick(NOW);

        // 组内 A/B：分配结果与组对象 trackAssignments 一致（TrackStrategy 消费入口）
        assertEquals(Track.Mode.MERGED, assignments.get("A").type());
        assertEquals(Track.Mode.MERGED, assignments.get("B").type());
        assertEquals(assignments.get("A"), cm.getActiveGroups().iterator().next().getTrackAssignment("A"),
                "tick 应回写活动组 trackAssignments（applyToGroup）");
        // 组外 C/D：按听力分量（500/505 近距）MERGED，与组内互不见
        assertEquals(Track.Mode.MERGED, assignments.get("C").type());
        assertEquals(Track.Mode.MERGED, assignments.get("D").type());
        assertFalse(assignments.get("C").visibleAgents().contains("A"), "组外分量不应看见组内 A");
        assertFalse(assignments.get("A").visibleAgents().contains("C"), "组内 A 不应看见组外 C");
    }

    @Test
    @DisplayName("④ 空世界安全：tick 返回空不抛异常")
    void emptyWorldSafe() {
        SimulationWorld world = new SimulationWorld();
        SimulationOrchestrator orchestrator = new SimulationOrchestrator(
                world, new WorldDirectorService(), new TrackDirectorService(),
                conversationManager(world));
        assertTrue(orchestrator.tick(NOW).isEmpty());
    }
}
