package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.track.SpatialTrackResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationManagerDistanceTest {

    @Test
    void fallbackDistanceStartsAtTrackResolverDefault() {
        ConversationManager manager = new ConversationManager();

        assertEquals(SpatialTrackResolver.DEFAULT_CONVERSATION_DISTANCE,
                manager.getConversationDistance());
    }

    @Test
    void fallbackDistanceUsesConfiguredValueAndRejectsInvalidValue() {
        ConversationManager manager = new ConversationManager();

        manager.setConversationDistance(96.0);
        assertEquals(96.0, manager.getConversationDistance());

        manager.setConversationDistance(0);
        assertEquals(SpatialTrackResolver.DEFAULT_CONVERSATION_DISTANCE,
                manager.getConversationDistance());
    }
}
