package com.roleplay.engine.simulation.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次 D 收尾：SpeechGate 发言门控专项测试（P0-1）。
 *
 * <p>SpeechGate 为纯确定性组件（无 LLM、无随机），直接单测 decide 契约，无需 Spring 上下文：
 * <ul>
 *   <li>规则触发（MENTION/QUESTION/HUMAN_CLUE/EMOTION/ROUND_FIRST/CLUE/COLD_BREAK）→ 必发言，
 *       不受 talkativeness 概率限制；</li>
 *   <li>否则 P = motiveScore(priority) × talkativeness（人类发言中且未被点名再 × wait_bias），
 *       P &lt; silence_floor → 静默（调用方以 SILENCE_MARKER 占位入讨论记录，ConversationManager
 *       executeRound 对静默成员跳过 LLM 生成并写入 {@link SpeechGate#SILENCE_MARKER}）；</li>
 *   <li>阈值边界（默认 0.15：pri 5 → P=0.145 静默 / pri 6 → P=0.154 发言）、wait_bias 打折、
 *       高动机突破阈值、冷场破冰候选强制发言、静态工具（isMentioning/isQuestioning/scanTurns/reasonOf）。</li>
 * </ul>
 *
 * <p>默认门控参数与主 yml 一致：silence-floor=0.15 / wait-bias=0.5 / cold-break=true
 * （application.yml roleplay.game.discussion.*，批次 D 配置）。
 */
class SpeechGateTest {

    // ── 1. 触发必发言（不受 talkativeness 概率限制） ─────────────────────

    @Test
    @DisplayName("MENTION 触发 → 必发言（talkativeness=0 仍发言）")
    void mentionForcesSpeakDespiteZeroTalkativeness() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.0, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.MENTION, "管家")),
                false, false);
        assertTrue(d.speak(), "被点名应强制发言");
        assertEquals("触发·被点名", d.reason());
    }

    @Test
    @DisplayName("QUESTION 触发 → 必发言")
    void questionForcesSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.0, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.QUESTION, "管家")),
                false, false);
        assertTrue(d.speak());
        assertEquals("触发·被提问", d.reason());
    }

    @Test
    @DisplayName("HUMAN_CLUE 触发（人类公开线索→相关 AI）→ 必发言")
    void humanClueForcesSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("女仆", 0.0, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.HUMAN_CLUE, "女仆")),
                false, false);
        assertTrue(d.speak());
        assertEquals("触发·人类线索相关·按动机", d.reason());
    }

    @Test
    @DisplayName("EMOTION 触发（情绪超阈值）→ 必发言")
    void emotionForcesSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("园丁", 0.0, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.EMOTION, "园丁")),
                false, false);
        assertTrue(d.speak());
        assertEquals("触发·情绪超阈值", d.reason());
    }

    @Test
    @DisplayName("ROUND_FIRST 触发（开局自我介绍）→ 必发言")
    void roundFirstForcesSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.0, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.ROUND_FIRST, "管家")),
                false, false);
        assertTrue(d.speak());
        assertEquals("触发·轮次首句", d.reason());
    }

    @Test
    @DisplayName("CLUE 触发（线索公开）→ 必发言")
    void clueTriggerForcesSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.0, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.CLUE, "管家")),
                false, false);
        assertTrue(d.speak());
        assertEquals("触发·线索公开·相关", d.reason());
    }

    @Test
    @DisplayName("COLD_BREAK 触发类型经 trigger 列表 → 必发言")
    void coldBreakTriggerTypeForcesSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.0, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.COLD_BREAK, "管家")),
                false, false);
        assertTrue(d.speak());
        assertEquals("触发·冷场破冰", d.reason());
    }

    @Test
    @DisplayName("多触发并存时命中本角色即必发言（含低健谈度）")
    void multipleTriggersHitOwnName() {
        SpeechGate gate = new SpeechGate();
        // 列表里混有他人触发与本角色触发，命中本角色即发言
        List<SpeechGate.SpeechTrigger> triggers = List.of(
                new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.QUESTION, "女仆"),
                new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.EMOTION, "管家"));
        SpeechGate.GateDecision d = gate.decide("管家", 0.1, 0, triggers, false, true);
        assertTrue(d.speak(), "命中本角色的触发（EMOTION）应覆盖低健谈度+wait_bias");
        assertEquals("触发·情绪超阈值", d.reason());
    }

    // ── 2. 低分静默（含 SILENCE_MARKER 占位） ───────────────────────────

    @Test
    @DisplayName("无触发 + 低动机分 + 低健谈度 → 静默（P<0.15），占位符为 SILENCE_MARKER")
    void lowScoreSilenceWithMarker() {
        SpeechGate gate = new SpeechGate();
        // 动机分 pri 0 → 0.1，talkativeness 0.1 → P = 0.01 < 0.15
        SpeechGate.GateDecision d = gate.decide("管家", 0.1, 0,
                List.of(), false, false);
        assertFalse(d.speak(), "P=0.01 < silence_floor=0.15 应静默");
        assertTrue(d.reason().startsWith("静默"), "静默原因应以「静默」标注，实际: " + d.reason());
        assertTrue(d.reason().contains("0.01"), "原因应含实际 P 值，实际: " + d.reason());

        // 静默占位：调用方（ConversationManager.executeRound）以 SILENCE_MARKER 写入发言记录
        assertEquals("……（沉默）", SpeechGate.SILENCE_MARKER, "静默占位常量");
        assertFalse(SpeechGate.SILENCE_MARKER.isBlank());
    }

    @Test
    @DisplayName("极端低分（动机 0 + 健谈 0）→ 静默；triggers=null 不抛 NPE")
    void extremeLowScoreAndNullTriggers() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.0, 0, null, false, false);
        assertFalse(d.speak());
        assertTrue(d.reason().contains("0.00"), "P = 0.1×0 = 0.00，实际: " + d.reason());
    }

    // ── 3. 阈值边界（P 在 0.15 附近上下的行为） ──────────────────────────

    @Test
    @DisplayName("默认阈值 0.15 边界：pri 5→P=0.145 静默，pri 6→P=0.154 发言")
    void thresholdBoundaryAroundDefaultFloor() {
        SpeechGate gate = new SpeechGate(); // floor 0.15, talk=1.0 → P 即动机分
        SpeechGate.GateDecision below = gate.decide("管家", 1.0, 5, List.of(), false, false);
        assertFalse(below.speak(), "P=0.145 < 0.15 应静默，实际: " + below.reason());

        SpeechGate.GateDecision above = gate.decide("管家", 1.0, 6, List.of(), false, false);
        assertTrue(above.speak(), "P=0.154 ≥ 0.15 应发言，实际: " + above.reason());
        assertTrue(above.reason().contains("0.15"), "发言原因应标注门控阈值，实际: " + above.reason());
    }

    @Test
    @DisplayName("自定义阈值下 P 恰好压线（0.154 附近）：floor=0.1535 发言 / floor=0.1545 静默")
    void thresholdBoundaryCustomFloorBracketing() {
        // pri 6 → P=0.154；阈值在 P 两侧各留 0.0005 余量，避开浮点表示误差
        SpeechGate belowFloor = new SpeechGate(0.1535, 0.5, true);
        assertTrue(belowFloor.decide("管家", 1.0, 6, List.of(), false, false).speak(),
                "floor=0.1535 < P=0.154 应发言");

        SpeechGate aboveFloor = new SpeechGate(0.1545, 0.5, true);
        assertFalse(aboveFloor.decide("管家", 1.0, 6, List.of(), false, false).speak(),
                "floor=0.1545 > P=0.154 应静默");
    }

    // ── 4. wait_bias：人类发言中未被点名 → P 打折 → 更易静默 ─────────────

    @Test
    @DisplayName("人类发言中未被点名 → P×0.5 打折 → 0.154→0.077 静默；无人类发言同参数发言")
    void waitBiasSilencesUntriggeredWhenHumanSpoke() {
        SpeechGate gate = new SpeechGate(); // waitBias 0.5
        List<SpeechGate.SpeechTrigger> noTriggers = List.of();

        SpeechGate.GateDecision withoutHuman = gate.decide("管家", 1.0, 6, noTriggers, false, false);
        assertTrue(withoutHuman.speak(), "无人类发言：P=0.154 ≥ 0.15 应发言");

        SpeechGate.GateDecision withHuman = gate.decide("管家", 1.0, 6, noTriggers, false, true);
        assertFalse(withHuman.speak(), "人类发言中未被点名：P=0.154×0.5=0.077 < 0.15 应静默");
        assertTrue(withHuman.reason().contains("人类发言中"), "静默原因应标注人类发言倾向静默，实际: " + withHuman.reason());
    }

    @Test
    @DisplayName("wait_bias 打折后仍发言：高动机（pri 100）人类发言中 → P=0.5 ≥ 0.15")
    void waitBiasDoesNotSilenceHighMotive() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 1.0, 100, List.of(), false, true);
        assertTrue(d.speak(), "P=1.0×0.5=0.5 ≥ 0.15 应仍发言，实际: " + d.reason());
        assertTrue(d.reason().contains("0.50"), "原因应含打折后 P 值，实际: " + d.reason());
    }

    // ── 5. 动机分输入：高动机 → 突破阈值发言 ─────────────────────────────

    @Test
    @DisplayName("高动机突破阈值：talk=0.2 + pri 100 → P=0.2 发言；同健谈度低动机 → 静默")
    void highMotiveOvercomesLowTalkativeness() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision speak = gate.decide("管家", 0.2, 100, List.of(), false, false);
        assertTrue(speak.speak(), "P=1.0×0.2=0.2 ≥ 0.15 应发言，实际: " + speak.reason());

        SpeechGate.GateDecision silent = gate.decide("管家", 0.2, 5, List.of(), false, false);
        assertFalse(silent.speak(), "P=0.145×0.2=0.029 < 0.15 应静默，实际: " + silent.reason());
    }

    @Test
    @DisplayName("motiveScore 映射：0→0.1 / 5→0.145 / 50→0.55 / 100→1.0，负值与超界钳制")
    void motiveScoreMapping() {
        assertEquals(0.1, SpeechGate.motiveScore(0), 1e-9);
        assertEquals(0.145, SpeechGate.motiveScore(5), 1e-9);
        assertEquals(0.55, SpeechGate.motiveScore(50), 1e-9);
        assertEquals(1.0, SpeechGate.motiveScore(100), 1e-9);
        assertEquals(0.1, SpeechGate.motiveScore(-5), 1e-9, "负优先级钳制为 0 → 0.1");
        assertEquals(1.0, SpeechGate.motiveScore(200), 1e-9, "超界优先级钳制为 1.0");
    }

    // ── 6. COLD_BREAK：冷场破冰候选 → 必发言 ─────────────────────────────

    @Test
    @DisplayName("coldBreakCandidate=true（全条件低分）→ 必发言，原因「触发·冷场破冰」")
    void coldBreakCandidateForcesSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.0, 0, List.of(), true, false);
        assertTrue(d.speak(), "破冰候选应强制发言");
        assertEquals("触发·冷场破冰", d.reason());
    }

    @Test
    @DisplayName("cold-break 开关反映构造参数（默认 true / 显式 false）")
    void coldBreakFlagConfiguration() {
        assertTrue(new SpeechGate().isColdBreakEnabled(), "默认 cold-break=true（主 yml 同步）");
        assertTrue(new SpeechGate(0.15, 0.5, true).isColdBreakEnabled());
        assertFalse(new SpeechGate(0.15, 0.5, false).isColdBreakEnabled());
    }

    // ── 触发扫描与静态工具 ────────────────────────────────────────────────

    @Test
    @DisplayName("null target 触发（全员相关事件）与 null 列表不强制发言——由调用方转成 per-member 触发")
    void nullTargetAndNullTriggersDoNotForceSpeak() {
        SpeechGate gate = new SpeechGate();
        // 线索公开为全员相关事件（target=null），不直接强制任何角色（调用方按动机转 per-member HUMAN_CLUE）
        SpeechGate.GateDecision d = gate.decide("管家", 0.1, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.CLUE, null)),
                false, false);
        assertFalse(d.speak(), "target=null 不强制发言，回落到打分（P=0.01 < 0.15 静默）");
    }

    @Test
    @DisplayName("触发目标为他人时不强制本角色发言（回落到打分）")
    void triggerForOtherMemberDoesNotForceSpeak() {
        SpeechGate gate = new SpeechGate();
        SpeechGate.GateDecision d = gate.decide("管家", 0.1, 0,
                List.of(new SpeechGate.SpeechTrigger(SpeechGate.TriggerType.QUESTION, "女仆")),
                false, false);
        assertFalse(d.speak(), "他人被提问不影响本角色（P=0.01 静默）");
    }

    @Test
    @DisplayName("isMentioning：@名/句首/标点后为点名；嵌入词（是管家/管家婆）不误判")
    void mentioningDetection() {
        assertTrue(SpeechGate.isMentioning("@管家 你怎么看", "管家"), "@显式点名");
        assertTrue(SpeechGate.isMentioning("管家，你说呢", "管家"), "句首点名");
        assertTrue(SpeechGate.isMentioning("我觉得……管家最有嫌疑", "管家"), "标点后点名");
        assertFalse(SpeechGate.isMentioning(" 管家 请回答", "管家"), "前字符为空格不视作点名（空格是普通分隔符，防句中误判）");
        assertFalse(SpeechGate.isMentioning("我觉得管家最有嫌疑", "管家"), "嵌入句中（前字符为汉字）不误判");
        assertFalse(SpeechGate.isMentioning("是管家干的", "管家"), "「是管家」嵌入词不误判");
        assertTrue(SpeechGate.isMentioning("管家婆不是凶手", "管家"), "句首出现即点名（句首语义优先于嵌入词）");
        assertFalse(SpeechGate.isMentioning("他不是管家婆", "管家"), "「管家婆」嵌入词（前字符为汉字）不误判");
        assertFalse(SpeechGate.isMentioning(null, "管家"), "null 文本");
        assertFalse(SpeechGate.isMentioning("随便说点什么", null), "null 角色名");
        assertFalse(SpeechGate.isMentioning("随便说点什么", ""), "空角色名");
    }

    @Test
    @DisplayName("isQuestioning：点名 + 问句标记（？/怎么/解释/为什么…）；仅点名无问句不算提问")
    void questioningDetection() {
        assertTrue(SpeechGate.isQuestioning("管家，你怎么看？", "管家"), "问号+怎么");
        assertTrue(SpeechGate.isQuestioning("@管家 解释一下", "管家"), "解释");
        assertTrue(SpeechGate.isQuestioning("管家，为什么是你", "管家"), "为什么");
        assertTrue(SpeechGate.isQuestioning("管家，你说实话", "管家"), "你说");
        assertFalse(SpeechGate.isQuestioning("管家来了", "管家"), "点名但非问句");
        assertFalse(SpeechGate.isQuestioning("你觉得谁可疑", "管家"), "未点名非问句");
    }

    @Test
    @DisplayName("scanTurns：扫描新增发言生成 MENTION/QUESTION 触发；自点名/空发言/空 speaker 跳过")
    void scanTurnsDetectsMentionsAndQuestions() {
        List<Map<String, String>> turns = new ArrayList<>();
        turns.add(turn("Alice", "管家，你怎么看？"));   // 点名管家 + 问句 → QUESTION
        turns.add(turn("Bob", "我觉得女仆很可疑"));      // 成员外名字 → 无触发
        turns.add(turn("管家", "@Bob 我没有"));           // 点名 Bob → MENTION
        turns.add(turn("Carol", ""));                     // 空发言跳过
        turns.add(turn(null, "没有说话人"));              // 空 speaker 跳过
        turns.add(new LinkedHashMap<>());                 // 空 map 跳过

        List<String> members = List.of("Alice", "Bob", "Carol", "管家");
        List<SpeechGate.SpeechTrigger> out = SpeechGate.scanTurns(turns, members);

        assertEquals(2, out.size(), "应只产出 2 条触发，实际: " + out);
        SpeechGate.SpeechTrigger t1 = out.get(0);
        assertEquals(SpeechGate.TriggerType.QUESTION, t1.type(), "Alice 对管家提问");
        assertEquals("管家", t1.target());
        SpeechGate.SpeechTrigger t2 = out.get(1);
        assertEquals(SpeechGate.TriggerType.MENTION, t2.type(), "管家 @Bob 点名");
        assertEquals("Bob", t2.target());

        // 边界：null 入参
        assertTrue(SpeechGate.scanTurns(null, members).isEmpty());
        assertTrue(SpeechGate.scanTurns(turns, null).isEmpty());
    }

    @Test
    @DisplayName("reasonOf：7 种触发类型原因标注齐全（demo 实录同款）")
    void reasonOfMapsAllTypes() {
        assertEquals("触发·轮次首句", SpeechGate.reasonOf(SpeechGate.TriggerType.ROUND_FIRST));
        assertEquals("触发·被点名", SpeechGate.reasonOf(SpeechGate.TriggerType.MENTION));
        assertEquals("触发·被提问", SpeechGate.reasonOf(SpeechGate.TriggerType.QUESTION));
        assertEquals("触发·线索公开·相关", SpeechGate.reasonOf(SpeechGate.TriggerType.CLUE));
        assertEquals("触发·人类线索相关·按动机", SpeechGate.reasonOf(SpeechGate.TriggerType.HUMAN_CLUE));
        assertEquals("触发·情绪超阈值", SpeechGate.reasonOf(SpeechGate.TriggerType.EMOTION));
        assertEquals("触发·冷场破冰", SpeechGate.reasonOf(SpeechGate.TriggerType.COLD_BREAK));
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private static Map<String, String> turn(String speaker, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("speaker", speaker);
        m.put("message", message);
        return m;
    }
}
