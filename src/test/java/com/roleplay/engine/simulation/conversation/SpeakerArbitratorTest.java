package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeakerArbitratorTest {
    @Test
    void directQuestionWinsThenADeclinedOpportunityFallsBackToAnotherCandidate() {
        AgentState a = new AgentState("A", 0, 0);
        AgentState b = new AgentState("B", 1, 0);
        AgentState c = new AgentState("C", 2, 0);
        ConversationGroup group = new ConversationGroup("g", ConversationMode.GROUP_DISCUSSION, List.of(a, b, c));
        group.recordTurn("A", "B，你拿了吗？");
        Map<String, Map<String, String>> contexts = new LinkedHashMap<>();
        contexts.put("B", Map.of("role", "active"));
        contexts.put("C", Map.of("role", "listener"));
        SpeakerArbitrator arbitrator = new SpeakerArbitrator();

        assertEquals("B", arbitrator.select(group, contexts));
        group.markOpportunity("B"); // B 返回 SILENT，不记录为一次发言
        assertEquals("C", arbitrator.select(group, contexts));
    }
}
