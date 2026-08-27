package com.roleplay.engine.simulation;

import java.util.*;

public class HearingSystem {

    private final SpatialGrid spatialGrid;
    /** 当前地图的实体障碍；墙体和建筑的直线遮挡会阻断普通对话和听觉。 */
    private volatile List<Obstacle> obstacles = List.of();

    public record HearingResult(String speakerName, String listenerName,
                                 double distance, double volume,
                                 double rawRange, double effectiveRange) {
        public boolean canHear() { return distance <= effectiveRange; }
        public double clarity() { return Math.max(0, 1.0 - (distance / effectiveRange)); }
    }

    public HearingSystem(SpatialGrid spatialGrid) {
        this.spatialGrid = spatialGrid;
    }

    public void setObstacles(List<Obstacle> obstacles) {
        this.obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
    }

    private boolean soundBlocked(AgentState a, AgentState b) {
        for (Obstacle obstacle : obstacles) {
            if (obstacle.blocksSound() && obstacle.intersectsLine(a.getX(), a.getY(), b.getX(), b.getY())) return true;
        }
        return false;
    }

    public List<HearingResult> computeAudibility(Collection<AgentState> agents) {
        return computeAudibility(agents, Map.of());
    }

    /** 同一声学模型的逐句音量入口；未指定者仍按情绪推定，保持旧调用兼容。 */
    public List<HearingResult> computeAudibility(Collection<AgentState> agents, Map<String, SpeechVolume> utteranceVolumes) {
        List<HearingResult> results = new ArrayList<>();
        List<AgentState> agentList = new ArrayList<>(agents);

        for (int i = 0; i < agentList.size(); i++) {
            AgentState speaker = agentList.get(i);
            SpeechVolume chosen = utteranceVolumes == null ? null : utteranceVolumes.get(speaker.getAgentName());
            double volume = computeVolume(speaker) * (chosen == null ? 1.0 : chosen.multiplier());
            double rawRange = speaker.getHearRange() * volume;

            List<AgentState> nearby = spatialGrid.queryNearby(speaker, rawRange * 1.5);

            for (AgentState listener : nearby) {
                if (listener == speaker) continue;
                if (soundBlocked(speaker, listener)) continue;
                double dist = speaker.distanceTo(listener);
                double attenuation = 1.0 / (1.0 + dist * dist * 0.0001);
                double effectiveRange = rawRange * attenuation * listener.getHearRange() / 200.0;

                results.add(new HearingResult(
                        speaker.getAgentName(), listener.getAgentName(),
                        dist, volume, rawRange, effectiveRange));
            }
        }
        return results;
    }

    /**
     * 声学听众计数（演讲广播的判定单事实源，正式版 merged 与方案B split 共用）：
     * 以 speaker 为声源跑 {@link #computeAudibility}（体积×听觉范围×距离衰减×对方听觉范围），
     * 统计能听到（{@code canHear()}，距离 ≤ 有效听觉范围）的听众数。
     * 有听众（≥1）→ 区域演讲；无听众 → 按兜底配置决定是否升级全局公告。
     *
     * <p>这是「判定集中回管线层」的落点：SimulationService（merged）与
     * SpeechStrategy（split）都只调本方法，不各自实现距离判定，避免双份漂移。
     */
    public int countHearingListeners(AgentState speaker, Collection<AgentState> allStates) {
        if (speaker == null || allStates == null || allStates.isEmpty()) return 0;
        int n = 0;
        for (HearingResult h : computeAudibility(allStates)) {
            if (h.speakerName().equals(speaker.getAgentName()) && h.canHear()) n++;
        }
        return n;
    }

    public Set<String> findAudiblePeers(AgentState self, Collection<AgentState> allAgents) {
        Set<String> audible = new LinkedHashSet<>();
        double volume = computeVolume(self);
        double rawRange = self.getHearRange() * volume;
        List<AgentState> nearby = spatialGrid.queryNearby(self, rawRange * 1.5);

        for (AgentState other : nearby) {
            if (other == self) continue;
            if (soundBlocked(self, other)) continue;
            double dist = self.distanceTo(other);
            double attenuation = 1.0 / (1.0 + dist * dist * 0.0001);
            double effectiveRange = rawRange * attenuation * other.getHearRange() / 200.0;
            if (dist <= effectiveRange) {
                audible.add(other.getAgentName());
            }
        }
        return audible;
    }

    public boolean canHearEachOther(AgentState a, AgentState b) {
        return canHear(a, b, SpeechVolume.NORMAL) && canHear(b, a, SpeechVolume.NORMAL);
    }

    /** 单向发言判定：speaker 的本次音量决定 listener 是否实际听到。 */
    public boolean canHear(AgentState speaker, AgentState listener, SpeechVolume utteranceVolume) {
        if (speaker == null || listener == null || soundBlocked(speaker, listener)) return false;
        double distance = speaker.distanceTo(listener);
        double volume = computeVolume(speaker) * (utteranceVolume == null ? 1.0 : utteranceVolume.multiplier());
        double attenuation = 1.0 / (1.0 + distance * distance * 0.0001);
        double effectiveRange = speaker.getHearRange() * volume * attenuation * listener.getHearRange() / 200.0;
        return distance <= effectiveRange;
    }

    /** 非角色声源（例如 DM 创建的玻璃碎裂声）的空间听觉判定，仍受墙体与听力影响。 */
    public boolean canHearEvent(double x, double y, double rawRange, AgentState listener) {
        if (listener == null || rawRange <= 0) return false;
        AgentState source = new AgentState("world-event", x, y);
        if (soundBlocked(source, listener)) return false;
        double distance = source.distanceTo(listener);
        double attenuation = 1.0 / (1.0 + distance * distance * 0.0001);
        double effectiveRange = rawRange * attenuation * listener.getHearRange() / 200.0;
        return distance <= effectiveRange;
    }

    /**
     * 玩家主动发言自动建立 DYAD 使用明确的会话距离上限；仍保留障碍物的隔音判断，
     * 不把普通持续交流的距离衰减规则误用于“玩家找最近 AI 建组”这一入口。
     */
    public boolean canAutoDyadWithinDistance(AgentState a, AgentState b, double maxDistance) {
        if (a == null || b == null || soundBlocked(a, b)) return false;
        return a.distanceTo(b) < maxDistance;
    }

    private double computeVolume(AgentState agent) {
        double base = 1.0;
        return switch (agent.getEmotion()) {
            case ANGRY, EXCITED -> base * 1.4;
            case HAPPY -> base * 1.15;
            case SHY, SAD -> base * 0.7;
            default -> base;
        };
    }
}
