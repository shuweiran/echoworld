package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.navigation.portal.*;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.spatial.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class SpeechDecisionAndPerceptionTest {
    @Test
    void noOutputIsAControlProtocolAndVolumeIsRemovedFromVisibleText() {
        assertFalse(SpeechDecision.parse("<NO_OUTPUT>").speak());
        SpeechDecision decision = SpeechDecision.parse("小声些。【音量：LOW】");
        assertAll(() -> assertTrue(decision.speak()),
                () -> assertEquals(SpeechVolume.LOW, decision.volume()),
                () -> assertEquals("小声些。", decision.text()));
    }

    @Test
    void perceptionContainsOnlyAudiblePeersAndExplicitVolumeChangesRange() {
        AgentState self = new AgentState("我", 0, 0);
        AgentState near = new AgentState("近处", 20, 0);
        AgentState far = new AgentState("远处", 400, 0);
        SpatialGrid grid = new SpatialGrid(1000, 600, 100);
        grid.rebuild(List.of(self, near, far));
        HearingSystem hearing = new HearingSystem(grid);

        LocalPerceptionSnapshot snapshot = LocalPerceptionSnapshot.from(self, List.of(self, near, far), hearing);
        assertEquals(List.of("近处"), snapshot.peers().stream().map(LocalPerceptionSnapshot.Peer::name).toList());

        double normal = hearing.computeAudibility(List.of(self, near), Map.of()).getFirst().rawRange();
        double shout = hearing.computeAudibility(List.of(self, near), Map.of("我", SpeechVolume.SHOUT)).getFirst().rawRange();
        assertEquals(normal * SpeechVolume.SHOUT.multiplier(), shout, 0.0001);
    }

    @Test
    void hearingIsDirectionalForEachUtteranceVolume() {
        AgentState a = new AgentState("A", 0, 0);
        AgentState b = new AgentState("B", 90, 0);
        SpatialGrid grid = new SpatialGrid(1000, 600, 100);
        grid.rebuild(List.of(a, b));
        HearingSystem hearing = new HearingSystem(grid);
        assertTrue(hearing.canHear(a, b, SpeechVolume.SHOUT));
        assertFalse(hearing.canHear(b, a, SpeechVolume.WHISPER));
    }

    @Test
    void speechCommitDoesNotDeliverToListenerWhoLeavesWhileLlmIsPending() {
        Fixture f = fixture("A", 0, 0, "B", 20, 0);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        f.world.getState("B").setX(500);

        ConversationManager.SpeechDelivery delivery = resolve(f);
        assertAll(() -> assertTrue(delivery.actualListeners().isEmpty()),
                () -> assertTrue(f.world.getState("B").getVisibleMessages().isEmpty()));
    }

    @Test
    void speechCommitDeliversToListenerWhoEntersWhileLlmIsPending() {
        Fixture f = fixture("A", 0, 0, "B", 500, 0);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        f.world.getState("B").setX(20);

        ConversationManager.SpeechDelivery delivery = resolve(f);
        assertAll(() -> assertEquals(java.util.Set.of("B"), delivery.actualListeners()),
                () -> assertTrue(f.world.getState("B").getVisibleMessages().getFirst().contains("A：测试发言")));
    }

    @Test
    void speechCommitUsesSpeakersCurrentPositionNotGenerationPosition() {
        Fixture f = fixture("A", 0, 0, "B", 500, 0);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        f.world.getState("A").setX(480);

        assertEquals(java.util.Set.of("B"), resolve(f).actualListeners());
    }

    @Test
    void speechCommitRechecksWallAtDeliveryTime() {
        Fixture f = fixture("A", 0, 0, "B", 20, 0);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        f.world.setCustomObstacles(List.of(new Obstacle(Obstacle.Type.WALL, 8, -10, 4, 20, true, "隔墙")), "test-wall");

        assertTrue(resolve(f).actualListeners().isEmpty());
    }

    @Test
    void speechCommitUsesUtteranceVolumeRatherThanDefaultRange() {
        Fixture whisper = fixture("A", 0, 0, "B", 90, 0);
        enqueue(whisper, utterance("A", SpeechVolume.WHISPER));
        assertTrue(resolve(whisper).actualListeners().isEmpty());

        Fixture shout = fixture("A", 0, 0, "B", 90, 0);
        enqueue(shout, utterance("A", SpeechVolume.SHOUT));
        assertEquals(java.util.Set.of("B"), resolve(shout).actualListeners());
    }

    @Test
    void speechCommitResolvesEntireListenerSetAfterConcurrentMovement() {
        Fixture f = fixture("A", 0, 0, "B", 20, 0, "C", 20, 0, "D", 500, 0);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        f.world.getState("B").setX(500);
        f.world.getState("D").setX(20);

        assertEquals(java.util.Set.of("C", "D"), resolve(f).actualListeners());
    }

    @Test
    void speechCommitDropsListenerWhoChangesToSealedFloorWhilePending() {
        Fixture f = fixture("A", 0, 0, "B", 20, 0);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        setFloor(f.world.getState("B"), "f2", "s2");
        assertTrue(resolve(f).actualListeners().isEmpty());
    }

    @Test
    void speechCommitAddsListenerWhoReturnsToSpeakersFloorWhilePending() {
        Fixture f = fixture("A", 0, 0, "B", 20, 0);
        setFloor(f.world.getState("B"), "f2", "s2");
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        setFloor(f.world.getState("B"), "ground", "ground");
        assertEquals(java.util.Set.of("B"), resolve(f).actualListeners());
    }

    @Test
    void speechCommitUsesSpeakersCommitTimeFloor() {
        Fixture f = fixture("A", 0, 0, "B", 20, 0);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        setFloor(f.world.getState("A"), "f2", "s2");
        assertTrue(resolve(f).actualListeners().isEmpty());
    }

    @Test
    void speechCommitRechecksConnectorStateAtDelivery() {
        Fixture f = fixture("A", 0, 0, "B", 20, 0);
        setFloor(f.world.getState("B"), "f2", "s2");
        SemanticPortal stairs = stairs();
        ConcurrentHashMap<String, PortalRuntimeState> states = new ConcurrentHashMap<>();
        states.put("stairs", PortalRuntimeState.available("stairs"));
        f.world.getHearingSystem().setSemanticPortals(List.of(stairs), states);
        enqueue(f, utterance("A", SpeechVolume.NORMAL));
        states.put("stairs", new PortalRuntimeState("stairs", PortalRuntimeState.Availability.DISABLED, 1, "closed"));
        assertTrue(resolve(f).actualListeners().isEmpty());
    }

    private ConversationManager.PendingUtterance utterance(String speaker, SpeechVolume volume) {
        return new ConversationManager.PendingUtterance(speaker, "测试发言", volume, 100, 200);
    }

    private static void setFloor(AgentState state, String floor, String surface) {
        state.getSpatial().setNavLocation(new NavLocation(surface, floor,
                new Vec3(state.getX(), 0, state.getY()), -1L));
    }

    private static SemanticPortal stairs() {
        return new SemanticPortal("stairs", SemanticPortal.Kind.STAIRS,
                new PortalEndpoint("ground", "ground", new Vec3(10, 0, 10)),
                new PortalEndpoint("f2", "s2", new Vec3(10, 3, 10)), true, 10, "", java.util.Set.of("acoustic"));
    }

    private ConversationManager.SpeechDelivery resolve(Fixture fixture) {
        assertEquals(1, fixture.manager.resolvePendingSpeechCommits());
        assertNotNull(fixture.delivery.get());
        return fixture.delivery.get();
    }

    private void enqueue(Fixture fixture, ConversationManager.PendingUtterance utterance) {
        fixture.manager.enqueueSpeechCommit(utterance, fixture.delivery::set);
    }

    private Fixture fixture(Object... agents) {
        SimulationWorld world = new SimulationWorld();
        world.setCustomObstacles(List.of(), "test-open");
        for (int i = 0; i < agents.length; i += 3) {
            String name = (String) agents[i];
            world.registerAgent(new Agent(new Persona(name, "测试人格"), "test", null),
                    ((Number) agents[i + 1]).doubleValue(), ((Number) agents[i + 2]).doubleValue(), 200, 60);
        }
        ConversationManager manager = new ConversationManager();
        manager.init(world, null, world::getAgent, world::getWorldNarration);
        return new Fixture(world, manager, new AtomicReference<>());
    }

    private record Fixture(SimulationWorld world, ConversationManager manager,
                           AtomicReference<ConversationManager.SpeechDelivery> delivery) {}
}
