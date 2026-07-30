package com.roleplay.engine.simulation;

public enum Emotion {
    NEUTRAL("neutral", "\uD83D\uDE10"),
    HAPPY("happy", "\uD83D\uDE0A"),
    EXCITED("excited", "\uD83E\uDD29"),
    SAD("sad", "\uD83D\uDE22"),
    ANGRY("angry", "\uD83D\uDE20"),
    SURPRISED("surprised", "\uD83D\uDE32"),
    THOUGHTFUL("thoughtful", "\uD83E\uDD14"),
    SHY("shy", "\uD83D\uDE33"),
    BORED("bored", "\uD83D\uDE34"),
    CONFUSED("confused", "\uD83D\uDE35");

    private final String label;
    private final String emoji;

    Emotion(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }

    public String getLabel() { return label; }
    public String getEmoji() { return emoji; }

    public static Emotion fromText(String text) {
        if (text == null || text.isBlank()) return NEUTRAL;
        String lower = text.toLowerCase().trim();
        if (lower.contains("happy") || lower.contains("开心") || lower.contains("高兴") || lower.contains("快乐")) return HAPPY;
        if (lower.contains("excit") || lower.contains("兴奋") || lower.contains("激动")) return EXCITED;
        if (lower.contains("sad") || lower.contains("难过") || lower.contains("悲伤") || lower.contains("伤心") || lower.contains("哭")) return SAD;
        if (lower.contains("angry") || lower.contains("愤怒") || lower.contains("生气") || lower.contains("怒")) return ANGRY;
        if (lower.contains("surprise") || lower.contains("惊讶") || lower.contains("吃惊")) return SURPRISED;
        if (lower.contains("think") || lower.contains("思考") || lower.contains("沉思")) return THOUGHTFUL;
        if (lower.contains("shy") || lower.contains("害羞") || lower.contains("不好意思")) return SHY;
        if (lower.contains("bore") || lower.contains("无聊")) return BORED;
        if (lower.contains("confus") || lower.contains("困惑") || lower.contains("迷惑")) return CONFUSED;
        return NEUTRAL;
    }
}
