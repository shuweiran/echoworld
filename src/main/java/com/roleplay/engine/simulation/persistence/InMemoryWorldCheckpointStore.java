package com.roleplay.engine.simulation.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic adapter for tests and ephemeral worlds. */
public final class InMemoryWorldCheckpointStore implements WorldCheckpointStore {
    private final ConcurrentHashMap<String, WorldCheckpoint> snapshots = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<DurableWorldEvent> events = new CopyOnWriteArrayList<>();

    @Override public void save(WorldCheckpoint checkpoint) {
        snapshots.compute(checkpoint.worldId(), (id, current) -> current == null
                || checkpoint.worldVersion() >= current.worldVersion() ? checkpoint : current);
    }

    @Override public Optional<WorldCheckpoint> loadLatest(String worldId) {
        return Optional.ofNullable(snapshots.get(worldId));
    }

    @Override public void append(DurableWorldEvent event) {
        boolean duplicate = events.stream().anyMatch(existing -> existing.eventId().equals(event.eventId()));
        if (!duplicate) events.add(event);
    }

    @Override public List<DurableWorldEvent> eventsAfter(String worldId, long worldVersion) {
        return events.stream().filter(event -> event.worldId().equals(worldId) && event.worldVersion() > worldVersion)
                .sorted(Comparator.comparingLong(DurableWorldEvent::worldVersion).thenComparing(DurableWorldEvent::occurredAt))
                .toList();
    }
}
