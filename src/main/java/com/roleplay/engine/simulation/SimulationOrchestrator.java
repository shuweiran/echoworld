package com.roleplay.engine.simulation;

import com.roleplay.engine.simulation.conversation.ConversationGroup;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import com.roleplay.engine.simulation.director.WorldDirectorService;
import com.roleplay.engine.simulation.track.InteractionDetector;
import com.roleplay.engine.simulation.track.TrackAssignment;
import com.roleplay.engine.interrupt.TrackChangeEvent;
import com.roleplay.engine.interrupt.WorldEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3 Simulation Orchestrator — 2D 世界每 tick 的双导演编排入口。
 *
 * <p>需求文档第十四条：不立即重构 RouterService（风险：影响 SSE / Memory / Agent 调用 /
 * 游戏模式），而是新增外层编排器，先 Router → Orchestrator → Track/World，稳定后再拆。
 *
 * <p>每 tick 流程（需求文档第十五条）：收集 AgentState → World Director 更新角色目标 →
 * Interaction Detector 计算 TrackScore → Track Director（SpatialTrackResolver）生成
 * Track 关系 → 写入 ConversationGroup（Phase 2 {@code TrackStrategy} 自动消费）→
 * MovementSystem 照常（由 {@link SimulationWorld#tick} 运行，本类不干预）。
 *
 * <p>设计约束：不改变 ConversationManager 现有 tick 流程，只补充"轨道分配"输入；
 * 不碰 RouterService / ArbiterService。
 */
public class SimulationOrchestrator {

    private final SimulationWorld world;
    private final WorldDirectorService worldDirector;
    private final TrackDirectorService trackDirector;
    /** 可空：为 null 时只产出分配结果，不回写活动群组（便于纯函数测试）。 */
    private final ConversationManager conversationManager;
    /** D1: 世界事件总线（可空：测试直构时不发布轨道变化事件）。 */
    private final WorldEventBus eventBus;

    public SimulationOrchestrator(SimulationWorld world,
                                  WorldDirectorService worldDirector,
                                  TrackDirectorService trackDirector,
                                  ConversationManager conversationManager) {
        this(world, worldDirector, trackDirector, conversationManager, null);
    }

    public SimulationOrchestrator(SimulationWorld world,
                                  WorldDirectorService worldDirector,
                                  TrackDirectorService trackDirector,
                                  ConversationManager conversationManager,
                                  WorldEventBus eventBus) {
        this.world = world;
        this.worldDirector = worldDirector;
        this.trackDirector = trackDirector;
        this.conversationManager = conversationManager;
        this.eventBus = eventBus;
    }

    /**
     * 每 tick 双导演编排入口。返回全场景 agent → TrackAssignment。
     *
     * <ol>
     *   <li>World Director：规则式更新角色目标（角色想做什么）</li>
     *   <li>Track Director：InteractionDetector 评分 + SpatialTrackResolver 分配（谁知道什么）</li>
     *   <li>将分配结果回写当前活动群组（Phase 2 TrackStrategy 读取并构建隔离上下文）</li>
     * </ol>
     */
    public Map<String, TrackAssignment> tick(long now) {
        List<AgentState> agents = new ArrayList<>(world.getAllStates().values());
        if (agents.isEmpty()) return Map.of();

        // 1. World Director 更新角色目标。
        Map<String, String> goals = worldDirector.updateGoals(world, agents, now);

        // 2. Track Director 生成 Track 关系（内部含 InteractionDetector 评分 + 空间分配）。
        Map<String, TrackAssignment> assignments = trackDirector.assign(agents, goals);

        // 3. 回写活动群组：2D 群聊自动按轨道隔离（TrackStrategy 每轮读取）。
        if (conversationManager != null) {
            for (ConversationGroup group : conversationManager.getActiveGroups()) {
                applyToGroup(group);
            }
        }
        return assignments;
    }

    /**
     * 对单个群组执行 TrackDirector 分配并写入其 trackAssignments。
     * 群组成员自动拥有当前全部目标 / 秘密任务视角（谁知道什么）。
     *
     * <p>D1: 若某成员轨道模式发生变化（如新增秘密任务 → MERGED 变 ISOLATED），
     * 发布 {@link TrackChangeEvent}，事件驱动取消其基于旧轨道的进行中生成任务
     * （需求文档第八条 §七：A/B 上下文失效 → 取消当前生成任务）。
     */
    public Map<String, TrackAssignment> applyToGroup(ConversationGroup group) {
        if (group == null) return Map.of();
        Map<String, TrackAssignment> oldAssignments = group.getTrackAssignments();
        Map<String, TrackAssignment> assignments =
                trackDirector.assign(group.getParticipantList(), worldDirector.getAllGoals());
        group.setTrackAssignments(assignments);
        if (eventBus != null) {
            publishTrackChangeIfNeeded(group, oldAssignments, assignments);
        }
        return assignments;
    }

    /** D1: 轨道模式变化检测 + TrackChangeEvent 发布（仅涉及成员所在轨道，命名空间 sim:{gid}:{mode}）。 */
    private void publishTrackChangeIfNeeded(ConversationGroup group,
                                            Map<String, TrackAssignment> oldAssignments,
                                            Map<String, TrackAssignment> assignments) {
        List<String> changedAgents = new ArrayList<>();
        for (Map.Entry<String, TrackAssignment> e : assignments.entrySet()) {
            TrackAssignment old = oldAssignments.get(e.getKey());
            if (old == null || old.type() != e.getValue().type()) {
                changedAgents.add(e.getKey());
            }
        }
        if (changedAgents.isEmpty()) return;

        // 新轨道布局：未变化成员沿用旧模式，变化成员用新模式（任务按 sim:{gid}:{mode} 匹配）
        String gid = group.getGroupId();
        Map<String, List<String>> trackAgents = new java.util.LinkedHashMap<>();
        List<String> newTrackIds = new ArrayList<>();
        for (Map.Entry<String, TrackAssignment> e : assignments.entrySet()) {
            String trackId = "sim:" + gid + ":" + e.getValue().type().name();
            trackAgents.computeIfAbsent(trackId, k -> new ArrayList<>()).add(e.getKey());
            if (!newTrackIds.contains(trackId)) newTrackIds.add(trackId);
        }
        eventBus.publish(new TrackChangeEvent("simulation", newTrackIds, trackAgents,
                List.of(), changedAgents));
    }

    // ── 委派给双导演的对外接口 ─────────────────────────────────

    /** World Director：显式设定角色目标（玩家指令 / 秘密任务注入）。 */
    public void setGoal(String agent, String goal) {
        worldDirector.setGoal(agent, goal);
    }

    /** World Director：清除显式目标，恢复规则驱动。 */
    public void clearGoal(String agent) {
        worldDirector.clearGoal(agent);
    }

    /** World Director：当前全部有效目标。 */
    public Map<String, String> getGoals() {
        return worldDirector.getAllGoals();
    }

    /** Track Director：标记秘密任务成员（强制 ISOLATED）。 */
    public void addSecretAgent(String name) {
        trackDirector.addSecretAgent(name);
    }

    public void removeSecretAgent(String name) {
        trackDirector.removeSecretAgent(name);
    }

    public void setSecretAgents(Set<String> names) {
        trackDirector.setSecretAgents(names);
    }

    public Set<String> getSecretAgents() {
        return trackDirector.getSecretAgents();
    }

    /** Track Director：最近一次 TrackScore（可观测性）。 */
    public InteractionDetector.TrackScore getLastTrackScore() {
        return trackDirector.getLastScore();
    }
}
