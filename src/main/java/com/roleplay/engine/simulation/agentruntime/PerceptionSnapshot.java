package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable local knowledge used for one cognition decision. */
public record PerceptionSnapshot(String agentId,
                                 long worldVersion,
                                 long perceptionVersion,
                                 long capturedAtTick,
                                 Set<String> perceivedEntityIds,
                                 Map<String, Set<ActionType>> affordances,
                                 Map<String, Object> facts) {
    public PerceptionSnapshot {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        if (worldVersion < 0 || perceptionVersion < 0 || capturedAtTick < 0) {
            throw new IllegalArgumentException("snapshot versions and tick must be non-negative");
        }
        perceivedEntityIds = perceivedEntityIds == null ? Set.of() : Set.copyOf(perceivedEntityIds);
        Map<String, Set<ActionType>> copied = new LinkedHashMap<>();
        if (affordances != null) {
            affordances.forEach((target, actions) -> copied.put(target, actions == null ? Set.of() : Set.copyOf(actions)));
        }
        affordances = Map.copyOf(copied);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public boolean perceives(String entityId) {
        return entityId != null && !entityId.isBlank() && perceivedEntityIds.contains(entityId);
    }

    public boolean offers(String entityId, ActionType action) {
        return action != null && affordances.getOrDefault(entityId, Set.of()).contains(action);
    }
}
