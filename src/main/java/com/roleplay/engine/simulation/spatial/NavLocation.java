package com.roleplay.engine.simulation.spatial;

/** Membership of a navigable surface; distinct from the physical transform. */
public record NavLocation(String surfaceId, String floorId, Vec3 worldPosition, long polygonRef) {
    public static NavLocation ground(Vec3 position) {
        return new NavLocation("ground", "ground", position, -1L);
    }
}
