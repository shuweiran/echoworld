package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.broadcast.BroadcastMessage;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.HearingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class SpeechStrategy implements ConversationStrategy {

    private static final Logger log = LoggerFactory.getLogger(SpeechStrategy.class);
    private final java.util.function.Function<String, Agent> agentLookup;
    private final java.util.function.Function<String, String> narrationSupplier;
    private final TopicManager topicManager;

    /** 方案B：内联广播依赖（可空——方案A 模式 / 单元测试直构时不传）。 */
    private final AnnouncementService announcementService;
    /** 方案B：HearingSystem 提供者（远近判定复用，2D 世界接线时传 {@code world::getHearingSystem}）。 */
    private final java.util.function.Supplier<HearingSystem> hearingSystem;
    /** 方案B：全量角色状态提供者（听众计算，接线时传 {@code () -> world.getAllStates().values()}）。 */
    private final java.util.function.Supplier<Collection<AgentState>> allStates;

    public SpeechStrategy(java.util.function.Function<String, Agent> agentLookup,
                          java.util.function.Function<String, String> narrationSupplier,
                          TopicManager topicManager) {
        this(agentLookup, narrationSupplier, topicManager, null, null, null);
    }

    /**
     * 方案B 构造：额外注入 AnnouncementService + HearingSystem/全量状态提供者，
     * 使 processResults 可内联发区域广播（与现有 agentLookup/narrationSupplier
     * 注入方式一致，调研报告 §4.2 建议）。旧三参构造保留（方案A 路径零依赖）。
     */
    public SpeechStrategy(java.util.function.Function<String, Agent> agentLookup,
                          java.util.function.Function<String, String> narrationSupplier,
                          TopicManager topicManager,
                          AnnouncementService announcementService,
                          java.util.function.Supplier<HearingSystem> hearingSystem,
                          java.util.function.Supplier<Collection<AgentState>> allStates) {
        this.agentLookup = agentLookup;
        this.narrationSupplier = narrationSupplier;
        this.topicManager = topicManager;
        this.announcementService = announcementService;
        this.hearingSystem = hearingSystem;
        this.allStates = allStates;
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
                // P-0813-K：玩家成员不回写 currentMessage（单次消费输入通道，见 executeRound）
                if (!speakerState.isPlayerControlled()) {
                    speakerState.setCurrentMessage("【演讲】" + speechContent);
                }
                group.recordTurn(speaker, speechContent);
                // 方案B（分步落地）：演讲产出内联接区域广播——携带 speaker 坐标与半径，
                // 远近判定复用 HearingSystem 语义（谁在半径内谁收到，远处角色/前端按距离衰减展示）；
                // speech-mode=split（方案B 旧行为）时才内联，merged（正式版）/auto（方案A）
                // 此处静默，由 ConversationManager 回调走 SimulationService 判定路径。
                broadcastSpeechInline(group, speaker, speechContent);
            }
        }

        for (AgentState listener : group.getParticipantList()) {
            if (listener.getAgentName().equals(speaker)) continue;
            String reaction = responses.get(listener.getAgentName());
            if (reaction == null) continue;
            if (reaction.length() > 100) reaction = reaction.substring(0, 100);
            // P-0813-K：玩家成员不回写 currentMessage（单次消费输入通道，见 executeRound）
            if (!listener.isPlayerControlled()) listener.setCurrentMessage(reaction);

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

    /**
     * 方案B 核心：演讲即刻变区域广播。
     * ① 仅 {@code roleplay.broadcast.speech-mode=split} 时生效（auto 时由方案A 回调路径接管）；
     * ② 复用统一 AnnouncementService 管线（优先级/节流/合并/环形缓冲与方案A 完全一致）；
     * ③ 广播载荷带 speaker 坐标 (x,y) 与听觉半径（=speaker.getHearRange()），消费侧按
     *    HearingSystem 距离衰减语义展示（近=正常、远=「远处传来…」），无人可听时自然无人展示。
     */
    private void broadcastSpeechInline(ConversationGroup group, String speaker, String speechContent) {
        if (announcementService == null) return;
        if (!"split".equals(announcementService.getSpeechMode())) return;
        AgentState speakerState = group.getParticipant(speaker);
        if (speakerState == null) return;
        try {
            // 远近判定复用 HearingSystem：统计当前能听到演讲的听众数（日志/测试可观测）
            int listeners = countHearingListeners(speakerState);
            BroadcastMessage msg = announcementService.enqueue(BroadcastMessage.of(
                    BroadcastMessage.Level.NPC, "area", speaker, speechContent,
                    speakerState.getX(), speakerState.getY(), speakerState.getHearRange(),
                    BroadcastMessage.MODE_SPEECH));
            log.info("方案B 演讲内联区域广播: speaker={} listeners={} enqueued={} radius={}",
                    speaker, listeners, msg != null, Math.round(speakerState.getHearRange()));
        } catch (Exception e) {
            log.warn("方案B 演讲内联广播失败: {}", e.getMessage());
        }
    }

    /**
     * 复用 HearingSystem 的远近判定（判定单事实源，与正式版 merged 共用同一声学工具方法）：
     * 对全量角色跑 computeAudibility，统计以 speaker 为声源、可听到（canHear）的听众数。
     * 委托 {@link HearingSystem#countHearingListeners}，避免 split 与 merged 双份实现漂移。
     */
    int countHearingListeners(AgentState speakerState) {
        if (hearingSystem == null || allStates == null) return 0;
        try {
            HearingSystem hs = hearingSystem.get();
            Collection<AgentState> states = allStates.get();
            if (hs == null || states == null || states.isEmpty()) return 0;
            return hs.countHearingListeners(speakerState, states);
        } catch (Exception e) {
            return 0;
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
