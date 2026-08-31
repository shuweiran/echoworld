package com.roleplay.engine.simulation.replication;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.roleplay.engine.simulation.replication.ClientReplicationBuffer.AckStatus;
import static com.roleplay.engine.simulation.replication.ClientReplicationBuffer.ReplayMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientReplicationBufferTest {
    private static final int VERSION = ReplicationProtocol.CURRENT_VERSION;

    @Test
    void acknowledgedClientCanReplayEveryContiguousFrameAfterItsSequence() {
        ClientReplicationBuffer buffer = new ClientReplicationBuffer("alice", VERSION, 4);
        buffer.append(frame(1));
        buffer.append(frame(2));
        buffer.append(frame(3));

        var ack = new ClientReplicationBuffer.ClientAck("alice", VERSION, 1);
        assertEquals(AckStatus.ACKNOWLEDGED, buffer.acknowledge(ack).status());
        assertEquals(List.of(2L, 3L), buffer.replayAfter(ack).frames().stream()
                .map(ReplicationFrame::sequence).toList());
        assertEquals(ReplayMode.DELTA_REPLAY, buffer.replayAfter(ack).mode());
        assertEquals(AckStatus.DUPLICATE, buffer.acknowledge(ack).status());
    }

    @Test
    void evictedGapRequiresCurrentFullSnapshotResync() {
        ClientReplicationBuffer buffer = new ClientReplicationBuffer("alice", VERSION, 2);
        buffer.append(frame(1));
        buffer.append(frame(2));
        buffer.append(frame(3));
        FullSnapshot current = snapshot(3);
        buffer.installSnapshot(current);

        var staleAck = new ClientReplicationBuffer.ClientAck("alice", VERSION, 0);
        var replay = buffer.replayAfter(staleAck);

        assertEquals(ReplayMode.FULL_RESYNC_REQUIRED, replay.mode());
        assertEquals("FRAME_EVICTED", replay.reason());
        assertEquals(current, replay.fullSnapshot().orElseThrow());
        assertEquals(AckStatus.RESYNC_REQUIRED, buffer.acknowledge(staleAck).status());
        assertEquals(AckStatus.ACKNOWLEDGED, buffer.acknowledge(
                new ClientReplicationBuffer.ClientAck("alice", VERSION, 3)).status());
    }

    @Test
    void protocolAndClientMismatchNeverReceiveDeltaReplay() {
        ClientReplicationBuffer buffer = new ClientReplicationBuffer("alice", VERSION, 2);
        buffer.append(frame(1));

        var wrongProtocol = new ClientReplicationBuffer.ClientAck("alice", VERSION + 1, 0);
        var wrongClient = new ClientReplicationBuffer.ClientAck("mallory", VERSION, 0);

        assertEquals(AckStatus.PROTOCOL_MISMATCH, buffer.acknowledge(wrongProtocol).status());
        assertEquals(AckStatus.WRONG_CLIENT, buffer.acknowledge(wrongClient).status());
        assertEquals(ReplayMode.FULL_RESYNC_REQUIRED, buffer.replayAfter(wrongProtocol).mode());
        assertEquals(ReplayMode.FULL_RESYNC_REQUIRED, buffer.replayAfter(wrongClient).mode());
    }

    @Test
    void futureAckAndNonContiguousAppendAreRejected() {
        ClientReplicationBuffer buffer = new ClientReplicationBuffer("alice", VERSION, 2);
        buffer.append(frame(1));

        assertEquals(AckStatus.FUTURE_SEQUENCE, buffer.acknowledge(
                new ClientReplicationBuffer.ClientAck("alice", VERSION, 9)).status());
        IllegalArgumentException gap = assertThrows(IllegalArgumentException.class,
                () -> buffer.append(frame(3)));
        assertTrue(gap.getMessage().contains("expected 2"));
    }

    @Test
    void snapshotSequenceBecomesBaselineForSubsequentFrames() {
        ClientReplicationBuffer buffer = new ClientReplicationBuffer("alice", VERSION, 2);
        buffer.installSnapshot(snapshot(10));
        buffer.append(frame(11));

        var replay = buffer.replayAfter(new ClientReplicationBuffer.ClientAck("alice", VERSION, 10));
        assertEquals(List.of(11L), replay.frames().stream().map(ReplicationFrame::sequence).toList());
        assertEquals(11, buffer.latestSequence());
    }

    private ReplicationFrame frame(long sequence) {
        return new ReplicationFrame(VERSION, sequence, sequence * 2, 1_000 + sequence,
                List.of(), List.of(), List.of(), List.of());
    }

    private FullSnapshot snapshot(long sequence) {
        return new FullSnapshot(VERSION, sequence, sequence * 2, 1_000 + sequence,
                List.of(), List.of());
    }
}
