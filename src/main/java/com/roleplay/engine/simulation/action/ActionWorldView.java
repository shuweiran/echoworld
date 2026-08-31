package com.roleplay.engine.simulation.action;

import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.Transform3D;

/** Minimal world query surface used during validation; implementations must not mutate world state. */
public interface ActionWorldView {
    long worldVersion();

    /** Oldest snapshot version still safe for execution after asynchronous planning. */
    default long minimumAcceptedWorldVersion() {
        return Math.max(0, worldVersion() - 25);
    }

    boolean entityExists(String entityId);
    ControlAuthority authorityOf(String entityId);

    /** Optional semantic hook for world-object affordances. */
    default boolean affordanceAvailable(String actorId, String targetId, ActionType action) {
        return true;
    }

    default Transform3D transformOf(String entityId) { return null; }
}
