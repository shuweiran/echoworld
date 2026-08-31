package com.roleplay.engine.simulation.world;

import com.roleplay.engine.simulation.AgentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldEntityContractTest {
    @Test
    void agentStateProjectsIntoTypedWorldEntityWithoutBreakingLegacyCoordinates() {
        AgentState state = new AgentState("alice", 10, 20);
        WorldEntity entity = state;

        assertEquals("alice", entity.id());
        assertEquals(EntityKind.AGENT, entity.kind());
        assertEquals(10, entity.transform().position().x());
        assertEquals(20, entity.transform().position().z());
        assertEquals(state.getSpatial().authority(), entity.controlAuthority());
    }
}
