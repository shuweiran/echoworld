package com.roleplay.engine.simulation.movement;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.track.TrackAssignment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 4 Movement Constraint — 轨道 → 运动约束（需求文档第八条：Track 不直接移动
 * 角色，而是生成空间约束；第九条三种轨道对应运动）。纯规则计算，零 LLM。
 *
 * <p>输入 Track 分配（TrackDirector 产物），输出每个角色的期望位置
 * {@link MovementTarget}，规则表：
 * <ul>
 *   <li><b>MERGED</b>   → 跟随/聚集：GroupAnchor（leader + follow slot）。leader = 组内
 *       字典序最小名的成员（确定性锚点），收敛到组质心保持凝聚；其余成员按名称序占
 *       follow slot（k=1,2,…），排列在 leader 后方一条直线上（间隔 {@link #SLOT_SPACING}）。
 *       leader 移动时成员随槽位重算跟随——需求文档 §九-十「群体移动系统」。</li>
 *   <li><b>WEAK</b>     → 保持听觉范围：期望距离 ∈ [hearRange*0.5, hearRange]
 *       （太近 → 拉远到 0.7·hearRange 避免贴脸偷听；太远 → 靠近到 0.8·hearRange 避免跟丢）</li>
 *   <li><b>ISOLATED</b> → 主动保持距离：距 secretAgents/指定避让目标 ≥ 60 格
 *       （过近 → 沿反向推到 60·1.2 格）</li>
 * </ul>
 *
 * <p>冲突优先级（需求文档第九条"冲突时"）：<b>ISOLATED 避让 &gt; WEAK 保持距离
 * &gt; MERGED 跟随</b>。同一角色只会得到一个约束目标；若角色本身在秘密任务集合中
 * （即使其分配是 MERGED / WEAK），也按 ISOLATED 避让处理。
 *
 * <p>角色已处于期望位置附近（&lt; {@link #ARRIVAL_EPSILON}）时不输出目标，
 * 避免每 tick 反复重设目标导致抖动。
 */
public class MovementConstraint {

    private final SimulationWorld world;

    /** 兼容既有纯规则单测；运行时由 SimulationService 注入同一 SimulationWorld。 */
    public MovementConstraint() {
        this.world = null;
    }

    public MovementConstraint(SimulationWorld world) {
        this.world = world;
    }

    /** ISOLATED：与 secretAgents/指定目标的最小安全距离（格）。 */
    public static final double ISOLATED_SAFE_DISTANCE = 60.0;
    /** ISOLATED：过近时把角色推到 安全距离×1.2 处（留出缓冲）。 */
    public static final double ISOLATED_PUSH_FACTOR = 1.2;
    /** WEAK：听觉带下限因子（距离 ≥ hearRange×0.5 才算"不贴脸"）。 */
    public static final double WEAK_MIN_FACTOR = 0.5;
    /** WEAK：太近时的目标距离因子（拉远到 hearRange×0.7）。 */
    public static final double WEAK_TARGET_FACTOR_CLOSE = 0.7;
    /** WEAK：太远时的目标距离因子（靠近到 hearRange×0.8）。 */
    public static final double WEAK_TARGET_FACTOR_FAR = 0.8;
    /** GroupAnchor：leader 后方 follow slot 的间隔（格）。 */
    public static final double SLOT_SPACING = 16.0;
    /** P-0815-A：MERGED 成员距 leader 超过该距离（px）不强制归队（保持原位，防瞬移式聚拢）。
     *  调研报告 2.4 #6——全场景 allMerged 下相距 400px+ 的成员也会被物理拉到 leader 队形；
     *  超距成员本轮不输出目标（MovementSystem 按自由漫游），待自然靠近后再归队。 */
    public static final double MERGED_MAX_FOLLOW_DISTANCE = 300.0;
    /** 距期望位置小于该值时不再输出目标（防抖动）。 */
    public static final double ARRIVAL_EPSILON = 8.0;

    // ── 主入口 ────────────────────────────────────────────────

    /**
     * 计算全场景运动约束。
     *
     * @param world       当前世界（取全部 AgentState）
     * @param assignments agent → TrackAssignment（TrackDirector / Orchestrator 产物）
     * @param secretAgents 秘密任务成员集合，兼作 ISOLATED 避让目标
     * @return agent → MovementTarget（无约束的角色不出现在结果中）
     */
    public Map<String, MovementTarget> compute(SimulationWorld world,
                                               Map<String, TrackAssignment> assignments,
                                               Set<String> secretAgents) {
        if (world == null) return Map.of();
        return compute(world.getAllStates().values(), assignments, secretAgents);
    }

    /**
     * 纯函数版：直接喂 AgentState 集合（便于单元测试）。
     *
     * @param secretAgents 秘密任务成员集合；额外避让目标可用 {@link #compute(Collection, Map, Set, Set)}
     */
    public Map<String, MovementTarget> compute(Collection<AgentState> agents,
                                               Map<String, TrackAssignment> assignments,
                                               Set<String> secretAgents) {
        return compute(agents, assignments, secretAgents, Set.of());
    }

    /**
     * 带额外避让目标的版本：ISOLATED 角色会同时避开 secretAgents 与 avoidTargets。
     */
    public Map<String, MovementTarget> compute(Collection<AgentState> agents,
                                               Map<String, TrackAssignment> assignments,
                                               Set<String> secretAgents,
                                               Set<String> avoidTargets) {
        Map<String, MovementTarget> result = new LinkedHashMap<>();
        if (agents == null || agents.isEmpty()
                || assignments == null || assignments.isEmpty()) {
            return result;
        }
        Map<String, AgentState> byName = new HashMap<>();
        for (AgentState s : agents) {
            byName.put(s.getAgentName(), s);
        }

        Set<String> avoid = new java.util.LinkedHashSet<>();
        if (secretAgents != null) avoid.addAll(secretAgents);
        if (avoidTargets != null) avoid.addAll(avoidTargets);

        for (AgentState self : agents) {
            String name = self.getAgentName();
            if (name == null) continue;
            TrackAssignment ta = assignments.get(name);
            if (ta == null) continue;

            MovementTarget target;
            if (avoid.contains(name)) {
                // 秘密任务成员：ISOLATED 避让优先级最高，覆盖任何分配。
                target = computeIsolated(self, byName, avoid);
            } else {
                target = switch (ta.type()) {
                    case ISOLATED -> computeIsolated(self, byName, avoid);
                    case WEAK -> computeWeak(self, byName, ta);
                    case MERGED -> computeMerged(self, byName, ta);
                };
            }
            if (target != null) {
                result.put(name, target);
            }
        }
        return result;
    }

    /**
     * 把约束目标写回 AgentState（覆盖/补充当前目标）。
     *
     * <p>玩家手动控制优先：{@code playerControlled}（玩家亲自扮演的角色）与
     * {@code manualTarget}（/target 端点手动指定的目标）不会被约束覆盖；
     * 对话中的角色不写入（MovementSystem 对对话中角色本就清零速度）。
     */
    public void apply(SimulationWorld world, Map<String, MovementTarget> targets) {
        if (world == null || targets == null || targets.isEmpty()) return;
        for (MovementTarget t : targets.values()) {
            AgentState s = world.getState(t.agentName());
            if (s == null || s.isPlayerControlled() || s.isManualTarget() || s.isInConversation()) {
                continue;
            }
            s.setAutonomousTarget(t.targetX(), t.targetY());
        }
    }

    // ── MERGED：GroupAnchor（leader + follow slot）──────────────

    /**
     * MERGED 群组成员 → GroupAnchor 队形。
     * 成员集合 = 自己 + assignment.visibleAgents（同组可见伙伴）。
     *
     * <p>需求文档 §九-十「群体移动系统」：组内选定 leader 作为移动锚点，其余成员占
     * follow slot 形成队形，leader 移动时成员跟随。
     * <ul>
     *   <li><b>leader</b> = 组内字典序最小名的成员（确定性，跨 tick 稳定）→ 收敛到组质心
     *       （保持凝聚，同时作为其余成员的跟随锚点）</li>
     *   <li><b>follow slot</b> = 其余成员按名称序 k=1,2,…，目标 = leader 位置 +
     *       {formationAngle(leader)} × SLOT_SPACING × k（一条直线队形，避免叠点）</li>
     * </ul>
     */
    private MovementTarget computeMerged(AgentState self, Map<String, AgentState> byName,
                                         TrackAssignment ta) {
        List<AgentState> members = new ArrayList<>();
        members.add(self);
        for (String v : ta.visibleAgents()) {
            AgentState s = byName.get(v);
            if (s != null && s != self) members.add(s);
        }
        if (members.size() < 2) return null; // 无同伴 → 无需队形

        String leaderName = members.stream()
                .map(AgentState::getAgentName)
                .filter(Objects::nonNull)
                .min(String::compareTo)
                .orElse(null);
        if (leaderName == null) return null;
        AgentState leader = byName.get(leaderName);
        if (leader == null) return null;

        // leader：收敛到组质心（锚点；成员以它为参照跟队）。
        if (leaderName.equals(self.getAgentName())) {
            double cx = 0, cy = 0;
            for (AgentState m : members) {
                cx += m.getX();
                cy += m.getY();
            }
            cx /= members.size();
            cy /= members.size();
            if (distance(self, cx, cy) < ARRIVAL_EPSILON) return null;
            return new MovementTarget(self.getAgentName(), clamp(cx), clamp(cy),
                    "MERGED 群组锚点（leader 聚向组质心）");
        }

        // 跟随者：按名称序占 follow slot，排列在 leader 后方直线队形。
        List<String> followerNames = members.stream()
                .map(AgentState::getAgentName)
                .filter(n -> n != null && !n.equals(leaderName))
                .sorted()
                .toList();
        int k = followerNames.indexOf(self.getAgentName()) + 1;
        double dir = angleFor(leaderName);

        // P-0815-A：距离守卫——成员距 leader 超过 MERGED_MAX_FOLLOW_DISTANCE（px）不强制归队
        // （保持原位，避免远处成员被瞬移式聚拢到队形；等自然靠近后再归队）。
        if (distance(self, leader.getX(), leader.getY()) > MERGED_MAX_FOLLOW_DISTANCE) {
            return null;
        }

        double tx = leader.getX() + Math.cos(dir) * SLOT_SPACING * k;
        double ty = leader.getY() + Math.sin(dir) * SLOT_SPACING * k;

        if (distance(self, tx, ty) < ARRIVAL_EPSILON) return null;
        return new MovementTarget(self.getAgentName(), clamp(tx), clamp(ty),
                "MERGED 跟随 leader(" + leaderName + ") 槽位#" + k);
    }

    // ── WEAK：保持听觉范围 ────────────────────────────────────

    /**
     * WEAK 监听者 → 目标距离 ∈ [hearRange*0.5, hearRange]。
     * 监听锚点 = 最近的可见监听对象；无则回退最近的非 ISOLATED 角色。
     */
    private MovementTarget computeWeak(AgentState self, Map<String, AgentState> byName,
                                       TrackAssignment ta) {
        AgentState anchor = findAnchor(self, byName, ta);
        if (anchor == null) return null;

        double h = Math.max(self.getHearRange(), 1.0);
        double d = self.distanceTo(anchor);
        double targetDist;
        String reason;
        if (d < h * WEAK_MIN_FACTOR) {
            // 太近（贴脸偷听）→ 拉远到听觉带中部偏外。
            targetDist = h * WEAK_TARGET_FACTOR_CLOSE;
            reason = "WEAK 监听保持距离（太近，拉远）";
        } else if (d > h) {
            // 太远（听不到）→ 靠近到听觉带内。
            targetDist = h * WEAK_TARGET_FACTOR_FAR;
            reason = "WEAK 监听保持距离（太远，靠近）";
        } else {
            return null; // 已在 [0.5h, h] 听觉带内。
        }

        double dx = self.getX() - anchor.getX();
        double dy = self.getY() - anchor.getY();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01) { dx = 1; dy = 0; len = 1; }

        double tx = clamp(anchor.getX() + (dx / len) * targetDist);
        double ty = clamp(anchor.getY() + (dy / len) * targetDist);
        if (distance(self, tx, ty) < ARRIVAL_EPSILON) return null;
        return new MovementTarget(self.getAgentName(), tx, ty, reason);
    }

    /** 监听锚点：优先 assignment.visibleAgents 中最近者，回退最近的非 ISOLATED 角色。 */
    private AgentState findAnchor(AgentState self, Map<String, AgentState> byName,
                                  TrackAssignment ta) {
        AgentState best = null;
        double bestDist = Double.MAX_VALUE;
        for (String v : ta.visibleAgents()) {
            AgentState s = byName.get(v);
            if (s == null || s == self) continue;
            double d = self.distanceTo(s);
            if (d < bestDist) { bestDist = d; best = s; }
        }
        if (best != null) return best;

        for (AgentState s : byName.values()) {
            if (s == self) continue;
            double d = self.distanceTo(s);
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return best;
    }

    // ── ISOLATED：主动保持距离 ────────────────────────────────

    /**
     * ISOLATED → 距最近的 secretAgents/指定避让目标 ≥ ISOLATED_SAFE_DISTANCE。
     * 过近时沿"远离威胁"方向推到 安全距离×PUSH_FACTOR 处。
     */
    private MovementTarget computeIsolated(AgentState self, Map<String, AgentState> byName,
                                           Set<String> avoid) {
        if (avoid == null || avoid.isEmpty()) return null;

        AgentState nearestThreat = null;
        double nearestD = Double.MAX_VALUE;
        for (String a : avoid) {
            AgentState t = byName.get(a);
            if (t == null || t == self) continue;
            double d = self.distanceTo(t);
            if (d < nearestD) { nearestD = d; nearestThreat = t; }
        }
        if (nearestThreat == null || nearestD >= ISOLATED_SAFE_DISTANCE) return null;

        double dx = self.getX() - nearestThreat.getX();
        double dy = self.getY() - nearestThreat.getY();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01) { dx = 1; dy = 0; len = 1; }

        double targetDist = ISOLATED_SAFE_DISTANCE * ISOLATED_PUSH_FACTOR;
        double tx = clamp(nearestThreat.getX() + (dx / len) * targetDist);
        double ty = clamp(nearestThreat.getY() + (dy / len) * targetDist);
        if (distance(self, tx, ty) < ARRIVAL_EPSILON) return null;
        return new MovementTarget(self.getAgentName(), tx, ty,
                "ISOLATED 保持安全距离（远离 " + nearestThreat.getAgentName() + "）");
    }

    // ── 工具 ──────────────────────────────────────────────────

    private static double angleFor(String name) {
        int h = name == null ? 0 : name.hashCode() & 0x7fffffff;
        return (h % 360) * Math.PI / 180.0;
    }

    private double clamp(double v) {
        double min = SimulationWorld.WORLD_MARGIN + 20;
        double maxX = (world == null ? SimulationWorld.DEFAULT_WORLD_WIDTH : world.getWorldWidth()) - min;
        double maxY = (world == null ? SimulationWorld.DEFAULT_WORLD_HEIGHT : world.getWorldHeight()) - min;
        return Math.max(min, Math.min(maxX, Math.min(maxY, v)));
    }

    private static double distance(AgentState s, double x, double y) {
        double dx = s.getX() - x;
        double dy = s.getY() - y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
