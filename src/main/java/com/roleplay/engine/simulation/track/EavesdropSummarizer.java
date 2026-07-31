package com.roleplay.engine.simulation.track;

import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.Emotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Eavesdrop observation summarizer — turns overheard conversation lines into a
 * single vague summary observation for WEAK-track listeners.
 *
 * <p>Summary keeps exactly three elements (调研结论): 说话者 + 主题 + 情绪基调.
 * It must NOT reproduce concrete details (names, locations, numbers, event facts),
 * mirroring the demo's {@code SUMMARY_OBS} which achieved 0% leak + token ↓14%.
 *
 * <p>Two paths:
 * <ul>
 *   <li>LLM path: {@link LLMClient#callSimple} (prompt-constrained, vaguer phrasing)</li>
 *   <li>Rule-based fallback (llmClient null or LLM failure): "X和Y正在讨论Z" style拼接</li>
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
                String summary = llmClient.callSimple(buildLlmPrompt(lines), 120);
                if (summary != null && !summary.isBlank()) {
                    return summary.trim();
                }
            } catch (Exception e) {
                log.warn("Eavesdrop LLM summary failed, using rule-based fallback: {}", e.getMessage());
            }
        }
        return ruleBasedSummary(lines);
    }

    // ── LLM path ───────────────────────────────────────────────

    private String buildLlmPrompt(List<String> lines) {
        StringBuilder sb = new StringBuilder("你无意中听到了一段不完整的对话片段：\n");
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
        sb.append("\n请用一句话输出一条【摘要观察】，只保留三个要素：谁在交谈、话题是什么、情绪基调如何。");
        sb.append("严禁复述对话中的具体细节（人名、地点、数字、事件经过）。");
        sb.append("示例：A和B正在低声交谈，似乎在讨论某件重要的事，气氛有些紧张。");
        return sb.toString();
    }

    // ── Rule-based fallback ────────────────────────────────────

    /** Split into CJK chars / latin word tokens. */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}\\p{IsAlphabetic}]+");

    /** Single CJK chars that rarely carry topic meaning (stop chars). */
    private static final String STOP_CHARS =
            "的了在是我你他她它我们你们他们和与就都也还很不没有着过吗呢吧啊呀这那什么怎么为什么地把被让给对从到说";

    private String ruleBasedSummary(List<String> lines) {
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
        String topic = extractTopic(contents);
        String tone = detectTone(joined);
        String speakerText = formatSpeakers(speakers);

        if (speakerText.isEmpty()) {
            return "你听到附近有人在交谈，但听不清具体内容。";
        }
        return speakerText + "正在低声交谈，似乎在讨论" + (topic.isEmpty() ? "某件重要的事" : topic)
                + "，气氛" + (tone.isEmpty() ? "平静" : tone) + "。你离得较远，听不清具体内容。";
    }

    /** Frequency-based topic keywords: top-2 meaningful CJK bigrams / latin words. */
    private String extractTopic(List<String> contents) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String c : contents) {
            if (c == null || c.isBlank()) continue;
            String[] tokens = TOKEN_SPLIT.split(c);
            for (String t : tokens) {
                if (t.length() < 2) continue;
                if (t.length() == 2 && STOP_CHARS.indexOf(t.charAt(0)) >= 0) continue;
                if (t.length() == 2 && STOP_CHARS.indexOf(t.charAt(1)) >= 0) continue;
                if (t.chars().allMatch(ch -> STOP_CHARS.indexOf(ch) >= 0)) continue;
                freq.merge(t, 1, Integer::sum);
            }
            // CJK bigrams: adjacent char pairs give real words like 仓库/尸体/发现
            String cjk = c.replaceAll("[^\\p{IsHan}]", "");
            for (int i = 0; i + 1 < cjk.length(); i++) {
                char a = cjk.charAt(i);
                char b = cjk.charAt(i + 1);
                if (STOP_CHARS.indexOf(a) >= 0 || STOP_CHARS.indexOf(b) >= 0) continue;
                String bigram = String.valueOf(new char[]{a, b});
                freq.merge(bigram, 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(2)
                .map(e -> e.getKey().length() > 4 ? e.getKey().substring(0, 4) : e.getKey())
                .reduce((x, y) -> x + "、" + y)
                .orElse("");
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
