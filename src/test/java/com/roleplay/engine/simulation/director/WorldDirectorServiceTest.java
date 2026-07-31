package com.roleplay.engine.simulation.director;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.SimulationWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WorldDirectorService} — Phase 3 World Director goal management.
 *
 * <p>Core scenarios:
 * <ul>
 *   <li>setGoal/getGoal round-trip + sticky across updateGoals</li>
 *   <li>情绪异常 → "平静情绪" 自动生成目标</li>
 *   <li>在对话中 → "参与讨论"</li>
 *   <li>长时间无活动 → "探索周围"</li>
 *   <li>有目标/近期活动 → 默认 "闲逛"</li>
 * </ul>
 */
class WorldDirectorServiceTest {

    private static final long NOW = 1_000_000L;

    private SimulationWorld world() {
        SimulationWorld w = new SimulationWorld();
        // Agents registered so world.getAllStates() mirrors the test list.
        return w;
    }

    private AgentState agent(String name, double x, double y) {
        AgentState s = new AgentState(name, x, y);
        s.setHearRange(200.0);
        return s;
    }

    private List<AgentState> states(AgentState... arr) {
        List<AgentState> list = new ArrayList<>();
        for (AgentState s : arr) list.add(s);
        return list;
    }

    // ── setGoal / getGoal ──────────────────────────────────────

    @Test
    @DisplayName("setGoal/getGoal 往返 + getAllGoals 汇总")
    void setAndGetGoal() {
        WorldDirectorService director = new WorldDirectorService();

        director.setGoal("A", "调查");
        director.setGoal("B", "隐瞒");

        assertEquals("调查", director.getGoal("A"));
        assertEquals("隐瞒", director.getGoal("B"));
        assertNull(director.getGoal("C"));

        Map<String, String> all = director.getAllGoals();
        assertEquals(2, all.size());
        assertEquals("调查", all.get("A"));
        assertEquals("隐瞒", all.get("B"));
    }

    @Test
    @DisplayName("手动目标粘性：updateGoals 不覆盖 setGoal 设定的目标")
    void manualGoalIsSticky() {
        WorldDirectorService director = new WorldDirectorService();
        director.setGoal("A", "调查");

        AgentState a = agent("A", 0, 0);
        a.setEmotion(Emotion.HAPPY);           // 规则会给出"闲逛/探索周围"
        a.setInConversation(false);

        Map<String, String> updated = director.updateGoals(world(), states(a), NOW);

        assertEquals("调查", updated.get("A"), "manual goal must survive rule update");
        assertEquals("调查", director.getGoal("A"));
    }

    // ── 规则式目标生成 ─────────────────────────────────────────

    @Test
    @DisplayName("情绪异常(ANGRY) → 自动生成目标「平静情绪」")
    void abnormalEmotionGeneratesCalmGoal() {
        WorldDirectorService director = new WorldDirectorService();

        AgentState a = agent("A", 0, 0);
        a.setEmotion(Emotion.ANGRY);
        AgentState b = agent("B", 0, 0);
        b.setEmotion(Emotion.HAPPY);

        Map<String, String> updated = director.updateGoals(world(), states(a, b), NOW);

        assertEquals(WorldDirectorService.GOAL_CALM, updated.get("A"));
        assertEquals(WorldDirectorService.GOAL_CALM, director.getGoal("A"));
        assertNotEquals(WorldDirectorService.GOAL_CALM, updated.get("B"));

        // Priority detail available for output.
        WorldDirectorService.AgentGoal detail = director.getGoalDetails().get("A");
        assertNotNull(detail);
        assertEquals(30, detail.priority());
    }

    @Test
    @DisplayName("在对话中 → 目标「参与讨论」")
    void inConversationGeneratesJoinDiscussionGoal() {
        WorldDirectorService director = new WorldDirectorService();

        AgentState a = agent("A", 0, 0);
        a.setInConversation(true);

        Map<String, String> updated = director.updateGoals(world(), states(a), NOW);

        assertEquals(WorldDirectorService.GOAL_JOIN_DISCUSSION, updated.get("A"));
    }

    @Test
    @DisplayName("长时间无活动（从未对话且无目标）→ 目标「探索周围」")
    void idleGeneratesExploreGoal() {
        WorldDirectorService director = new WorldDirectorService();

        AgentState a = agent("A", 0, 0);   // lastConversationTime == 0 → idle
        a.setInConversation(false);
        a.setHasTarget(false);

        Map<String, String> updated = director.updateGoals(world(), states(a), NOW);

        assertEquals(WorldDirectorService.GOAL_EXPLORE, updated.get("A"));
    }

    @Test
    @DisplayName("近期有活动（有目标 / 刚结束对话）→ 默认「闲逛」")
    void activeAgentGetsWanderGoal() {
        WorldDirectorService director = new WorldDirectorService();

        AgentState a = agent("A", 0, 0);
        a.setInConversation(false);
        a.setHasTarget(true);                       // 正在走路 → 活跃
        AgentState b = agent("B", 0, 0);
        b.setInConversation(false);
        b.setLastConversationTime(NOW - 1_000);     // 1s 前刚聊完 → 非闲置

        Map<String, String> updated = director.updateGoals(world(), states(a, b), NOW);

        assertEquals(WorldDirectorService.GOAL_WANDER, updated.get("A"));
        assertEquals(WorldDirectorService.GOAL_WANDER, updated.get("B"));
    }

    @Test
    @DisplayName("clearGoal 清除手动目标后恢复规则驱动")
    void clearGoalRestoresRuleDriven() {
        WorldDirectorService director = new WorldDirectorService();
        director.setGoal("A", "调查");

        director.clearGoal("A");
        assertNull(director.getGoal("A"));

        AgentState a = agent("A", 0, 0);
        a.setEmotion(Emotion.ANGRY);
        Map<String, String> updated = director.updateGoals(world(), states(a), NOW);

        assertEquals(WorldDirectorService.GOAL_CALM, updated.get("A"));
    }
}
