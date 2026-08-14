package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class DebateStrategy implements ConversationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DebateStrategy.class);
    private static final double EMOTION_CONTAGION_RATE = 0.25;
    private static final int ESCALATION_THRESHOLD = 3;
    private static final int MAX_ROUNDS = 8;

    private final java.util.function.Function<String, Agent> agentLookup;
    private final java.util.function.Function<String, String> narrationSupplier;
    private final java.util.function.BiConsumer<String, String> arbitrationCallback;

    private final Map<String, Integer> escalationCounters = new ConcurrentHashMap<>();
    private int consecutiveAngryRounds = 0;
    private boolean arbitrationTriggered = false;

    public DebateStrategy(java.util.function.Function<String, Agent> agentLookup,
                          java.util.function.Function<String, String> narrationSupplier,
                          java.util.function.BiConsumer<String, String> arbitrationCallback) {
        this.agentLookup = agentLookup;
        this.narrationSupplier = narrationSupplier;
        this.arbitrationCallback = arbitrationCallback;
    }

    @Override
    public ConversationMode supportedMode() { return ConversationMode.DEBATE; }

    @Override
    public void prepareContext(ConversationGroup group, Map<String, Map<String, String>> agentContexts) {
        assignInitialStances(group);

        applyEmotionContagion(group);

        for (AgentState self : group.getParticipantList()) {
            AgentState.Stance stance = self.getStance();
            boolean isActive = self.getAgentName().equals(group.getCurrentSpeaker())
                    || Math.random() < 0.5;

            String ctx;
            if (isActive) {
                ctx = buildArguerContext(group, self);
            } else {
                ctx = buildObserverContext(group, self);
            }
            agentContexts.put(self.getAgentName(), Map.of("context", ctx,
                    "role", isActive ? "active" : "observer"));
        }
    }

    @Override
    public void processResults(ConversationGroup group, Map<String, String> responses, LLMClient llmClient) {
        int angryCount = 0;

        for (var entry : responses.entrySet()) {
            String name = entry.getKey();
            String resp = entry.getValue();
            if (resp == null) continue;
            if (resp.length() > 200) resp = resp.substring(0, 200);

            AgentState state = group.getParticipant(name);
            if (state == null) continue;

            Emotion detected = Emotion.fromText(resp);
            int tagStart = resp.lastIndexOf("【情绪");
            if (tagStart < 0) tagStart = resp.lastIndexOf("[情绪");
            String clean = resp;
            if (tagStart >= 0) {
                int tagEnd = resp.indexOf("】", tagStart);
                if (tagEnd < 0) tagEnd = resp.indexOf("]", tagStart);
                if (tagEnd > tagStart) {
                    Emotion fromTag = Emotion.fromText(resp.substring(tagStart, tagEnd + 1));
                    if (fromTag != Emotion.NEUTRAL) detected = fromTag;
                    clean = resp.substring(0, tagStart).trim();
                }
            }

            state.setEmotion(detected);
            // P-0813-K：玩家成员不回写 currentMessage（单次消费输入通道，见 executeRound）
            if (!state.isPlayerControlled()) state.setCurrentMessage(clean);
            group.recordTurn(name, clean);

            if (detected == Emotion.ANGRY) angryCount++;

            detectStanceChange(state, clean);
        }

        if (angryCount >= 2) {
            consecutiveAngryRounds++;
        } else {
            consecutiveAngryRounds = Math.max(0, consecutiveAngryRounds - 1);
        }

        if (consecutiveAngryRounds >= ESCALATION_THRESHOLD && !arbitrationTriggered) {
            arbitrationTriggered = true;
            triggerArbitration(group);
        }

        String nextSpeaker = selectNextDebater(group);
        group.setCurrentSpeaker(nextSpeaker);
    }

    @Override
    public boolean shouldContinue(ConversationGroup group) {
        if (group.getRoundCount() >= MAX_ROUNDS) return false;
        return !arbitrationTriggered || group.getRoundCount() <= 2;
    }

    private void assignInitialStances(ConversationGroup group) {
        List<AgentState> members = group.getParticipantList();
        for (AgentState s : members) {
            if (s.getStance() == AgentState.Stance.NEUTRAL) {
                s.setStance(Math.random() < 0.5 ? AgentState.Stance.FOR : AgentState.Stance.AGAINST);
            }
        }
    }

    private void applyEmotionContagion(ConversationGroup group) {
        List<AgentState> members = group.getParticipantList();
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                AgentState a = members.get(i);
                AgentState b = members.get(j);
                Emotion ea = a.getEmotion(), eb = b.getEmotion();

                if (ea == Emotion.ANGRY && a.getStance() != b.getStance()) {
                    if (Math.random() < EMOTION_CONTAGION_RATE) {
                        b.setEmotion(Emotion.ANGRY);
                        log.debug("Emotion contagion: {} anger -> {}", a.getAgentName(), b.getAgentName());
                    }
                }
                if (eb == Emotion.ANGRY && b.getStance() != a.getStance()) {
                    if (Math.random() < EMOTION_CONTAGION_RATE) {
                        a.setEmotion(Emotion.ANGRY);
                    }
                }

                if (ea == Emotion.HAPPY && a.getStance() == b.getStance()) {
                    if (Math.random() < EMOTION_CONTAGION_RATE * 0.5) {
                        b.setEmotion(Emotion.HAPPY);
                    }
                }
            }
        }
    }

    private void detectStanceChange(AgentState state, String response) {
        String lower = response.toLowerCase();
        if (lower.contains("你说的对") || lower.contains("我同意") || lower.contains("有道理")) {
            if (state.getStance() == AgentState.Stance.AGAINST) {
                state.setStance(AgentState.Stance.NEUTRAL);
            }
        }
        if (lower.contains("不对") || lower.contains("不同意") || lower.contains("反对")) {
            if (state.getStance() != AgentState.Stance.AGAINST) {
                state.setStance(AgentState.Stance.AGAINST);
            }
        }
    }

    private String selectNextDebater(ConversationGroup group) {
        List<AgentState> members = group.getParticipantList();
        String curSpeaker = group.getCurrentSpeaker();

        List<AgentState> opponents = new ArrayList<>();
        for (AgentState s : members) {
            if (!s.getAgentName().equals(curSpeaker) && s.getStance() != members.stream()
                    .filter(m -> m.getAgentName().equals(curSpeaker)).findFirst()
                    .map(AgentState::getStance).orElse(AgentState.Stance.NEUTRAL)) {
                opponents.add(s);
            }
        }

        if (!opponents.isEmpty()) {
            return opponents.get(new Random().nextInt(opponents.size())).getAgentName();
        }

        for (AgentState s : members) {
            if (!s.getAgentName().equals(curSpeaker)) return s.getAgentName();
        }
        return curSpeaker;
    }

    private void triggerArbitration(ConversationGroup group) {
        log.warn("Arbitration triggered for group {} ({} consecutive angry rounds)",
                group.getGroupId(), consecutiveAngryRounds);

        StringBuilder report = new StringBuilder();
        report.append("辩论情绪升级警告！连续 ").append(consecutiveAngryRounds).append(" 轮出现愤怒情绪。\n");
        report.append("参与者状态:\n");
        for (AgentState s : group.getParticipantList()) {
            report.append("- ").append(s.getAgentName()).append(": ")
                    .append(s.getStance().name()).append(" / ").append(s.getEmotion().getLabel()).append("\n");
        }
        report.append("请导演介入调解。");

        if (arbitrationCallback != null) {
            arbitrationCallback.accept(group.getGroupId(), report.toString());
        }

        consecutiveAngryRounds = 0;
    }

    private String buildArguerContext(ConversationGroup group, AgentState self) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt()).append("\n\n");

        sb.append("【辩论模式】你正在参与一场辩论。你的立场：").append(self.getStance().name()).append("。\n");

        List<AgentState> all = group.getParticipantList();
        for (AgentState o : all) {
            if (o == self) continue;
            sb.append(o.getAgentName()).append("(立场").append(o.getStance().name())
                    .append("，情绪").append(o.getEmotion().getLabel()).append(") ");
            String msg = o.getCurrentMessage();
            if (msg != null && msg.length() < 60) {
                sb.append("说: ").append(msg);
            }
            sb.append("\n");
        }

        List<AgentState> opponents = new ArrayList<>();
        List<AgentState> allies = new ArrayList<>();
        for (AgentState o : all) {
            if (o == self) continue;
            if (o.getStance() != self.getStance()) opponents.add(o);
            else allies.add(o);
        }

        sb.append("\n你的对立面（").append(opponents.size()).append("人）: ");
        for (AgentState o : opponents) sb.append(o.getAgentName()).append(" ");
        sb.append("\n你的同盟（").append(allies.size()).append("人）: ");
        for (AgentState o : allies) sb.append(o.getAgentName()).append(" ");

        sb.append("\n\n请发表你的论点，反驳对方（50字内）。立场: ").append(self.getStance().name());
        sb.append("。末尾加【情绪：xxx】。");

        return sb.toString();
    }

    private String buildObserverContext(ConversationGroup group, AgentState self) {
        Agent agent = agentLookup.apply(self.getAgentName());
        if (agent == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPersona().buildSystemPrompt()).append("\n\n");
        sb.append("【辩论模式】你正在旁观一场辩论，立场中立。当前发言人是")
                .append(group.getCurrentSpeaker()).append("。\n");
        sb.append("请做一个简短的观战反应（10字内），末尾加【情绪：xxx】。");
        return sb.toString();
    }
}
