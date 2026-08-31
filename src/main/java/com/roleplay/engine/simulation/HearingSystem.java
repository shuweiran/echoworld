package com.roleplay.engine.simulation;

import com.roleplay.engine.simulation.navigation.portal.PortalRuntimeState;
import com.roleplay.engine.simulation.navigation.portal.SemanticPortal;

import java.util.*;

public class HearingSystem {

    private static final double CONNECTOR_ATTENUATION = 0.45;
    private static final double CONNECTOR_ROUTE_PENALTY = 80.0;

    private volatile SpatialGrid spatialGrid;
    /** 当前地图的实体障碍；墙体和建筑的直线遮挡会阻断普通对话和听觉。 */
    private volatile List<Obstacle> obstacles = List.of();
    private volatile List<SemanticPortal> semanticPortals = List.of();
    private volatile Map<String, PortalRuntimeState> portalStates = Map.of();

    public record HearingResult(String speakerName, String listenerName,
                                 double distance, double volume,
                                 double rawRange, double effectiveRange) {
        public boolean canHear() { return distance <= effectiveRange; }
        public double clarity() { return Math.max(0, 1.0 - (distance / effectiveRange)); }
    }

    public HearingSystem(SpatialGrid spatialGrid) {
        this.spatialGrid = Objects.requireNonNull(spatialGrid, "spatialGrid");
    }

    public void setSpatialGrid(SpatialGrid spatialGrid) {
        this.spatialGrid = Objects.requireNonNull(spatialGrid, "spatialGrid");
    }

    public void setObstacles(List<Obstacle> obstacles) {
        this.obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
    }

    /** Installs the same server-owned connector facts used by navigation. */
    public void setSemanticPortals(List<SemanticPortal> portals, Map<String, PortalRuntimeState> states) {
        semanticPortals = portals == null ? List.of() : List.copyOf(portals);
        portalStates = states == null ? Map.of() : states;
    }

    private boolean soundBlocked(String floorId, double ax, double ay, double bx, double by) {
        for (Obstacle obstacle : obstacles) {
            if (!obstacle.belongsToFloor(floorId)) continue;
            if (obstacle.blocksSound() && obstacle.intersectsLine(ax, ay, bx, by)) return true;
        }
        return false;
    }

    private boolean soundBlocked(AgentState a, AgentState b) {
        if (!a.navLocation().floorId().equals(b.navLocation().floorId())) return acousticPath(a, b) == null;
        return soundBlocked(a.navLocation().floorId(), a.getX(), a.getY(), b.getX(), b.getY());
    }

    /**
     * Computes the deterministic acoustic route through connector endpoints. Same-floor
     * edges are admitted only when the server-owned sound obstacles allow line of sight;
     * connector edges use the same live availability facts as navigation.
     */
    private AcousticPath acousticPath(AgentState source, AgentState listener) {
        AcousticNode start = new AcousticNode("@source", source.navLocation().floorId(), source.getX(), source.getY());
        AcousticNode goal = new AcousticNode("@listener", listener.navLocation().floorId(), listener.getX(), listener.getY());
        if (start.floorId().equals(goal.floorId())) {
            return soundBlocked(start.floorId(), start.x(), start.y(), goal.x(), goal.y())
                    ? null : new AcousticPath(Math.hypot(goal.x() - start.x(), goal.y() - start.y()), 0);
        }

        List<AcousticNode> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(goal);
        Map<String, int[]> portalNodeIndexes = new LinkedHashMap<>();
        for (SemanticPortal portal : semanticPortals) {
            if (!isAcoustic(portal) || !isAvailable(portal.id())) continue;
            int a = nodes.size();
            nodes.add(new AcousticNode(portal.id() + ":a", portal.endpointA().floorId(),
                    portal.endpointA().worldPosition().x(), portal.endpointA().worldPosition().z()));
            int b = nodes.size();
            nodes.add(new AcousticNode(portal.id() + ":b", portal.endpointB().floorId(),
                    portal.endpointB().worldPosition().x(), portal.endpointB().worldPosition().z()));
            portalNodeIndexes.put(portal.id(), new int[]{a, b});
        }
        if (portalNodeIndexes.isEmpty()) return null;

        double[] scores = new double[nodes.size()];
        double[] distances = new double[nodes.size()];
        int[] hops = new int[nodes.size()];
        Arrays.fill(scores, Double.POSITIVE_INFINITY);
        scores[0] = 0;
        PriorityQueue<AcousticVisit> queue = new PriorityQueue<>(Comparator.comparingDouble(AcousticVisit::score));
        queue.add(new AcousticVisit(0, 0));
        while (!queue.isEmpty()) {
            AcousticVisit visit = queue.remove();
            int current = visit.nodeIndex();
            if (visit.score() > scores[current]) continue;
            if (current == 1) return new AcousticPath(distances[current], hops[current]);
            AcousticNode from = nodes.get(current);

            for (int next = 0; next < nodes.size(); next++) {
                if (next == current) continue;
                AcousticNode to = nodes.get(next);
                if (!from.floorId().equals(to.floorId())
                        || soundBlocked(from.floorId(), from.x(), from.y(), to.x(), to.y())) continue;
                double segment = Math.hypot(to.x() - from.x(), to.y() - from.y());
                relax(queue, scores, distances, hops, current, next, segment, 0);
            }
            for (SemanticPortal portal : semanticPortals) {
                int[] endpoints = portalNodeIndexes.get(portal.id());
                if (endpoints == null) continue;
                if (current == endpoints[0]) relax(queue, scores, distances, hops, current, endpoints[1], 0, 1);
                if (portal.bidirectional() && current == endpoints[1]) {
                    relax(queue, scores, distances, hops, current, endpoints[0], 0, 1);
                }
            }
        }
        return null;
    }

