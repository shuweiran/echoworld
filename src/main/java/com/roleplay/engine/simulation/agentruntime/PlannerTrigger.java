package com.roleplay.engine.simulation.agentruntime;

/** Explicit low-frequency reasons that may justify a semantic planner call. */
public enum PlannerTrigger {
    NONE,
    GOAL_ACTIVATED,
    GOAL_FAILED,
    KEY_PERCEPTION_CHANGED,
    WORLD_EVENT,
    PLAN_INVALIDATED,
    NEED_THRESHOLD,
    PERIODIC_REFLECTION
}
