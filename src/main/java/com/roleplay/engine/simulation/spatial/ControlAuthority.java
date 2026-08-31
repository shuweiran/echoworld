package com.roleplay.engine.simulation.spatial;

/** The only authority allowed to create locomotion intent for an entity. */
public enum ControlAuthority {
    AI_AUTONOMOUS,
    PLAYER_INPUT
}
