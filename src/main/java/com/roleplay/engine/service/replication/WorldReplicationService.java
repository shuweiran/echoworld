package com.roleplay.engine.service.replication;

import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.observability.ReplicationFrameJfrEvent;
import com.roleplay.engine.simulation.replication.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Projects authoritative snapshots into per-client, interest-filtered replication streams. */
@Service
public class WorldReplicationService {
    private static final double CELL_SIZE = 100.0;
    private static final Set<String> SAFE_AGENT_FIELDS = Set.of(
            "agentName", "x", "y", "vx", "vy", "emotion", "emotionEmoji",
            "inConversation", "stance", "locomotionState", "controlAuthority", "transform", "navLocation");

    private final SimulationWorld world;
    private final ReplicationDeltaBuilder deltaBuilder = new ReplicationDeltaBuilder(new DefaultInterestPolicy());
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();
    private final Queue<ReplicationEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final ThreadPoolExecutor replicationWorker;
    private final Consumer<SimulationWorld.WorldSnapshot> tickListener;
    private final Consumer<Map<String, Object>> actionListener;

    public WorldReplicationService(SimulationWorld world) {
        this.world = world;
        this.replicationWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "world-replication");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
        this.tickListener = this::enqueueSnapshot;
        this.actionListener = this::captureActionEvent;
        world.addTickListener(tickListener);
        world.addActionEventListener(actionListener);
    }

    public FullSnapshot connect(String clientId, InterestContext interest, ReplicationClientSink sink) {
        ClientState state = new ClientState(interest, sink,
                new ClientReplicationBuffer(clientId, ReplicationProtocol.CURRENT_VERSION, 128));
        clients.put(clientId, state);
        SimulationWorld.WorldSnapshot snapshot = currentSnapshot();
        long sequence = state.sequence.getAndIncrement();
        FullSnapshot full = deltaBuilder.fullSnapshot(toReplicas(snapshot), List.of(), interest,
                ReplicationProtocol.CURRENT_VERSION, sequence, snapshot.tick(), snapshot.timestamp());
        state.visible = byId(full.entities());
        state.buffer.installSnapshot(full);
        sink.send(new Envelope("full_snapshot", full));
        return full;
    }

    public void disconnect(String clientId) { clients.remove(clientId); }

    public void updateInterest(String clientId, InterestContext context) {
        ClientState state = requireClient(clientId);
        if (!clientId.equals(context.clientId())) throw new IllegalArgumentException("clientId mismatch");
        state.interest = context;
    }

    public ClientReplicationBuffer.AckResult acknowledge(ClientReplicationBuffer.ClientAck ack) {
        ClientState state = requireClient(ack.clientId());
        ClientReplicationBuffer.AckResult result = state.buffer.acknowledge(ack);
        if (result.status() == ClientReplicationBuffer.AckStatus.RESYNC_REQUIRED) resync(ack.clientId());
        return result;
    }

    public ClientReplicationBuffer.ReplayPlan replayAfter(ClientReplicationBuffer.ClientAck ack) {
        ClientState state = requireClient(ack.clientId());
        ClientReplicationBuffer.ReplayPlan plan = state.buffer.replayAfter(ack);
        if (plan.mode() == ClientReplicationBuffer.ReplayMode.DELTA_REPLAY) {
            plan.frames().forEach(frame -> state.sink.send(new Envelope("replication_frame", frame)));
        } else {
            plan.fullSnapshot().ifPresent(snapshot -> state.sink.send(new Envelope("full_snapshot", snapshot)));
        }
        return plan;
    }

    public int clientCount() { return clients.size(); }

    /** Public adapter boundary for deterministic tests and alternate world hosts. */
    public void publishSnapshot(SimulationWorld.WorldSnapshot snapshot) {
        publishSnapshot(snapshot, drainEvents());
    }

    private void publishSnapshot(SimulationWorld.WorldSnapshot snapshot, List<ReplicationEvent> events) {
        List<ReplicaEntity> entities = toReplicas(snapshot);
        for (Map.Entry<String, ClientState> entry : clients.entrySet()) {
            ClientState state = entry.getValue();
            long sequence = state.sequence.getAndIncrement();
            ReplicationDeltaBuilder.BuildResult built = deltaBuilder.build(state.visible, entities, events,
                    state.interest, ReplicationProtocol.CURRENT_VERSION, sequence, snapshot.tick(), snapshot.timestamp());
            state.visible = built.visibleState();
            state.buffer.append(built.frame());
            state.sink.send(new Envelope("replication_frame", built.frame()));
            recordFrame(entry.getKey(), built.frame());
        }
    }

    /** Tick listener copies immutable data and returns immediately; socket I/O stays off the world thread. */
    private void enqueueSnapshot(SimulationWorld.WorldSnapshot snapshot) {
        List<ReplicationEvent> events = drainEvents();
        replicationWorker.execute(() -> publishSnapshot(snapshot, events));
    }

    private void captureActionEvent(Map<String, Object> actionEvent) {
        String actorId = String.valueOf(actionEvent.getOrDefault("actorId", ""));
        String action = String.valueOf(actionEvent.getOrDefault("action", "UNKNOWN"));
        long serverTick = numberAsLong(actionEvent.get("worldVersion"), world.getTickCount());
        var state = world.getState(actorId);
        SpatialCell cell = state == null ? null : new SpatialCell("world", state.navLocation().floorId(),
                (int) Math.floor(state.getX() / CELL_SIZE), (int) Math.floor(state.getY() / CELL_SIZE));
        Map<String, Object> safePayload = new LinkedHashMap<>();
        if (!actorId.isBlank()) safePayload.put("actorId", actorId);
        safePayload.put("action", action);
        pendingEvents.add(new ReplicationEvent("action-" + serverTick + "-" + eventSequence.incrementAndGet(),
                "ACTION_COMMITTED", serverTick, cell, cell == null,
                PerceptionScope.publicScope(), Set.of(), safePayload));
    }

    private List<ReplicationEvent> drainEvents() {
        List<ReplicationEvent> events = new ArrayList<>();
        ReplicationEvent event;
        while ((event = pendingEvents.poll()) != null) events.add(event);
        return List.copyOf(events);
    }

    private void resync(String clientId) {
        ClientState state = requireClient(clientId);
        SimulationWorld.WorldSnapshot snapshot = currentSnapshot();
        long sequence = state.sequence.getAndIncrement();
        FullSnapshot full = deltaBuilder.fullSnapshot(toReplicas(snapshot), List.of(), state.interest,
                ReplicationProtocol.CURRENT_VERSION, sequence, snapshot.tick(), snapshot.timestamp());
        state.visible = byId(full.entities());
        state.buffer.installSnapshot(full);
        state.sink.send(new Envelope("full_snapshot", full));
    }

    private SimulationWorld.WorldSnapshot currentSnapshot() {
        List<Map<String, Object>> agents = world.getAllStates().values().stream().map(state -> state.toMap()).toList();
        return new SimulationWorld.WorldSnapshot(world.getTickCount(), agents,
                world.getObstacles().stream().map(obstacle -> obstacle.toMap()).toList(), System.currentTimeMillis(),
                world.getWorldNarration(), world.isDirectorActive(), world.getCurrentScene(),
                world.getWorldWidth(), world.getWorldHeight());
    }

    private List<ReplicaEntity> toReplicas(SimulationWorld.WorldSnapshot snapshot) {
        List<ReplicaEntity> result = new ArrayList<>();
        for (Map<String, Object> raw : snapshot.agents()) {
            String id = String.valueOf(raw.getOrDefault("agentName", ""));
            if (id.isBlank()) continue;
            double x = number(raw.get("x")), y = number(raw.get("y"));
            Map<String, Object> safe = new LinkedHashMap<>();
            SAFE_AGENT_FIELDS.forEach(key -> { if (raw.get(key) != null) safe.put(key, raw.get(key)); });
            result.add(new ReplicaEntity(id, "AGENT", snapshot.tick(),
                    new SpatialCell("world", "ground", (int) Math.floor(x / CELL_SIZE), (int) Math.floor(y / CELL_SIZE)),
                    "", PerceptionScope.publicScope(), Set.of(), safe));
        }
        return List.copyOf(result);
    }

    private ClientState requireClient(String clientId) {
        ClientState state = clients.get(clientId);
        if (state == null) throw new IllegalArgumentException("unknown replication client");
        return state;
    }

    private static Map<String, ReplicaEntity> byId(Collection<ReplicaEntity> entities) {
        Map<String, ReplicaEntity> result = new LinkedHashMap<>();
        entities.forEach(entity -> result.put(entity.entityId(), entity));
        return Map.copyOf(result);
    }

    private static double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0.0; }
    private static long numberAsLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    @PreDestroy
    public void close() {
        world.removeTickListener(tickListener);
        world.removeActionEventListener(actionListener);
        replicationWorker.shutdownNow();
        clients.clear();
        pendingEvents.clear();
    }

    private static void recordFrame(String clientId, ReplicationFrame frame) {
        ReplicationFrameJfrEvent event = new ReplicationFrameJfrEvent();
        if (!event.isEnabled()) return;
        event.clientId = clientId;
        event.sequence = frame.sequence();
        event.serverTick = frame.serverTick();
        event.creates = frame.creates().size();
        event.updates = frame.updates().size();
        event.removes = frame.removes().size();
        event.events = frame.events().size();
        event.estimatedBytes = 0;
        event.commit();
    }

    public record Envelope(String type, Object payload) { }

    private static final class ClientState {
        private volatile InterestContext interest;
        private final ReplicationClientSink sink;
        private final ClientReplicationBuffer buffer;
        private final AtomicLong sequence = new AtomicLong();
        private volatile Map<String, ReplicaEntity> visible = Map.of();

        private ClientState(InterestContext interest, ReplicationClientSink sink, ClientReplicationBuffer buffer) {
            this.interest = Objects.requireNonNull(interest, "interest");
            this.sink = Objects.requireNonNull(sink, "sink");
            this.buffer = Objects.requireNonNull(buffer, "buffer");
        }
    }
}
