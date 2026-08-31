package com.roleplay.engine.simulation.navigation.portal;

import java.util.List;

/** Deterministic topology plan; floor-local geometry is delegated to a backend. */
public record PortalRoute(Status status, List<Leg> legs, double totalCost, String reason) {
    public PortalRoute {
        legs = legs == null ? List.of() : List.copyOf(legs);
        reason = reason == null ? "" : reason;
    }

    public static PortalRoute ready(List<Leg> legs, double totalCost) {
        return new PortalRoute(Status.READY, legs, totalCost, "");
    }

    public static PortalRoute unreachable(String reason) {
        return new PortalRoute(Status.UNREACHABLE, List.of(), Double.POSITIVE_INFINITY, reason);
    }

    public static PortalRoute rejected(String reason) {
        return new PortalRoute(Status.REJECTED, List.of(), Double.POSITIVE_INFINITY, reason);
    }

    public enum Status { READY, UNREACHABLE, REJECTED }

    public record Leg(Type type,
                      String portalId,
                      PortalEndpoint from,
                      PortalEndpoint to,
                      String interaction,
                      double cost) {
        public Leg {
            portalId = portalId == null ? "" : portalId;
            interaction = interaction == null ? "" : interaction;
        }

        public static Leg floorTransit(PortalEndpoint from, PortalEndpoint to, double cost) {
            return new Leg(Type.FLOOR_TRANSIT, "", from, to, "", cost);
        }

        public static Leg interact(String portalId, PortalEndpoint at, String action) {
            return new Leg(Type.INTERACT, portalId, at, at, action, 0.0);
        }

        public static Leg traverse(String portalId,
                                   PortalEndpoint from,
                                   PortalEndpoint to,
                                   double cost) {
            return new Leg(Type.PORTAL_TRAVERSAL, portalId, from, to, "", cost);
        }
    }

    public enum Type { FLOOR_TRANSIT, INTERACT, PORTAL_TRAVERSAL }
}
