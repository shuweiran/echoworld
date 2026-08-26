package com.roleplay.engine.simulation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 模型一次发言的控制协议解析器。控制标记绝不可进入对话、记忆或 SSE。 */
public record SpeechDecision(boolean speak, SpeechVolume volume, String text) {
    public static final String NO_OUTPUT = "<NO_OUTPUT>";
    private static final Pattern VOLUME = Pattern.compile("(?:【|\\[)音量[：:](WHISPER|LOW|NORMAL|LOUD|SHOUT)(?:】|\\])", Pattern.CASE_INSENSITIVE);

    public static SpeechDecision parse(String response) {
        if (response == null || response.isBlank()) return new SpeechDecision(false, SpeechVolume.NORMAL, "");
        if (response.trim().equalsIgnoreCase(NO_OUTPUT)) return new SpeechDecision(false, SpeechVolume.NORMAL, "");
        Matcher matcher = VOLUME.matcher(response);
        SpeechVolume volume = matcher.find() ? SpeechVolume.from(matcher.group(1)) : SpeechVolume.NORMAL;
        String text = matcher.replaceAll("").trim();
        return text.isBlank() || text.equalsIgnoreCase(NO_OUTPUT)
                ? new SpeechDecision(false, volume, "") : new SpeechDecision(true, volume, text);
    }
}
