package com.roleplay.engine.simulation.movement;

import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static com.roleplay.engine.simulation.movement.MovementConstraint.MERGED_MAX_FOLLOW_DISTANCE;

/**
 * P-0815-A（调研报告-移动与分组问题.md 2.4 #6）：MovementConstraint.computeMerged 距离守卫——
 * 成员与 leader 距离超过 {@link MovementConstraint#MERGED_MAX_FOLLOW_DISTANCE}（300px）
 * 不强制归队（保持原位，防瞬移式聚拢）；直径内成员照常占 follow slot；leader 收敛质心行为不变。
 */
class MovementConstraintDistanceGuardTest {

    private static final double HEAR_RANGE = 200.0;
    private static final double EPS = 1.0;

    private final MovementConstraint constraint = new MovementConstraint();

    private AgentState agent(String name, double x, double y) {
        AgentState s = new AgentState(name, x, y);
        s.setHearRange(HEAR_RANGE);
        return s;
    }

    /** 全 MERGED 群组分配：每人可见同组其他人。 */
    private Map<String, TrackAssignment> mergedGroup(String... names) {
        Map<String, TrackAssignment> m = new LinkedHashMap<>();
        List<String> all = Arrays.asList(names);
        for (String n : names) {
            List<String> visible = all.stream().filter(v -> !v.equals(n)).toList();
            m.put(n, TrackAssignment.of(n, Track.Mode.MERGED, visible, "公开聊天"));
        }
        return m;
    }

    private double dist(double x1, double y1, double x2, double y2) {
        return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));
    }

    @Test
    @DisplayName("① 成员距 leader > 300px → 不强制归队（无约束目标，保持原位）")
    void followerBeyondMaxDistance_noTarget() {
        // leader=A（字典序最小）；B 距 A 450px > 300px → B 不应被拉到 A 的队形
        AgentState a = agent("A", 100, 100);
        AgentState b = agent("B", 550, 100);

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(a, b), mergedGroup("A", "B"), Set.of());

        assertNull(targets.get("B"), "距 leader 450px > 300px 不应强制归队（保持原位）");
        // leader 收敛质心行为不变（质心=325,100）
        MovementTarget ta = targets.get("A");
        assertNotNull(ta, "leader 仍聚向组质心");
        assertTrue(ta.reason().contains("锚点"), ta.reason());
        assertEquals(325.0, ta.targetX(), EPS, "leader 收敛到质心 x=325");
        assertEquals(100.0, ta.targetY(), EPS);
    }

    @Test
    @DisplayName("② 成员距 leader ≤ 300px → 照常占 follow slot（归队）")
    void followerWithinMaxDistance_stillJoins() {
        AgentState a = agent("A", 100, 100);
        AgentState b = agent("B", 300, 100);   // 距 leader 200px ≤ 300px

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(a, b), mergedGroup("A", "B"), Set.of());

        MovementTarget tb = targets.get("B");
        assertNotNull(tb, "200px 内成员应归队（占 follow slot）");
        assertTrue(tb.reason().contains("槽位#1"), tb.reason());
        assertEquals(MovementConstraint.SLOT_SPACING,
                dist(tb.targetX(), tb.targetY(), 100, 100), EPS,
                "B 槽位#1 距 leader 一个 SLOT_SPACING");
    }

    @Test
    @DisplayName("③ 三人：近距两成员照常归队，超距成员保持原位（部分归队语义）")
    void mixed_farMemberStaysNearMembersJoin() {
        double dir = slotAngle("A");
        double dx = Math.cos(dir) * MovementConstraint.SLOT_SPACING;
        double dy = Math.sin(dir) * MovementConstraint.SLOT_SPACING;
        AgentState a = agent("A", 100, 100);
        AgentState b = agent("B", 100 + dx, 100 + dy);       // 恰在槽位#1（距 leader 16px）
        AgentState c = agent("C", 700, 100);                  // 距 leader 600px > 300px

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(a, b, c), mergedGroup("A", "B", "C"), Set.of());

        assertNull(targets.get("B"), "B 已在槽位#1 → 无需移动（防抖动语义保持）");
        assertNull(targets.get("C"), "C 距 leader 600px > 300px → 不强制归队");
        assertNotNull(targets.get("A"), "leader 仍聚向质心（300, 66.7）");
    }

    @Test
    @DisplayName("④ 边界：距 leader 恰 300px（未超过阈值）→ 仍归队")
    void followerAtExactlyMaxDistance_stillJoins() {
        AgentState a = agent("A", 100, 100);
        AgentState b = agent("B", 400, 100);   // 距 leader 恰 300px = 阈值（未超过）

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(a, b), mergedGroup("A", "B"), Set.of());

        assertNotNull(targets.get("B"), "恰 300px（未超过）应仍归队");
    }

    /** 与实现 angleFor 同规则的确定性队形方向（供槽位摆放）。 */
    private static double slotAngle(String leaderName) {
        int h = leaderName == null ? 0 : leaderName.hashCode() & 0x7fffffff;
        return (h % 360) * Math.PI / 180.0;
    }

    @Test
    @DisplayName("⑤ 守卫常量可观测：MERGED_MAX_FOLLOW_DISTANCE=300px")
    void guardConstantVisible() {
        assertEquals(300.0, MERGED_MAX_FOLLOW_DISTANCE, 1e-9);
    }
}
