package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.spatial.Vec3;

/** One executable step. Portal/interaction steps are first-class, not hidden in geometry. */
public record PathStep(Type type, Vec3 target, String worldObjectId, String interaction) {
    public enum Type { WALK, INTERACT, USE_PORTAL }

    public static PathStep walk(Vec3 target) { return new PathStep(Type.WALK, target, "", ""); }
}
