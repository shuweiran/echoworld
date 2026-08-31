package com.roleplay.engine.simulation.world;

import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.spatial.Transform3D;

/**
 * Minimal engine-facing entity contract. Presentation clients may project it,
 * but they never become the owner of these facts.
 */
public interface WorldEntity {
    String id();
    EntityKind kind();
    Transform3D transform();
    NavLocation navLocation();
    ControlAuthority controlAuthority();
}
