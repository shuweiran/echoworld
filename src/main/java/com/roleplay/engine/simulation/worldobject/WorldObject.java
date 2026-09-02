package com.roleplay.engine.simulation.worldobject;

import com.roleplay.engine.simulation.action.ActionType;
import com.roleplay.engine.simulation.spatial.Transform3D;

import java.util.Map;
import java.util.Set;

/** Semantic world entity. GLB/Mesh references are presentation data, not behavior. */
public record WorldObject(String id,
                          String type,
                          Transform3D transform,
                          Map<ActionType, AffordanceDefinition> affordances,
                          Set<String> tags,
                          Map<String, Object> properties) {
    public WorldObject(String id, String type, Transform3D transform,
                       Map<ActionType, AffordanceDefinition> affordances, Set<String> tags) {
        this(id, type, transform, affordances, tags, Map.of());
    }

    public WorldObject {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("world object id required");
        type = type == null ? "OBJECT" : type;
        affordances = affordances == null ? Map.of() : Map.copyOf(affordances);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
