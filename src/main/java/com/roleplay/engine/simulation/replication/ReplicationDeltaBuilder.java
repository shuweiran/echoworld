package com.roleplay.engine.simulation.replication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure deterministic projection/delta builder; it never mutates authoritative world state. */
public final class ReplicationDeltaBuilder {
    private final InterestPolicy interestPolicy;

    public ReplicationDeltaBuilder(InterestPolicy interestPolicy) {
        this.interestPolicy = Objects.requireNonNull(interestPolicy, "interestPolicy");
    }

    public BuildResult build(
            Map<String, ReplicaEntity> previousVisibleState,
            Collection<ReplicaEntity> authoritativeEntities,
            Collection<ReplicationEvent> authoritativeEvents,
            InterestContext context,
            int protocolVersion,
            long sequence,
            long serverTick,
            long serverTimeEpochMillis
    ) {
        TreeMap<String, ReplicaEntity> previous = checkedMap(previousVisibleState == null
                ? List.of() : previousVisibleState.values());
        TreeMap<String, ReplicaEntity> authoritative = checkedMap(authoritativeEntities);
        TreeMap<String, ReplicaEntity> visible = visibleEntities(authoritative.values(), context);

        List<ReplicationFrame.Create> creates = new ArrayList<>();
        List<ReplicationFrame.Update> updates = new ArrayList<>();
        List<ReplicationFrame.Remove> removes = new ArrayList<>();
        visible.forEach((id, entity) -> {
            ReplicaEntity old = previous.get(id);
            if (old == null) creates.add(new ReplicationFrame.Create(entity));
            else if (!old.equals(entity)) updates.add(new ReplicationFrame.Update(entity));
        });
        previous.forEach((id, entity) -> {
            if (!visible.containsKey(id)) {
                ReplicationFrame.RemoveReason reason = authoritative.containsKey(id)
                        ? ReplicationFrame.RemoveReason.LEFT_INTEREST
                        : ReplicationFrame.RemoveReason.REMOVED_FROM_WORLD;
                removes.add(new ReplicationFrame.Remove(id, entity.revision(), reason));
            }
        });

        ReplicationFrame frame = new ReplicationFrame(protocolVersion, sequence, serverTick,
                serverTimeEpochMillis, creates, updates, removes,
                visibleEvents(authoritativeEvents, context));
        return new BuildResult(frame, Collections.unmodifiableMap(visible));
    }

    public FullSnapshot fullSnapshot(
            Collection<ReplicaEntity> authoritativeEntities,
            Collection<ReplicationEvent> authoritativeEvents,
            InterestContext context,
            int protocolVersion,
            long sequence,
            long serverTick,
            long serverTimeEpochMillis
    ) {
        return new FullSnapshot(protocolVersion, sequence, serverTick, serverTimeEpochMillis,
                List.copyOf(visibleEntities(authoritativeEntities, context).values()),
                visibleEvents(authoritativeEvents, context));
    }

    private TreeMap<String, ReplicaEntity> visibleEntities(
            Collection<ReplicaEntity> entities, InterestContext context) {
        TreeMap<String, ReplicaEntity> visible = new TreeMap<>();
        checkedMap(entities).forEach((id, entity) -> {
            if (interestPolicy.shouldReplicate(entity, context)) visible.put(id, entity);
        });
        return visible;
    }

    private List<ReplicationEvent> visibleEvents(
            Collection<ReplicationEvent> events, InterestContext context) {
        if (events == null) return List.of();
        TreeMap<String, ReplicationEvent> unique = new TreeMap<>();
        for (ReplicationEvent event : events) {
            Objects.requireNonNull(event, "event");
            if (unique.putIfAbsent(event.eventId(), event) != null) {
                throw new IllegalArgumentException("duplicate eventId: " + event.eventId());
            }
        }
        return unique.values().stream()
                .filter(event -> interestPolicy.shouldReplicate(event, context))
                .sorted(java.util.Comparator.comparingLong(ReplicationEvent::serverTick)
                        .thenComparing(ReplicationEvent::eventId)).toList();
    }

    private TreeMap<String, ReplicaEntity> checkedMap(Collection<ReplicaEntity> entities) {
        Objects.requireNonNull(entities, "entities");
        TreeMap<String, ReplicaEntity> result = new TreeMap<>();
        for (ReplicaEntity entity : entities) {
            Objects.requireNonNull(entity, "entity");
            if (result.putIfAbsent(entity.entityId(), entity) != null) {
                throw new IllegalArgumentException("duplicate entityId: " + entity.entityId());
            }
        }
        return result;
    }

    public record BuildResult(ReplicationFrame frame, Map<String, ReplicaEntity> visibleState) {
        public BuildResult {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(visibleState, "visibleState");
            visibleState = Collections.unmodifiableMap(new TreeMap<>(visibleState));
        }
    }
}
