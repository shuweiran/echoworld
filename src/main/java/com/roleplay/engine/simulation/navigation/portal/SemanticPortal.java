package com.roleplay.engine.simulation.navigation.portal;

import java.util.Set;

/**
 * Static semantic connection between two navigation surfaces. Geometry backends
 * plan floor-local movement; this contract owns stairs, lifts, doors and links.
 */
public record SemanticPortal(String id,
                             Kind kind,
                             PortalEndpoint endpointA,
                             PortalEndpoint endpointB,
                             boolean bidirectional,
                             double traversalCost,
                             String interactionAction,
                             Set<String> tags) {
    public SemanticPortal {
        interactionAction = interactionAction == null ? "" : interactionAction;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public enum Kind { DOOR, STAIRS, ELEVATOR, LADDER, TELEPORT, LINK }
}
