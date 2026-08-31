package com.roleplay.engine.simulation.agentruntime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveLodPolicyTest {
    @Test
    void classifiesDistanceButKeepsStoryCriticalAgentsFull() {
        CognitiveLodPolicy policy = new CognitiveLodPolicy(20, 80, 10, 100);

        assertEquals(CognitiveLod.FULL, policy.classify(10, false));
        assertEquals(CognitiveLod.REDUCED, policy.classify(50, false));
        assertEquals(CognitiveLod.MACRO, policy.classify(120, false));
        assertEquals(CognitiveLod.FULL, policy.classify(120, true));
    }

    @Test
    void reducedAndMacroAgentsThinkAtLowerDeterministicFrequency() {
        CognitiveLodPolicy policy = new CognitiveLodPolicy(20, 80, 10, 100);

        assertFalse(policy.shouldThink(CognitiveLod.REDUCED, 9, 0));
        assertTrue(policy.shouldThink(CognitiveLod.REDUCED, 10, 0));
        assertFalse(policy.shouldThink(CognitiveLod.MACRO, 99, 0));
        assertTrue(policy.shouldThink(CognitiveLod.MACRO, 100, 0));
    }
}
