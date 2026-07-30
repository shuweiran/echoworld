package com.roleplay.engine.simulation;

import java.util.*;

public class EmotionSystem {

    public record PADVector(double pleasure, double arousal, double dominance) {
        public static final PADVector NEUTRAL = new PADVector(0, 0, 0);

        public double distanceTo(PADVector other) {
            double dp = pleasure - other.pleasure;
            double da = arousal - other.arousal;
            double dd = dominance - other.dominance;
            return Math.sqrt(dp * dp + da * da + dd * dd);
        }
    }

    private static final Map<Emotion, PADVector> PAD_MAP = new LinkedHashMap<>();
    static {
        PAD_MAP.put(Emotion.HAPPY,     new PADVector( 0.70,  0.35,  0.35));
        PAD_MAP.put(Emotion.EXCITED,   new PADVector( 0.75,  0.85,  0.60));
        PAD_MAP.put(Emotion.SAD,       new PADVector(-0.60, -0.40, -0.55));
        PAD_MAP.put(Emotion.ANGRY,     new PADVector(-0.50,  0.70,  0.65));
        PAD_MAP.put(Emotion.SURPRISED, new PADVector( 0.30,  0.80, -0.20));
        PAD_MAP.put(Emotion.THOUGHTFUL,new PADVector( 0.10, -0.30,  0.40));
        PAD_MAP.put(Emotion.SHY,       new PADVector(-0.10,  0.20, -0.60));
        PAD_MAP.put(Emotion.BORED,     new PADVector(-0.30, -0.70, -0.30));
        PAD_MAP.put(Emotion.CONFUSED,  new PADVector(-0.20,  0.30, -0.40));
        PAD_MAP.put(Emotion.NEUTRAL,   PADVector.NEUTRAL);
    }

    private static final Map<PADVector, Emotion> PAD_TO_EMOTION = new LinkedHashMap<>();
    static {
        for (var entry : PAD_MAP.entrySet()) {
            PAD_TO_EMOTION.put(entry.getValue(), entry.getKey());
        }
    }

    public Emotion classifyPAD(PADVector pad) {
        Emotion best = Emotion.NEUTRAL;
        double bestDist = Double.MAX_VALUE;
        for (var entry : PAD_MAP.entrySet()) {
            double dist = pad.distanceTo(entry.getValue());
            if (dist < bestDist) {
                bestDist = dist;
                best = entry.getKey();
            }
        }
        return best;
    }

    public Emotion updateFromText(String text, Emotion current) {
        if (text == null || text.isBlank()) return current;

        Emotion fromText = Emotion.fromText(text);
        if (fromText != Emotion.NEUTRAL) return fromText;

        return current;
    }

    public Emotion blend(Emotion current, Emotion target, double weight) {
        if (current == target) return current;
        PADVector curPad = PAD_MAP.getOrDefault(current, PADVector.NEUTRAL);
        PADVector tgtPad = PAD_MAP.getOrDefault(target, PADVector.NEUTRAL);
        PADVector blended = new PADVector(
                curPad.pleasure + (tgtPad.pleasure - curPad.pleasure) * weight,
                curPad.arousal + (tgtPad.arousal - curPad.arousal) * weight,
                curPad.dominance + (tgtPad.dominance - curPad.dominance) * weight);
        return classifyPAD(blended);
    }

    public double moveSpeedModifier(Emotion emotion) {
        return switch (emotion) {
            case EXCITED -> 1.15;
            case HAPPY -> 1.05;
            case ANGRY -> 1.10;
            case SAD, BORED -> 0.75;
            case SHY -> 0.80;
            case THOUGHTFUL -> 0.85;
            default -> 1.0;
        };
    }

    public Map<String, Object> extractEmotionFromResponse(String response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("emotion", Emotion.NEUTRAL);
        result.put("emotionChanged", false);

        if (response == null || response.isBlank()) return result;

        int tagStart = response.lastIndexOf("【情绪");
        if (tagStart < 0) tagStart = response.lastIndexOf("[情绪");
        if (tagStart >= 0) {
            int tagEnd = response.indexOf("】", tagStart);
            if (tagEnd < 0) tagEnd = response.indexOf("]", tagStart);
            if (tagEnd > tagStart) {
                String tag = response.substring(tagStart, tagEnd + 1);
                Emotion parsed = Emotion.fromText(tag);
                if (parsed != Emotion.NEUTRAL) {
                    result.put("emotion", parsed);
                    result.put("emotionChanged", true);
                    String cleanResponse = response.substring(0, tagStart).trim();
                    result.put("cleanResponse", cleanResponse);
                    return result;
                }
            }
        }

        Emotion fromText = Emotion.fromText(response);
        result.put("emotion", fromText);
        result.put("cleanResponse", response);
        return result;
    }
}
