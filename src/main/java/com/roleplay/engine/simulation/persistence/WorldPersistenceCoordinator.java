package com.roleplay.engine.simulation.persistence;

import com.roleplay.engine.simulation.SimulationWorld;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Asynchronous adapter: the world tick copies facts but never waits for storage I/O. */
public final class WorldPersistenceCoordinator implements AutoCloseable {
    private final String worldId;
    private final SimulationWorld world;
    private final WorldCheckpointStore store;
    private final int checkpointEveryTicks;
    private final ExecutorService writer;
    private final AtomicBoolean checkpointPending = new AtomicBoolean();
    private final Consumer<Map<String, Object>> actionListener;
    private final Consumer<SimulationWorld.WorldSnapshot> tickListener;

    public WorldPersistenceCoordinator(String worldId, SimulationWorld world, WorldCheckpointStore store,
                                       int checkpointEveryTicks) {
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId required");
        if (checkpointEveryTicks < 1) throw new IllegalArgumentException("checkpointEveryTicks must be positive");
        this.worldId = worldId;
        this.world = Objects.requireNonNull(world, "world");
        this.store = Objects.requireNonNull(store, "store");
        this.checkpointEveryTicks = checkpointEveryTicks;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "world-persistence-" + worldId);
            thread.setDaemon(true);
            return thread;
        });
        this.actionListener = this::appendAction;
        this.tickListener = this::onTick;
        world.addActionEventListener(actionListener);
        world.addTickListener(tickListener);
    }

    private void appendAction(Map<String, Object> event) {
        DurableWorldEvent durable = world.durableActionEvent(worldId, event);
        writer.submit(() -> store.append(durable));
    }

    private void onTick(SimulationWorld.WorldSnapshot snapshot) {
        if (snapshot.tick() % checkpointEveryTicks != 0 || !checkpointPending.compareAndSet(false, true)) return;
        WorldCheckpoint checkpoint = world.createCheckpoint(worldId);
        writer.submit(() -> {
            try { store.save(checkpoint); }
            finally { checkpointPending.set(false); }
        });
    }

    public void checkpointNow() {
        WorldCheckpoint checkpoint = world.createCheckpoint(worldId);
        writer.submit(() -> store.save(checkpoint));
    }

    @Override public void close() {
        world.removeActionEventListener(actionListener);
        world.removeTickListener(tickListener);
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
