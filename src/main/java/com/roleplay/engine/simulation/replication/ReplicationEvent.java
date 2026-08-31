package com.roleplay.engine.simulation.replication;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Transient, client-safe event. Visibility is still enforced server-side. */
public record ReplicationEvent(
        String eventId,
        String eventType,
        long serverTick,
        SpatialCell cell,
        boolean globalInterest,
        PerceptionScope perceptionScope,
        Set<String> narrativeTags,
        Map<String, Object> payload
) {
    public ReplicationEvent {
        eventId = ReplicaEntity.requireText(eventId, "eventId");
        eventType = ReplicaEntity.requireText(eventType, "eventType");
        if (serverTick < 0) throw new IllegalArgumentException("serverTick must be non-negative");
        Objects.requireNonNull(perceptionScope, "perceptionScope");
        narrativeTags = ReplicaEntity.immutableTextSet(narrativeTags, "narrativeTags");
        TreeMap<String, Object> copy = new TreeMap<>();
        if (payload != null) {
            payload.forEach((key, value) -> {
                ReplicaEntity.requireText(key, "payload key");
                copy.put(key, Objects.requireNonNull(value, "payload value"));
            });
        }
        payload = Collections.unmodifiableMap(copy);
    }
}
