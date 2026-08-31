package com.roleplay.engine.simulation.navigation.portal;

import com.roleplay.engine.simulation.spatial.Vec3;

/** One authored endpoint on a floor-bound navigable surface. */
public record PortalEndpoint(String floorId, String surfaceId, Vec3 worldPosition) {
    public String surfaceKey() {
        return String.valueOf(floorId) + "\u0000" + String.valueOf(surfaceId);
    }
}
