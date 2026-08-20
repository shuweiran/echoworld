package com.roleplay.engine.simulation.social;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SocialStateTest {
    @Test
    void conversationCreatesPersistentRelationshipsAndMemories() {
        SocialState state = new SocialState();
        state.registerAgent("A");
        state.registerAgent("B");
        Map<String, Object> conversation = new LinkedHashMap<>();
        conversation.put("group", "A+B");
        conversation.put("mode", "DYAD");
        conversation.put("round", 1);
        conversation.put("A", "你好，我们一起去广场吧");
        conversation.put("B", "好，我愿意帮忙");

        state.recordConversation(conversation);

        Map<String, Object> a = state.forAgent("A");
        assertTrue(((Map<?, ?>) a.get("relationships")).containsKey("B"));
        assertEquals(1, ((List<?>) a.get("memories")).size());
        assertEquals("social_contact", ((Map<?, ?>) ((List<?>) state.toMap().get("events")).get(0)).get("type"));
    }

    @Test
    void negativeConversationChangesConflictAndRemovingAgentCleansSocialState() {
        SocialState state = new SocialState();
        state.registerAgent("A");
        state.registerAgent("B");
        state.recordConversation(Map.of("group", "A+B", "A", "我不信你，反驳！", "B", "你在质疑我"));
        Map<?, ?> relation = (Map<?, ?>) ((Map<?, ?>) state.forAgent("A").get("relationships")).get("B");
        assertTrue((double) relation.get("conflict") > 0);

        state.setGoal("A", "寻找 B", "B");
        state.removeAgent("B");
        Map<String, Object> all = state.toMap();
        assertFalse(((Map<?, ?>) all.get("relationships")).containsKey("B"));
        assertFalse(((Map<?, ?>) ((Map<?, ?>) all.get("relationships")).get("A")).containsKey("B"));
    }
}
