package com.roleplay.engine.simulation.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Local durable adapter using atomic JSON snapshot replacement and append-only JSONL events. */
public final class JsonFileWorldCheckpointStore implements WorldCheckpointStore {
    private final Path directory;
    private final ObjectMapper mapper;

    public JsonFileWorldCheckpointStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override public synchronized void save(WorldCheckpoint checkpoint) {
        try {
            Files.createDirectories(directory);
            Path target = snapshotPath(checkpoint.worldId());
            Path temp = Files.createTempFile(directory, safe(checkpoint.worldId()), ".tmp");
            mapper.writeValue(temp.toFile(), checkpoint);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to save world checkpoint", e);
        }
    }

    @Override public synchronized Optional<WorldCheckpoint> loadLatest(String worldId) {
        Path path = snapshotPath(worldId);
        if (!Files.exists(path)) return Optional.empty();
        try { return Optional.of(mapper.readValue(path.toFile(), WorldCheckpoint.class)); }
        catch (IOException e) { throw new IllegalStateException("failed to load world checkpoint", e); }
    }

    @Override public synchronized void append(DurableWorldEvent event) {
        try {
            Files.createDirectories(directory);
            Path path = eventPath(event.worldId());
            String line = mapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(path, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("failed to append world event", e);
        }
    }

    @Override public synchronized List<DurableWorldEvent> eventsAfter(String worldId, long worldVersion) {
        Path path = eventPath(worldId);
        if (!Files.exists(path)) return List.of();
        try {
            List<DurableWorldEvent> result = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) continue;
                DurableWorldEvent event = mapper.readValue(line, DurableWorldEvent.class);
                if (event.worldVersion() > worldVersion) result.add(event);
            }
            result.sort(Comparator.comparingLong(DurableWorldEvent::worldVersion).thenComparing(DurableWorldEvent::occurredAt));
            return List.copyOf(result);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load world events", e);
        }
    }

    private Path snapshotPath(String worldId) { return contained(safe(worldId) + ".snapshot.json"); }
    private Path eventPath(String worldId) { return contained(safe(worldId) + ".events.jsonl"); }
    private Path contained(String file) {
        Path resolved = directory.resolve(file).normalize();
        if (!resolved.startsWith(directory)) throw new IllegalArgumentException("invalid world id");
        return resolved;
    }
    private static String safe(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("invalid world id");
        return value;
    }
}
