package com.roleplay.engine.simulation.spatial;

/** World-space vector. Babylon uses X/Y/Z; Phaser projects X/Z to x/y. */
public record Vec3(double x, double y, double z) {
    public static Vec3 ground(double x, double z) { return new Vec3(x, 0.0, z); }
    public static Vec3 zero() { return new Vec3(0.0, 0.0, 0.0); }

    public double groundDistance(Vec3 other) {
        return Math.hypot(x - other.x, z - other.z);
    }
}
