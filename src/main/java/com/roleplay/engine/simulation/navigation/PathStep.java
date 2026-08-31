package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.spatial.Vec3;

/** One executable step. Portal/interaction steps are first-class, not hidden in geometry. */
public record PathStep(Type type, Vec3 target, String worldObjectId, String interaction, String floorId, String surfaceId) {
    public PathStep(Type type, Vec3 target, String worldObjectId, String interaction) {
        this(type, target, worldObjectId, interaction, "ground", "ground");
    }
    public PathStep(Type type, Vec3 target, String worldObjectId, String interaction, String floorId) {
        this(type, target, worldObjectId, interaction, floorId, "ground");
    }
    public PathStep {
        floorId = floorId == null || floorId.isBlank() ? "ground" : floorId;
        surfaceId = surfaceId == null || surfaceId.isBlank() ? "ground" : surfaceId;
    }
    public enum Type { WALK, INTERACT, USE_PORTAL }

    public static PathStep walk(Vec3 target) { return new PathStep(Type.WALK, target, "", ""); }
    public static PathStep walk(Vec3 target, String floorId) { return new PathStep(Type.WALK, target, "", "", floorId); }
    public static PathStep usePortal(String portalId, Vec3 target, String floorId, String surfaceId) {
        return new PathStep(Type.USE_PORTAL, target, portalId, "", floorId, surfaceId);
    }
}
