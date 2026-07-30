package com.roleplay.engine.agent;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.llm.LLMClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An AI character that participates in the roleplay conversation.
 *
 * <p>Each Agent wraps a {@link Persona} and generates responses via {@link LLMClient}.
 * The key difference from Python: Agent.generate() returns a {@link CompletableFuture},
 * enabling true parallel execution across agents via Virtual Threads.
 *
 * <p>Maps from Python {@code core/agent.py → Agent}.
 */
public class Agent {

    private final Persona persona;
    private final String role;
    private final LLMClient llmClient;
    private volatile boolean isGenerating = false;

    public Agent(Persona persona, String role, LLMClient llmClient) {
        this.persona = persona;
        this.role = role;
        this.llmClient = llmClient;
    }

    public String getName() {
        return persona.getName();
    }

    public Persona getPersona() {
        return persona;
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    // ── Core generation (blocking + non-blocking variants) ─────

    /**
     * Build the full message list for the LLM call.
     *
     * <p>This replaces Python's {@code build_messages()} method.
     * Constructs system prompt, history, role-lock, track mode constraints,
     * and output rules into a single OpenAI-format message list.
     */
    public List<Message> buildContext(
            String sceneDescription,
            List<Message> history,
            String trackMode,
            List<String> allAgentNames,
            String summaryContext,
            Message sameRoundPeerOutput,
            String userInterjection) {

        List<Message> messages = new ArrayList<>();

        // 1. System prompt from persona
        String systemContent = persona.buildSystemPrompt();
        if (sceneDescription != null && !sceneDescription.isEmpty()) {
            systemContent += "\n\n【当前场景】\n" + sceneDescription;
        }
        if (summaryContext != null && !summaryContext.isEmpty()) {
            systemContent += "\n\n" + summaryContext;
        }
        systemContent += "\n\n【轨道模式】\n" + (trackMode != null ? trackMode : "merged");
        messages.add(new Message(Message.Role.SYSTEM, "system", systemContent));

        // 2. Conversation history
        if (history != null) {
            for (Message m : history) {
                if (m.getRole() == Message.Role.SYSTEM) continue;
                messages.add(m);
            }
        }

        // 3. User interjection (if any)
        if (userInterjection != null && !userInterjection.isEmpty()) {
            messages.add(new Message(Message.Role.USER, "主控", userInterjection));
        }

        return messages;
    }

    /**
     * Generate a response synchronously （blocking call, suitable for Virtual Threads）.
     *
     * <p>Unlike Python's async generator, this returns the complete string.
     * Streaming is handled at the SSE layer, not at the agent level.
     */
    public String generateSync(
            String systemPrompt,
            List<Message> history,
            String trackMode,
            List<String> allAgentNames,
            String summaryContext,
            Message sameRoundPeerOutput,
            String userInterjection) {

        isGenerating = true;
        try {
            List<Message> messages = buildContext(
                    systemPrompt, history, trackMode, allAgentNames,
                    summaryContext, sameRoundPeerOutput, userInterjection);

            return llmClient.callSync(messages);
        } finally {
            isGenerating = false;
        }
    }

    /**
     * Generate a response asynchronously （returns CompletableFuture）.
     *
     * <p>Used by {@link AgentExecutor} for parallel execution.
     */
    public CompletableFuture<String> generateAsync(
            String systemPrompt,
            List<Message> history,
            String trackMode,
            List<String> allAgentNames,
            String summaryContext,
            Message sameRoundPeerOutput,
            String userInterjection) {

        return CompletableFuture.supplyAsync(() ->
                generateSync(systemPrompt, history, trackMode, allAgentNames,
                        summaryContext, sameRoundPeerOutput, userInterjection)
        );
    }

    /**
     * Simplified generation for simulation module.
     * Generates a response given just a context string.
     */
    public String generateWithContext(String context) {
        return llmClient.callSync(List.of(
            new Message(Message.Role.SYSTEM, "system", persona.buildSystemPrompt()),
            new Message(Message.Role.USER, "user", context)
        ));
    }

    @Override
    public String toString() {
        return "Agent{" + getName() + "}";
    }
}
