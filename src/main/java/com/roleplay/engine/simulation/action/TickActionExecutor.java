package com.roleplay.engine.simulation.action;

/** Executor for actions whose deterministic completion spans world ticks. */
public interface TickActionExecutor extends ActionExecutor {
    ActionResult advance(ActionIntent intent, ActionWorldView world, long startedAtMillis, long nowMillis);
}
