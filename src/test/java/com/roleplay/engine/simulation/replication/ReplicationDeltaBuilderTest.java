package com.roleplay.engine.simulation.replication;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplicationDeltaBuilderTest {
    private static final int VERSION = ReplicationProtocol.CURRENT_VERSION;
    private final ReplicationDeltaBuilder builder = new ReplicationDeltaBuilder(new DefaultInterestPolicy());
    private final InterestContext alice = new InterestContext(
            "alice", cell(0, 0), 1, Set.of("quest:main"));

    @Test
    void initialFrameCreatesVisibleEntitiesInStableIdOrder() {
        ReplicaEntity beta = entity("beta", 1, cell(1, 0), PerceptionScope.publicScope(), Set.of(), 10);
        ReplicaEntity alpha = entity("alpha", 1, cell(0, 0), PerceptionScope.publicScope(), Set.of(), 20);

        var result = builder.build(Map.of(), List.of(beta, alpha), List.of(), alice,
                VERSION, 1, 50, 1_000);

        assertEquals(List.of("alpha", "beta"), result.frame().creates().stream()
                .map(create -> create.entity().entityId()).toList());
        assertEquals(1, result.frame().sequence());
        assertEquals(50, result.frame().serverTick());
        assertEquals(VERSION, result.frame().protocolVersion());
        assertEquals(List.of("alpha", "beta"), result.visibleState().keySet().stream().toList());
    }

    @Test
    void deltaContainsCreateUpdateWorldRemoveInterestRemoveAndFilteredEvents() {
        ReplicaEntity alphaOld = entity("alpha", 1, cell(0, 0), PerceptionScope.publicScope(), Set.of(), 20);
        ReplicaEntity betaOld = entity("beta", 4, cell(0, 1), PerceptionScope.publicScope(), Set.of(), 20);
        ReplicaEntity walkerOld = entity("walker", 2, cell(1, 1), PerceptionScope.publicScope(), Set.of(), 20);
        Map<String, ReplicaEntity> previous = Map.of(
                "alpha", alphaOld, "beta", betaOld, "walker", walkerOld);

        ReplicaEntity alphaNew = entity("alpha", 2, cell(0, 0), PerceptionScope.publicScope(), Set.of(), 19);
        ReplicaEntity created = entity("created", 1, cell(1, 0), PerceptionScope.publicScope(), Set.of(), 30);
        ReplicaEntity walkerFar = entity("walker", 3, cell(9, 9), PerceptionScope.publicScope(), Set.of(), 20);
        ReplicaEntity hidden = entity("hidden", 1, cell(0, 0),
                PerceptionScope.restrictedTo(Set.of("bob")), Set.of("quest:main"), 99);
        ReplicationEvent visibleEvent = event("visible", PerceptionScope.publicScope());
        ReplicationEvent hiddenEvent = event("hidden-event", PerceptionScope.restrictedTo(Set.of("bob")));

        var result = builder.build(previous, List.of(hidden, walkerFar, created, alphaNew),
                List.of(hiddenEvent, visibleEvent), alice, VERSION, 2, 51, 1_050);

        assertEquals(List.of("created"), idsFromCreates(result.frame()));
        assertEquals(List.of("alpha"), result.frame().updates().stream()
                .map(update -> update.entity().entityId()).toList());
        assertEquals(19, result.frame().updates().getFirst().entity().state().get("hp"));
        assertEquals(List.of("beta", "walker"), result.frame().removes().stream()
                .map(ReplicationFrame.Remove::entityId).toList());
        assertEquals(ReplicationFrame.RemoveReason.REMOVED_FROM_WORLD,
                result.frame().removes().get(0).reason());
        assertEquals(ReplicationFrame.RemoveReason.LEFT_INTEREST,
                result.frame().removes().get(1).reason());
        assertEquals(List.of("visible"), result.frame().events().stream()
                .map(ReplicationEvent::eventId).toList());
        assertFalse(result.visibleState().containsKey("hidden"));
        assertFalse(result.frame().toString().contains("vault-code"));
    }

    @Test
    void fullSnapshotNeverContainsNonPerceivableEntitiesOrEvents() {
        ReplicaEntity publicEntity = entity("public", 1, cell(0, 0),
                PerceptionScope.publicScope(), Set.of(), 100);
        ReplicaEntity secretEntity = new ReplicaEntity("secret", "CLUE", 1, cell(0, 0), "",
                PerceptionScope.restrictedTo(Set.of("bob")), Set.of("quest:main"),
                Map.of("secret", "vault-code"));
        ReplicationEvent hiddenEvent = new ReplicationEvent("secret-event", "PRIVATE", 2,
                null, true, PerceptionScope.restrictedTo(Set.of("bob")), Set.of("quest:main"),
                Map.of("secret", "vault-code"));

        FullSnapshot snapshot = builder.fullSnapshot(List.of(secretEntity, publicEntity),
                List.of(hiddenEvent), alice, VERSION, 7, 90, 2_000);

        assertEquals(List.of("public"), snapshot.entities().stream().map(ReplicaEntity::entityId).toList());
        assertEquals(List.of(), snapshot.events());
        assertFalse(snapshot.toString().contains("vault-code"));
    }

    @Test
    void shuffledInputProducesByteOrderEquivalentDomainFrame() {
        ReplicaEntity a = entity("a", 1, cell(0, 0), PerceptionScope.publicScope(), Set.of(), 1);
        ReplicaEntity b = entity("b", 1, cell(1, 0), PerceptionScope.publicScope(), Set.of(), 2);
        ReplicationEvent first = new ReplicationEvent("a-event", "MOVE", 4, cell(0, 0), false,
                PerceptionScope.publicScope(), Set.of(), Map.of("x", 1));
        ReplicationEvent second = new ReplicationEvent("b-event", "MOVE", 4, cell(0, 0), false,
                PerceptionScope.publicScope(), Set.of(), Map.of("x", 2));
        HashMap<String, ReplicaEntity> priorA = new HashMap<>();
        priorA.put("b", b);
        priorA.put("a", a);
        HashMap<String, ReplicaEntity> priorB = new HashMap<>();
        priorB.put("a", a);
        priorB.put("b", b);

        var left = builder.build(priorA, List.of(b, a), List.of(second, first), alice,
                VERSION, 8, 100, 3_000);
        var right = builder.build(priorB, List.of(a, b), List.of(first, second), alice,
                VERSION, 8, 100, 3_000);

        assertEquals(left.frame(), right.frame());
        assertEquals(left.visibleState(), right.visibleState());
        assertEquals(List.of("a-event", "b-event"), left.frame().events().stream()
                .map(ReplicationEvent::eventId).toList());
    }

    @Test
    void duplicateAuthoritativeEntityIdsAreRejectedDeterministically() {
        ReplicaEntity first = entity("same", 1, cell(0, 0), PerceptionScope.publicScope(), Set.of(), 1);
        ReplicaEntity second = entity("same", 2, cell(0, 0), PerceptionScope.publicScope(), Set.of(), 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> builder.build(Map.of(), List.of(first, second), List.of(), alice,
                        VERSION, 1, 1, 1));
        assertEquals("duplicate entityId: same", error.getMessage());
    }

    @Test
    void duplicateEventIdsAreRejectedDeterministically() {
        ReplicationEvent first = event("same-event", PerceptionScope.publicScope());
        ReplicationEvent second = new ReplicationEvent("same-event", "OTHER", 52,
                null, true, PerceptionScope.publicScope(), Set.of(), Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> builder.build(Map.of(), List.of(), List.of(first, second), alice,
                        VERSION, 1, 1, 1));
        assertEquals("duplicate eventId: same-event", error.getMessage());
    }

    private List<String> idsFromCreates(ReplicationFrame frame) {
        return frame.creates().stream().map(create -> create.entity().entityId()).toList();
    }

    private ReplicaEntity entity(String id, long revision, SpatialCell cell,
                                 PerceptionScope scope, Set<String> tags, int hp) {
        return new ReplicaEntity(id, "AGENT", revision, cell, "", scope, tags, Map.of("hp", hp));
    }

    private ReplicationEvent event(String id, PerceptionScope scope) {
        return new ReplicationEvent(id, "CLUE", 51, null, true, scope,
                Set.of("quest:main"), Map.of("text", id));
    }

    private SpatialCell cell(int x, int z) {
        return new SpatialCell("town", "ground", x, z);
    }
}
