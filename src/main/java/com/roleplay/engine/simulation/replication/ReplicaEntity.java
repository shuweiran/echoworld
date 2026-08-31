package com.roleplay.engine.simulation.replication;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Client-safe projection of one authoritative world entity. */
public record ReplicaEntity(
        String entityId,
        String entityType,
        long revision,
        SpatialCell cell,
        String ownerClientId,
        PerceptionScope perceptionScope,
        Set<String> narrativeTags,
        Map<String, Object> state
) {
    public ReplicaEntity {
        entityId = requireText(entityId, "entityId");
        entityType = requireText(entityType, "entityType");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        Objects.requireNonNull(cell, "cell");
        ownerClientId = ownerClientId == null ? "" : ownerClientId.trim();
        Objects.requireNonNull(perceptionScope, "perceptionScope");
        narrativeTags = immutableTextSet(narrativeTags, "narrativeTags");
        state = immutableState(state);
    }

    public boolean isPerceivableBy(String clientId) {
        return (!ownerClientId.isEmpty() && ownerClientId.equals(clientId)) || perceptionScope.allows(clientId);
    }

    private static Map<String, Object> immutableState(Map<String, Object> source) {
        TreeMap<String, Object> copy = new TreeMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                requireText(key, "state key");
                copy.put(key, Objects.requireNonNull(value, "state value"));
            });
        }
        return Collections.unmodifiableMap(copy);
    }

    static Set<String> immutableTextSet(Set<String> source, String field) {
        TreeSet<String> copy = new TreeSet<>();
        if (source != null) {
            for (String value : source) copy.add(requireText(value, field));
        }
        return Collections.unmodifiableSet(copy);
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
