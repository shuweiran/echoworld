package com.roleplay.engine.simulation.director;

import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.SimulationWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 World Director — 角色目标管理（角色想做什么）。
 *
 * <p>维护 agent → goal 映射（{@code Map<String, String>}，输出格式对齐需求文档：
 * {@code {"agent":"A","goal":"investigate"}}），并提供每 tick 的规则式目标更新
 * {@link #updateGoals}（Phase 3 不调 LLM，成本控制；LLM 版留给 Phase 4）：
 * <ul>
 *   <li>情绪异常（ANGRY / SAD / CONFUSED / SURPRISED）→ "平静情绪"（priority 30）</li>
 *   <li>在对话中 → "参与讨论"（priority 20）</li>
 *   <li>长时间无活动（lastConversationTime 距今超过阈值且无目标）→ "探索周围"（priority 10）</li>
 *   <li>其余 → "闲逛"（priority 5）</li>
 * </ul>
 *
 * <p>通过 {@link #setGoal} 显式设定的目标（玩家指令 / 秘密任务注入）是粘性的——
 * {@link #updateGoals} 不会覆盖它们，保证外部注入的目标跨 tick 保持。
 *
 * <p>{@code SimulationWorld} 参数保留给 Phase 4 LLM 扩展点（当前规则不依赖世界状态）。
 */
public class WorldDirectorService {

    private static final Logger log = LoggerFactory.getLogger(WorldDirectorService.class);

    public static final String GOAL_CALM = "平静情绪";
    public static final String GOAL_JOIN_DISCUSSION = "参与讨论";
    public static final String GOAL_EXPLORE = "探索周围";
    public static final String GOAL_WANDER = "闲逛";

    /** 规则判定为"情绪异常"的情绪集合 → 需要"平静情绪"目标。 */
    private static final Set<Emotion> ABNORMAL_EMOTIONS =
            EnumSet.of(Emotion.ANGRY, Emotion.SAD, Emotion.CONFUSED, Emotion.SURPRISED);

    /** 距上次对话多久视为"长时间无活动"。 */
    public static final long IDLE_THRESHOLD_MS = 30_000;

    /** 手动设定的目标优先级（sticky，高于任何规则目标）。 */
    private static final int MANUAL_GOAL_PRIORITY = 100;

    /** agent → 当前有效目标（规则或手动或临时）。 */
    private final Map<String, String> agentGoals = new ConcurrentHashMap<>();
    /** agent → 手动设定目标（粘性，updateGoals 不覆盖）。 */
    private final Map<String, String> manualGoals = new ConcurrentHashMap<>();
    /** agent → 手动目标优先级（批次 D：动机优先级，默认 MANUAL_GOAL_PRIORITY=100）。 */
    private final Map<String, Integer> manualPriorities = new ConcurrentHashMap<>();
    /** agent → 目标详情（含优先级），供输出/调试。 */
    private final Map<String, AgentGoal> goalDetails = new ConcurrentHashMap<>();
    /** agent → 临时应激目标（批次 D：被质疑→辩解，瞬时高优先 + N 轮衰减回落，对齐 Bates 情绪→目标再评价）。
     *  临时目标存在时压过手动/规则目标；衰减到期后回落。 */
    private final Map<String, TemporaryGoal> temporaryGoals = new ConcurrentHashMap<>();

    /** Phase 4 LLM 扩展点：可为 null（null 时 generateGoalWithLLM 直接规则回退）。 */
    private final LLMClient llmClient;

    public WorldDirectorService() {
        this(null);
    }

    public WorldDirectorService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 目标输出记录：与需求文档输出对齐，含优先级用于冲突裁决。 */
    public record AgentGoal(String agentId, String goal, int priority) {}

    /**
     * 临时应激目标（批次 D）：瞬时高优先级、N 轮线性衰减后回落（Bates 1994：情绪→目标再评价）。
     * 示例：被质疑 → 辩解目标 pri 100 压过日常隐藏/脱罪目标，衰减 N 轮后回落到主动机。
     */
    public static final class TemporaryGoal {
        public final String goal;
        public volatile int priority;
        public volatile int remainingRounds;
        public final int decayStep;

        TemporaryGoal(String goal, int priority, int remainingRounds, int decayStep) {
            this.goal = goal;
            this.priority = priority;
            this.remainingRounds = remainingRounds;
            this.decayStep = decayStep;
        }
    }

    /** 显式设定目标（玩家指令 / 秘密任务注入）。粘性：规则更新不覆盖。旧签名保留（等价 pri=100 sticky）。 */
    public void setGoal(String agent, String goal) {
        setGoal(agent, goal, MANUAL_GOAL_PRIORITY, true);
    }

    /** 显式设定目标并指定优先级（粘性）。 */
    public void setGoal(String agent, String goal, int priority) {
        setGoal(agent, goal, priority, true);
    }

    /**
     * 显式设定目标：priority 为动机优先级（高优先=更愿意为推进该目标而主动发言），
     * sticky=true 粘性（updateGoals 不覆盖）/ false 非粘性（规则可覆盖）。
     */
    public void setGoal(String agent, String goal, int priority, boolean sticky) {
        if (agent == null || agent.isBlank()) return;
        int p = Math.max(1, priority);
        if (sticky) {
            manualGoals.put(agent, goal);
            manualPriorities.put(agent, p);
            agentGoals.put(agent, goal);
            goalDetails.put(agent, new AgentGoal(agent, goal, p));
        } else {
            // 非粘性：立即生效但不受 updateGoals 保护（规则可覆盖），也不锁 manualGoals
            agentGoals.put(agent, goal);
            goalDetails.put(agent, new AgentGoal(agent, goal, p));
        }
    }

    /**
     * 临时应激目标：瞬时高优先级压过当前目标，decayRounds 轮线性衰减后移除并回落到手动/规则目标。
     * （批次 D：被质疑→辩解 pri 100，priority-decay-rounds 可配，默认 3 轮）
     */
    public void pushTemporaryGoal(String agent, String goal, int priority, int decayRounds) {
        if (agent == null || agent.isBlank() || goal == null || goal.isBlank()) return;
        int rounds = Math.max(1, decayRounds);
        int step = Math.max(1, Math.max(1, priority) / rounds);
        temporaryGoals.put(agent, new TemporaryGoal(goal, Math.max(1, priority), rounds, step));
        agentGoals.put(agent, goal);
        goalDetails.put(agent, new AgentGoal(agent, goal, Math.max(1, priority)));
    }

    /**
     * 每轮衰减临时目标（调用方在每轮决策前调用）。到期 → 移除并回落到手动（粘性）目标或规则目标。
     * 返回本轮受影响（衰减/到期）的角色列表，供调用方观测。
     */
    public List<String> decayTemporaryGoals() {
        List<String> affected = new java.util.ArrayList<>();
        if (temporaryGoals.isEmpty()) return affected;
        for (Map.Entry<String, TemporaryGoal> e : temporaryGoals.entrySet()) {
            TemporaryGoal tg = e.getValue();
            tg.remainingRounds--;
            if (tg.remainingRounds <= 0) {
                affected.add(e.getKey());
            } else {
                tg.priority = Math.max(1, tg.priority - tg.decayStep);
                affected.add(e.getKey());
            }
        }
        for (String a : affected) {
            TemporaryGoal tg = temporaryGoals.get(a);
            if (tg != null) {
                agentGoals.put(a, tg.goal);
                goalDetails.put(a, new AgentGoal(a, tg.goal, tg.priority));
            } else {
                recomputeEffectiveGoal(a);
            }
        }
        return affected;
    }

    /** 临时目标到期/被清除后，回落到手动（粘性）目标；无手动目标则清空（交由规则 updateGoals 重建）。 */
    private void recomputeEffectiveGoal(String agent) {
        if (manualGoals.containsKey(agent)) {
            String g = manualGoals.get(agent);
            agentGoals.put(agent, g);
            goalDetails.put(agent, new AgentGoal(agent, g, manualPriorities.getOrDefault(agent, MANUAL_GOAL_PRIORITY)));
        } else {
            agentGoals.remove(agent);
            goalDetails.remove(agent);
        }
    }

    /** 清除手动目标与有效目标，恢复规则驱动。 */
    public void clearGoal(String agent) {
        manualGoals.remove(agent);
        manualPriorities.remove(agent);
        temporaryGoals.remove(agent);
        agentGoals.remove(agent);
        goalDetails.remove(agent);
    }

    public String getGoal(String agent) {
        return agentGoals.get(agent);
    }

    /** 当前有效目标优先级（批次 D：动机优先级——临时目标 > 手动目标 > 规则目标详情；缺省 50）。 */
    public int getGoalPriority(String agent) {
        if (agent == null) return 50;
        TemporaryGoal tg = temporaryGoals.get(agent);
        if (tg != null) return tg.priority;
        if (manualGoals.containsKey(agent)) {
            return manualPriorities.getOrDefault(agent, MANUAL_GOAL_PRIORITY);
        }
        AgentGoal g = goalDetails.get(agent);
        return g != null ? g.priority() : 50;
    }

    /** 当前全部有效目标（agent → goal）。 */
    public Map<String, String> getAllGoals() {
        return new HashMap<>(agentGoals);
    }

    /** 当前目标详情（含优先级）。 */
    public Map<String, AgentGoal> getGoalDetails() {
        return new HashMap<>(goalDetails);
    }

    public Map<String, String> updateGoals(SimulationWorld world, List<AgentState> agents) {
        return updateGoals(world, agents, System.currentTimeMillis());
    }

    /**
     * 每 tick 规则式更新目标。返回本次更新后的 agent → goal 映射。
     *
     * @param world 世界（Phase 4 LLM 扩展点；当前规则不使用）
     * @param agents 要评估的角色
     * @param now    当前时间戳（毫秒），用于"长时间无活动"判定
     */
    public Map<String, String> updateGoals(SimulationWorld world, List<AgentState> agents, long now) {
        Map<String, String> updated = new LinkedHashMap<>();
        if (agents == null) return updated;
        for (AgentState s : agents) {
            String name = s.getAgentName();
            if (name == null) continue;
            // 临时应激目标优先于一切（批次 D：辩解等瞬时目标压过手动/规则）。
            if (temporaryGoals.containsKey(name)) {
                updated.put(name, temporaryGoals.get(name).goal);
                continue;
            }
            // 手动目标粘性优先：外部注入的目标不被规则覆盖。
            if (manualGoals.containsKey(name)) {
                updated.put(name, manualGoals.get(name));
                continue;
            }
            AgentGoal ruleGoal = decideGoal(s, now);
            agentGoals.put(name, ruleGoal.goal());
            goalDetails.put(name, ruleGoal);
            updated.put(name, ruleGoal.goal());
        }
        return updated;
    }

    /** 规则式目标决策：情绪异常 > 参与讨论 > 探索周围 > 闲逛。 */
    private AgentGoal decideGoal(AgentState s, long now) {
        if (ABNORMAL_EMOTIONS.contains(s.getEmotion())) {
            return new AgentGoal(s.getAgentName(), GOAL_CALM, 30);
        }
        if (s.isInConversation()) {
            return new AgentGoal(s.getAgentName(), GOAL_JOIN_DISCUSSION, 20);
        }
        long last = s.getLastConversationTime();
        boolean idle = last <= 0 || now - last > IDLE_THRESHOLD_MS;
        if (idle && !s.isHasTarget()) {
            return new AgentGoal(s.getAgentName(), GOAL_EXPLORE, 10);
        }
        return new AgentGoal(s.getAgentName(), GOAL_WANDER, 5);
    }

    // ── Phase 4 LLM 版目标生成（手动扩展点，不自动触发） ────────

    /**
     * LLM 版目标生成：用 LLMClient 为单个角色生成目标（参照 EavesdropSummarizer
     * 的调用方式）。成功 → 写入手动目标（粘性，优先级 100）；失败 / 无 LLMClient →
     * 规则回退 {@link #decideGoal}。不接自动触发：保持规则为主，LLM 为手动扩展点。
     *
     * @param agent   目标角色
     * @param context 上下文提示（场景 / 秘密任务 / 用户指令等），可为空
     * @return 生成的目标文案
     */
    public String generateGoalWithLLM(AgentState agent, String context) {
        if (agent == null) return GOAL_WANDER;
        if (llmClient == null) {
            return ruleFallbackGoal(agent);
        }
        try {
            Map<String, Object> result = llmClient.callJson(buildGoalPrompt(agent, context), 300);
            Object goal = result.get("goal");
            if (goal instanceof String s && !s.isBlank()) {
                setGoal(agent.getAgentName(), s);
                log.info("LLM goal for {}: {}", agent.getAgentName(), s);
                return s;
            }
        } catch (Exception e) {
            log.warn("LLM goal generation failed for {}: {}", agent.getAgentName(), e.getMessage());
        }
        return ruleFallbackGoal(agent);
    }

    /** 规则回退：复用现有规则决策，不引入新逻辑。 */
    private String ruleFallbackGoal(AgentState agent) {
        if (agent == null) return GOAL_WANDER;
        return decideGoal(agent, System.currentTimeMillis()).goal();
    }

    private String buildGoalPrompt(AgentState agent, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是角色扮演世界导演（DM），为单个角色生成下一步目标。\n\n");
        sb.append("角色：").append(agent.getAgentName());
        sb.append(" | 位置(").append(Math.round(agent.getX())).append(",").append(Math.round(agent.getY())).append(")");
        sb.append(" | 情绪:").append(agent.getEmotion().getLabel());
        sb.append(" | 对话中:").append(agent.isInConversation() ? "是" : "否");
        if (agent.isHasTarget()) {
            sb.append(" | 当前走向(").append(Math.round(agent.getTargetX())).append(",")
                    .append(Math.round(agent.getTargetY())).append(")");
        }
        sb.append("\n");
        if (context != null && !context.isBlank()) {
            sb.append("上下文：").append(context).append("\n");
        }
        sb.append("目标须是简短中文短语（2-10字，如 调查/隐瞒/参与讨论/平静情绪/探索周围/闲逛）。\n");
        sb.append("JSON格式：{\"goal\":\"目标文案\"}");
        return sb.toString();
    }
}
