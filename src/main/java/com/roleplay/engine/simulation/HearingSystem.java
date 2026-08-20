package com.roleplay.engine.simulation;

import java.util.*;

public class HearingSystem {

    private final SpatialGrid spatialGrid;
    /** 当前地图的实体障碍；墙/建筑的直线遮挡会阻断普通对话和听觉。 */
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
        List<HearingResult> results = new ArrayList<>();
        List<AgentState> agentList = new ArrayList<>(agents);

        for (int i = 0; i < agentList.size(); i++) {
            AgentState speaker = agentList.get(i);
            double volume = computeVolume(speaker);
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
        if (soundBlocked(a, b)) return false;
        double dist = a.distanceTo(b);
        double volA = computeVolume(a);
        double volB = computeVolume(b);
        double rangeA = a.getHearRange() * volA;
        double rangeB = b.getHearRange() * volB;
        double att = 1.0 / (1.0 + dist * dist * 0.0001);
        double effA = rangeA * att * b.getHearRange() / 200.0;
        double effB = rangeB * att * a.getHearRange() / 200.0;
        return dist <= Math.min(effA, effB);
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
