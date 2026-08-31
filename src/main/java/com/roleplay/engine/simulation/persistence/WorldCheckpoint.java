package com.roleplay.engine.simulation.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Durable coarse-grained snapshot; transient per-frame data is intentionally excluded. */
public record WorldCheckpoint(String worldId,
                              long worldVersion,
                              long tick,
                              Instant createdAt,
                              List<Map<String, Object>> entities,
                              Map<String, Map<String, String>> objectStates) {
    public WorldCheckpoint {
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId required");
        if (worldVersion < 0 || tick < 0) throw new IllegalArgumentException("negative version/tick");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        entities = entities == null ? List.of() : entities.stream().map(Map::copyOf).toList();
        if (objectStates == null) objectStates = Map.of();
        else objectStates = objectStates.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }
}
