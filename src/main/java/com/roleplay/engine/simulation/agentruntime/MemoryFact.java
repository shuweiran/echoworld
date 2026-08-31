package com.roleplay.engine.simulation.agentruntime;

import java.util.Map;

/** Immutable remembered fact with authoritative observation provenance. */
public record MemoryFact(String id,
                         MemoryKind kind,
                         String subjectId,
                         String summary,
                         Map<String, Object> facts,
                         long observedWorldVersion,
                         long observedAtTick,
                         double salience,
                         long expiresAtTick) {
    public MemoryFact {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("memory id required");
        if (kind == null) throw new IllegalArgumentException("memory kind required");
        subjectId = subjectId == null ? "" : subjectId;
        summary = summary == null ? "" : summary;
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        if (observedWorldVersion < 0 || observedAtTick < 0 || expiresAtTick < 0) {
            throw new IllegalArgumentException("memory versions/ticks must be non-negative");
        }
        if (!Double.isFinite(salience) || salience < 0 || salience > 1) {
            throw new IllegalArgumentException("salience must be between 0 and 1");
        }
    }

    public boolean expiredAt(long tick) { return expiresAtTick > 0 && tick > expiresAtTick; }
}
