package com.roleplay.engine.simulation.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 发言门控（SpeechGate）——每轮先判断“是否发言”，再决定“说什么”。
 *
 * <p>门控使用事件触发与确定性阈值，避免把每个候选角色都直接送入 LLM：
 * <ul>
 *   <li><b>规则触发 → 必发言</b>：被点名（@/被提问）、轮次首句（开局自我介绍）、新线索公开、
 *       人类 @/点名质疑、情绪超阈值（ANGRY/SAD/CONFUSED/SURPRISED）、冷场破冰——事件驱动是防冷场第一道闸；</li>
 *   <li><b>阈值打分 → 静默</b>：否则 P = 动机分(motiveScore) × 人格化 talkativeness，
 *       人类发言中（未被点名）再 × wait_bias；P &lt; silence_floor → 静默（输出占位"……（沉默）"）；</li>
 *   <li>阈值区间：silence_floor ∈ [0.10, 0.20] 安全（demo：0.25 会冷场失衡、凶手获益）；默认 0.15。</li>
 * </ul>
 *
 * <p>纯确定性组件（无 LLM、无随机），可单测；"是否发言"与"说什么"解耦——
 * 门控只输出 speak/silent 决策，发言内容仍由 TrackStrategy + LLM 生成。
 * 阈值可配（roleplay.game.discussion.silence-floor / wait-bias / cold-break），对齐 D-004「勿 hardcode」纪律。
 */
public class SpeechGate {

    /** 静默占位（进讨论记录/前端显示"…沉默"）。 */
    public static final String SILENCE_MARKER = "……（沉默）";

    /** 触发事件类型（规则触发 → 必发言，不受概率/阈值限制）。 */
    public enum TriggerType {
        /** 轮次首句（开局每人自我介绍）。 */
        ROUND_FIRST,
        /** 被点名（他人/人类发言含 @名 或名字出现在句首/标点后）。 */
        MENTION,
        /** 被提问（他人发言点名且带问句标记）。 */
        QUESTION,
        /** 新线索公开（游戏线索事件，全员按动机评估）。 */
        CLUE,
        /** 人类公开新线索 → 相关 AI 按动机触发发言。 */
        HUMAN_CLUE,
        /** 情绪超阈值（ANGRY/SAD/CONFUSED/SURPRISED）→ 必发言。 */
        EMOTION,
        /** 冷场破冰（连续静默 → 按动机指定破冰者）。 */
        COLD_BREAK
    }

    /** 单条触发事件：type + 目标角色（target 为 null/空 = 全员相关，如线索公开）。 */
    public record SpeechTrigger(TriggerType type, String target) {}

    /** 门控决策：发言与否 + 原因（决策可观测，对齐 demo 实录标注）。 */
    public record GateDecision(boolean speak, String reason) {}

    /** 门控配置（对齐 D-004 阈值可配纪律；默认值 = demo 实测安全区间）。 */
    private final double silenceFloor;
    private final double waitBias;
    private final boolean coldBreakEnabled;

    public SpeechGate() {
        this(0.15, 0.5, true);
    }

    public SpeechGate(double silenceFloor, double waitBias, boolean coldBreakEnabled) {
        this.silenceFloor = silenceFloor;
        this.waitBias = waitBias;
        this.coldBreakEnabled = coldBreakEnabled;
    }

    public double getSilenceFloor() { return silenceFloor; }
    public double getWaitBias() { return waitBias; }
    public boolean isColdBreakEnabled() { return coldBreakEnabled; }

