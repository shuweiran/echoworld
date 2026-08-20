package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class DyadStrategy implements ConversationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DyadStrategy.class);
    private final java.util.function.Function<String, Agent> agentLookup;
    private final java.util.function.Function<String, String> worldNarrationSupplier;

    public DyadStrategy(java.util.function.Function<String, Agent> agentLookup,
                        java.util.function.Function<String, String> worldNarrationSupplier) {
        this.agentLookup = agentLookup;
        this.worldNarrationSupplier = worldNarrationSupplier;
    }

    @Override
    public ConversationMode supportedMode() { return ConversationMode.DYAD; }

    @Override
    public void prepareContext(ConversationGroup group, Map<String, Map<String, String>> agentContexts) {
        List<AgentState> members = group.getParticipantList();
        AgentState a = members.get(0);
        AgentState b = members.get(1);

        String aContext = buildContext(a, b, group);
        String bContext = buildContext(b, a, group);

        agentContexts.put(a.getAgentName(), Map.of("context", aContext, "role", "active"));
        agentContexts.put(b.getAgentName(), Map.of("context", bContext, "role", "active"));
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
            // P-0813-K：玩家成员不回写 currentMessage（单次消费输入通道，见 executeRound）
            if (!state.isPlayerControlled()) state.setCurrentMessage(cleanResponse);
            group.recordTurn(name, cleanResponse);
        }
    }

    @Override
    public boolean shouldContinue(ConversationGroup group) {
        // 自然离场是普通 2D 对话的主要结束机制；这里仅保留较高安全上限与空闲兜底。
        return group.getRoundCount() < 20 && group.idleMs() < 60_000;
    }

    private String buildContext(AgentState self, AgentState other, ConversationGroup group) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt());
        sb.append("\n\n");

        String nar = worldNarrationSupplier.apply("dummy");
        sb.append("【场景旁白】").append(nar != null && !nar.isEmpty() ? nar : "你们正在交谈。").append("\n\n");

        sb.append("【对话状态】你正在和 ").append(other.getAgentName()).append(" 一对一交谈。");
        sb.append("这是第 ").append(group.getRoundCount() + 1).append(" 轮对话。\n");
        sb.append("你的情绪：").append(self.getEmotion().getLabel())
                .append("，对方情绪：").append(other.getEmotion().getLabel()).append("\n\n");

        List<Map<String, String>> history = group.getMessageHistory();
        if (!history.isEmpty()) {
            sb.append("【对话记录】\n");
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                Map<String, String> h = history.get(i);
                sb.append(h.get("speaker")).append(": ").append(h.get("message")).append("\n");
            }
            sb.append("\n");
        }

        // P-0813-K：玩家成员发言单次消费后 currentMessage 可能为 null（见 executeRound），
        // 空则回退到对话记录最近一句，避免 prompt 出现字面 "null"。
        String otherMsg = other.getCurrentMessage();
        if (otherMsg == null || otherMsg.isBlank()) {
            List<Map<String, String>> hist = group.getMessageHistory();
            for (int i = hist.size() - 1; i >= 0; i--) {
                Map<String, String> h = hist.get(i);
                if (h != null && h.get("speaker") != null
                        && h.get("speaker").equals(other.getAgentName())
                        && h.get("message") != null && !h.get("message").isBlank()) {
                    otherMsg = h.get("message");
                    break;
                }
            }
        }
        sb.append(other.getAgentName()).append("刚才说：").append(otherMsg == null ? "……" : otherMsg).append("\n\n");
        sb.append("请以第一人称简短回复（50字内），末尾加【情绪：xxx】标注你的情绪。");
        sb.append("不要抢话，自然回应对方说的内容。");

        return sb.toString();
    }
}
