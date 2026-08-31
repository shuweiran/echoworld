package com.roleplay.engine.simulation.navigation.portal;

/** Runtime-only portal fact, intentionally excluded from WorldDefinition. */
public record PortalRuntimeState(String portalId,
                                 Availability availability,
                                 long revision,
                                 String reason) {
    public PortalRuntimeState {
        availability = availability == null ? Availability.AVAILABLE : availability;
        reason = reason == null ? "" : reason;
    }

    public static PortalRuntimeState available(String portalId) {
        return new PortalRuntimeState(portalId, Availability.AVAILABLE, 0L, "");
    }

    public enum Availability { AVAILABLE, CLOSED, LOCKED, DISABLED }
}
