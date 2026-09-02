package com.roleplay.engine.simulation.gameplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentGameplayStateTest {
    @Test
    void defaultsAreExtensibleAndAlwaysClamped() {
        AgentGameplayState state = new AgentGameplayState();
        assertEquals(100, state.value("health", -1));
        state.adjust("health", -250);
        assertEquals(0, state.value("health", -1));
        state.set("health", 500);
        assertEquals(100, state.value("health", -1));
        state.define("reputation.guild", "公会声望", 12, -100, 100, "点");
        assertEquals(12, state.value("reputation.guild", -1));
        assertThrows(IllegalArgumentException.class, () -> state.adjust("unknown", 1));
    }
}
