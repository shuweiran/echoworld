package com.roleplay.engine.simulation.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldCheckpointStoreTest {
    @TempDir Path temp;

    @Test
    void jsonStorePersistsSnapshotAndOrderedDurableEvents() {
        JsonFileWorldCheckpointStore store = new JsonFileWorldCheckpointStore(temp);
        WorldCheckpoint checkpoint = new WorldCheckpoint("town-1", 10, 8, Instant.parse("2026-08-30T00:00:00Z"),
                List.of(Map.of("agentName", "alice", "x", 1, "y", 2)),
                Map.of("door", Map.of("open", "false")));
        store.save(checkpoint);
        store.append(new DurableWorldEvent("e2", "town-1", 12, "DOOR_OPEN", Instant.now(), Map.of()));
        store.append(new DurableWorldEvent("e1", "town-1", 11, "ARRIVED", Instant.now(), Map.of()));

        assertEquals(checkpoint, store.loadLatest("town-1").orElseThrow());
        assertEquals(List.of(11L, 12L), store.eventsAfter("town-1", 10).stream()
                .map(DurableWorldEvent::worldVersion).toList());
    }

    @Test
    void inMemoryStoreKeepsNewestSnapshotAndDeduplicatesEvents() {
        InMemoryWorldCheckpointStore store = new InMemoryWorldCheckpointStore();
        store.save(new WorldCheckpoint("w", 2, 2, Instant.now(), List.of(), Map.of()));
        store.save(new WorldCheckpoint("w", 1, 1, Instant.now(), List.of(), Map.of()));
        DurableWorldEvent event = new DurableWorldEvent("same", "w", 3, "TEST", Instant.now(), Map.of());
        store.append(event);
        store.append(event);

        assertEquals(2, store.loadLatest("w").orElseThrow().worldVersion());
        assertEquals(1, store.eventsAfter("w", 0).size());
    }

    @Test
    void unsafeWorldIdIsRejected() {
        JsonFileWorldCheckpointStore store = new JsonFileWorldCheckpointStore(temp);
        assertThrows(IllegalArgumentException.class, () -> store.loadLatest("../escape"));
    }
}
