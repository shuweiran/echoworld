package com.roleplay.engine.simulation;

import java.util.*;

public class HearingSystem {

    private final SpatialGrid spatialGrid;

    public record HearingResult(String speakerName, String listenerName,
                                 double distance, double volume,
                                 double rawRange, double effectiveRange) {
        public boolean canHear() { return distance <= effectiveRange; }
        public double clarity() { return Math.max(0, 1.0 - (distance / effectiveRange)); }
    }

    public HearingSystem(SpatialGrid spatialGrid) {
        this.spatialGrid = spatialGrid;
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

    public Set<String> findAudiblePeers(AgentState self, Collection<AgentState> allAgents) {
        Set<String> audible = new LinkedHashSet<>();
        double volume = computeVolume(self);
        double rawRange = self.getHearRange() * volume;
        List<AgentState> nearby = spatialGrid.queryNearby(self, rawRange * 1.5);

        for (AgentState other : nearby) {
            if (other == self) continue;
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
