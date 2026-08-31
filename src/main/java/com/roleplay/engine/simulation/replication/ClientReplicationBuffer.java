package com.roleplay.engine.simulation.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Per-client bounded frame history used for ACK processing, reconnect replay, and full resync. */
public final class ClientReplicationBuffer {
    private final String clientId;
    private final int protocolVersion;
    private final int capacity;
    private final NavigableMap<Long, ReplicationFrame> frames = new TreeMap<>();
    private long highestAcknowledgedSequence = -1;
    private FullSnapshot latestSnapshot;

    public ClientReplicationBuffer(String clientId, int protocolVersion, int capacity) {
        this.clientId = ReplicaEntity.requireText(clientId, "clientId");
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.protocolVersion = protocolVersion;
        this.capacity = capacity;
    }

    public synchronized void installSnapshot(FullSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireProtocol(snapshot.protocolVersion());
        latestSnapshot = snapshot;
        frames.headMap(snapshot.sequence(), true).clear();
    }

    public synchronized void append(ReplicationFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireProtocol(frame.protocolVersion());
        long latest = latestSequence();
        if (latest >= 0 && frame.sequence() != latest + 1) {
            throw new IllegalArgumentException("frame sequence must be contiguous: expected "
                    + (latest + 1) + " but was " + frame.sequence());
        }
        frames.put(frame.sequence(), frame);
        while (frames.size() > capacity) frames.pollFirstEntry();
    }

    public synchronized AckResult acknowledge(ClientAck ack) {
        Objects.requireNonNull(ack, "ack");
        if (!clientId.equals(ack.clientId())) return result(AckStatus.WRONG_CLIENT);
        if (protocolVersion != ack.protocolVersion()) return result(AckStatus.PROTOCOL_MISMATCH);
        long latest = latestSequence();
        if (ack.sequence() > latest) return result(AckStatus.FUTURE_SEQUENCE);
        if (ack.sequence() <= highestAcknowledgedSequence) return result(AckStatus.DUPLICATE);
        ReplayPlan replay = replayAfterInternal(ack.sequence());
        if (replay.mode() == ReplayMode.FULL_RESYNC_REQUIRED) return result(AckStatus.RESYNC_REQUIRED);
        highestAcknowledgedSequence = ack.sequence();
        return result(AckStatus.ACKNOWLEDGED);
    }

    public synchronized ReplayPlan replayAfter(ClientAck ack) {
        Objects.requireNonNull(ack, "ack");
        if (!clientId.equals(ack.clientId())) return fullResync("WRONG_CLIENT");
        if (protocolVersion != ack.protocolVersion()) return fullResync("PROTOCOL_MISMATCH");
        return replayAfterInternal(ack.sequence());
    }

    public synchronized long latestSequence() {
        long snapshotSequence = latestSnapshot == null ? -1 : latestSnapshot.sequence();
        long frameSequence = frames.isEmpty() ? -1 : frames.lastKey();
        return Math.max(snapshotSequence, frameSequence);
    }

    public synchronized long highestAcknowledgedSequence() {
        return highestAcknowledgedSequence;
    }

    private ReplayPlan replayAfterInternal(long acknowledgedSequence) {
        long latest = latestSequence();
        if (acknowledgedSequence < 0) return fullResync("NEGATIVE_SEQUENCE");
        if (acknowledgedSequence > latest) return fullResync("FUTURE_SEQUENCE");
        if (acknowledgedSequence == latest) return deltaReplay(List.of());
        long expected = acknowledgedSequence + 1;
        if (frames.isEmpty() || frames.firstKey() > expected) return fullResync("FRAME_EVICTED");
        List<ReplicationFrame> replay = new ArrayList<>();
        for (ReplicationFrame frame : frames.tailMap(expected, true).values()) {
            if (frame.sequence() != expected) return fullResync("SEQUENCE_GAP");
            replay.add(frame);
            expected++;
        }
        if (expected - 1 != latest) return fullResync("SEQUENCE_GAP");
        return deltaReplay(replay);
    }

    private ReplayPlan deltaReplay(List<ReplicationFrame> replay) {
        return new ReplayPlan(ReplayMode.DELTA_REPLAY, replay, Optional.empty(), "");
    }

    private ReplayPlan fullResync(String reason) {
        return new ReplayPlan(ReplayMode.FULL_RESYNC_REQUIRED, List.of(),
                Optional.ofNullable(latestSnapshot), reason);
    }

    private AckResult result(AckStatus status) {
        return new AckResult(status, highestAcknowledgedSequence, latestSequence());
    }

    private void requireProtocol(int actual) {
        if (actual != protocolVersion) {
            throw new IllegalArgumentException("protocolVersion mismatch: expected "
                    + protocolVersion + " but was " + actual);
        }
    }

    public record ClientAck(String clientId, int protocolVersion, long sequence) {
        public ClientAck {
            clientId = ReplicaEntity.requireText(clientId, "clientId");
            if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
            if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        }
    }

    public record AckResult(AckStatus status, long highestAcknowledgedSequence, long latestSequence) {
        public AckResult { Objects.requireNonNull(status, "status"); }
    }

    public enum AckStatus {
        ACKNOWLEDGED, DUPLICATE, RESYNC_REQUIRED, FUTURE_SEQUENCE, PROTOCOL_MISMATCH, WRONG_CLIENT
    }

    public record ReplayPlan(
            ReplayMode mode,
            List<ReplicationFrame> frames,
            Optional<FullSnapshot> fullSnapshot,
            String reason
    ) {
        public ReplayPlan {
            Objects.requireNonNull(mode, "mode");
            frames = List.copyOf(frames);
            Objects.requireNonNull(fullSnapshot, "fullSnapshot");
            reason = reason == null ? "" : reason;
        }
    }

    public enum ReplayMode { DELTA_REPLAY, FULL_RESYNC_REQUIRED }
}
