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

    /** agent → 当前有效目标（规则或手动）。 */
    private final Map<String, String> agentGoals = new ConcurrentHashMap<>();
    /** agent → 手动设定目标（粘性，updateGoals 不覆盖）。 */
    private final Map<String, String> manualGoals = new ConcurrentHashMap<>();
    /** agent → 目标详情（含优先级），供输出/调试。 */
    private final Map<String, AgentGoal> goalDetails = new ConcurrentHashMap<>();

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

    /** 显式设定目标（玩家指令 / 秘密任务注入）。粘性：规则更新不覆盖。 */
    public void setGoal(String agent, String goal) {
        manualGoals.put(agent, goal);
        agentGoals.put(agent, goal);
        goalDetails.put(agent, new AgentGoal(agent, goal, MANUAL_GOAL_PRIORITY));
    }

    /** 清除手动目标与有效目标，恢复规则驱动。 */
    public void clearGoal(String agent) {
        manualGoals.remove(agent);
        agentGoals.remove(agent);
        goalDetails.remove(agent);
    }

    public String getGoal(String agent) {
        return agentGoals.get(agent);
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
