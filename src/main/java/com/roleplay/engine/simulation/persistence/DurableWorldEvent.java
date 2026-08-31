package com.roleplay.engine.simulation.persistence;

import java.time.Instant;
import java.util.Map;

/** Low-frequency domain event retained between checkpoints. */
public record DurableWorldEvent(String eventId,
                                String worldId,
                                long worldVersion,
                                String type,
                                Instant occurredAt,
                                Map<String, Object> payload) {
    public DurableWorldEvent {
        if (eventId == null || eventId.isBlank() || worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("eventId/worldId required");
        }
        if (worldVersion < 0 || type == null || type.isBlank()) throw new IllegalArgumentException("version/type required");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
