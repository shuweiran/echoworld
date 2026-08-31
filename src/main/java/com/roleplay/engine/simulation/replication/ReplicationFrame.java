package com.roleplay.engine.simulation.replication;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Ordered delta frame sent after a join snapshot. */
public record ReplicationFrame(
        int protocolVersion,
        long sequence,
        long serverTick,
        long serverTimeEpochMillis,
        List<Create> creates,
        List<Update> updates,
        List<Remove> removes,
        List<ReplicationEvent> events
) {
    public ReplicationFrame {
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        if (serverTick < 0) throw new IllegalArgumentException("serverTick must be non-negative");
        if (serverTimeEpochMillis < 0) throw new IllegalArgumentException("serverTimeEpochMillis must be non-negative");
        creates = sorted(creates, Comparator.comparing(value -> value.entity().entityId()));
        updates = sorted(updates, Comparator.comparing(value -> value.entity().entityId()));
        removes = sorted(removes, Comparator.comparing(Remove::entityId));
        events = sorted(events, Comparator.comparingLong(ReplicationEvent::serverTick)
                .thenComparing(ReplicationEvent::eventId));
    }

    private static <T> List<T> sorted(List<T> source, Comparator<T> comparator) {
        Objects.requireNonNull(source, "frame list");
        return source.stream().map(value -> Objects.requireNonNull(value, "frame item"))
                .sorted(comparator).toList();
    }

    public record Create(ReplicaEntity entity) {
        public Create { Objects.requireNonNull(entity, "entity"); }
    }

    public record Update(ReplicaEntity entity) {
        public Update { Objects.requireNonNull(entity, "entity"); }
    }

    public record Remove(String entityId, long revision, RemoveReason reason) {
        public Remove {
            entityId = ReplicaEntity.requireText(entityId, "entityId");
            if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum RemoveReason { REMOVED_FROM_WORLD, LEFT_INTEREST }
}
