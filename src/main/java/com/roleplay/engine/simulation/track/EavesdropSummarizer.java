package com.roleplay.engine.simulation.track;

import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.Emotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Eavesdrop observation summarizer — turns overheard conversation lines into a
 * single vague summary observation for WEAK-track listeners.
 *
 * <p>Summary keeps exactly three elements: speakers, a coarse topic category and
 * emotional tone. It never returns model-generated prose or source keywords because
 * even a shortened noun phrase can reveal a password, location or facility detail.
 *
 * <p>Two paths:
 * <ul>
 *   <li>Optional LLM path: classify into a closed topic enum only</li>
 *   <li>Rule-based fallback: classify through a small, non-sensitive allowlist</li>
 * </ul>
 */
public class EavesdropSummarizer {

    private static final Logger log = LoggerFactory.getLogger(EavesdropSummarizer.class);

    private final LLMClient llmClient;

    /** Fallback-only instance (no LLM). */
    public EavesdropSummarizer() {
        this(null);
    }

    public EavesdropSummarizer(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Summarize conversation-group message history.
     *
     * @param messages map entries with "speaker" / "message" keys
     *                 (same shape as {@code ConversationGroup.getMessageHistory()})
     */
    public String summarize(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (Map<String, String> m : messages) {
            lines.add(m.getOrDefault("speaker", "?") + ": " + m.getOrDefault("message", ""));
        }
        return summarizeLines(lines);
    }

    /** Summarize raw "speaker: message" lines (demo format). */
    public String summarizeLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";

        if (llmClient != null) {
            try {
                TopicCategory category = parseCategory(llmClient.callSimple(buildCategoryPrompt(lines), 12));
                if (category != null) {
                    return ruleBasedSummary(lines, category);
                }
            } catch (Exception e) {
                log.warn("Eavesdrop LLM summary failed, using rule-based fallback: {}", e.getMessage());
            }
        }
        return ruleBasedSummary(lines, null);
    }

    // ── LLM path ───────────────────────────────────────────────

    private String buildCategoryPrompt(List<String> lines) {
        StringBuilder sb = new StringBuilder("将对话主题只分类为以下一个代码：FACILITY, LOCATION, CLUE, PLAN, PERSON, OTHER。\n");
        for (String line : lines) {
            String safeLine = line == null ? "" : line;
            sb.append("- ").append(safeLine, 0, Math.min(safeLine.length(), 500)).append("\n");
        }
        sb.append("只输出代码，不要解释，不要复述任何原文。");
        return sb.toString();
    }

    // ── Rule-based fallback ────────────────────────────────────

    private enum TopicCategory {
        FACILITY("某项设施"),
        LOCATION("某个地点"),
        CLUE("某条线索"),
        PLAN("某项安排"),
        PERSON("某个人"),
        OTHER("某件重要的事");

        private final String label;

        TopicCategory(String label) {
            this.label = label;
        }
    }

    private String ruleBasedSummary(List<String> lines, TopicCategory classifiedCategory) {
        List<String> speakers = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        for (String line : lines) {
            if (line == null) continue;
            int colon = line.indexOf(':');
            if (colon > 0) {
                speakers.add(line.substring(0, colon).trim());
                contents.add(line.substring(colon + 1).trim());
            } else {
                contents.add(line.trim());
            }
        }

        String joined = String.join(" ", contents);
        TopicCategory topic = classifiedCategory == null ? classifyTopic(contents) : classifiedCategory;
        String tone = detectTone(joined);
        String speakerText = formatSpeakers(speakers);

        if (speakerText.isEmpty()) {
            return "你听到附近有人在交谈，但听不清具体内容。";
        }
        return speakerText + "正在低声交谈，似乎在讨论" + topic.label
                + "，气氛" + (tone.isEmpty() ? "平静" : tone) + "。你离得较远，听不清具体内容。";
    }

    private TopicCategory parseCategory(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        try {
            return TopicCategory.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Maps source text to a coarse allowlisted category. Returning a category instead
     * of extracted keywords is the privacy boundary for WEAK listeners.
     */
    private TopicCategory classifyTopic(List<String> contents) {
        String joined = String.join(" ", contents).toLowerCase(Locale.ROOT);
        if (containsAny(joined, "设施", "设备", "机器", "闸门", "控制柜", "泵站", "管道", "堤坝", "水库")) {
            return TopicCategory.FACILITY;
        }
        if (containsAny(joined, "地点", "位置", "房间", "仓库", "地下室", "街道", "村庄", "地图")) {
            return TopicCategory.LOCATION;
        }
        if (containsAny(joined, "线索", "证据", "痕迹", "秘密", "真相")) {
            return TopicCategory.CLUE;
        }
        if (containsAny(joined, "计划", "安排", "时间", "行动", "任务", "会议")) {
            return TopicCategory.PLAN;
        }
        if (containsAny(joined, "人物", "某人", "嫌疑人", "同伴", "朋友", "家人")) {
            return TopicCategory.PERSON;
        }
        return TopicCategory.OTHER;
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) return true;
        }
        return false;
    }

    /** Emotion tone of the overheard text; empty string if neutral. */
    private String detectTone(String joined) {
        Emotion e = Emotion.fromText(joined);
        return e == Emotion.NEUTRAL ? "" : e.getLabel();
    }

    /** Distinct speaker names, up to 3, joined by "、". */
    private String formatSpeakers(List<String> speakers) {
        List<String> distinct = new ArrayList<>();
        for (String s : speakers) {
            if (s.isEmpty() || s.equals("?") || distinct.contains(s)) continue;
            distinct.add(s);
            if (distinct.size() == 3) break;
        }
        return String.join("、", distinct);
    }
}
