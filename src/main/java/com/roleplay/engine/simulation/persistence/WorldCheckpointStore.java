package com.roleplay.engine.simulation.persistence;

import java.util.List;
import java.util.Optional;

public interface WorldCheckpointStore {
    void save(WorldCheckpoint checkpoint);
    Optional<WorldCheckpoint> loadLatest(String worldId);
    void append(DurableWorldEvent event);
    List<DurableWorldEvent> eventsAfter(String worldId, long worldVersion);
}
