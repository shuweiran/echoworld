package com.roleplay.engine.hooks;

import com.roleplay.engine.service.RouterService.RoundResult;

import java.util.List;
import java.util.Map;

/**
 * Lifecycle hook interface for the RouterService round pipeline.
 *
 * <p>All methods have default empty implementations, so implementors only
 * override the events they care about. Hooks are called in registration
 * order via a CopyOnWriteArrayList for safe concurrent iteration.
 *
 * <p>Example use cases:
 * <ul>
 *   <li>WebSearchHook — inject live web-search results into agent context</li>
 *   <li>MetricsHook — record latency/token-usage per round phase</li>
 *   <li>AuditHook — log every round decision for offline review</li>
 *   <li>SSEHook — push phase-change events to connected clients</li>
 * </ul>
 */
public interface RoundHook {

    /**
     * Called at the very start of {@code runRound()}, before any
     * Arbiter or agent work.
     *
     * @param userInput  the raw user input (may be null for auto-rounds)
     * @param mode       the current mode (free, protagonist, werewolf, script)
     * @param roundCount the round number about to execute
     */
    default void beforeRound(String userInput, String mode, int roundCount) {}

    /**
     * Called after the Arbiter has configured tracks for this round.
     *
     * @param tracks    the list of track maps produced by the Arbiter
     * @param reasoning the Arbiter's textual reasoning for the config
     */
    default void afterTrackConfig(List<Map<String, Object>> tracks, String reasoning) {}

    /**
     * Called just before building a single agent's LLM context.
     * Implementations can mutate {@code contextParts} to inject additional
     * context (e.g. web-search results, lorebook entries, user reminders).
     *
     * @param agentName    the agent about to generate
     * @param trackMode    the track mode (merged, weak, isolated)
     * @param contextParts the mutable list of context strings that will
     *                     be joined to form the final agent prompt
     */
    default void beforeAgentContext(String agentName, String trackMode, List<String> contextParts) {}

    /**
     * Called after each individual agent has produced output.
     *
     * @param agentName the agent that generated
     * @param output    the raw text output from the LLM
     * @param success   true if the agent completed without error
     * @param durationMs elapsed wall-clock time for this agent's generation
     */
    default void afterAgentOutput(String agentName, String output, boolean success, long durationMs) {}

    /**
     * Called at the very end of a successful round, after persistence.
     *
     * @param result the complete RoundResult for this round
     */
    default void afterRound(RoundResult result) {}

    /**
     * Called when any phase of the round throws an unhandled exception.
     * Implementations should log, notify, or initiate circuit-breaking.
     *
     * @param phase a human-readable label for where the error occurred
     *              (e.g. "track_config", "agent_execution", "integration")
     * @param error the exception that was thrown
     */
    default void onRoundError(String phase, Exception error) {}
}
