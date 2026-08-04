package com.roleplay.engine.simulation.movement;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static com.roleplay.engine.simulation.movement.MovementConstraint.SLOT_SPACING;

/**
 * Tests for {@link MovementConstraint} — Phase 4 轨道 → 运动约束（纯规则，零 LLM）。
 *
 * <p>覆盖需求文档第九条三种轨道的运动：
 * <ul>
 *   <li>MERGED 群组成员 → GroupAnchor（leader + follow slot 队形跟随）</li>
 *   <li>WEAK 监听者 → 听觉带 [hearRange*0.5, hearRange]（太近拉远 / 太远靠近）</li>
 *   <li>ISOLATED → 距 secretAgents ≥ 安全距离（过近避让）</li>
 *   <li>优先级：ISOLATED 避让 &gt; WEAK &gt; MERGED（秘密成员即使分配为 MERGED 也避让）</li>
 * </ul>
 */
class MovementConstraintTest {

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

    private TrackAssignment weak(String self, String... visible) {
        return TrackAssignment.of(self, Track.Mode.WEAK, Arrays.asList(visible), "摘要观察");
    }

    private TrackAssignment isolated(String self) {
        return TrackAssignment.isolated(self, "完全隔离");
    }

    private double dist(double x1, double y1, double x2, double y2) {
        return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));
    }

    // ── MERGED：GroupAnchor（leader + follow slot）──────────────

    @Test
    @DisplayName("MERGED：leader=字典序最小名，收敛到组质心；其余成员占 follow slot 排成直线")
    void mergedGroupUsesLeaderFollowSlots() {
        AgentState a = agent("A", 100, 100);
        AgentState b = agent("B", 120, 100);
        AgentState c = agent("C", 110, 140);

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(c, b, a), mergedGroup("A", "B", "C"), Set.of());

        assertEquals(3, targets.size());
        MovementTarget ta = targets.get("A");
        MovementTarget tb = targets.get("B");
        MovementTarget tc = targets.get("C");
        assertNotNull(ta);
        assertNotNull(tb);
        assertNotNull(tc);
        assertTrue(ta.reason().contains("锚点"), "leader 应聚向组质心，实际=" + ta.reason());
        assertTrue(tb.reason().contains("槽位#1"), "B 应为队首槽位，实际=" + tb.reason());
        assertTrue(tc.reason().contains("槽位#2"), "C 应为队尾槽位，实际=" + tc.reason());

        // 组质心 = (110, 113.33)；leader A 目标应为其质心。
        assertEquals(110.0, ta.targetX(), EPS, "leader A 应聚向质心 x=110");
        assertEquals(340.0 / 3.0, ta.targetY(), EPS, "leader A 应聚向质心 y=113.33");

        // 跟随者按 slot 排在 leader 后方直线：B 距 A = 1×SLOT_SPACING，C = 2×SLOT_SPACING。
        double dir = slotAngle("A");
        assertEquals(SLOT_SPACING, dist(tb.targetX(), tb.targetY(), 100, 100), EPS,
                "B 槽位#1 应距 leader A 一个 SLOT_SPACING");
        assertEquals(SLOT_SPACING * 2, dist(tc.targetX(), tc.targetY(), 100, 100), EPS,
                "C 槽位#2 应距 leader A 两个 SLOT_SPACING");
        // B/C 在 leader 后方的同一直线上（方向 = formationAngle(leader)）。
        assertEquals(Math.cos(dir), (tb.targetX() - 100) / SLOT_SPACING, 1e-6);
        assertEquals(Math.sin(dir), (tb.targetY() - 100) / SLOT_SPACING, 1e-6);
        assertEquals(Math.cos(dir), (tc.targetX() - 100) / (SLOT_SPACING * 2), 1e-6);
        assertEquals(Math.sin(dir), (tc.targetY() - 100) / (SLOT_SPACING * 2), 1e-6);
    }

    /** GroupAnchor 队形方向的确定性角度（与实现 angleFor 同规则）。 */
    private static double slotAngle(String leaderName) {
        int h = leaderName == null ? 0 : leaderName.hashCode() & 0x7fffffff;
        return (h % 360) * Math.PI / 180.0;
    }

    @Test
    @DisplayName("MERGED leader 确定性与名称序无关（B,A,C 输入仍选 A 为 leader）")
    void leaderSelectionIsDeterministicByName() {
        AgentState a = agent("A", 100, 100);
        AgentState b = agent("B", 120, 100);
        AgentState c = agent("C", 110, 140);

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(b, a, c), mergedGroup("A", "B", "C"), Set.of());

        assertTrue(targets.get("A").reason().contains("锚点"), "字典序最小名 A 恒为 leader");
        assertTrue(targets.get("B").reason().contains("跟随 leader(A)"));
        assertTrue(targets.get("C").reason().contains("跟随 leader(A)"));
    }

    @Test
    @DisplayName("MERGED 无可见同伴 → 无约束目标")
    void mergedWithoutPeersProducesNoTarget() {
        AgentState a = agent("A", 100, 100);
        Map<String, TrackAssignment> assignments =
                Map.of("A", TrackAssignment.of("A", Track.Mode.MERGED, List.of(), "单人"));

        Map<String, MovementTarget> targets = constraint.compute(List.of(a), assignments, Set.of());

        assertTrue(targets.isEmpty());
    }

    @Test
    @DisplayName("MERGED 已处于期望位置（leader 在质心 / 跟随者在槽位）→ 不重设目标（防抖动）")
    void mergedAlreadyAtAnchorNoTarget() {
        // A(leader) 置于质心；B 置于其槽位#1；C 偏离其槽位#2 → 仅 C 需移动。
        double dir = slotAngle("A");
        double dx = Math.cos(dir) * SLOT_SPACING;
        double dy = Math.sin(dir) * SLOT_SPACING;
        AgentState a = agent("A", 200, 100);            // 质心（B、C 对称摆放）
        AgentState b = agent("B", 200 + dx, 100 + dy);   // 恰在槽位#1
        AgentState c = agent("C", 200 - dx, 100 - dy);   // 偏离槽位#2（应在 200+2dx, 100+2dy）

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(a, b, c), mergedGroup("A", "B", "C"), Set.of());

        assertNull(targets.get("A"), "leader A 已在质心，不应重设目标");
        assertNull(targets.get("B"), "跟随者 B 已在槽位#1，不应重设目标");
        assertNotNull(targets.get("C"), "跟随者 C 偏离槽位#2，应重设目标");
    }

    @Test
    @DisplayName("MERGED 跟随语义：leader 移动后，跟随者槽位相对新 leader 位置重算")
    void followersRefollowWhenLeaderMoves() {
        double dir = slotAngle("A");
        double dx1 = Math.cos(dir) * SLOT_SPACING;
        double dy1 = Math.sin(dir) * SLOT_SPACING;
        AgentState a = agent("A", 100, 100);
        AgentState b = agent("B", 100 + dx1, 100 + dy1);    // 恰在槽位#1
        AgentState c = agent("C", 100 + dx1 * 2, 100 + dy1 * 2); // 恰在槽位#2

        Map<String, MovementTarget> first = constraint.compute(
                List.of(a, b, c), mergedGroup("A", "B", "C"), Set.of());
        assertNull(first.get("B"), "leader 未移动时 B 在槽位，无需移动");
        assertNull(first.get("C"));

        // leader 移动后：B/C 槽位应随 A 新位置重算。
        a.setX(300);
        a.setY(200);
        Map<String, MovementTarget> second = constraint.compute(
                List.of(a, b, c), mergedGroup("A", "B", "C"), Set.of());
        assertEquals(300 + Math.cos(dir) * SLOT_SPACING, second.get("B").targetX(), EPS,
                "B 槽位应随 leader 新位置重算");
        assertEquals(200 + Math.sin(dir) * SLOT_SPACING, second.get("B").targetY(), EPS);
        assertEquals(300 + Math.cos(dir) * SLOT_SPACING * 2, second.get("C").targetX(), EPS,
                "C 槽位应随 leader 新位置重算");
        assertEquals(200 + Math.sin(dir) * SLOT_SPACING * 2, second.get("C").targetY(), EPS);
    }

    // ── WEAK：保持听觉范围 ─────────────────────────────────────

    @Test
    @DisplayName("WEAK 太近（贴脸偷听）→ 拉远到听觉带中部（≈0.7·hearRange）")
    void weakTooClosePushesOut() {
        AgentState anchor = agent("A", 100, 100);
        AgentState listener = agent("B", 105, 100); // 距 A 仅 5 < 0.5·200

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(anchor, listener),
                Map.of("A", mergedGroup("A", "B").get("A"), "B", weak("B", "A")),
                Set.of());

        MovementTarget t = targets.get("B");
        assertNotNull(t);
        assertTrue(t.reason().contains("WEAK"));
        assertTrue(t.reason().contains("拉远"));
        double d = dist(t.targetX(), t.targetY(), 100, 100);
        assertEquals(HEAR_RANGE * MovementConstraint.WEAK_TARGET_FACTOR_CLOSE, d, EPS,
                "太近时应拉远到 0.7·hearRange = 140");
    }

    @Test
    @DisplayName("WEAK 太远（听不到）→ 靠近到听觉带内（≈0.8·hearRange）")
    void weakTooFarPullsIn() {
        AgentState anchor = agent("A", 100, 100);
        AgentState listener = agent("B", 900, 100); // 距 A 800 > hearRange

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(anchor, listener),
                Map.of("A", mergedGroup("A", "B").get("A"), "B", weak("B", "A")),
                Set.of());

        MovementTarget t = targets.get("B");
        assertNotNull(t);
        assertTrue(t.reason().contains("靠近"));
        double d = dist(t.targetX(), t.targetY(), 100, 100);
        assertEquals(HEAR_RANGE * MovementConstraint.WEAK_TARGET_FACTOR_FAR, d, EPS,
                "太远时应靠近到 0.8·hearRange = 160");
    }

    @Test
    @DisplayName("WEAK 已在听觉带内 [0.5h, h] → 无约束目标")
    void weakInBandNoConstraint() {
        AgentState anchor = agent("A", 100, 100);
        AgentState listener = agent("B", 250, 100); // 距 A 150 ∈ [100, 200]

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(anchor, listener),
                Map.of("A", mergedGroup("A", "B").get("A"), "B", weak("B", "A")),
                Set.of());

        assertNull(targets.get("B"));
    }

    // ── ISOLATED：主动保持距离 ─────────────────────────────────

    @Test
    @DisplayName("ISOLATED 距 secretAgent 过近 → 远离到 ≥ 安全距离")
    void isolatedAvoidsSecretAgent() {
        AgentState secret = agent("S", 100, 100);
        AgentState iso = agent("I", 120, 100); // 距 S 仅 20 < 60

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(secret, iso),
                Map.of("S", mergedGroup("S", "I").get("S"), "I", isolated("I")),
                Set.of("S"));

        MovementTarget t = targets.get("I");
        assertNotNull(t);
        assertTrue(t.reason().contains("ISOLATED"));
        assertTrue(t.reason().contains("安全距离"));
        double d = dist(t.targetX(), t.targetY(), 100, 100);
        assertTrue(d >= MovementConstraint.ISOLATED_SAFE_DISTANCE,
                "避让目标距 secretAgent 应 ≥ 60，实际=" + d);
        assertEquals(MovementConstraint.ISOLATED_SAFE_DISTANCE * MovementConstraint.ISOLATED_PUSH_FACTOR,
                d, EPS, "默认推到 60×1.2=72");
    }

    @Test
    @DisplayName("ISOLATED 已距 secretAgent 足够远 → 无约束目标")
    void isolatedAlreadySafeNoConstraint() {
        AgentState secret = agent("S", 100, 100);
        AgentState iso = agent("I", 500, 100); // 距 S 400 ≥ 60

        Map<String, MovementTarget> targets = constraint.compute(
                List.of(secret, iso),
                Map.of("S", mergedGroup("S", "I").get("S"), "I", isolated("I")),
                Set.of("S"));

        assertNull(targets.get("I"));
    }

    // ── 优先级：ISOLATED > WEAK > MERGED ───────────────────────

    @Test
    @DisplayName("优先级：秘密任务成员即使分配为 MERGED 也按 ISOLATED 避让")
    void secretAgentOverridesMergedAssignment() {
        AgentState secret = agent("A", 100, 100);
        AgentState peer = agent("B", 108, 100); // 距 A 8 < 60

        // A 的分配是 MERGED（同组可见 B），但 A 在 secretAgents 中，且 B 是指定避让目标
        // → 应避让（ISOLATED）而非聚集（MERGED）。
        Map<String, MovementTarget> targets = constraint.compute(
                List.of(secret, peer),
                mergedGroup("A", "B"),
                Set.of("A"),       // secretAgents
                Set.of("B"));      // 指定避让目标

        MovementTarget ta = targets.get("A");
        assertNotNull(ta);
        assertTrue(ta.reason().contains("ISOLATED"), "秘密成员应走 ISOLATED 避让，实际=" + ta.reason());
        double d = dist(ta.targetX(), ta.targetY(), 108, 100);
        assertTrue(d >= MovementConstraint.ISOLATED_SAFE_DISTANCE,
                "A 应远离指定避让目标 B ≥ 60，实际=" + d);
    }

    @Test
    @DisplayName("空输入（无角色 / 无分配）→ 空结果不抛异常")
    void emptyInputsAreSafe() {
        assertTrue(constraint.compute(List.of(), Map.of(), Set.of()).isEmpty());
        assertTrue(constraint.compute(List.of(agent("A", 0, 0)), null, Set.of()).isEmpty());
        assertTrue(constraint.compute((List<AgentState>) null, Map.of(), null).isEmpty());
    }

    // ── apply：玩家手动控制优先 ────────────────────────────────

    @Test
    @DisplayName("apply：playerControlled / manualTarget 角色不被约束覆盖，其余写入目标")
    void applyRespectsPlayerControl() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("A", "测试"), "npc", null), 100, 100, 200, 80);
        world.registerAgent(new Agent(new Persona("B", "测试"), "npc", null), 300, 100, 200, 80);
        AgentState a = world.getState("A");
        AgentState b = world.getState("B");

        // 玩家控制的 A：已有手动目标，不应被覆盖。
        a.setPlayerControlled(true);
        a.setTarget(500, 500);
        a.setManualTarget(true);
        // 普通角色 B：MERGED 聚集目标。
        Map<String, MovementTarget> targets = constraint.compute(
                world, mergedGroup("A", "B"), Set.of());

        constraint.apply(world, targets);

        assertTrue(a.isHasTarget());
        assertEquals(500, a.getTargetX(), 0.001);
        assertEquals(500, a.getTargetY(), 0.001);
        // A 不应被约束改写。
        MovementTarget ta = targets.get("A");
        if (ta != null) {
            assertNotEquals(ta.targetX(), a.getTargetX(), 0.001);
        }
        // B 应获得约束目标（GroupAnchor：跟随 leader A 的槽位）。
        MovementTarget tb = targets.get("B");
        assertNotNull(tb);
        assertTrue(b.isHasTarget());
        assertEquals(tb.targetX(), b.getTargetX(), 0.001);
        assertEquals(tb.targetY(), b.getTargetY(), 0.001);
    }
}
