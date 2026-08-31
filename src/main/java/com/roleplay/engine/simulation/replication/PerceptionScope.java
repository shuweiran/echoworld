package com.roleplay.engine.simulation.replication;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Hard information boundary evaluated before any interest optimization. */
public record PerceptionScope(boolean publicToAll, Set<String> clientIds) {
    public PerceptionScope {
        TreeSet<String> normalized = new TreeSet<>();
        if (clientIds != null) {
            for (String clientId : clientIds) {
                if (clientId == null || clientId.isBlank()) {
                    throw new IllegalArgumentException("clientIds must not contain blank values");
                }
                normalized.add(clientId);
            }
        }
        clientIds = Collections.unmodifiableSet(normalized);
    }

    public static PerceptionScope publicScope() {
        return new PerceptionScope(true, Set.of());
    }

    public static PerceptionScope restrictedTo(Set<String> clientIds) {
        return new PerceptionScope(false, clientIds);
    }

    public static PerceptionScope nobody() {
        return restrictedTo(Set.of());
    }

    public boolean allows(String clientId) {
        return publicToAll || (clientId != null && clientIds.contains(clientId));
    }
}
