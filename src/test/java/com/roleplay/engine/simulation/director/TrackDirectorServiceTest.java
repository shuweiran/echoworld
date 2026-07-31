package com.roleplay.engine.simulation.director;

import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TrackDirectorService} — Phase 3 Track Director track decisions.
 *
 * <p>Core scenarios (需求文档):
 * <ul>
 *   <li>无秘密无冲突 + TrackScore 低 → 全部 MERGED（公开聊天，覆盖空间隔离）</li>
 *   <li>secretAgents 含某人 → 该人强制 ISOLATED</li>
 *   <li>目标互斥（调查 vs 隐瞒）→ 相关成员 WEAK</li>
 *   <li>相同敏感目标 → 竞争双方 WEAK</li>
 *   <li>TrackScore 触发 → 空间分配生效（近=MERGED / 远=WEAK / 超范围=ISOLATED）</li>
 * </ul>
 */
class TrackDirectorServiceTest {

    private static final double HEAR_RANGE = 200.0;
    private static final double CONVERSATION_DISTANCE = 5.0;

    private AgentState agent(String name, double x, double y) {
        AgentState s = new AgentState(name, x, y);
        s.setHearRange(HEAR_RANGE);
        return s;
    }

    private TrackDirectorService director() {
        return new TrackDirectorService(new com.roleplay.engine.simulation.track.SpatialTrackResolver(CONVERSATION_DISTANCE));
    }

    // ── 公开聊天模式 ───────────────────────────────────────────

    @Test
    @DisplayName("无秘密无冲突 + TrackScore 低（0分）→ 全部 MERGED（公开聊天）")
    void lowScoreAllMergedEvenWhenSpatiallyFar() {
        // A/B 相距 100 (> conversationDistance, < hearRange)，空间上应为 WEAK；
        // 但 TrackScore=0 → 公开聊天模式强制全部 MERGED。
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 100, 0);

        Map<String, TrackAssignment> result = director().assign(List.of(a, b));

        assertEquals(Track.Mode.MERGED, result.get("A").type());
        assertEquals(Track.Mode.MERGED, result.get("B").type());
        assertTrue(result.get("A").visibleAgents().contains("B"));
        assertTrue(result.get("A").contextNote().contains("公开聊天"));
    }

    @Test
    @DisplayName("三人普通闲聊（无敏感触发）→ 全部 MERGED")
    void threePersonPublicChatAllMerged() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);
        AgentState c = agent("C", 6, 0);

        Map<String, TrackAssignment> result = director().assign(List.of(a, b, c));

        assertEquals(3, result.size());
        for (TrackAssignment ta : result.values()) {
            assertEquals(Track.Mode.MERGED, ta.type());
        }
    }

    // ── 秘密任务 → ISOLATED ────────────────────────────────────

    @Test
    @DisplayName("secretAgents 含某人 → 该人强制 ISOLATED，TrackScore 触发")
    void secretAgentForcedIsolated() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);
        TrackDirectorService director = director();
        director.setSecretAgents(Set.of("B"));

        Map<String, TrackAssignment> result = director.assign(List.of(a, b));

        assertTrue(director.getLastScore().triggered());
        assertEquals(Track.Mode.ISOLATED, result.get("B").type());
        assertTrue(result.get("B").contextNote().contains("秘密任务"));
        assertTrue(result.get("B").visibleAgents().isEmpty());
        // A 不受秘密任务影响（空间近 → MERGED）。
        assertEquals(Track.Mode.MERGED, result.get("A").type());
    }

    @Test
    @DisplayName("addSecretAgent / removeSecretAgent 增删秘密任务成员")
    void addRemoveSecretAgent() {
        TrackDirectorService director = director();
        director.addSecretAgent("X");
        director.addSecretAgent("Y");
        assertEquals(Set.of("X", "Y"), director.getSecretAgents());

        director.removeSecretAgent("X");
        assertEquals(Set.of("Y"), director.getSecretAgents());
    }

    // ── 目标冲突 → WEAK ────────────────────────────────────────

    @Test
    @DisplayName("目标互斥（A=调查, B=隐瞒）→ 双方 WEAK（即使空间很近）")
    void exclusiveGoalsDowngradeToWeak() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);   // 空间近 → 基线 MERGED

        Map<String, String> goals = Map.of("A", "调查", "B", "隐瞒");
        Map<String, TrackAssignment> result = director().assign(List.of(a, b), goals);

        assertEquals(Track.Mode.WEAK, result.get("A").type(), "A 调查 vs B 隐瞒 → 冲突");
        assertEquals(Track.Mode.WEAK, result.get("B").type(), "B 隐瞒 vs A 调查 → 冲突");
        assertTrue(result.get("A").contextNote().contains("冲突"));
    }

    @Test
    @DisplayName("相同敏感目标（A=调查, B=调查）→ 竞争双方 WEAK")
    void sameSensitiveGoalCompetitionDowngradesToWeak() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);

        Map<String, String> goals = Map.of("A", "调查", "B", "调查");
        Map<String, TrackAssignment> result = director().assign(List.of(a, b), goals);

        assertEquals(Track.Mode.WEAK, result.get("A").type());
        assertEquals(Track.Mode.WEAK, result.get("B").type());
    }

    @Test
    @DisplayName("良性目标（闲逛/探索）不构成冲突 → 保持 MERGED")
    void benignGoalsDoNotConflict() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);

        Map<String, String> goals = Map.of("A", WorldDirectorService.GOAL_EXPLORE,
                "B", WorldDirectorService.GOAL_WANDER);
        Map<String, TrackAssignment> result = director().assign(List.of(a, b), goals);

        assertEquals(Track.Mode.MERGED, result.get("A").type());
        assertEquals(Track.Mode.MERGED, result.get("B").type());
    }

    // ── TrackScore 触发 → 空间分配生效 ─────────────────────────

    @Test
    @DisplayName("TrackScore 触发时：A/B 近=MERGED，C 远=WEAK，D 超范围=ISOLATED")
    void triggeredUsesSpatialResolution() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);
        AgentState c = agent("C", 50, 0);
        AgentState d = agent("D", 500, 0);
        a.setStance(AgentState.Stance.FOR);
        b.setStance(AgentState.Stance.AGAINST);   // 立场冲突 +40 → 触发 Track
        TrackDirectorService director = director();

        Map<String, TrackAssignment> result = director.assign(List.of(a, b, c, d));

        assertTrue(director.getLastScore().triggered());
        assertEquals(Track.Mode.MERGED, result.get("A").type());
        assertEquals(Track.Mode.MERGED, result.get("B").type());
        assertEquals(Track.Mode.WEAK, result.get("C").type());
        assertEquals(Track.Mode.ISOLATED, result.get("D").type());
    }

    @Test
    @DisplayName("秘密任务 + 空间：远处秘密成员 ISOLATED，旁观者不受影响")
    void secretWithSpatialMixed() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);
        AgentState c = agent("C", 50, 0);
        TrackDirectorService director = director();
        director.setSecretAgents(Set.of("B"));

        Map<String, TrackAssignment> result = director.assign(List.of(a, b, c));

        assertEquals(Track.Mode.ISOLATED, result.get("B").type());
        assertEquals(Track.Mode.MERGED, result.get("A").type());
        assertEquals(Track.Mode.WEAK, result.get("C").type(), "C 旁观者保持空间 WEAK");
    }

    @Test
    @DisplayName("空输入 → 空分配，不抛异常")
    void emptyInputReturnsEmptyMap() {
        Map<String, TrackAssignment> result = director().assign(List.of());
        assertTrue(result.isEmpty());
    }
}
