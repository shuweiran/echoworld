package com.roleplay.engine.simulation.spatial;

import java.util.Objects;

/** Authoritative physical transform. Navigation membership is deliberately separate. */
public record Transform3D(Vec3 position, Quaternion rotation) {
    public Transform3D {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotation, "rotation");
    }

    public static Transform3D ground(double x, double z) {
        return new Transform3D(Vec3.ground(x, z), Quaternion.identity());
    }

    public Transform3D withPosition(Vec3 value) { return new Transform3D(value, rotation); }
}
