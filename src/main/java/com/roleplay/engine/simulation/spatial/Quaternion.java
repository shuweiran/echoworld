package com.roleplay.engine.simulation.spatial;

/** Renderer-independent world rotation. */
public record Quaternion(double x, double y, double z, double w) {
    public static Quaternion identity() { return new Quaternion(0.0, 0.0, 0.0, 1.0); }
}
