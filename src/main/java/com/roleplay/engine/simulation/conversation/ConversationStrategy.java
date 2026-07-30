package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.llm.LLMClient;
import java.util.Map;

public interface ConversationStrategy {

    ConversationMode supportedMode();

    void prepareContext(ConversationGroup group, Map<String, Map<String, String>> agentContexts);

    void processResults(ConversationGroup group, Map<String, String> agentResponses, LLMClient llmClient);

    default boolean shouldContinue(ConversationGroup group) {
        return group.getRoundCount() < 8 && group.idleMs() < 30_000;
    }
}
