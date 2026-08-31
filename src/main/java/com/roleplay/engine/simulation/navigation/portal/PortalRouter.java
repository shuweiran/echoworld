package com.roleplay.engine.simulation.navigation.portal;

import com.roleplay.engine.simulation.navigation.NavProfile;
import com.roleplay.engine.simulation.navigation.portal.PortalRoute.Leg;
import com.roleplay.engine.simulation.navigation.portal.PortalRuntimeState.Availability;
import com.roleplay.engine.simulation.spatial.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Deterministic Dijkstra router over floor-local surfaces and semantic portals.
 * It never invokes or mutates Grid/NavMesh backends; FLOOR_TRANSIT legs are the
 * explicit hand-off points for those planners.
 */
public final class PortalRouter {
    private static final double EPSILON = 1.0e-9;

    public PortalRoute route(PortalRouteRequest request) {
        String rejection = validateRequest(request);
        if (!rejection.isEmpty()) {
            return PortalRoute.rejected(rejection);
        }

        PortalEndpoint from = request.from();
        PortalEndpoint to = request.to();
        if (sameSurface(from, to)) {
            double cost = distance(from.worldPosition(), to.worldPosition());
            return PortalRoute.ready(cost <= EPSILON
                    ? List.of()
                    : List.of(Leg.floorTransit(from, to, cost)), cost);
        }

        List<Node> nodes = buildNodes(request);
        Node start = new Node("@start", from, null, false);
        Node target = new Node("@target", to, null, false);
        nodes.add(start);
        nodes.add(target);
        nodes.sort(Comparator.comparing(Node::id));

        PriorityQueue<State> open = new PriorityQueue<>(Comparator
                .comparingDouble(State::cost)
                .thenComparing(State::signature)
                .thenComparing(state -> state.node().id()));
        Map<String, Best> best = new HashMap<>();
        State initial = new State(start, 0.0, "", List.of());
        open.add(initial);
        best.put(start.id(), new Best(0.0, ""));

        while (!open.isEmpty()) {
            State current = open.poll();
            Best known = best.get(current.node().id());
            if (known == null || worseThan(current.cost(), current.signature(), known)) {
                continue;
            }
            if (current.node().id().equals(target.id())) {
                return PortalRoute.ready(current.legs(), current.cost());
            }
            for (Edge edge : edgesFrom(current.node(), nodes, request)) {
                double candidateCost = current.cost() + edge.cost();
                String candidateSignature = current.signature() + "|" + edge.signature();
                Best previous = best.get(edge.to().id());
                if (previous != null && !betterThan(candidateCost, candidateSignature, previous)) {
                    continue;
                }
                List<Leg> candidateLegs = new ArrayList<>(current.legs());
                candidateLegs.addAll(edge.legs());
                best.put(edge.to().id(), new Best(candidateCost, candidateSignature));
                open.add(new State(edge.to(), candidateCost, candidateSignature, List.copyOf(candidateLegs)));
            }
        }
        return PortalRoute.unreachable("NO_PORTAL_ROUTE");
    }

    private List<Node> buildNodes(PortalRouteRequest request) {
        List<SemanticPortal> sorted = request.portals().stream()
                .filter(Objects::nonNull)
                .filter(portal -> usable(portal, request.profile(), request.runtimeStates().get(portal.id())))
                .sorted(Comparator.comparing(SemanticPortal::id))
                .toList();
        List<Node> nodes = new ArrayList<>(sorted.size() * 2);
        for (SemanticPortal portal : sorted) {
            nodes.add(new Node(portal.id() + ":A", portal.endpointA(), portal, true));
            nodes.add(new Node(portal.id() + ":B", portal.endpointB(), portal, false));
        }
        return nodes;
    }

