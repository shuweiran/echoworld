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
 * #45 补测：TrackDirectorService 秘密任务强制 ISOLATED（含与 WEAK 叠加）、
 * 目标冲突降级（MERGED→WEAK、ISOLATED 不降级）、公开聊天全 MERGED。
 */
class TrackDirectorSecretOverrideTest {

    private TrackDirectorService director;

    private AgentState agent(String name, double x, double y) {
        AgentState s = new AgentState(name, x, y);
        s.setHearRange(200.0);
        s.setEmotion(Emotion.NEUTRAL);
        return s;
    }

    @Test
    @DisplayName("秘密任务成员强制 ISOLATED，覆盖空间分配的 MERGED/WEAK")
    void secretAgentForcedIsolated() {
        director = new TrackDirectorService();

        // 5 人密集（score=40 触发）→ 空间分配 MERGED；secret B → 强制 ISOLATED
        List<AgentState> agents = List.of(
                agent("A", 0, 0), agent("B", 2, 0), agent("C", 4, 0),
                agent("D", 6, 0), agent("E", 8, 0));
        director.setSecretAgents(Set.of("B"));
        Map<String, TrackAssignment> result = director.assign(agents);

        assertEquals(Track.Mode.ISOLATED, result.get("B").type(),
                "秘密任务成员 B 必须 ISOLATED（覆盖空间分配）");
        assertTrue(result.get("B").visibleAgents().isEmpty(),
                "ISOLATED 成员不应可见任何人");

        // 秘密与 WEAK 叠加：B 若本应 WEAK（中距离），秘密仍强制 ISOLATED
        List<AgentState> sparse = List.of(
                agent("A", 0, 0), agent("B", 50, 0), agent("C", 60, 0),
                agent("D", 100, 0), agent("E", 120, 0));
        director.setSecretAgents(Set.of("B"));
        Map<String, TrackAssignment> sparseResult = director.assign(sparse);
        assertEquals(Track.Mode.ISOLATED, sparseResult.get("B").type(),
                "WEAK 叠加秘密任务仍须 ISOLATED");
    }

    @Test
    @DisplayName("无触发且无冲突 → 全部 MERGED（公开聊天）")
    void noTriggerAllMerged() {
        director = new TrackDirectorService();
        List<AgentState> agents = List.of(
                agent("A", 0, 0), agent("B", 2, 0));
        Map<String, TrackAssignment> result = director.assign(agents, Map.of());
        assertEquals(Track.Mode.MERGED, result.get("A").type());
        assertEquals(Track.Mode.MERGED, result.get("B").type());
    }

    @Test
    @DisplayName("目标冲突：MERGED 降 WEAK，ISOLATED 不降级；互斥目标对全组合")
    void conflictDowngrade() {
        director = new TrackDirectorService();
        // 5 人触发（size=40），A/B 持互斥目标 调查/隐瞒
        List<AgentState> agents = List.of(
                agent("A", 0, 0), agent("B", 2, 0), agent("C", 4, 0),
                agent("D", 6, 0), agent("E", 8, 0));
        Map<String, String> goals = Map.of("A", "调查", "B", "隐瞒");
        Map<String, TrackAssignment> result = director.assign(agents, goals);

        assertEquals(Track.Mode.WEAK, result.get("A").type(), "互斥目标 A 应降 WEAK");
        assertEquals(Track.Mode.WEAK, result.get("B").type(), "互斥目标 B 应降 WEAK");

        // 互斥目标对全组合（揭发/保护、追踪/躲避、争夺/回避）
        String[][] pairs = {{"揭发", "保护"}, {"追踪", "躲避"}, {"争夺", "回避"}};
        for (String[] pair : pairs) {
            Map<String, String> g2 = Map.of("A", pair[0], "B", pair[1]);
            Map<String, TrackAssignment> r2 = director.assign(agents, g2);
            assertEquals(Track.Mode.WEAK, r2.get("A").type(), pair[0] + "/" + pair[1] + " A 应 WEAK");
            assertEquals(Track.Mode.WEAK, r2.get("B").type(), pair[0] + "/" + pair[1] + " B 应 WEAK");
        }

        // 秘密成员不因冲突降级：A 秘密 + A/B 冲突 → A 保持 ISOLATED
        director.setSecretAgents(Set.of("A"));
        Map<String, TrackAssignment> r3 = director.assign(agents, goals);
        assertEquals(Track.Mode.ISOLATED, r3.get("A").type(), "秘密 ISOLATED 不应被冲突降级");
        assertEquals(Track.Mode.WEAK, r3.get("B").type());
    }

    @Test
    @DisplayName("空 agents/空 goals 安全；良性目标不冲突")
    void emptyAndBenign() {
        director = new TrackDirectorService();
        assertTrue(director.assign(List.of(), Map.of()).isEmpty(), "空 agents 应安全返回空");
        assertTrue(director.assign(List.of(), null).isEmpty());

        List<AgentState> agents = List.of(
                agent("A", 0, 0), agent("B", 2, 0), agent("C", 4, 0),
                agent("D", 6, 0), agent("E", 8, 0));
        // 良性目标（如“探索周围”）不冲突 → 保持 MERGED（空间近距离）
        Map<String, TrackAssignment> r = director.assign(agents, Map.of("A", "探索周围", "B", "探索周围"));
        assertEquals(Track.Mode.MERGED, r.get("A").type(), "良性目标不应触发冲突降级");
    }
}
