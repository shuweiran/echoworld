package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.track.EavesdropSummarizer;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Phase 2 Track-fusion conversation strategy — the unified replacement for
 * {@link GroupStrategy} / {@link DebateStrategy}.
 *
 * <p>Reads {@link ConversationGroup#getTrackAssignments()} (computed at group
 * creation by {@code SpatialTrackResolver}) and builds per-agent context
 * according to each member's {@link Track.Mode}:
 *
 * <table>
 *   <caption>TrackMode → context visibility rules</caption>
 *   <tr><th>Track.Mode</th><th>Context content</th><th>processResults</th></tr>
 *   <tr><td>MERGED</td><td>full chat history + member roster</td><td>record turn, update emotion/message</td></tr>
 *   <tr><td>WEAK</td><td>eavesdrop summary only (no full content)</td><td>record brief reaction, update emotion/message</td></tr>
 *   <tr><td>ISOLATED</td><td>alone/outside scene, zero conversation content</td><td>inner monologue: message only, no group turn</td></tr>
 *   <tr><td>(no assignment)</td><td>fallback → {@link GroupStrategy} legacy behavior</td><td>fallback → GroupStrategy</td></tr>
 * </table>
 *
 * <p>Handles mixed-track groups (A/B=MERGED, C=WEAK, D=ISOLATED in one group) —
 * the core Phase 2 scenario. WEAK summaries are cached on the group
 * ({@link ConversationGroup#getTrackSummary()}) so the LLM is not called on
 * every round; rule-based fallback (or TopicManager context) is used when no
 * LLM / no summary is available.
 *
 * <p>Registered for {@link ConversationMode#GROUP_DISCUSSION} and
 * {@link ConversationMode#DEBATE}; {@code GroupStrategy} / {@code DebateStrategy}
 * are NOT deleted (requirement doc §13) — GroupStrategy is retained as the
 * no-track-info fallback path, DebateStrategy remains for reference.
 */
public class TrackStrategy implements ConversationStrategy {

    private static final Logger log = LoggerFactory.getLogger(TrackStrategy.class);

    /** Recompute the WEAK eavesdrop summary only after this many new turns. */
    private static final int SUMMARY_REFRESH_TURNS = 4;
    /** Full chat history window for MERGED agents. */
    private static final int HISTORY_WINDOW = 8;

    private final java.util.function.Function<String, Agent> agentLookup;
    private final java.util.function.Function<String, String> narrationSupplier;
    private final EavesdropSummarizer eavesdropSummarizer;
    private final java.util.function.Function<String, TopicManager> topicManagerSupplier;
    /** Legacy no-track fallback (old GroupStrategy behavior). */
    private final GroupStrategy fallbackStrategy;

    public TrackStrategy(java.util.function.Function<String, Agent> agentLookup,
                         java.util.function.Function<String, String> narrationSupplier) {
        this(agentLookup, narrationSupplier, null, null);
    }

    public TrackStrategy(java.util.function.Function<String, Agent> agentLookup,
                         java.util.function.Function<String, String> narrationSupplier,
                         EavesdropSummarizer eavesdropSummarizer) {
        this(agentLookup, narrationSupplier, eavesdropSummarizer, null);
    }

    /**
     * @param eavesdropSummarizer   optional WEAK-track observer summarizer (null → rule-based only)
     * @param topicManagerSupplier  optional lookup for the group's TopicManager, used as a
     *                              no-LLM fallback source for WEAK observations
     */
    public TrackStrategy(java.util.function.Function<String, Agent> agentLookup,
                         java.util.function.Function<String, String> narrationSupplier,
                         EavesdropSummarizer eavesdropSummarizer,
                         java.util.function.Function<String, TopicManager> topicManagerSupplier) {
        this.agentLookup = agentLookup;
        this.narrationSupplier = narrationSupplier;
        this.eavesdropSummarizer = eavesdropSummarizer;
        this.topicManagerSupplier = topicManagerSupplier;
        this.fallbackStrategy = new GroupStrategy(agentLookup, narrationSupplier, eavesdropSummarizer);
    }

    @Override
    public ConversationMode supportedMode() { return ConversationMode.GROUP_DISCUSSION; }

    // ── ConversationStrategy ───────────────────────────────────

    @Override
    public void prepareContext(ConversationGroup group, Map<String, Map<String, String>> agentContexts) {
        if (group.getTrackAssignments().isEmpty()) {
            // Legacy path: no track info → old GroupStrategy behavior unchanged.
            fallbackStrategy.prepareContext(group, agentContexts);
            return;
        }

        List<AgentState> members = group.getParticipantList();
        List<String> mergedNames = new ArrayList<>();
        for (AgentState s : members) {
            if (modeOf(group, s.getAgentName()) == Track.Mode.MERGED) {
                mergedNames.add(s.getAgentName());
            }
        }
        // Degenerate group (e.g. everyone ISOLATED): fall back to full roster for rotation.
        if (mergedNames.isEmpty()) {
            for (AgentState s : members) mergedNames.add(s.getAgentName());
        }

        String curSpeaker = group.getCurrentSpeaker();
        if (curSpeaker == null || !mergedNames.contains(curSpeaker)) {
            curSpeaker = mergedNames.get(0);
            group.setCurrentSpeaker(curSpeaker);
        }

        // One eavesdrop observation shared by all WEAK members (cached on the group).
        String weakObservation = buildWeakObservation(group);

        for (AgentState self : members) {
            Track.Mode mode = modeOf(group, self.getAgentName());
            String context = switch (mode) {
                case MERGED -> buildMergedContext(group, self, mergedNames, curSpeaker);
                case WEAK -> buildWeakContext(group, self, weakObservation);
                case ISOLATED -> buildIsolatedContext(group, self);
            };
            agentContexts.put(self.getAgentName(), Map.of(
                    "context", context,
                    "role", roleFor(mode)));
        }

        String nextSpeaker = selectNextSpeaker(mergedNames, curSpeaker, group);
        group.setCurrentSpeaker(nextSpeaker);
    }

    @Override
    public void processResults(ConversationGroup group, Map<String, String> agentResponses, LLMClient llmClient) {
        if (group.getTrackAssignments().isEmpty()) {
            fallbackStrategy.processResults(group, agentResponses, llmClient);
            return;
        }

        for (var entry : agentResponses.entrySet()) {
            String name = entry.getKey();
            String response = entry.getValue();
            if (response == null) continue;

            AgentState state = group.getParticipant(name);
            if (state == null) continue;

            if (response.length() > 200) response = response.substring(0, 200);

            Emotion detected = Emotion.fromText(response);
            int tagStart = response.lastIndexOf("【情绪");
            if (tagStart < 0) tagStart = response.lastIndexOf("[情绪");
            String cleanResponse = response;
            if (tagStart >= 0) {
                int tagEnd = response.indexOf("】", tagStart);
                if (tagEnd < 0) tagEnd = response.indexOf("]", tagStart);
                if (tagEnd > tagStart) {
                    String tag = response.substring(tagStart, tagEnd + 1);
                    Emotion fromTag = Emotion.fromText(tag);
                    if (fromTag != Emotion.NEUTRAL) detected = fromTag;
                    cleanResponse = response.substring(0, tagStart).trim();
                }
            }

            if (detected != Emotion.NEUTRAL) state.setEmotion(detected);
            state.setCurrentMessage(cleanResponse);

            if (modeOf(group, name) == Track.Mode.ISOLATED) {
                // ISOLATED members do not participate in the group conversation:
                // their output stays an inner monologue and is never recorded as a
                // group turn (nothing they say can be heard by the others).
                log.debug("ISOLATED member {} inner monologue (not recorded as turn)", name);
                continue;
            }

            group.recordTurn(name, cleanResponse);
            group.setEngagement(name, group.getEngagement(name) * 0.9 + 0.1);
        }
    }

    @Override
    public boolean shouldContinue(ConversationGroup group) {
        if (group.getTrackAssignments().isEmpty()) {
            return fallbackStrategy.shouldContinue(group);
        }
        // Default policy (8 rounds / 30s idle). ISOLATED members never block
        // continuation — the default only looks at rounds + idle time.
        return ConversationStrategy.super.shouldContinue(group);
    }

    // ── Context builders ───────────────────────────────────────

    private String buildMergedContext(ConversationGroup group, AgentState self,
                                      List<String> mergedNames, String curSpeaker) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt()).append("\n\n");

        String nar = narrationSupplier.apply("dummy");
        sb.append("【场景】").append(nar != null && !nar.isEmpty() ? nar : "你们正在聊天。").append("\n");

        sb.append("你是这场对话的核心成员，身处谈话中心，能听到每个人的完整发言。");
        sb.append("当前主要发言人是 ").append(curSpeaker).append("。\n\n");

        sb.append("【聊天成员】");
        for (AgentState o : group.getParticipantList()) {
            if (o.getAgentName().equals(self.getAgentName())) continue;
            sb.append(o.getAgentName()).append("(").append(o.getEmotion().getLabel()).append(") ");
        }
        sb.append("\n\n");

        List<Map<String, String>> history = group.getMessageHistory();
        if (!history.isEmpty()) {
            sb.append("【聊天记录】\n");
            int start = Math.max(0, history.size() - HISTORY_WINDOW);
            for (int i = start; i < history.size(); i++) {
                Map<String, String> h = history.get(i);
                String msg = h.get("message");
                if (msg.length() > 50) msg = msg.substring(0, 50);
                sb.append(h.get("speaker")).append(": ").append(msg).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请发表你的看法，简短（50字内）。末尾标注【情绪：xxx】。");
        return sb.toString();
    }

    private String buildWeakContext(ConversationGroup group, AgentState self, String weakObservation) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt()).append("\n\n");

        String nar = narrationSupplier.apply("dummy");
        sb.append("【场景】").append(nar != null && !nar.isEmpty() ? nar : "你们正在聊天。").append("\n");

        sb.append("你在谈话圈外沿，离得较远，只能听到模糊的片段，听不清具体内容。\n\n");

        sb.append("【旁观信息】")
                .append(weakObservation == null || weakObservation.isBlank()
                        ? "你听到附近有人在交谈，但听不清具体内容。"
                        : weakObservation)
                .append("\n\n");

        sb.append("作为旁观者，你可以简短回应、提问或保持沉默（回复…表示沉默）。末尾标注【情绪：xxx】。");
        return sb.toString();
    }

    private String buildIsolatedContext(ConversationGroup group, AgentState self) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        TrackAssignment assignment = group.getTrackAssignment(self.getAgentName());
        String note = assignment != null && assignment.contextNote() != null
                ? assignment.contextNote() : "完全隔离";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt()).append("\n\n");

        String nar = narrationSupplier.apply("dummy");
        sb.append("【场景】").append(nar != null && !nar.isEmpty() ? nar : "你独自一人在某处。").append("\n");

        sb.append("你此刻").append(note).append("，与任何交谈完全隔绝，听不到任何对话内容。\n");
        sb.append("你在场外独处，只能感知到自己的思绪。\n\n");

        sb.append("请以内心独白的方式表达你此刻的所思所想（30字内），末尾标注【情绪：xxx】。");
        sb.append("（你的独白不会被在场任何人听到。）");
        return sb.toString();
    }

    // ── WEAK observation (summary, cost-controlled) ────────────

    /**
     * WEAK observation for all bystanders of a group. Priority:
     * <ol>
     *   <li>cached group summary (EavesdropSummarizer product) — recomputed only
     *       after {@value #SUMMARY_REFRESH_TURNS} new turns, so the LLM is not
     *       invoked on every prepareContext;</li>
     *   <li>TopicManager topic context + in-range roster (no LLM);
     *       then a generic "can't make out the details" line.</li>
     * </ol>
     * Never contains the full chat history.
     */
    private String buildWeakObservation(ConversationGroup group) {
        List<Map<String, String>> history = group.getMessageHistory();
        int historySize = history.size();
        String cached = group.getTrackSummary();

        if (cached != null && !cached.isBlank()
                && historySize - group.getTrackSummaryHistorySize() < SUMMARY_REFRESH_TURNS) {
            return cached;
        }

        String summary = "";
        if (!history.isEmpty() && eavesdropSummarizer != null) {
            try {
                summary = eavesdropSummarizer.summarize(history);
            } catch (Exception e) {
                log.warn("Eavesdrop summary failed for group {}, using rule-based fallback: {}",
                        group.getGroupId(), e.getMessage());
            }
        }

        if (summary == null || summary.isBlank()) {
            summary = buildTopicFallbackObservation(group);
        }

        group.setTrackSummary(summary);
        group.setTrackSummaryHistorySize(historySize);
        return summary;
    }

    /** No-LLM fallback: who is present + TopicManager topic description. */
    private String buildTopicFallbackObservation(ConversationGroup group) {
        List<String> names = new ArrayList<>();
        for (AgentState o : group.getParticipantList()) {
            if (!o.getAgentName().equals(group.getCurrentSpeaker())) {
                names.add(o.getAgentName());
            }
        }

        StringBuilder sb = new StringBuilder("你听到附近有人在交谈");
        if (!names.isEmpty()) {
            sb.append("：").append(String.join("、", names));
        }

        if (topicManagerSupplier != null) {
            try {
                TopicManager tm = topicManagerSupplier.apply(group.getGroupId());
                if (tm != null && tm.hasActiveTopic()) {
                    Object desc = tm.getTopicContext().get("description");
                    if (desc != null && !String.valueOf(desc).isBlank()) {
                        sb.append("，话题围绕「").append(desc).append("」展开");
                    }
                }
            } catch (Exception e) {
                log.warn("TopicManager lookup failed for group {}, skipping topic context: {}",
                        group.getGroupId(), e.getMessage());
            }
        }

        sb.append("。你离得较远，听不清具体内容。");
        return sb.toString();
    }

    // ── Helpers ────────────────────────────────────────────────

    private Track.Mode modeOf(ConversationGroup group, String name) {
        TrackAssignment assignment = group.getTrackAssignment(name);
        return assignment == null ? Track.Mode.MERGED : assignment.type();
    }

    private String roleFor(Track.Mode mode) {
        return switch (mode) {
            case MERGED -> "active";
            case WEAK -> "listener";
            case ISOLATED -> "isolated";
        };
    }

    private String selectNextSpeaker(List<String> mergedNames, String currentSpeaker,
                                     ConversationGroup group) {
        if (mergedNames.size() <= 1) return currentSpeaker;
        List<String> candidates = new ArrayList<>(mergedNames);
        candidates.remove(currentSpeaker);

        // Avoid letting the same agent speak two rounds in a row when possible.
        List<String> history = group.getTurnHistory();
        if (!history.isEmpty()) {
            candidates.remove(history.get(history.size() - 1));
        }
        if (candidates.isEmpty()) candidates.addAll(mergedNames);
        if (candidates.size() == 1) return candidates.get(0);
        return candidates.get(new Random().nextInt(candidates.size()));
    }
}
