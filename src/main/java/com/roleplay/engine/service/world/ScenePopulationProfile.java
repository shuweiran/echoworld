package com.roleplay.engine.service.world;

import java.time.Instant;

/** LLM 的场景判断经 Java 类别区间、全局容量和置信度校验后的权威人口预算。 */
public record ScenePopulationProfile(
        ScenePopulationCategory category,
        String sceneLabel,
        int targetCount,
        double confidence,
        String reason,
        Instant assessedAt) {

    public static ScenePopulationProfile validated(ScenePopulationCategory category, String sceneLabel,
                                                     int suggestedTarget, double confidence, String reason,
                                                     int fallbackTarget, int hardMax, Instant now) {
        ScenePopulationCategory safeCategory = category == null ? ScenePopulationCategory.UNKNOWN : category;
        double safeConfidence = Double.isFinite(confidence) ? Math.max(0d, Math.min(1d, confidence)) : 0d;
        int target = safeConfidence < 0.55d
                ? Math.max(0, Math.min(hardMax, fallbackTarget))
                : safeCategory.clamp(suggestedTarget, hardMax);
        return new ScenePopulationProfile(safeCategory, compact(sceneLabel, "未识别场景"), target,
                safeConfidence, compact(reason, ""), now == null ? Instant.now() : now);
    }

    private static String compact(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.replaceAll("[\\r\\n]+", " ").trim();
        return clean.length() <= 120 ? clean : clean.substring(0, 120);
    }
}
