package com.roleplay.engine.simulation.agentruntime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Short-lived cognition state; knowledge never bypasses current perception checks. */
public record WorkingMemory(long version,
                            Map<String, Object> facts,
                            List<FailureFeedback> recentFailures) {
    public WorkingMemory {
        if (version < 0) throw new IllegalArgumentException("memory version must be non-negative");
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        recentFailures = recentFailures == null ? List.of() : List.copyOf(recentFailures);
    }

    public static WorkingMemory empty() {
        return new WorkingMemory(0, Map.of(), List.of());
    }

    public Optional<String> stringFact(String key) {
        Object value = facts.get(key);
        return value instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    public WorkingMemory withFailures(List<FailureFeedback> failures) {
        return new WorkingMemory(version, facts, failures);
    }
}
