package com.roleplay.engine.service.world;

import java.util.Locale;

/** 与是否存在 2D 地图无关的场景人口语义。 */
public enum ScenePopulationCategory {
    OUTDOOR_BUSY(18, 36, 24),
    OUTDOOR_QUIET(6, 16, 10),
    PUBLIC_INDOOR(5, 18, 10),
    PRIVATE_INDOOR(0, 5, 2),
    TRANSIT(8, 24, 14),
    ISOLATED(0, 3, 1),
    UNKNOWN(0, 24, 8);

    private final int min;
    private final int max;
    private final int defaultTarget;

    ScenePopulationCategory(int min, int max, int defaultTarget) {
        this.min = min;
        this.max = max;
        this.defaultTarget = defaultTarget;
    }

    public int clamp(int suggested, int hardMax) {
        int safeMax = Math.max(0, Math.min(max, hardMax));
        return Math.max(Math.min(min, safeMax), Math.min(safeMax, suggested));
    }

    public int defaultTarget(int hardMax) {
        return clamp(defaultTarget, hardMax);
    }

    public static ScenePopulationCategory parse(Object raw) {
        if (raw == null) return UNKNOWN;
        try {
            return valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