    /**
     * 门控决策核心：
     * <ol>
     *   <li>命中该角色的规则触发（点名/提问/线索/情绪/冷场破冰）→ 必发言；</li>
     *   <li>否则 P = motiveScore(priority) × talkativeness；人类发言中且未被点名 → P ×= wait_bias；</li>
     *   <li>P &lt; silence_floor → 静默，否则发言。</li>
     * </ol>
     *
     * @param name                角色（玩家）名
     * @param talkativeness       人格化健谈度 [0,1]（内向 0.2 / 外向 0.8；被点名强制发言不受其限制）
     * @param motivePriority      当前动机优先级（WorldDirectorService.getGoalPriority：临时目标>手动>规则）
     * @param triggers            本轮全部触发事件（含 target 为空的全局事件）
     * @param coldBreakCandidate  是否为本轮冷场破冰者（按动机指定）
     * @param humanSpokeThisRound 本轮是否有人类发言（未被点名的 AI 倾向静默等待，P × wait_bias）
     * @return 发言/静默决策 + 原因
     */
    public GateDecision decide(String name, double talkativeness, int motivePriority,
                               List<SpeechTrigger> triggers, boolean coldBreakCandidate,
                               boolean humanSpokeThisRound) {
        boolean directlyTriggered = false;
        String triggerReason = "";
        if (triggers != null) {
            for (SpeechTrigger t : triggers) {
                if (t == null || t.target() == null) continue;
                if (!t.target().equals(name)) continue;
                directlyTriggered = true;
                triggerReason = reasonOf(t.type());
                break;
            }
        }
        if (directlyTriggered) {
            return new GateDecision(true, triggerReason);
        }
        if (coldBreakCandidate) {
            return new GateDecision(true, "触发·冷场破冰");
        }

        double p = motiveScore(motivePriority) * clamp01(talkativeness);
        if (humanSpokeThisRound) {
            double gated = p * waitBias;
            if (gated < p) {
                p = gated;
            }
            if (p < silenceFloor) {
                return new GateDecision(false,
                        String.format("静默·人类发言中·倾向静默(P=%.2f<%.2f)", p, silenceFloor));
            }
        }
        if (p < silenceFloor) {
            return new GateDecision(false, String.format("静默·P=%.2f<%.2f 意愿过低", p, silenceFloor));
        }
        return new GateDecision(true, String.format("门控·P=%.2f≥%.2f", p, silenceFloor));
    }

    // ── 静态工具：触发扫描（可单测） ──────────────────────────

    /** 动机优先级 → 发言意愿分 [0,1]：score = 0.1 + priority/100 × 0.9（pri 100→1.0，pri 5→0.145）。 */
    public static double motiveScore(int priority) {
        int p = Math.max(0, priority);
        return Math.min(1.0, 0.1 + p / 100.0 * 0.9);
    }

    /**
     * 点名判定：发言文本是否"点名"了指定角色。
     * <ul>
     *   <li>显式 @：文本含 "@角色名"；</li>
     *   <li>句首/标点后出现角色名（被点名）：名字在句首，或前一个字符是标点（。，！？：、）；</li>
     * </ul>
     * "是管家""管家婆"等嵌入词不误判（前字符为字母/汉字）。
     */
    public static boolean isMentioning(String text, String name) {
        if (text == null || name == null || name.isBlank()) return false;
        if (text.contains("@" + name)) return true;
        int idx = text.indexOf(name);
        while (idx >= 0) {
            if (idx == 0) return true;
            char before = text.charAt(idx - 1);
            if (!Character.isLetterOrDigit(before) && before != ' ') {
                // 中文标点（。，！？：；、…）与英文标点均为非字母数字 → 视为点名
                return true;
            }
            idx = text.indexOf(name, idx + name.length());
        }
        return false;
    }

    /** 提问判定：点名 + 问句标记（？/?/怎么/解释/说明/你说/是吗/为什么）。 */
    public static boolean isQuestioning(String text, String name) {
        if (!isMentioning(text, name)) return false;
        if (text == null) return false;
        return text.contains("？") || text.contains("?")
                || text.contains("怎么") || text.contains("解释")
                || text.contains("说明") || text.contains("你说")
                || text.contains("是吗") || text.contains("为什么");
    }

    /**
     * 从新增发言轮次扫描触发事件：对每个非本人成员判定 点名/提问。
     * 人类与 AI 发言统一处理（人类发言即事件源：@某 AI → 该 AI 强制发言）。
     *
     * @param turns 新增发言轮次（speaker → message）
     * @param members 全部讨论成员
     * @return 触发事件列表
     */
    public static List<SpeechTrigger> scanTurns(List<Map<String, String>> turns, List<String> members) {
        List<SpeechTrigger> out = new ArrayList<>();
        if (turns == null || members == null) return out;
        for (Map<String, String> turn : turns) {
            if (turn == null) continue;
            String speaker = turn.get("speaker");
            String msg = turn.get("message");
            if (speaker == null || msg == null || msg.isBlank()) continue;
            for (String member : members) {
                if (member == null || member.equals(speaker)) continue;
                if (isMentioning(msg, member)) {
                    out.add(new SpeechTrigger(
                            isQuestioning(msg, member) ? TriggerType.QUESTION : TriggerType.MENTION,
                            member));
                }
            }
        }
        return out;
    }

    /** 触发类型 → 决策原因标注（demo 实录同款）。 */
    public static String reasonOf(TriggerType type) {
        return switch (type) {
            case ROUND_FIRST -> "触发·轮次首句";
            case MENTION -> "触发·被点名";
            case QUESTION -> "触发·被提问";
            case CLUE -> "触发·线索公开·相关";
            case HUMAN_CLUE -> "触发·人类线索相关·按动机";
            case EMOTION -> "触发·情绪超阈值";
            case COLD_BREAK -> "触发·冷场破冰";
        };
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
