package com.roleplay.engine.simulation.track;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R-1 专项：TrackScore 阈值 40 hardcode 边界测试（方案 #40 补测）。
 *
 * <p>评分因子均为 5 的倍数（size 0/20/40、bystander 20、conflict 40、secret 50、angry 15），
 * 因此分数 39/41 不可达——真实边界是 35（不触发）→ 40（触发）。
 */
class InteractionDetectorBoundaryTest {

    private final InteractionDetector detector = new InteractionDetector();

    private AgentState agent(String name, AgentState.Stance stance, Emotion emotion, boolean inConv) {
        AgentState s = new AgentState(name, 0, 0);
        s.setStance(stance);
        s.setEmotion(emotion);
        s.setInConversation(inConv);
        return s;
    }

    @Test
    @DisplayName("阈值边界：35 不触发 / 40 恰好触发（39、41 不可达）")
    void thresholdBoundary() {
        // 3 人 + 旁观者 + 无其他因子 = 20+20 = 40 → 恰好触发（边界值）
        var threePlusBystander = List.of(
                agent("A", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("B", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("C", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, false));
        InteractionDetector.TrackScore s40 = detector.evaluate(threePlusBystander);
        assertEquals(40, s40.score(), "3人+旁观者应为 40 分");
        assertTrue(s40.triggered(), "score=40 应触发（>=40）");

        // 3 人无旁观者 = 20 → 不触发
        var threeOnly = List.of(
                agent("A", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("B", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("C", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true));
        InteractionDetector.TrackScore s20 = detector.evaluate(threeOnly);
        assertEquals(20, s20.score());
        assertFalse(s20.triggered(), "score=20 不应触发");

        var twoAngry = List.of(
                agent("A", AgentState.Stance.NEUTRAL, Emotion.ANGRY, true),
                agent("B", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true));
        InteractionDetector.TrackScore s15 = detector.evaluate(twoAngry);
        assertEquals(15, s15.score());
        assertFalse(s15.triggered(), "score=15 不应触发");

        // 最大不触发组合：3人全部在对话 + 1人ANGRY = size20 + 情绪15 = 35
        var threePlusAngry = List.of(
                agent("A", AgentState.Stance.NEUTRAL, Emotion.ANGRY, true),
                agent("B", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("C", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true));
        InteractionDetector.TrackScore s35 = detector.evaluate(threePlusAngry);
        assertEquals(35, s35.score());
        assertFalse(s35.triggered(), "score=35 不应触发（40 之下最大可达分）");
    }

    @Test
    @DisplayName("五因子组合：5人/冲突/秘密/情绪 均触发")
    void factorCombos() {
        // 5 人 = 40 → 触发
        var five = List.of(
                agent("A", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("B", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("C", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("D", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("E", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true));
        assertTrue(detector.evaluate(five).triggered(), "5人应触发");

        // 2 人立场对立 = 40 → 触发
        var conflict = List.of(
                agent("A", AgentState.Stance.FOR, Emotion.NEUTRAL, true),
                agent("B", AgentState.Stance.AGAINST, Emotion.NEUTRAL, true));
        assertTrue(detector.evaluate(conflict).triggered(), "目标冲突应触发");

        // 秘密任务 = +50，单独即触发
        var twoNormal = List.of(
                agent("A", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true),
                agent("B", AgentState.Stance.NEUTRAL, Emotion.NEUTRAL, true));
        assertTrue(detector.evaluate(twoNormal, Set.of("A")).triggered(), "秘密任务单独应触发");

        // 空列表/null 安全
        assertFalse(detector.evaluate(List.of()).triggered());
        assertFalse(detector.evaluate(null).triggered());
        assertFalse(detector.evaluate(List.of(), null).triggered());
    }
}
