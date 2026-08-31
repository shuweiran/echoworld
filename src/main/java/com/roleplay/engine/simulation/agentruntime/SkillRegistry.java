package com.roleplay.engine.simulation.agentruntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Closed registry: planners may select skills, never invent them. */
public final class SkillRegistry {
    private final Map<String, SkillDefinition> definitions = new LinkedHashMap<>();

    public void register(SkillDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("skill definition required");
        if (definitions.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException("duplicate skill id: " + definition.id());
        }
    }

    public Optional<SkillDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public List<SkillDefinition> definitions() {
        List<SkillDefinition> ordered = new ArrayList<>(definitions.values());
        ordered.sort(java.util.Comparator.comparing(SkillDefinition::id));
        return List.copyOf(ordered);
    }

    public int size() {
        return definitions.size();
    }
}
