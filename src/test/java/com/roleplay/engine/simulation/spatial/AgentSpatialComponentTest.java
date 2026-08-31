package com.roleplay.engine.simulation.spatial;

import com.roleplay.engine.simulation.AgentState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSpatialComponentTest {

    @Test
    @SuppressWarnings("unchecked")
    void legacyGroundCoordinatesProjectFromTransform3d() {
        AgentState state = new AgentState("A", 120, 240);
        state.getSpatial().setPosition(200, 3.2, 400);
        state.setVx(10);
        state.setVy(20);

        assertEquals(200, state.getX());
        assertEquals(400, state.getY());
        Map<String, Object> transform = (Map<String, Object>) state.toMap().get("transform");
        Map<String, Object> position = (Map<String, Object>) transform.get("position");
        assertEquals(200.0, position.get("x"));
        assertEquals(3.2, position.get("y"));
        assertEquals(400.0, position.get("z"));
        assertEquals("ground", ((Map<String, Object>) state.toMap().get("navLocation")).get("floorId"));
    }
}
