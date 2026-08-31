package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.NavLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** A* query backend over a validated baked NavMesh graph. Baking remains an offline concern. */
public final class NavMeshNavigationService implements NavigationService {
    public static final String BACKEND = "baked-navmesh-a-star";
    private final BakedNavMesh mesh;

    public NavMeshNavigationService(BakedNavMesh mesh) {
        if (mesh == null) throw new IllegalArgumentException("baked navmesh required");
        this.mesh = mesh;
    }

    @Override
    public PathPlan plan(PathRequest request) {
        if (request == null || request.from() == null || request.to() == null) {
            return new PathPlan(PathPlan.Status.REJECTED, BACKEND, List.of(), "missing navigation request");
        }
        if (request.authority() != ControlAuthority.AI_AUTONOMOUS) {
            return new PathPlan(PathPlan.Status.REJECTED, BACKEND, List.of(),
                    "player input is never planned by the AI navigation service");
        }
        if (!request.from().floorId().equals(request.to().floorId())) {
            return PathPlan.unreachable(BACKEND, "cross-floor route requires semantic portal planning");
        }
        BakedNavMesh.Node start = locate(request.from());
        BakedNavMesh.Node goal = locate(request.to());
        if (start == null || goal == null) return PathPlan.unreachable(BACKEND, "no polygon on requested surface");
        NavProfile profile = request.profile() == null ? NavProfile.humanoid() : request.profile();
        List<BakedNavMesh.Node> route = search(start, goal, profile.radius());
        if (route.isEmpty()) return PathPlan.unreachable(BACKEND, "no walkable navmesh route");

        List<PathStep> steps = new ArrayList<>();
        for (int index = 1; index < route.size(); index++) steps.add(PathStep.walk(route.get(index).center()));
        if (steps.isEmpty() || !steps.getLast().target().equals(request.to().worldPosition())) {
            steps.add(PathStep.walk(request.to().worldPosition()));
        }
        return PathPlan.ready(BACKEND, steps);
    }

    private BakedNavMesh.Node locate(NavLocation location) {
        BakedNavMesh.Node exact = mesh.nodes().get(location.polygonRef());
        if (exact != null && compatible(exact, location)) return exact;
        return mesh.nodes().values().stream().filter(node -> compatible(node, location))
                .min(Comparator.comparingDouble(node -> node.center().groundDistance(location.worldPosition())))
                .orElse(null);
    }

    private boolean compatible(BakedNavMesh.Node node, NavLocation location) {
        return node.floorId().equals(location.floorId()) && node.surfaceId().equals(location.surfaceId());
    }

    private List<BakedNavMesh.Node> search(BakedNavMesh.Node start, BakedNavMesh.Node goal, double radius) {
        Map<Long, Double> score = new HashMap<>();
        Map<Long, Long> previous = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Candidate> open = new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimate));
        score.put(start.polygonRef(), 0.0);
        // Zero is an admissible A* heuristic for arbitrary author-provided edge costs;
        // this intentionally degenerates to deterministic Dijkstra rather than risk a non-optimal route.
        open.add(new Candidate(start.polygonRef(), 0));
        while (!open.isEmpty()) {
            long current = open.poll().polygonRef();
            if (!closed.add(current)) continue;
            if (current == goal.polygonRef()) return reconstruct(previous, start.polygonRef(), goal.polygonRef());
            for (BakedNavMesh.Edge edge : mesh.outgoing(current)) {
                if (edge.clearance() < radius || closed.contains(edge.toRef())) continue;
                double candidate = score.get(current) + edge.cost();
                if (candidate >= score.getOrDefault(edge.toRef(), Double.POSITIVE_INFINITY)) continue;
                score.put(edge.toRef(), candidate);
                previous.put(edge.toRef(), current);
                open.add(new Candidate(edge.toRef(), candidate));
            }
        }
        return List.of();
    }

    private List<BakedNavMesh.Node> reconstruct(Map<Long, Long> previous, long start, long goal) {
        List<BakedNavMesh.Node> reversed = new ArrayList<>();
        long cursor = goal;
        reversed.add(mesh.nodes().get(cursor));
        while (cursor != start) {
            Long parent = previous.get(cursor);
            if (parent == null) return List.of();
            cursor = parent;
            reversed.add(mesh.nodes().get(cursor));
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private record Candidate(long polygonRef, double estimate) { }
}
