package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.spatial.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable server-side projection of a baked navigation mesh adjacency graph. */
public final class BakedNavMesh {
    private final Map<Long, Node> nodes;
    private final Map<Long, List<Edge>> outgoing;

    public BakedNavMesh(List<Node> nodes, List<Link> links) {
        Map<Long, Node> nodeIndex = new LinkedHashMap<>();
        for (Node node : nodes == null ? List.<Node>of() : nodes) {
            if (nodeIndex.putIfAbsent(node.polygonRef(), node) != null) {
                throw new IllegalArgumentException("duplicate polygon ref: " + node.polygonRef());
            }
        }
        if (nodeIndex.isEmpty()) throw new IllegalArgumentException("navmesh requires nodes");
        Map<Long, List<Edge>> edgeIndex = new LinkedHashMap<>();
        nodeIndex.keySet().forEach(ref -> edgeIndex.put(ref, new ArrayList<>()));
        for (Link link : links == null ? List.<Link>of() : links) {
            requireNode(nodeIndex, link.fromRef());
            requireNode(nodeIndex, link.toRef());
            edgeIndex.get(link.fromRef()).add(new Edge(link.toRef(), link.cost(), link.clearance()));
            if (link.bidirectional()) {
                edgeIndex.get(link.toRef()).add(new Edge(link.fromRef(), link.cost(), link.clearance()));
            }
        }
        this.nodes = Map.copyOf(nodeIndex);
        Map<Long, List<Edge>> immutableEdges = new LinkedHashMap<>();
        edgeIndex.forEach((ref, edges) -> immutableEdges.put(ref, List.copyOf(edges)));
        this.outgoing = Map.copyOf(immutableEdges);
    }

    public Map<Long, Node> nodes() { return nodes; }
    public List<Edge> outgoing(long polygonRef) { return outgoing.getOrDefault(polygonRef, List.of()); }

    private static void requireNode(Map<Long, Node> nodes, long ref) {
        if (!nodes.containsKey(ref)) throw new IllegalArgumentException("link references missing polygon: " + ref);
    }

    public record Node(long polygonRef, String surfaceId, String floorId, Vec3 center) {
        public Node {
            if (polygonRef < 0) throw new IllegalArgumentException("polygonRef must be non-negative");
            if (surfaceId == null || surfaceId.isBlank()) throw new IllegalArgumentException("surfaceId required");
            if (floorId == null || floorId.isBlank()) throw new IllegalArgumentException("floorId required");
            if (center == null) throw new IllegalArgumentException("center required");
        }
    }

    public record Link(long fromRef, long toRef, double cost, double clearance, boolean bidirectional) {
        public Link {
            if (!Double.isFinite(cost) || cost <= 0) throw new IllegalArgumentException("cost must be positive");
            if (!Double.isFinite(clearance) || clearance < 0) {
                throw new IllegalArgumentException("clearance must be finite and non-negative");
            }
        }
    }

    public record Edge(long toRef, double cost, double clearance) { }
}
