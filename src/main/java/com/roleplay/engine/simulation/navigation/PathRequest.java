package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.Obstacle;
import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.NavLocation;

import java.util.List;

/** Complete path query. Control authority is included so player input can never enter AI planning. */
public record PathRequest(String entityId,
                          ControlAuthority authority,
                          NavLocation from,
                          NavLocation to,
                          NavProfile profile,
                          double worldWidth,
                          double worldHeight,
                          List<Obstacle> obstacles) {
    public PathRequest {
        obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
    }
}
