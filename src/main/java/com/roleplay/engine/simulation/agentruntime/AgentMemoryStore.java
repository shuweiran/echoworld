package com.roleplay.engine.simulation.agentruntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded deterministic memory store; remembered targets still require current perception to act on. */
public final class AgentMemoryStore {
    private final String agentId;
    private final int capacity;
    private final Map<String, MemoryFact> facts = new LinkedHashMap<>();
    private long version;

    public AgentMemoryStore(String agentId, int capacity) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.agentId = agentId;
        this.capacity = capacity;
    }

    public synchronized void rememberObserved(MemoryFact fact, PerceptionSnapshot observation) {
        if (fact == null || observation == null) throw new IllegalArgumentException("fact and observation required");
        if (!agentId.equals(observation.agentId())) throw new IllegalArgumentException("observation belongs to another agent");
        if (fact.observedWorldVersion() != observation.worldVersion()
                || fact.observedAtTick() != observation.capturedAtTick()) {
            throw new IllegalArgumentException("memory provenance does not match perception snapshot");
        }
        if (fact.kind() != MemoryKind.SEMANTIC && !fact.subjectId().isBlank()
                && !observation.perceivedEntityIds().contains(fact.subjectId())) {
            throw new IllegalArgumentException("cannot remember an unperceived subject");
        }
        facts.put(fact.id(), fact);
        trim();
        version++;
    }

    public synchronized List<MemoryFact> recall(MemoryKind kind, long tick, int limit) {
        if (kind == null || tick < 0 || limit < 1) throw new IllegalArgumentException("invalid recall query");
        pruneExpired(tick);
        return facts.values().stream().filter(fact -> fact.kind() == kind)
                .sorted(Comparator.comparingDouble(MemoryFact::salience).reversed()
                        .thenComparing(Comparator.comparingLong(MemoryFact::observedAtTick).reversed())
                        .thenComparing(MemoryFact::id))
                .limit(limit).toList();
    }

    public synchronized WorkingMemory workingSnapshot(long tick) {
        pruneExpired(tick);
        Map<String, Object> projection = new LinkedHashMap<>();
        facts.values().stream().sorted(Comparator.comparing(MemoryFact::id)).forEach(fact -> {
            projection.put("memory." + fact.id() + ".kind", fact.kind().name());
            projection.put("memory." + fact.id() + ".subjectId", fact.subjectId());
            projection.put("memory." + fact.id() + ".summary", fact.summary());
            fact.facts().forEach((key, value) -> projection.put("memory." + fact.id() + "." + key, value));
        });
        return new WorkingMemory(version, projection, List.of());
    }

    public synchronized int size() { return facts.size(); }

    private void trim() {
        if (facts.size() <= capacity) return;
        List<MemoryFact> evictionOrder = new ArrayList<>(facts.values());
        evictionOrder.sort(Comparator.comparingDouble(MemoryFact::salience)
                .thenComparingLong(MemoryFact::observedAtTick).thenComparing(MemoryFact::id));
        while (facts.size() > capacity) facts.remove(evictionOrder.removeFirst().id());
    }

    private void pruneExpired(long tick) {
        if (facts.values().removeIf(fact -> fact.expiredAt(tick))) version++;
    }
}