    private static void relax(PriorityQueue<AcousticVisit> queue, double[] scores, double[] distances, int[] hops,
                              int current, int next, double segmentDistance, int connectorHops) {
        double nextDistance = distances[current] + segmentDistance;
        int nextHops = hops[current] + connectorHops;
        double nextScore = nextDistance + nextHops * CONNECTOR_ROUTE_PENALTY;
        if (nextScore + 1e-9 >= scores[next]) return;
        scores[next] = nextScore;
        distances[next] = nextDistance;
        hops[next] = nextHops;
        queue.add(new AcousticVisit(next, nextScore));
    }

    private boolean isAvailable(String portalId) {
        PortalRuntimeState state = portalStates.get(portalId);
        return state == null || state.availability() == PortalRuntimeState.Availability.AVAILABLE;
    }

    private static boolean isAcoustic(SemanticPortal portal) {
        if (portal.tags().contains("acoustic")) return true;
        return switch (portal.kind()) {
            case STAIRS, ELEVATOR, LADDER, DOOR -> true;
            case TELEPORT, LINK -> false;
        };
    }

    private record AcousticNode(String id, String floorId, double x, double y) {}
    private record AcousticVisit(int nodeIndex, double score) {}
    private record AcousticPath(double distance, int connectorHops) {
        double attenuation() { return Math.pow(CONNECTOR_ATTENUATION, connectorHops); }
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
                AcousticPath path = acousticPath(speaker, listener);
                if (path == null) continue;
                double connectorLoss = path.attenuation();
                double dist = path.distance();
                double attenuation = 1.0 / (1.0 + dist * dist * 0.0001);
                double effectiveRange = rawRange * attenuation * connectorLoss * listener.getHearRange() / 200.0;

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
        double rawRange = self.getHearRange() * computeVolume(self);
        List<AgentState> nearby = spatialGrid.queryNearby(self, rawRange * 1.5);

        for (AgentState other : nearby) {
            if (other == self) continue;
            if (canHear(self, other, SpeechVolume.NORMAL)) {
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
        if (speaker == null || listener == null) return false;
        AcousticPath path = acousticPath(speaker, listener);
        if (path == null) return false;
        double connectorLoss = path.attenuation();
        double distance = path.distance();
        double volume = computeVolume(speaker) * (utteranceVolume == null ? 1.0 : utteranceVolume.multiplier());
        double attenuation = 1.0 / (1.0 + distance * distance * 0.0001);
        double effectiveRange = speaker.getHearRange() * volume * attenuation * connectorLoss * listener.getHearRange() / 200.0;
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
        if (a == null || b == null) return false;
        AcousticPath path = acousticPath(a, b);
        // Auto-DYAD intentionally uses its explicit social distance threshold rather
        // than the normal volume falloff, while still requiring a legal acoustic path.
        return path != null && path.distance() < maxDistance;
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