    private List<Edge> edgesFrom(Node current,
                                 List<Node> nodes,
                                 PortalRouteRequest request) {
        List<Edge> edges = new ArrayList<>();
        for (Node candidate : nodes) {
            if (current.id().equals(candidate.id()) || !sameSurface(current.endpoint(), candidate.endpoint())) {
                continue;
            }
            double cost = distance(current.endpoint().worldPosition(), candidate.endpoint().worldPosition());
            List<Leg> legs = cost <= EPSILON
                    ? List.of()
                    : List.of(Leg.floorTransit(current.endpoint(), candidate.endpoint(), cost));
            edges.add(new Edge(candidate, cost, "F:" + candidate.id(), legs));
        }

        if (current.portal() != null) {
            boolean forward = current.sideA();
            if (forward || current.portal().bidirectional()) {
                String counterpartId = current.portal().id() + (forward ? ":B" : ":A");
                Node counterpart = nodes.stream()
                        .filter(node -> node.id().equals(counterpartId))
                        .findFirst()
                        .orElse(null);
                if (counterpart != null) {
                    PortalRuntimeState state = request.runtimeStates().get(current.portal().id());
                    List<Leg> legs = new ArrayList<>();
                    if (state != null && state.availability() == Availability.CLOSED) {
                        legs.add(Leg.interact(current.portal().id(), current.endpoint(),
                                current.portal().interactionAction()));
                    }
                    legs.add(Leg.traverse(current.portal().id(), current.endpoint(),
                            counterpart.endpoint(), current.portal().traversalCost()));
                    edges.add(new Edge(counterpart, current.portal().traversalCost(),
                            "P:" + current.portal().id() + (forward ? ":A>B" : ":B>A"),
                            List.copyOf(legs)));
                }
            }
        }
        edges.sort(Comparator.comparing(Edge::signature).thenComparing(edge -> edge.to().id()));
        return edges;
    }

    private boolean usable(SemanticPortal portal,
                           NavProfile profile,
                           PortalRuntimeState state) {
        if (portal.id() == null || portal.endpointA() == null || portal.endpointB() == null
                || portal.kind() == null || !Double.isFinite(portal.traversalCost())
                || portal.traversalCost() < 0.0) {
            return false;
        }
        Availability availability = state == null ? Availability.AVAILABLE : state.availability();
        if (availability == Availability.DISABLED || availability == Availability.LOCKED) {
            return false;
        }
        if (availability == Availability.CLOSED
                && (portal.kind() != SemanticPortal.Kind.DOOR
                || portal.interactionAction().isBlank())) {
            return false;
        }
        return switch (portal.kind()) {
            case DOOR -> profile.canUseDoors();
            case STAIRS, LADDER -> profile.canUseStairs();
            case ELEVATOR -> profile.canUseElevators();
            case TELEPORT, LINK -> true;
        };
    }

    private String validateRequest(PortalRouteRequest request) {
        if (request == null || request.from() == null || request.to() == null || request.profile() == null) {
            return "INVALID_REQUEST";
        }
        if (!validEndpoint(request.from()) || !validEndpoint(request.to())) {
            return "INVALID_ENDPOINT";
        }
        return "";
    }

    private boolean validEndpoint(PortalEndpoint endpoint) {
        return endpoint.floorId() != null && !endpoint.floorId().isBlank()
                && endpoint.surfaceId() != null && !endpoint.surfaceId().isBlank()
                && finite(endpoint.worldPosition());
    }

    private boolean sameSurface(PortalEndpoint left, PortalEndpoint right) {
        return left != null && right != null && left.surfaceKey().equals(right.surfaceKey());
    }

    private boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x())
                && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }

    private double distance(Vec3 left, Vec3 right) {
        double dx = left.x() - right.x();
        double dy = left.y() - right.y();
        double dz = left.z() - right.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean betterThan(double cost, String signature, Best previous) {
        return cost < previous.cost() - EPSILON
                || (Math.abs(cost - previous.cost()) <= EPSILON
                && signature.compareTo(previous.signature()) < 0);
    }

    private boolean worseThan(double cost, String signature, Best known) {
        return cost > known.cost() + EPSILON
                || (Math.abs(cost - known.cost()) <= EPSILON
                && signature.compareTo(known.signature()) > 0);
    }

    private record Node(String id, PortalEndpoint endpoint, SemanticPortal portal, boolean sideA) { }
    private record Edge(Node to, double cost, String signature, List<Leg> legs) { }
    private record State(Node node, double cost, String signature, List<Leg> legs) { }
    private record Best(double cost, String signature) { }
}
