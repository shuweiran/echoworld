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
 *
 * <p>P-0815-E：编排器只管 AI 角色——玩家角色（{@code playerControlled}，如 2D 世界
 * 中玩家亲自扮演的角色）的行为/移动/发言由玩家自己控制，编排器不为玩家生成导演目标、
 * 不为其做轨道分配（含对话组内）；玩家在组内发言直通 ConversationManager，不受影响。
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
     *   <li>Track Director：InteractionDetector 评分 + SpatialTrackResolver 分配（谁知道什么）
     *       ——P-0815-A：不再把全场景 agents 一次性喂给 assign 做 allMerged（无触发时全员两两互见、
     *       与距离无关）；改为「每个活动对话组 + 每个听力连通分量」分别 assign：无敏感触发时
     *       MERGED 只覆盖同分量成员，单人分量（无听力接触）直接 ISOLATED。</li>
     *   <li>将分配结果回写当前活动群组（Phase 2 TrackStrategy 读取并构建隔离上下文）</li>
     * </ol>
     */
    public Map<String, TrackAssignment> tick(long now) {
        List<AgentState> agents = new ArrayList<>(world.getAllStates().values());
        if (agents.isEmpty()) return Map.of();

        // P-0815-E：编排器只管 AI 角色——玩家角色（playerControlled）的行为/移动/发言
        // 由玩家自己决定：不为其生成导演目标（World Director），也不为其做轨道分配。
        // 玩家若在对话组内仍作为组员参与（发言直通 ConversationManager），仅不参与
        // 目标/轨道决策。
        List<AgentState> aiAgents = new ArrayList<>();
        for (AgentState a : agents) {
            if (!a.isPlayerControlled()) aiAgents.add(a);
        }

        // 1. World Director 更新角色目标（仅 AI 角色；玩家无导演目标）。
        Map<String, String> goals = worldDirector.updateGoals(world, aiAgents, now);

        // P-0815-A：空间网格重建（听力计算前置；生产每 tick 已由 MovementSystem.update 重建，
        // 此处幂等重建保证 orchestrator.tick 直调（单元测试/时序差异）时分量计算确定性）。
        world.getSpatialGrid().rebuild(agents);

        // 2. Track Director 生成 Track 关系——按活动组 + 听力连通分量分别分配。
        Map<String, TrackAssignment> assignments = new java.util.LinkedHashMap<>();

        // 2a. 每个活动对话组独立分配（组内轨道决定谁知道什么；applyToGroup 同时回写组对象）。
        java.util.Set<String> groupMembers = new java.util.HashSet<>();
        if (conversationManager != null) {
            for (ConversationGroup group : conversationManager.getActiveGroups()) {
                for (AgentState m : group.getParticipantList()) {
                    groupMembers.add(m.getAgentName());
                }
            }
            for (ConversationGroup group : conversationManager.getActiveGroups()) {
                assignments.putAll(applyToGroup(group));
            }
        }

        // 2b. 组外 agent 按听力连通分量分别 assign：无触发时 MERGED 只覆盖同分量成员；
        //     单人分量（无任何听力接触）→ ISOLATED（不再全场景 allMerged 两两互见）。
        //     P-0815-E：玩家角色不参与自由轨道分配（不入听力分量/assign）。
        List<AgentState> freeAgents = new ArrayList<>();
        for (AgentState a : agents) {
            if (a.isPlayerControlled()) continue;
            if (!groupMembers.contains(a.getAgentName())) freeAgents.add(a);
        }
        for (List<AgentState> component : hearingComponents(freeAgents)) {
            if (component.size() == 1) {
                AgentState lone = component.get(0);
                assignments.put(lone.getAgentName(), TrackAssignment.isolated(
                        lone.getAgentName(), "完全隔离（无听力接触）"));
            } else {
                assignments.putAll(trackDirector.assign(component, goals));
            }
        }

        // 3. 回写活动群组：2D 群聊自动按轨道隔离（TrackStrategy 每轮读取）。
        //    （applyToGroup 已在 2a 完成回写，此处仅保留语义注释。）
        return assignments;
    }

    /**
     * 听力连通分量（调研报告 2.4 #4）：对输入角色跑 HearingSystem 声学判定，
     * canHear 边建无向图后求连通分量（与 ModeClassifier 同款传递闭包语义，
     * 但输入=组外 agent；链式可听成组由分量直径/组分配语义自然约束）。
     */
    private List<List<AgentState>> hearingComponents(List<AgentState> agents) {
        List<List<AgentState>> components = new ArrayList<>();
        if (agents == null || agents.isEmpty()) return components;

        Map<String, List<String>> adjacency = new java.util.LinkedHashMap<>();
        for (HearingSystem.HearingResult h : world.getHearingSystem().computeAudibility(agents)) {
            if (!h.canHear()) continue;
            adjacency.computeIfAbsent(h.speakerName(), k -> new ArrayList<>()).add(h.listenerName());
            adjacency.computeIfAbsent(h.listenerName(), k -> new ArrayList<>()).add(h.speakerName());
        }

        java.util.Set<String> visited = new java.util.HashSet<>();
        for (AgentState start : agents) {
            String startName = start.getAgentName();
            if (visited.contains(startName)) continue;
            List<AgentState> component = new ArrayList<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            queue.add(startName);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                if (!visited.add(cur)) continue;
                AgentState st = world.getState(cur);
                if (st != null) component.add(st);
                for (String neighbor : adjacency.getOrDefault(cur, List.of())) {
                    if (!visited.contains(neighbor)) queue.add(neighbor);
                }
            }
            components.add(component);
        }
        return components;
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
        // P-0815-E：仅对 AI 成员做轨道分配——玩家角色（playerControlled）的轨道/谁知道什么
        // 不由编排器决定（玩家在组内发言直通 ConversationManager，无需轨道条目；
        // AI 成员的轨道上下文仍按 AI 成员集合计算，不受影响）。
        List<AgentState> aiMembers = new ArrayList<>();
        for (AgentState m : group.getParticipantList()) {
            if (!m.isPlayerControlled()) aiMembers.add(m);
        }
        Map<String, TrackAssignment> assignments =
                trackDirector.assign(aiMembers, worldDirector.getAllGoals());
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
