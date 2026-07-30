package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class GroupStrategy implements ConversationStrategy {

    private static final Logger log = LoggerFactory.getLogger(GroupStrategy.class);
    private final java.util.function.Function<String, Agent> agentLookup;
    private final java.util.function.Function<String, String> worldNarrationSupplier;

    public GroupStrategy(java.util.function.Function<String, Agent> agentLookup,
                         java.util.function.Function<String, String> worldNarrationSupplier) {
        this.agentLookup = agentLookup;
        this.worldNarrationSupplier = worldNarrationSupplier;
    }

    @Override
    public ConversationMode supportedMode() { return ConversationMode.GROUP_DISCUSSION; }

    @Override
    public void prepareContext(ConversationGroup group, Map<String, Map<String, String>> agentContexts) {
        List<AgentState> members = group.getParticipantList();

        List<AgentState> active = new ArrayList<>();
        List<AgentState> listeners = new ArrayList<>();
        String curSpeaker = group.getCurrentSpeaker();

        for (AgentState s : members) {
            if (s.getAgentName().equals(curSpeaker) || active.size() == 0) {
                active.add(s);
            } else if (active.size() < 2) {
                active.add(s);
            } else {
                listeners.add(s);
            }
        }

        for (AgentState self : members) {
            boolean isActive = active.contains(self);
            List<AgentState> others = new ArrayList<>(members);
            others.remove(self);

            String context = buildContext(self, others, group, isActive, curSpeaker);
            agentContexts.put(self.getAgentName(),
                    Map.of("context", context, "role", isActive ? "active" : "listener"));
        }

        String newSpeaker = selectNextSpeaker(group, group.getCurrentSpeaker());
        group.setCurrentSpeaker(newSpeaker);
    }

    @Override
    public void processResults(ConversationGroup group, Map<String, String> agentResponses, LLMClient llmClient) {
        for (var entry : agentResponses.entrySet()) {
            String name = entry.getKey();
            String response = entry.getValue();
            if (response == null) continue;

            if (response.length() > 200) response = response.substring(0, 200);

            AgentState state = group.getParticipant(name);
            if (state == null) continue;

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
            group.recordTurn(name, cleanResponse);
            group.setEngagement(name, group.getEngagement(name) * 0.9 + 0.1);
        }
    }

    @Override
    public boolean shouldContinue(ConversationGroup group) {
        if (group.getRoundCount() >= 10) return false;
        if (group.idleMs() > 40_000) return false;
        double avgEngagement = 0;
        for (AgentState s : group.getParticipantList()) {
            avgEngagement += group.getEngagement(s.getAgentName());
        }
        avgEngagement /= group.getParticipantCount();
        return avgEngagement > 0.3;
    }

    private String selectNextSpeaker(ConversationGroup group, String currentSpeaker) {
        List<String> candidates = new ArrayList<>();
        for (AgentState s : group.getParticipantList()) {
            if (!s.getAgentName().equals(currentSpeaker)) {
                candidates.add(s.getAgentName());
            }
        }
        if (candidates.isEmpty()) return currentSpeaker;

        List<String> history = group.getTurnHistory();
        if (history.size() >= 2) {
            String last2 = history.get(history.size() - 1);
            String last1 = history.get(history.size() - 2);
            if (last2.equals(last1)) {
                candidates.remove(last2);
            }
        }

        if (!candidates.isEmpty()) {
            return candidates.get(new Random().nextInt(candidates.size()));
        }
        return currentSpeaker;
    }

    private String buildContext(AgentState self, List<AgentState> others, ConversationGroup group,
                                 boolean isActive, String currentSpeaker) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt());
        sb.append("\n\n");

        String nar = worldNarrationSupplier.apply("dummy");
        sb.append("【场景】").append(nar != null && !nar.isEmpty() ? nar : "你们正在聊天。").append("\n");

        sb.append("你是多人对话中的一员。");
        sb.append(isActive ? "这一轮你在发言。" : "这一轮你主要是听众。");
        sb.append("当前主要发言人是 ").append(currentSpeaker).append("。\n\n");

        sb.append("【聊天成员】");
        for (AgentState o : others) {
            sb.append(o.getAgentName()).append("(").append(o.getEmotion().getLabel()).append(") ");
        }
        sb.append("\n\n");

        List<Map<String, String>> history = group.getMessageHistory();
        if (!history.isEmpty()) {
            sb.append("【聊天记录】\n");
            int start = Math.max(0, history.size() - 8);
            for (int i = start; i < history.size(); i++) {
                Map<String, String> h = history.get(i);
                String msg = h.get("message");
                if (msg.length() > 50) msg = msg.substring(0, 50);
                sb.append(h.get("speaker")).append(": ").append(msg).append("\n");
            }
            sb.append("\n");
        }

        if (isActive) {
            sb.append("请发表你的看法，简短（50字内）。末尾标注【情绪：xxx】。");
        } else {
            sb.append("作为听众，你可以简短回应、提问或保持沉默（回复…表示沉默）。末尾标注【情绪：xxx】。");
        }

        return sb.toString();
    }
}
