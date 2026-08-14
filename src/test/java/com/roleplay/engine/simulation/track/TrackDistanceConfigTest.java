package com.roleplay.engine.simulation.track;

import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0815-A（调研报告-移动与分组问题.md 2.4 #5）：单位修正 + 阈值配置化——
 * conversationDistance 明确为 px 语义（默认 70px，原 5.0 实为「格」注释但按 px 用，5px≈贴脸
 * 导致近距离对话 MERGED 几乎永不触发）；配置键 roleplay.track.conversation-distance
 * （AppConfig.TrackConfig + yml 双份）→ TrackDirectorService.setConversationDistance 接线。
 */
class TrackDistanceConfigTest {

    private static final double HEAR_RANGE = 200.0;

    private AgentState agent(String name, double x, double y) {
        AgentState s = new AgentState(name, x, y);
        s.setHearRange(HEAR_RANGE);
        return s;
    }

    // ── 单位修正：px 语义默认值 ────────────────────────────────

    @Test
    @DisplayName("① 默认会话距离=70px（原 5.0「格」错位修正），且可观测")
    void defaultConversationDistance_is70px() {
        assertEquals(70.0, SpatialTrackResolver.DEFAULT_CONVERSATION_DISTANCE, 1e-9,
                "默认会话距离应修正为 px 语义 70px");
        assertEquals(70.0, new SpatialTrackResolver().getConversationDistance(), 1e-9);
        assertEquals(70.0, new SpatialTrackResolver(0).getConversationDistance(), 1e-9,
                "非法值回退默认");
    }

    @Test
    @DisplayName("② 60px 双人（旧 5.0 下为 WEAK）→ 新 px 语义下 MERGED（可对话）")
    void distance60_isMergedUnderPxSemantics() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 60, 0);   // 60 < 70 → MERGED（旧 5.0 阈值下是 WEAK）

        Map<String, TrackAssignment> result = new SpatialTrackResolver().resolve(List.of(a, b));

        assertEquals(Track.Mode.MERGED, result.get("A").type(), "60px 内应 MERGED（px 语义）");
        assertEquals(Track.Mode.MERGED, result.get("B").type());
        assertTrue(result.get("A").visibleAgents().contains("B"));
    }

    @Test
    @DisplayName("③ 显式会话距离生效：100px 双人 → MERGED；TrackDirectorService.setConversationDistance 接线")
    void explicitDistance_appliesToDirector() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 100, 0);
        TrackDirectorService director = new TrackDirectorService();
        director.setConversationDistance(100);

        Map<String, TrackAssignment> result = director.assign(List.of(a, b), Map.of());

        // 100px 双人 + 无触发 → allMerged 路径（公开聊天）
        assertEquals(Track.Mode.MERGED, result.get("A").type());

        // 触发空间路径时显式距离生效：C(0)/D(80) 互距 80px < 100 → 双人 MERGED；
        // E(190) 距 C=190px / 距 D=110px 均 ∈ [100, 200) 听觉带 → WEAK。
        // （注：E 不能放 150px——距 D(80) 仅 70px < 100px 会合法落入 MERGED，几何与断言矛盾。）
        TrackDirectorService dir2 = new TrackDirectorService();
        dir2.setConversationDistance(100);
        AgentState c = agent("C", 0, 0);
        AgentState d = agent("D", 80, 0);   // < 100 → MERGED
        AgentState e = agent("E", 190, 0);  // 距 C=190 / 距 D=110，均 ∈ [100, 200) → WEAK
        c.setStance(AgentState.Stance.FOR);
        d.setStance(AgentState.Stance.AGAINST);   // 立场冲突 +40 → 触发 Track
        Map<String, TrackAssignment> r2 = dir2.assign(List.of(c, d, e), Map.of());
        assertEquals(Track.Mode.MERGED, r2.get("C").type());
        assertEquals(Track.Mode.MERGED, r2.get("D").type());
        assertEquals(Track.Mode.WEAK, r2.get("E").type(), "距两成员均在 100px 会话距离外（听觉带内）→ WEAK");
    }

    // ── 配置绑定：AppConfig + yml 双份 ─────────────────────────

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @ActiveProfiles("test")
    static class TrackConfigBindingTest {

        @Autowired
        private AppConfig appConfig;

        @Test
        @DisplayName("④ yml roleplay.track.conversation-distance 绑定 AppConfig.TrackConfig（默认 70）")
        void trackConfig_boundFromYml() {
            AppConfig.TrackConfig track = appConfig.getTrack();
            assertNotNull(track, "roleplay.track.* 应绑定到 AppConfig");
            assertEquals(70.0, track.getConversationDistance(), 1e-9,
                    "conversation-distance 默认 70px（yml 双份）");
        }
    }
}
