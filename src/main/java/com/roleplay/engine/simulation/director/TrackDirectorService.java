package com.roleplay.engine.simulation.director;

import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.track.InteractionDetector;
import com.roleplay.engine.simulation.track.SpatialTrackResolver;
import com.roleplay.engine.simulation.track.TrackAssignment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 Track Director — 轨道决策（谁知道什么）。
 *
 * <p>输入：一组 {@link AgentState} + WorldDirector 的目标 + 秘密任务集合；输出
 * {@code Map<String, TrackAssignment>}（agent → MERGED / WEAK / ISOLATED），
 * 输出格式对齐需求文档：{@code A+B MERGED / C WEAK / D ISOLATED}。
 *
 * <p>决策流程（对齐需求文档第十五条，复用 Phase 1/2 组件，不重复造轮子）：
 * <ol>
 *   <li>{@link InteractionDetector} 计算 TrackScore（人数/旁观者/立场冲突/秘密任务/情绪）</li>
 *   <li>score &lt; 阈值 且 无目标冲突 → 全部 MERGED（公开聊天）</li>
 *   <li>score ≥ 阈值 → {@link SpatialTrackResolver} 按距离分配 MERGED / WEAK / ISOLATED</li>
 *   <li>秘密任务成员（secretAgents）→ 强制 ISOLATED（需求文档"+50 秘密任务"）</li>
 *   <li>目标冲突（相同敏感目标 / 互斥目标对）→ 相关成员降为 WEAK（MERGED 才降级，ISOLATED 不升不降）</li>
 * </ol>
 *
 * <p>秘密任务集合由本类持有（{@link #addSecretAgent} / {@link #setSecretAgents}），
 * 供 SimulationOrchestrator / 玩家指令注入。
 */
public class TrackDirectorService {

    /** 与任何目标都不构成冲突的"良性"目标（普通日常行为）。 */
    private static final Set<String> BENIGN_GOALS = Set.of(
            WorldDirectorService.GOAL_WANDER,
            WorldDirectorService.GOAL_EXPLORE,
            WorldDirectorService.GOAL_JOIN_DISCUSSION,
            WorldDirectorService.GOAL_CALM,
            "");

    /** 互斥目标对：一方持有 A、另一方持有 B 即视为目标冲突。 */
    private static final List<Set<String>> EXCLUSIVE_GOAL_PAIRS = List.of(
            Set.of("调查", "隐瞒"),
            Set.of("揭发", "保护"),
            Set.of("追踪", "躲避"),
            Set.of("争夺", "回避"));

    private final InteractionDetector detector = new InteractionDetector();
    private final SpatialTrackResolver spatialResolver;

    /** 秘密任务成员：强制 ISOLATED。 */
    private final Set<String> secretAgents = ConcurrentHashMap.newKeySet();

    /** 最近一次分配的 TrackScore（可观测性）。 */
    private volatile InteractionDetector.TrackScore lastScore;

    public TrackDirectorService() {
        this(new SpatialTrackResolver());
    }

    /**
     * @param spatialResolver 空间轨道解析器（Phase 1 组件）；可自定义会话距离/私密房间
     */
    public TrackDirectorService(SpatialTrackResolver spatialResolver) {
        this.spatialResolver = spatialResolver == null ? new SpatialTrackResolver() : spatialResolver;
    }

    // ── 秘密任务注入 ───────────────────────────────────────────

    public void addSecretAgent(String name) {
        if (name != null && !name.isBlank()) secretAgents.add(name);
    }

    public void removeSecretAgent(String name) {
        secretAgents.remove(name);
    }

    public void setSecretAgents(Set<String> names) {
        secretAgents.clear();
        if (names != null) {
            names.forEach(this::addSecretAgent);
        }
    }

    public Set<String> getSecretAgents() {
        return Set.copyOf(secretAgents);
    }

    /** 最近一次分配的 TrackScore（供观测/日志）。 */
    public InteractionDetector.TrackScore getLastScore() {
        return lastScore;
    }

    // ── 轨道分配 ───────────────────────────────────────────────

    /** 无目标信息时的分配（秘密任务仍生效）。 */
    public Map<String, TrackAssignment> assign(List<AgentState> agents) {
        return assign(agents, Map.of());
    }

    /**
     * 轨道分配主入口。
     *
     * @param agents 同一交互场景中的角色（如一个群组/一片听觉区域）
     * @param goals  WorldDirector 输出的 agent → goal 映射（可为空）
     */
    public Map<String, TrackAssignment> assign(List<AgentState> agents, Map<String, String> goals) {
        Map<String, TrackAssignment> result = new LinkedHashMap<>();
        if (agents == null || agents.isEmpty()) return result;

        InteractionDetector.TrackScore score = detector.evaluate(agents, secretAgents);
        this.lastScore = score;
        Set<String> conflicted = findGoalConflicts(agents, goals);

        // 无敏感触发且无目标冲突 → 公开聊天：全部 MERGED（需求文档：不需要 → 全部 MERGED）。
        if (!score.triggered() && conflicted.isEmpty()) {
            return allMerged(agents);
        }

        // 需要 Track 模式 → 空间分配作为基线（距离 → MERGED / WEAK / ISOLATED）。
        result.putAll(spatialResolver.resolve(agents));

        // 秘密任务 → 强制 ISOLATED（需求文档"+50 秘密任务"）。
        for (String secret : secretAgents) {
            if (findAgent(agents, secret) != null) {
                result.put(secret, TrackAssignment.isolated(secret, "秘密任务执行中，强制隔离"));
            }
        }

        // 目标冲突 → 相关成员降为 WEAK（仅降级 MERGED；已 WEAK / ISOLATED 保持不变）。
        for (String name : conflicted) {
            TrackAssignment cur = result.get(name);
            if (cur == null) {
                result.put(name, TrackAssignment.of(name, Track.Mode.WEAK, List.of(),
                        "目标冲突，仅保留摘要观察"));
            } else if (cur.type() == Track.Mode.MERGED) {
                result.put(name, TrackAssignment.of(name, Track.Mode.WEAK, cur.visibleAgents(),
                        "目标冲突，降级为摘要观察"));
            }
        }
        return result;
    }

    /** 公开聊天模式：每个成员对同组其他人全部可见（MERGED）。 */
    private Map<String, TrackAssignment> allMerged(List<AgentState> agents) {
        Map<String, TrackAssignment> result = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        for (AgentState s : agents) {
            all.add(s.getAgentName());
        }
        for (String name : all) {
            List<String> visible = all.stream().filter(n -> !n.equals(name)).toList();
            result.put(name, TrackAssignment.of(name, Track.Mode.MERGED, visible,
                    "公开聊天模式（无敏感触发）"));
        }
        return result;
    }

    // ── 目标冲突判定 ───────────────────────────────────────────

    /**
     * 找出因目标冲突需要降级的成员：
     * <ul>
     *   <li>两个成员持有相同的非良性目标（同一目标竞争）</li>
     *   <li>两个成员分别持有互斥目标对中的一方（如 调查 vs 隐瞒）</li>
     * </ul>
     */
    private Set<String> findGoalConflicts(List<AgentState> agents, Map<String, String> goals) {
        if (goals == null || goals.isEmpty()) return Set.of();
        Set<String> conflicted = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (AgentState s : agents) {
            names.add(s.getAgentName());
        }
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String g1 = goals.getOrDefault(names.get(i), "");
                String g2 = goals.getOrDefault(names.get(j), "");
                if (g1.isBlank() || g2.isBlank()) continue;
                boolean benign1 = BENIGN_GOALS.contains(g1);
                boolean benign2 = BENIGN_GOALS.contains(g2);
                if (benign1 && benign2) continue;

                // 相同敏感目标 → 竞争关系。
                if (!benign1 && g1.equals(g2)) {
                    conflicted.add(names.get(i));
                    conflicted.add(names.get(j));
                    continue;
                }
                // 互斥目标对。
                for (Set<String> pair : EXCLUSIVE_GOAL_PAIRS) {
                    if (pair.contains(g1) && pair.contains(g2)) {
                        conflicted.add(names.get(i));
                        conflicted.add(names.get(j));
                    }
                }
            }
        }
        return conflicted;
    }

    private AgentState findAgent(List<AgentState> agents, String name) {
        for (AgentState s : agents) {
            if (name.equals(s.getAgentName())) return s;
        }
        return null;
    }
}
