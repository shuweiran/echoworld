package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class SpeechStrategy implements ConversationStrategy {

    private static final Logger log = LoggerFactory.getLogger(SpeechStrategy.class);
    private final java.util.function.Function<String, Agent> agentLookup;
    private final java.util.function.Function<String, String> narrationSupplier;
    private final TopicManager topicManager;

    public SpeechStrategy(java.util.function.Function<String, Agent> agentLookup,
                          java.util.function.Function<String, String> narrationSupplier,
                          TopicManager topicManager) {
        this.agentLookup = agentLookup;
        this.narrationSupplier = narrationSupplier;
        this.topicManager = topicManager;
    }

    @Override
    public ConversationMode supportedMode() { return ConversationMode.PUBLIC_SPEAKING; }

    @Override
    public void prepareContext(ConversationGroup group, Map<String, Map<String, String>> agentContexts) {
        String speaker = group.getCurrentSpeaker();
        List<AgentState> audience = new ArrayList<>();
        for (AgentState s : group.getParticipantList()) {
            if (!s.getAgentName().equals(speaker)) audience.add(s);
        }

        String speakerCtx = buildSpeakerContext(group, speaker, audience);
        agentContexts.put(speaker, Map.of("context", speakerCtx, "role", "speaker"));

        for (AgentState listener : audience) {
            String ctx = buildAudienceContext(group, listener, speaker, audience);
            agentContexts.put(listener.getAgentName(), Map.of("context", ctx, "role", "audience"));
        }
    }

    @Override
    public void processResults(ConversationGroup group, Map<String, String> responses, LLMClient llmClient) {
        String speaker = group.getCurrentSpeaker();
        String speechContent = responses.get(speaker);

        if (speechContent != null) {
            if (speechContent.length() > 300) speechContent = speechContent.substring(0, 300);
            AgentState speakerState = group.getParticipant(speaker);
            if (speakerState != null) {
                speakerState.setCurrentMessage("【演讲】" + speechContent);
                group.recordTurn(speaker, speechContent);
            }
        }

        for (AgentState listener : group.getParticipantList()) {
            if (listener.getAgentName().equals(speaker)) continue;
            String reaction = responses.get(listener.getAgentName());
            if (reaction == null) continue;
            if (reaction.length() > 100) reaction = reaction.substring(0, 100);
            listener.setCurrentMessage(reaction);

            double att = listener.getAttention();
            if (reaction.contains("鼓掌") || reaction.contains("好") || reaction.contains("精彩")) {
                att = Math.min(1, att + 0.15);
            } else if (reaction.contains("无聊") || reaction.contains("走神")) {
                att = Math.max(0, att - 0.2);
            } else {
                att = att * 0.98 + 0.02;
            }
            listener.setAttention(att);
            group.recordTurn(listener.getAgentName(), reaction);
        }

        if (topicManager.hasActiveTopic()) {
            topicManager.advanceTopic(List.of(speaker), responses, false);
        }
    }

    @Override
    public boolean shouldContinue(ConversationGroup group) {
        if (group.getRoundCount() >= 6) return false;
        if (!topicManager.hasActiveTopic()) return false;
        double avgAttention = 0;
        for (AgentState s : group.getParticipantList()) {
            avgAttention += s.getAttention();
        }
        avgAttention /= group.getParticipantCount();
        return avgAttention > 0.25;
    }

    private String buildSpeakerContext(ConversationGroup group, String speaker, List<AgentState> audience) {
        Agent agent = agentLookup.apply(speaker);
        if (agent == null) return "";
        AgentState self = group.getParticipant(speaker);

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt()).append("\n\n");
        sb.append("【演讲模式】你是演讲者。面前有 ").append(audience.size()).append(" 个人在听你说话。\n");
        sb.append("请发表一段简短的演讲（3-5句话，80字内）。\n\n");

        String nar = narrationSupplier.apply("");
        sb.append("【场景】").append(nar != null && !nar.isEmpty() ? nar : "公共空间").append("\n");

        Map<String, Object> topic = topicManager.getTopicContext();
        if (!topic.isEmpty()) {
            sb.append("话题：").append(topic.get("description")).append("\n");
            sb.append("话题状态：").append(topic.get("state")).append("（已进行").append(topic.get("rounds")).append("轮）\n");
        }

        sb.append("\n【听众反应】");
        for (AgentState a : audience) {
            String lastMsg = a.getCurrentMessage();
            if (lastMsg != null && !lastMsg.isBlank() && lastMsg.length() < 50) {
                sb.append(a.getAgentName()).append("(").append(Math.round(a.getAttention() * 100))
                        .append("%注意力): ").append(lastMsg).append("; ");
            }
        }
        sb.append("\n\n请继续你的演讲，结尾加【情绪：xxx】。");
        return sb.toString();
    }

    private String buildAudienceContext(ConversationGroup group, AgentState self,
                                         String speaker, List<AgentState> others) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt()).append("\n\n");
        sb.append("【听众模式】你正在听 ").append(speaker).append(" 演讲。\n");
        sb.append("你的注意力：").append(Math.round(self.getAttention() * 100)).append("%\n");

        String lastSpeakerMsg = group.getParticipant(speaker).getCurrentMessage();
        if (lastSpeakerMsg != null && !lastSpeakerMsg.isBlank()) {
            sb.append(speaker).append("刚才说：").append(lastSpeakerMsg).append("\n");
        }

        sb.append("\n请做一个简短反应（10字内）。");
        if (self.getAttention() > 0.7) {
            sb.append("你听得很认真，可以点头鼓励、鼓掌或提问。");
        } else if (self.getAttention() > 0.3) {
            sb.append("你在听但不太投入，可以简单回应。");
        } else {
            sb.append("你走神了，想想别的事情。回复\"...\"或小声嘀咕。");
        }
        sb.append("末尾加【情绪：xxx】。");
        return sb.toString();
    }
}
