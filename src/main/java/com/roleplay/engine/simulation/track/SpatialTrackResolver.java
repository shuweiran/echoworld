package com.roleplay.engine.simulation.track;

import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;

import java.util.*;

/**
 * Spatial → Track assignment resolver.
 *
 * <p>Bridges the 2D spatial layer (position / distance / hearing) and the Track
 * system's information-isolation layer. For every agent in the input list it
 * computes a {@link TrackAssignment} (per requirement doc):
 * <ul>
 *   <li>dist &lt; conversationDistance → MERGED（可对话，可见对方）</li>
 *   <li>conversationDistance ≤ dist &lt; 听觉范围 → WEAK（只给摘要 observation，不给全文）</li>
 *   <li>dist ≥ 听觉范围 或 私密房间内外隔断 → ISOLATED（完全不可见）</li>
 * </ul>
 *
 * <p>Hearing radius is per-listener: agent A overhears agent B iff
 * dist(A, B) &lt; A.getHearRange(). Priority per agent: MERGED &gt; WEAK &gt; ISOLATED
 * (an agent in a close conversation is MERGED even if other peers are only audible).
 *
 * <p>Phase 1: pure function over {@link AgentState}s, no world/LLM coupling.
 * Phase 2 consumers ({@code TrackStrategy}) read the produced assignments to
 * decide context visibility (full / summary / none).
 */
public class SpatialTrackResolver {

    /** P-0815-A：会话距离默认值修正——需求文档「两两距离 < 5 格 → 可对话」中的「格」与世界
     *  像素坐标（1000×600 px）混用（5.0 实为「格」注释但按 px 用，5px≈贴脸，导致近距离对话
     *  MERGED 几乎永不触发）。统一为 px 语义：默认 70px（调研报告 2.4 #5 建议 60-80px 区间），
     *  可由 roleplay.track.conversation-distance 配置覆盖（AppConfig.TrackConfig）。 */
    public static final double DEFAULT_CONVERSATION_DISTANCE = 70.0;

    private final double conversationDistance;
    private final Set<String> privateRoomAgents;

    public SpatialTrackResolver() {
        this(DEFAULT_CONVERSATION_DISTANCE, Set.of());
    }

    public SpatialTrackResolver(double conversationDistance) {
        this(conversationDistance, Set.of());
    }

    /**
     * @param conversationDistance max distance (px) for two agents to be in a
     *                             direct conversation (MERGED)；P-0815-A：单位修正为 px，
     *                             默认 {@link #DEFAULT_CONVERSATION_DISTANCE}=70px
     * @param privateRoomAgents    agents inside a private room; any pair where one
     *                             side is inside and the other outside is always
     *                             ISOLATED (私密房间外 → 隔离)
     */
    public SpatialTrackResolver(double conversationDistance, Set<String> privateRoomAgents) {
        this.conversationDistance = conversationDistance > 0 ? conversationDistance : DEFAULT_CONVERSATION_DISTANCE;
        this.privateRoomAgents = privateRoomAgents == null ? Set.of() : Set.copyOf(privateRoomAgents);
    }

    /** 当前会话距离（px，可观测/测试）。 */
    public double getConversationDistance() { return conversationDistance; }

    /** Resolve track assignments for every agent in the list. */
    public Map<String, TrackAssignment> resolve(List<AgentState> agents) {
        Map<String, TrackAssignment> result = new LinkedHashMap<>();
        if (agents == null || agents.isEmpty()) return result;

        for (AgentState self : agents) {
            List<String> mergedPeers = new ArrayList<>();
            List<String> weakPeers = new ArrayList<>();
            String privateNote = null;

            for (AgentState other : agents) {
                if (other == self) continue;
                double dist = self.distanceTo(other);

                if (privateIsolated(self, other)) {
                    privateNote = privateNote == null ? "私密房间内外隔离" : privateNote;
                    continue;
                }
                if (dist < conversationDistance) {
                    mergedPeers.add(other.getAgentName());
                } else if (dist < self.getHearRange()) {
                    weakPeers.add(other.getAgentName());
                }
            }

            if (!mergedPeers.isEmpty()) {
                result.put(self.getAgentName(), TrackAssignment.of(
                        self.getAgentName(), Track.Mode.MERGED, mergedPeers,
                        "近距离对话中，可见对方（距离 < " + conversationDistance + "）"));
            } else if (!weakPeers.isEmpty()) {
                result.put(self.getAgentName(), TrackAssignment.of(
                        self.getAgentName(), Track.Mode.WEAK, weakPeers,
                        "在听觉范围内但距离较远，仅可获得摘要观察，无完整对话"));
            } else {
                String note = privateNote != null ? privateNote : "完全隔离（超出听觉范围或无人交谈）";
                result.put(self.getAgentName(), TrackAssignment.isolated(self.getAgentName(), note));
            }
        }
        return result;
    }

    /** True if one side is inside a private room and the other is not. */
    private boolean privateIsolated(AgentState a, AgentState b) {
        if (privateRoomAgents.isEmpty()) return false;
        return privateRoomAgents.contains(a.getAgentName()) != privateRoomAgents.contains(b.getAgentName());
    }
}
