package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.action.ActionResult;

import java.util.concurrent.CompletableFuture;

/** Queue boundary normally backed by ActionDispatcher.enqueue. */
@FunctionalInterface
public interface ActionSink {
    CompletableFuture<ActionResult> submit(ActionIntent intent);
}
