package com.roleplay.engine.service.replication;

import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.replication.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldReplicationServiceTest {
    @Test
    void sendsInterestFilteredSafeSnapshotThenDelta() {
        SimulationWorld world = new SimulationWorld();
        WorldReplicationService service = new WorldReplicationService(world);
        List<Object> sent = new ArrayList<>();
        InterestContext interest = new InterestContext("client-a", new SpatialCell("world", "ground", 0, 0),
                1, Set.of());

        FullSnapshot initial = service.connect("client-a", interest, sent::add);
        assertTrue(initial.entities().isEmpty());
        SimulationWorld.WorldSnapshot snapshot = snapshot(1, List.of(
                agent("near", 50, 50, "secret-near"), agent("far", 900, 900, "secret-far")));
        service.publishSnapshot(snapshot);

        assertEquals(2, sent.size());
        WorldReplicationService.Envelope envelope = (WorldReplicationService.Envelope) sent.get(1);
        ReplicationFrame frame = (ReplicationFrame) envelope.payload();
        assertEquals(List.of("near"), frame.creates().stream().map(value -> value.entity().entityId()).toList());
        assertFalse(frame.creates().getFirst().entity().state().containsKey("currentMessage"));
        assertFalse(frame.creates().getFirst().entity().state().containsKey("schedule"));
    }

    @Test
    void ackAndReplayUsePerClientSequenceBuffer() {
        SimulationWorld world = new SimulationWorld();
        WorldReplicationService service = new WorldReplicationService(world);
        List<Object> sent = new ArrayList<>();
        service.connect("client-a", new InterestContext("client-a",
                new SpatialCell("world", "ground", 0, 0), 2, Set.of()), sent::add);
        service.publishSnapshot(snapshot(1, List.of(agent("near", 10, 10, "hidden"))));

        var ack = service.acknowledge(new ClientReplicationBuffer.ClientAck("client-a",
                ReplicationProtocol.CURRENT_VERSION, 0));
        assertEquals(ClientReplicationBuffer.AckStatus.ACKNOWLEDGED, ack.status());
        assertEquals(ClientReplicationBuffer.AckStatus.DUPLICATE,
                service.acknowledge(new ClientReplicationBuffer.ClientAck("client-a",
                        ReplicationProtocol.CURRENT_VERSION, 0)).status());
        var replay = service.replayAfter(new ClientReplicationBuffer.ClientAck("client-a",
                ReplicationProtocol.CURRENT_VERSION, 0));
        assertEquals(ClientReplicationBuffer.ReplayMode.DELTA_REPLAY, replay.mode());
        assertEquals(1, replay.frames().size());
    }

    @Test
    void actionEventsAreProjectedOnceWithoutPrivatePayload() {
        SimulationWorld world = new SimulationWorld();
        WorldReplicationService service = new WorldReplicationService(world);
        List<Object> sent = new ArrayList<>();
        service.connect("client-a", new InterestContext("client-a", null, 0, Set.of()), sent::add);
        world.emitActionEvent("near", com.roleplay.engine.simulation.action.ActionType.SPEAK,
                Map.of("privateText", "must-not-replicate"));

        service.publishSnapshot(snapshot(1, List.of()));
        ReplicationFrame first = (ReplicationFrame) ((WorldReplicationService.Envelope) sent.getLast()).payload();
        assertEquals(1, first.events().size());
        assertEquals(Map.of("action", "SPEAK", "actorId", "near"), first.events().getFirst().payload());

        service.publishSnapshot(snapshot(2, List.of()));
        ReplicationFrame second = (ReplicationFrame) ((WorldReplicationService.Envelope) sent.getLast()).payload();
        assertTrue(second.events().isEmpty());
        service.close();
    }

    private SimulationWorld.WorldSnapshot snapshot(int tick, List<Map<String, Object>> agents) {
        return new SimulationWorld.WorldSnapshot(tick, agents, List.of(), System.currentTimeMillis(),
                "", false, "test", 1000, 1000);
    }

    private Map<String, Object> agent(String id, double x, double y, String secret) {
        return Map.of("agentName", id, "x", x, "y", y, "vx", 0, "vy", 0,
                "emotion", "neutral", "currentMessage", secret, "schedule", "private schedule");
    }
}
