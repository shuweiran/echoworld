package com.roleplay.engine.simulation.replication;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Join/resync-only full projection for one client. */
public record FullSnapshot(
        int protocolVersion,
        long sequence,
        long serverTick,
        long serverTimeEpochMillis,
        List<ReplicaEntity> entities,
        List<ReplicationEvent> events
) {
    public FullSnapshot {
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        if (serverTick < 0) throw new IllegalArgumentException("serverTick must be non-negative");
        if (serverTimeEpochMillis < 0) throw new IllegalArgumentException("serverTimeEpochMillis must be non-negative");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(events, "events");
        entities = entities.stream().map(value -> Objects.requireNonNull(value, "entity"))
                .sorted(Comparator.comparing(ReplicaEntity::entityId)).toList();
        events = events.stream().map(value -> Objects.requireNonNull(value, "event"))
                .sorted(Comparator.comparingLong(ReplicationEvent::serverTick)
                        .thenComparing(ReplicationEvent::eventId)).toList();
    }
}
