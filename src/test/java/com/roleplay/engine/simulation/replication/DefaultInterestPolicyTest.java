package com.roleplay.engine.simulation.replication;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultInterestPolicyTest {
    private final DefaultInterestPolicy policy = new DefaultInterestPolicy();
    private final InterestContext alice = new InterestContext(
            "alice", new SpatialCell("town", "ground", 10, 10), 2, Set.of("quest:bell"));

    @Test
    void perceptionBoundaryCannotBeOverriddenBySpatialOrNarrativeInterest() {
        ReplicaEntity secret = entity("secret", new SpatialCell("town", "ground", 10, 10), "",
                PerceptionScope.restrictedTo(Set.of("bob")), Set.of("quest:bell"));
        ReplicationEvent hiddenGlobal = new ReplicationEvent("event-secret", "PRIVATE_CLUE", 8,
                null, true, PerceptionScope.restrictedTo(Set.of("bob")), Set.of("quest:bell"),
                Map.of("clue", "vault-code"));

        assertEquals(InterestPolicy.Reason.DENIED_BY_PERCEPTION, policy.evaluate(secret, alice).reason());
        assertFalse(policy.shouldReplicate(secret, alice));
        assertEquals(InterestPolicy.Reason.DENIED_BY_PERCEPTION,
                policy.evaluate(hiddenGlobal, alice).reason());
        assertFalse(policy.shouldReplicate(hiddenGlobal, alice));
    }

    @Test
    void ownershipSpatialCellAndNarrativeRelevanceAreIndependentInterestPaths() {
        ReplicaEntity owned = entity("owned", new SpatialCell("remote", "roof", 99, 99), "alice",
                PerceptionScope.nobody(), Set.of());
        ReplicaEntity nearby = entity("nearby", new SpatialCell("town", "ground", 12, 9), "",
                PerceptionScope.publicScope(), Set.of());
        ReplicaEntity narrative = entity("narrative", new SpatialCell("remote", "roof", 99, 99), "",
                PerceptionScope.publicScope(), Set.of("quest:bell"));

        assertEquals(InterestPolicy.Reason.OWNERSHIP, policy.evaluate(owned, alice).reason());
        assertEquals(InterestPolicy.Reason.SPATIAL_CELL, policy.evaluate(nearby, alice).reason());
        assertEquals(InterestPolicy.Reason.NARRATIVE_RELEVANCE,
                policy.evaluate(narrative, alice).reason());
        assertTrue(policy.shouldReplicate(owned, alice));
        assertTrue(policy.shouldReplicate(nearby, alice));
        assertTrue(policy.shouldReplicate(narrative, alice));
    }

    @Test
    void spatialInterestNeverCrossesFloorOrZone() {
        ReplicaEntity otherFloor = entity("upstairs", new SpatialCell("town", "upper", 10, 10), "",
                PerceptionScope.publicScope(), Set.of());
        ReplicaEntity otherZone = entity("harbor", new SpatialCell("harbor", "ground", 10, 10), "",
                PerceptionScope.publicScope(), Set.of());

        assertEquals(InterestPolicy.Reason.OUT_OF_INTEREST, policy.evaluate(otherFloor, alice).reason());
        assertEquals(InterestPolicy.Reason.OUT_OF_INTEREST, policy.evaluate(otherZone, alice).reason());
    }

    @Test
    void perceivableGlobalEventIsIncludedWithoutSpatialFocus() {
        InterestContext observer = new InterestContext("director", null, 0, Set.of());
        ReplicationEvent announcement = new ReplicationEvent("e1", "ANNOUNCEMENT", 12,
                null, true, PerceptionScope.publicScope(), Set.of(), Map.of("text", "storm"));

        assertEquals(InterestPolicy.Reason.GLOBAL_EVENT, policy.evaluate(announcement, observer).reason());
    }

    private ReplicaEntity entity(String id, SpatialCell cell, String owner,
                                 PerceptionScope scope, Set<String> tags) {
        return new ReplicaEntity(id, "AGENT", 1, cell, owner, scope, tags, Map.of("hp", 100));
    }
}
