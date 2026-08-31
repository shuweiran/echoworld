package com.roleplay.engine.simulation.action;

import com.roleplay.engine.simulation.spatial.ControlAuthority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionDispatcherTest {
    @Test
    void queuedIntentDoesNotExecuteUntilWorldTickDrain() {
        ActionDispatcher dispatcher = new ActionDispatcher();
        dispatcher.register(ActionType.WAIT, (intent, world) ->
                new ActionResult(intent.intentId(), ActionResult.Status.SUCCEEDED, "WAITED", "ok",
                        world.worldVersion(), List.of()));
        ActionIntent intent = new ActionIntent("wait-1", "alice", ActionSource.AI_PLANNER,
                ActionType.WAIT, "", 7, 0, Map.of());
        var future = dispatcher.enqueue(intent);

        assertEquals(1, dispatcher.pendingCount());
        assertEquals(false, future.isDone());
        dispatcher.drain(world(), 1000);
        assertEquals("WAITED", future.join().code());
    }

    @Test
    void missingExecutorProducesDeterministicFailure() {
        ActionDispatcher dispatcher = new ActionDispatcher();
        var future = dispatcher.enqueue(new ActionIntent("open-1", "alice", ActionSource.AI_PLANNER,
                ActionType.OPEN, "door", 7, 0, Map.of()));
        dispatcher.drain(world(), 1000);
        assertEquals(ActionResult.Status.FAILED, future.join().status());
        assertEquals("NO_EXECUTOR", future.join().code());
    }

    private ActionWorldView world() {
        return new ActionWorldView() {
            public long worldVersion() { return 7; }
            public boolean entityExists(String id) { return id.equals("alice") || id.equals("door"); }
            public ControlAuthority authorityOf(String id) { return ControlAuthority.AI_AUTONOMOUS; }
        };
    }
}
