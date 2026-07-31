package com.roleplay.engine.simulation.track;

import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SpatialTrackResolver}: the three distance bands must map to
 * the three track modes (MERGED / WEAK / ISOLATED), and private-room boundary
 * pairs must be isolated.
 */
class SpatialTrackResolverTest {

    private static final double CONVERSATION_DISTANCE = 5.0;
    private static final double HEAR_RANGE = 200.0;

    private AgentState agent(String name, double x, double y) {
        AgentState s = new AgentState(name, x, y);
        s.setHearRange(HEAR_RANGE);
        return s;
    }

    @Test
    @DisplayName("近距离（<5）→ MERGED，互相可见")
    void closeAgentsAreMerged() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 2, 0);

        Map<String, TrackAssignment> result =
                new SpatialTrackResolver(CONVERSATION_DISTANCE).resolve(List.of(a, b));

        assertEquals(Track.Mode.MERGED, result.get("A").type());
        assertEquals(Track.Mode.MERGED, result.get("B").type());
        assertTrue(result.get("A").visibleAgents().contains("B"));
        assertTrue(result.get("B").visibleAgents().contains("A"));
    }

    @Test
    @DisplayName("听觉范围内但≥5 → WEAK，仅摘要")
    void audibleButNotCloseIsWeak() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 10, 0);   // 10 >= 5 且 < hearRange(200)

        Map<String, TrackAssignment> result =
                new SpatialTrackResolver(CONVERSATION_DISTANCE).resolve(List.of(a, b));

        assertEquals(Track.Mode.WEAK, result.get("A").type());
        assertEquals(Track.Mode.WEAK, result.get("B").type());
        assertTrue(result.get("A").visibleAgents().contains("B"));
        assertTrue(result.get("A").contextNote().contains("摘要"));
    }

    @Test
    @DisplayName("超出听觉范围 → ISOLATED，完全不可见")
    void outOfHearingRangeIsIsolated() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 500, 0);  // 500 >= hearRange(200)

        Map<String, TrackAssignment> result =
                new SpatialTrackResolver(CONVERSATION_DISTANCE).resolve(List.of(a, b));

        assertEquals(Track.Mode.ISOLATED, result.get("A").type());
        assertEquals(Track.Mode.ISOLATED, result.get("B").type());
        assertTrue(result.get("A").visibleAgents().isEmpty());
        assertTrue(result.get("A").contextNote().contains("完全隔离"));
    }

    @Test
    @DisplayName("混合场景：A/B 密谈 MERGED，C 远处旁观 WEAK")
    void mixedMergedAndWeak() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);    // 与 A 密谈
        AgentState c = agent("C", 50, 0);   // 远处可听见

        Map<String, TrackAssignment> result =
                new SpatialTrackResolver(CONVERSATION_DISTANCE).resolve(List.of(a, b, c));

        assertEquals(Track.Mode.MERGED, result.get("A").type());
        assertEquals(Track.Mode.MERGED, result.get("B").type());
        assertEquals(Track.Mode.WEAK, result.get("C").type());
        // C 能看到 A/B 在场（仅摘要），A/B 看不到 C 的完整上下文之外的信息
        assertTrue(result.get("C").visibleAgents().containsAll(List.of("A", "B")));
        assertFalse(result.get("A").visibleAgents().contains("C"));
    }

    @Test
    @DisplayName("私密房间：房内与房外之间 ISOLATED")
    void privateRoomBoundaryIsIsolated() {
        AgentState a = agent("A", 0, 0);   // 房内
        AgentState b = agent("B", 1, 0);   // 房外（距离很近但被隔断）

        Map<String, TrackAssignment> result = new SpatialTrackResolver(
                CONVERSATION_DISTANCE, Set.of("A")).resolve(List.of(a, b));

        assertEquals(Track.Mode.ISOLATED, result.get("A").type());
        assertEquals(Track.Mode.ISOLATED, result.get("B").type());
        assertTrue(result.get("B").contextNote().contains("私密房间"));
    }

    @Test
    @DisplayName("空输入返回空结果，不抛异常")
    void emptyInputReturnsEmptyMap() {
        Map<String, TrackAssignment> result =
                new SpatialTrackResolver().resolve(List.of());
        assertTrue(result.isEmpty());
    }
}
