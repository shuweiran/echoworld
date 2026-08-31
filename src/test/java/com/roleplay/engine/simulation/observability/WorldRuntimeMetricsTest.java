package com.roleplay.engine.simulation.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldRuntimeMetricsTest {
    @Test
    void recordsIndependentTickAndActionBudgets() {
        WorldRuntimeMetrics metrics = new WorldRuntimeMetrics();
        metrics.recordTick(100);
        metrics.recordTick(300);
        metrics.recordAction(true);
        metrics.recordAction(false);
        metrics.pendingActions(4);
        var snapshot = metrics.snapshot();

        assertEquals(2, snapshot.ticks());
        assertEquals(200, snapshot.averageTickNanos());
        assertEquals(300, snapshot.maxTickNanos());
        assertEquals(1, snapshot.actionSucceeded());
        assertEquals(1, snapshot.actionFailed());
        assertEquals(4, snapshot.pendingActions());
    }
}
