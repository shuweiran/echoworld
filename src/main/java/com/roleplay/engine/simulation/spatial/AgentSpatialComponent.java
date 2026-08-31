package com.roleplay.engine.simulation.spatial;

import java.util.concurrent.atomic.AtomicReference;

/** Typed spatial component owned by one Agent entity. */
public final class AgentSpatialComponent {
    private final AtomicReference<Transform3D> transform;
    private final AtomicReference<Vec3> velocity = new AtomicReference<>(Vec3.zero());
    private volatile NavLocation navLocation;
    private volatile ControlAuthority authority = ControlAuthority.AI_AUTONOMOUS;
    private volatile LocomotionState locomotion = LocomotionState.IDLE;

    public AgentSpatialComponent(double worldX, double worldZ) {
        Transform3D initial = Transform3D.ground(worldX, worldZ);
        this.transform = new AtomicReference<>(initial);
        this.navLocation = NavLocation.ground(initial.position());
    }

    public Transform3D transform() { return transform.get(); }
    public Vec3 velocity() { return velocity.get(); }
    public NavLocation navLocation() { return navLocation; }
    public ControlAuthority authority() { return authority; }
    public LocomotionState locomotion() { return locomotion; }

    public void setPosition(double worldX, double elevation, double worldZ) {
        Vec3 value = new Vec3(worldX, elevation, worldZ);
        transform.updateAndGet(current -> current.withPosition(value));
        navLocation = new NavLocation(navLocation.surfaceId(), navLocation.floorId(), value, navLocation.polygonRef());
    }

    public void setGroundPosition(double worldX, double worldZ) {
        setPosition(worldX, transform.get().position().y(), worldZ);
    }

    public void setVelocity(double worldVx, double verticalVy, double worldVz) {
        velocity.set(new Vec3(worldVx, verticalVy, worldVz));
        double speed = Math.hypot(worldVx, worldVz);
        if (speed < 0.5) locomotion = LocomotionState.IDLE;
        else if (speed < 90.0) locomotion = LocomotionState.WALK;
        else locomotion = LocomotionState.RUN;
    }

    public void setAuthority(ControlAuthority value) { this.authority = value; }
    public void setNavLocation(NavLocation value) { this.navLocation = value; }
    public void setLocomotion(LocomotionState value) { this.locomotion = value; }
}
