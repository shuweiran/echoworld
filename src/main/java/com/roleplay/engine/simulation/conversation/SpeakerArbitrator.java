package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 确定性的发言机会仲裁器。它只决定“下一位可向 LLM 请求行动的候选人”，
 * 不决定角色最终是否开口；最终 SILENT/SPEAK 仍由 Agent 的 {@code SpeechDecision} 给出。
 */
public final class SpeakerArbitrator {
    public String select(ConversationGroup group, Map<String, Map<String, String>> contexts) {
        if (contexts == null || contexts.isEmpty()) return null;
        String latest = latestMessage(group);
        return contexts.keySet().stream()
                .filter(name -> {
                    AgentState state = group.getParticipant(name);
                    return state != null && !state.isPlayerControlled();
                })
                .max(Comparator.<String>comparingInt(name -> priority(group, name, contexts.get(name), latest))
                        .thenComparing(Comparator.reverseOrder()))
                .orElse(null);
    }

    int priority(ConversationGroup group, String name, Map<String, String> context, String latest) {
        int score = 0;
        if (SpeechGate.isQuestioning(latest, name)) score += 100;
        else if (SpeechGate.isMentioning(latest, name)) score += 80;
        if (name.equals(group.getCurrentSpeaker())) score += 60;
        if (context != null && "active".equals(context.get("role"))) score += 40;
        AgentState state = group.getParticipant(name);
        if (state != null && switch (state.getEmotion()) {
            case ANGRY, SAD, CONFUSED, SURPRISED -> true;
            default -> false;
        }) score += 30;
        // 本次机会已被其主动放弃时，必须让下一候选先获得机会；后续新事件会自然重置排名。
        if (name.equals(group.getLastOpportunitySpeaker())
                && group.getLastOpportunityHistorySize() == group.getMessageHistory().size()) score -= 200;
        List<String> turns = group.getTurnHistory();
        if (!turns.isEmpty() && name.equals(turns.get(turns.size() - 1))) score -= 30;
        return score;
    }

    private String latestMessage(ConversationGroup group) {
        List<Map<String, String>> history = group.getMessageHistory();
        return history.isEmpty() ? "" : history.get(history.size() - 1).getOrDefault("message", "");
    }
}
