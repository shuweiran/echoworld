package com.roleplay.engine.simulation.track;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InteractionDetector}: rule-based TrackScore factors and the
 * score >= 40 trigger threshold (人数 / 旁观者 / 目标冲突 / 秘密任务 / 情绪异常).
 */
class InteractionDetectorTest {

    private AgentState agent(String name, Emotion emotion) {
        AgentState s = new AgentState(name, 0, 0);
        s.setEmotion(emotion);
        return s;
    }

    @Test
    @DisplayName("2人普通交谈 → 0 分，不触发 Track")
    void twoPersonChatDoesNotTrigger() {
        List<AgentState> agents = List.of(agent("A", Emotion.NEUTRAL), agent("B", Emotion.NEUTRAL));

        InteractionDetector.TrackScore score = new InteractionDetector().evaluate(agents);

        assertEquals(0, score.score());
        assertFalse(score.triggered());
    }

    @Test
    @DisplayName("5人以上 → 人数因子40，达到阈值")
    void fivePlusPeopleTriggerBySize() {
        List<AgentState> agents = List.of(
                agent("A", Emotion.NEUTRAL), agent("B", Emotion.NEUTRAL),
                agent("C", Emotion.NEUTRAL), agent("D", Emotion.NEUTRAL),
                agent("E", Emotion.NEUTRAL));

        InteractionDetector.TrackScore score = new InteractionDetector().evaluate(agents);

        assertEquals(InteractionDetector.SIZE_5_PLUS, score.sizeFactor());
        assertEquals(40, score.score());
        assertTrue(score.triggered());
    }

    @Test
    @DisplayName("3-4人 → 人数因子20；存在旁观者(+20) 后达到 40")
    void bystanderPushesOverThreshold() {
        AgentState a = agent("A", Emotion.NEUTRAL);
        AgentState b = agent("B", Emotion.NEUTRAL);
        AgentState c = agent("C", Emotion.NEUTRAL);
        a.setInConversation(true);
        b.setInConversation(true);   // A/B 在对话中，C 是旁观者

        List<AgentState> agents = List.of(a, b, c);
        InteractionDetector.TrackScore score = new InteractionDetector().evaluate(agents);

        assertEquals(InteractionDetector.SIZE_3_4, score.sizeFactor());
        assertEquals(InteractionDetector.BYSTANDER, score.bystanderFactor());
        assertEquals(40, score.score());
        assertTrue(score.triggered());
    }

    @Test
    @DisplayName("秘密任务(+50) 单独即可触发")
    void secretTaskTriggersAlone() {
        AgentState a = agent("A", Emotion.NEUTRAL);
        AgentState b = agent("B", Emotion.NEUTRAL);
        List<AgentState> agents = List.of(a, b);

        InteractionDetector.TrackScore score =
                new InteractionDetector().evaluate(agents, Set.of("B"));

        assertEquals(InteractionDetector.SECRET_TASK, score.secretFactor());
        assertEquals(50, score.score());
        assertTrue(score.triggered());
    }

    @Test
    @DisplayName("目标冲突：FOR 与 AGAINST 对立立场并存 → +40 触发")
    void stanceConflictTriggers() {
        AgentState a = agent("A", Emotion.NEUTRAL);
        AgentState b = agent("B", Emotion.NEUTRAL);
        a.setStance(AgentState.Stance.FOR);
        b.setStance(AgentState.Stance.AGAINST);
        List<AgentState> agents = List.of(a, b);

        InteractionDetector.TrackScore score = new InteractionDetector().evaluate(agents);

        assertEquals(InteractionDetector.TARGET_CONFLICT, score.conflictFactor());
        assertEquals(40, score.score());
        assertTrue(score.triggered());
    }

    @Test
    @DisplayName("情绪异常(ANGRY) → +15，但单独不触发")
    void angryEmotionAddsButNotEnoughAlone() {
        AgentState a = agent("A", Emotion.NEUTRAL);
        AgentState b = agent("B", Emotion.ANGRY);
        List<AgentState> agents = List.of(a, b);

        InteractionDetector.TrackScore score = new InteractionDetector().evaluate(agents);

        assertEquals(InteractionDetector.ABNORMAL_EMOTION, score.emotionFactor());
        assertEquals(15, score.score());
        assertFalse(score.triggered());
    }

    @Test
    @DisplayName("因子明细：4人 + 旁观者 + 秘密任务 = 20+20+50 = 90")
    void combinedFactorsBreakdown() {
        AgentState a = agent("A", Emotion.NEUTRAL);
        AgentState b = agent("B", Emotion.NEUTRAL);
        AgentState c = agent("C", Emotion.NEUTRAL);
        AgentState d = agent("D", Emotion.NEUTRAL);
        a.setInConversation(true);
        b.setInConversation(true);
        c.setInConversation(true);
        List<AgentState> agents = List.of(a, b, c, d);   // D 旁观

        InteractionDetector.TrackScore score =
                new InteractionDetector().evaluate(agents, Set.of("C"));

        assertEquals(20, score.sizeFactor());
        assertEquals(20, score.bystanderFactor());
        assertEquals(50, score.secretFactor());
        assertEquals(90, score.score());
        assertTrue(score.triggered());
        assertEquals(90, score.toMap().get("score"));
        assertTrue((Boolean) score.toMap().get("triggered"));
    }
}
