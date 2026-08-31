package com.roleplay.engine.simulation.agentruntime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryStoreTest {
    @Test
    void memoryRequiresMatchingPerceptionProvenanceAndPerceivedSubject() {
        AgentMemoryStore store = new AgentMemoryStore("alice", 4);
        PerceptionSnapshot perception = perception(5, 10, Set.of("door"));
        store.rememberObserved(fact("seen-door", MemoryKind.SPATIAL, "door", 0.8, 0), perception);
        assertEquals(1, store.size());
        assertThrows(IllegalArgumentException.class, () -> store.rememberObserved(
                fact("invented", MemoryKind.EPISODIC, "secret", 1, 0), perception));
    }

    @Test
    void recallIsBoundedDeterministicAndExpiresOldFacts() {
        AgentMemoryStore store = new AgentMemoryStore("alice", 2);
        PerceptionSnapshot perception = perception(5, 10, Set.of("a", "b", "c"));
        store.rememberObserved(fact("low", MemoryKind.EPISODIC, "a", 0.1, 0), perception);
        store.rememberObserved(fact("high", MemoryKind.EPISODIC, "b", 0.9, 0), perception);
        store.rememberObserved(fact("expiring", MemoryKind.EPISODIC, "c", 0.8, 10), perception);

        assertEquals(2, store.size());
        assertEquals("high", store.recall(MemoryKind.EPISODIC, 10, 1).getFirst().id());
        assertEquals(1, store.recall(MemoryKind.EPISODIC, 11, 5).size());
        assertTrue(store.workingSnapshot(11).facts().containsKey("memory.high.subjectId"));
    }

    private PerceptionSnapshot perception(long worldVersion, long tick, Set<String> entities) {
        return new PerceptionSnapshot("alice", worldVersion, worldVersion, tick, entities, Map.of(), Map.of());
    }

    private MemoryFact fact(String id, MemoryKind kind, String subject, double salience, long expiresAt) {
        return new MemoryFact(id, kind, subject, id, Map.of("location", "hall"),
                5, 10, salience, expiresAt);
    }
}
