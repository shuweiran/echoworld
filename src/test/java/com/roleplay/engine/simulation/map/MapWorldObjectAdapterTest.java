package com.roleplay.engine.simulation.map;

import com.roleplay.engine.simulation.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapWorldObjectAdapterTest {
    @Test
    void decorBecomesAuthoritativeWorldObjectWithAffordances() {
        Map<String, Object> map = new LinkedHashMap<>(BspMapGenerator.generate(BspMapGenerator.Options.defaults(83L)));
        Map<String, Object> first = new LinkedHashMap<>((Map<String, Object>) ((List<?>) map.get("decor")).getFirst());
        first.put("id", "field-note");
        first.put("type", "note");
        first.put("portable", true);
        first.put("useEffects", Map.of("insight", 1));
        map.put("decor", List.of(first));

        var adapted = MapWorldDefinitionAdapter.adapt(map);
        assertEquals(1, adapted.worldObjects().size());
        var object = adapted.worldObjects().getFirst();
        assertEquals("field-note", object.id());
        assertTrue(object.affordances().containsKey(ActionType.PICK_UP));
        assertTrue(object.affordances().containsKey(ActionType.PUT_DOWN));
        assertTrue(object.affordances().containsKey(ActionType.USE));
        assertEquals(Boolean.TRUE, object.properties().get("portable"));
    }
}
