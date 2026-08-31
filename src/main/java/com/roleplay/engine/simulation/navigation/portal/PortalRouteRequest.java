package com.roleplay.engine.simulation.navigation.portal;

import com.roleplay.engine.simulation.navigation.NavProfile;

import java.util.List;
import java.util.Map;

/** Complete input for topology-only Floor + Portal routing. */
public record PortalRouteRequest(PortalEndpoint from,
                                 PortalEndpoint to,
                                 NavProfile profile,
                                 List<SemanticPortal> portals,
                                 Map<String, PortalRuntimeState> runtimeStates) {
    public PortalRouteRequest {
        portals = portals == null ? List.of() : List.copyOf(portals);
        runtimeStates = runtimeStates == null ? Map.of() : Map.copyOf(runtimeStates);
    }
}
