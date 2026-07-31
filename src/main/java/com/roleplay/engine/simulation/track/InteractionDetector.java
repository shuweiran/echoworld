package com.roleplay.engine.simulation.track;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;

import java.util.*;

/**
 * Lightweight rule-based Track-mode trigger detector — NO LLM call.
 *
 * <p>Per requirement doc TrackScore:
 * <ul>
 *   <li>人数: 2人=0, 3-4人=20, 5人以上=40</li>
 *   <li>存在旁观者: +20</li>
 *   <li>目标冲突 (FOR 与 AGAINST 对立立场同时存在): +40</li>
 *   <li>秘密任务 (agent 处于秘密任务中): +50</li>
 *   <li>情绪异常 (ANGRY；Emotion 枚举无 FEAR，按需求文档精神以 ANGRY 计): +15</li>
 * </ul>
 * score &gt;= 40 → 判定需要 Track 模式。
 *
 * <p>Phase 1: pure function over {@link AgentState}s; the secret-task set is passed
 * in by the caller (Phase 2 WorldDirector / SimulationOrchestrator provides it).
 */
public class InteractionDetector {

    public static final int TRACK_THRESHOLD = 40;
    public static final int SIZE_2 = 0;
    public static final int SIZE_3_4 = 20;
    public static final int SIZE_5_PLUS = 40;
    public static final int BYSTANDER = 20;
    public static final int TARGET_CONFLICT = 40;
    public static final int SECRET_TASK = 50;
    public static final int ABNORMAL_EMOTION = 15;

    /** Detailed scoring result with per-factor breakdown. */
    public record TrackScore(
            int sizeFactor,
            int bystanderFactor,
            int conflictFactor,
            int secretFactor,
            int emotionFactor
    ) {
        public int score() {
            return sizeFactor + bystanderFactor + conflictFactor + secretFactor + emotionFactor;
        }

        public boolean triggered() {
            return score() >= TRACK_THRESHOLD;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", score());
            m.put("size", sizeFactor);
            m.put("bystander", bystanderFactor);
            m.put("target_conflict", conflictFactor);
            m.put("secret_task", secretFactor);
            m.put("abnormal_emotion", emotionFactor);
            m.put("triggered", triggered());
            return m;
        }
    }

    /** Evaluate without secret-task info (no agent carries a secret flag). */
    public TrackScore evaluate(List<AgentState> agents) {
        return evaluate(agents, Set.of());
    }

    /**
     * @param agents           nearby agents to score
     * @param secretTaskAgents names of agents currently on a secret task
     */
    public TrackScore evaluate(List<AgentState> agents, Set<String> secretTaskAgents) {
        List<AgentState> list = agents == null ? List.of() : new ArrayList<>(agents);
        Set<String> secrets = secretTaskAgents == null ? Set.of() : secretTaskAgents;

        int size = list.size();
        int sizeFactor = size <= 2 ? SIZE_2 : size <= 4 ? SIZE_3_4 : SIZE_5_PLUS;

        // 旁观者: ≥2 人在对话中，且至少 1 人未参与对话但同在附近
        long inConversation = list.stream().filter(AgentState::isInConversation).count();
        int bystanderFactor = (inConversation >= 2 && size - inConversation >= 1) ? BYSTANDER : 0;

        boolean hasFor = false;
        boolean hasAgainst = false;
        boolean hasAngry = false;
        boolean hasSecret = false;
        for (AgentState s : list) {
            if (s.getStance() == AgentState.Stance.FOR) hasFor = true;
            if (s.getStance() == AgentState.Stance.AGAINST) hasAgainst = true;
            if (s.getEmotion() == Emotion.ANGRY) hasAngry = true;
            if (secrets.contains(s.getAgentName())) hasSecret = true;
        }

        int conflictFactor = (hasFor && hasAgainst) ? TARGET_CONFLICT : 0;
        int secretFactor = hasSecret ? SECRET_TASK : 0;
        int emotionFactor = hasAngry ? ABNORMAL_EMOTION : 0;

        return new TrackScore(sizeFactor, bystanderFactor, conflictFactor, secretFactor, emotionFactor);
    }

    /** Convenience: should this interaction switch to Track mode? */
    public boolean shouldUseTrack(List<AgentState> agents) {
        return evaluate(agents).triggered();
    }

    public boolean shouldUseTrack(List<AgentState> agents, Set<String> secretTaskAgents) {
        return evaluate(agents, secretTaskAgents).triggered();
    }
}
