package com.roleplay.engine.simulation.spatial;

/** Semantic locomotion state sent to renderers; renderers must not infer world facts from it. */
public enum LocomotionState {
    IDLE,
    WALK,
    RUN,
    TURN,
    TALK,
    INTERACT
}
