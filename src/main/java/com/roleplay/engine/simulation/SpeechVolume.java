package com.roleplay.engine.simulation;

/** 一次发言的音量意图；不是角色的持久属性。 */
public enum SpeechVolume {
    WHISPER(0.35), LOW(0.65), NORMAL(1.00), LOUD(1.40), SHOUT(1.80);

    private final double multiplier;
    SpeechVolume(double multiplier) { this.multiplier = multiplier; }
    public double multiplier() { return multiplier; }

    public static SpeechVolume from(String value) {
        if (value == null) return NORMAL;
        try { return valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return NORMAL; }
    }
}
