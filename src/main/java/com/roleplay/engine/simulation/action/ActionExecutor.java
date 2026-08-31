package com.roleplay.engine.simulation.action;

/** Executes one validated skill. Implementations own deterministic consequences. */
public interface ActionExecutor {
    ActionResult execute(ActionIntent intent, ActionWorldView world);
}
