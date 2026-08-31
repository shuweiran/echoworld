package com.roleplay.engine.simulation.navigation;

/** Navigation capabilities and physical clearance for one agent type. */
public record NavProfile(double radius, boolean canUseDoors, boolean canUseStairs, boolean canUseElevators) {
    public NavProfile {
        if (!Double.isFinite(radius) || radius < 0) {
            throw new IllegalArgumentException("navigation radius must be finite and non-negative");
        }
    }

    public static NavProfile humanoid() { return new NavProfile(12.0, true, true, true); }
}
