package com.roleplay.engine.simulation.replication;

import java.util.Set;

/** Per-client interest input. The focus cell may be absent for non-spatial observers. */
public record InterestContext(
        String clientId,
        SpatialCell focusCell,
        int spatialRadiusCells,
        Set<String> narrativeSubscriptions
) {
    public InterestContext {
        clientId = ReplicaEntity.requireText(clientId, "clientId");
        if (spatialRadiusCells < 0) throw new IllegalArgumentException("spatialRadiusCells must be non-negative");
        narrativeSubscriptions = ReplicaEntity.immutableTextSet(narrativeSubscriptions, "narrativeSubscriptions");
    }
}
