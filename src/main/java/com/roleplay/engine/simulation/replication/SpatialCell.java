package com.roleplay.engine.simulation.replication;

import java.util.Objects;

/** Stable server-side interest cell; it is independent from any client scene or prefab. */
public record SpatialCell(String zoneId, String floorId, int x, int z) {
    public SpatialCell {
        zoneId = requireText(zoneId, "zoneId");
        floorId = requireText(floorId, "floorId");
    }

    public boolean isWithin(SpatialCell other, int radius) {
        Objects.requireNonNull(other, "other");
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        return zoneId.equals(other.zoneId)
                && floorId.equals(other.floorId)
                && Math.max(Math.abs(x - other.x), Math.abs(z - other.z)) <= radius;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
