package com.roleplay.engine.simulation.action;

/** Authoritative lifecycle of a world action. */
public enum ActionPhase {
    PROPOSED,
    VALIDATED,
    NAVIGATING,
    READY,
    EXECUTING,
    SUCCESS,
    FAILED,
    INTERRUPTED,
    BLOCKED;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == INTERRUPTED || this == BLOCKED;
    }
}
