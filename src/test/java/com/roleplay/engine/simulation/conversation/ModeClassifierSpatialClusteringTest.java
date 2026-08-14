package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.HearingSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0815-A（调研报告-移动与分组问题.md 2.4 #1）：分组空间聚类——听力连通分量是传递闭包，
 * 链式可听（A↔B↔C）会把相距 400px+ 的成员拉进同一组；findConnectedComponents 后增加
 * 「组内任意成员对距离 &lt; {@link ModeClassifier#MAX_GROUP_DIAMETER}（300px）」校验，超距不成组。
 *
 * <p>classify 输入为可构造的 HearingResult（canHear()=distance ≤ effectiveRange），
 * 直接验证直径校验逻辑（不依赖声学模型）。
 */
class ModeClassifierSpatialClusteringTest {

    private AgentState agent(String name, double x, double y) {
        return new AgentState(name, x, y);
    }

    /** 构造双向可听结果（speaker↔listener 互为可听，dist ≤ effectiveRange）。 */
    private HearingSystem.HearingResult hear(String a, String b, double dist, double effectiveRange) {
        return new HearingSystem.HearingResult(a, b, dist, 1.0, 200.0, effectiveRange);
    }

    private List<ModeClassifier.GroupCandidate> classify(AgentState... agents) {
        Map<String, AgentState> all = new LinkedHashMap<>();
        for (AgentState a : agents) all.put(a.getAgentName(), a);
        List<HearingSystem.HearingResult> hearing = new java.util.ArrayList<>();
        // 输入调用方直接喂听对（测试构造），这里以「距离 ≤ 有效听觉范围」为 canHear 判定。
        for (AgentState a : agents) {
            for (AgentState b : agents) {
                if (a == b) continue;
                double d = a.distanceTo(b);
                if (d <= 300) { // effectiveRange 取 300：保证测试中的听对 canHear
                    hearing.add(hear(a.getAgentName(), b.getAgentName(), d, 300));
                }
            }
        }
        return new ModeClassifier().classify(hearing, all);
    }

    @Test
    @DisplayName("① 链式传递超距不成组：A↔B↔C 链 A-C 相距 400px ≥ 300px 直径 → 整组拒绝")
    void chainBeyondDiameter_rejected() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 150, 0);
        AgentState c = agent("C", 400, 0);   // A-C = 400 ≥ 300

        List<ModeClassifier.GroupCandidate> groups = classify(a, b, c);

        assertTrue(groups.isEmpty(), "超距链式分量不应成组（A↔B↔C 链 A-C 400px），实际=" + groups);
    }

    @Test
    @DisplayName("② 直径内成组：A↔B↔C 链 A-C 相距 290px < 300px → 三人组成立")
    void chainWithinDiameter_groupFormed() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 150, 0);
        AgentState c = agent("C", 290, 0);   // A-C = 290 < 300

        List<ModeClassifier.GroupCandidate> groups = classify(a, b, c);

        assertEquals(1, groups.size(), "直径内链式分量应成组，实际=" + groups);
        List<String> names = groups.get(0).members().stream()
                .map(AgentState::getAgentName).sorted().toList();
        assertEquals(List.of("A", "B", "C"), names);
        assertEquals(ConversationMode.GROUP_DISCUSSION, groups.get(0).mode(), "三人组 → GROUP_DISCUSSION");
    }

    @Test
    @DisplayName("③ 双人近距成组：相距 250px < 300px → DYAD 成立")
    void pairWithinDiameter_dyadFormed() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 250, 0);

        List<ModeClassifier.GroupCandidate> groups = classify(a, b);

        assertEquals(1, groups.size(), "双人直径内应成组，实际=" + groups);
        assertEquals(ConversationMode.DYAD, groups.get(0).mode());
    }

    @Test
    @DisplayName("④ 双人超距不成组：相距 400px ≥ 300px（即使 canHear）→ 不成组")
    void pairBeyondDiameter_rejected() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 400, 0);

        List<ModeClassifier.GroupCandidate> groups = classify(a, b);

        assertTrue(groups.isEmpty(), "超距双人不应成组（相距 400px），实际=" + groups);
    }

    @Test
    @DisplayName("⑤ 混合：近距对成组、超距孤立者被排除（不会把全分量拉进一组）")
    void mixed_clusterAndFarAgentSeparated() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 100, 0);   // A-B 直径内
        AgentState d = agent("D", 600, 0);   // 距 A/B 均 ≥ 300 → 不能与 A/B 同组

        List<ModeClassifier.GroupCandidate> groups = classify(a, b, d);

        assertEquals(1, groups.size(), "只有 A/B 成组，D 不混入，实际=" + groups);
        List<String> names = groups.get(0).members().stream()
                .map(AgentState::getAgentName).sorted().toList();
        assertEquals(List.of("A", "B"), names);
    }
}
