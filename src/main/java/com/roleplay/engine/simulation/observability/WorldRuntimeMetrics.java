package com.roleplay.engine.simulation.observability;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Dependency-free metrics core; adapters may export this to Micrometer/JFR. */
public final class WorldRuntimeMetrics {
    private final LongAdder ticks = new LongAdder();
    private final LongAdder tickNanos = new LongAdder();
    private final AtomicLong maxTickNanos = new AtomicLong();
    private final LongAdder actionSucceeded = new LongAdder();
    private final LongAdder actionFailed = new LongAdder();
    private final AtomicLong pendingActions = new AtomicLong();

    public void recordTick(long elapsedNanos) {
        ticks.increment();
        tickNanos.add(Math.max(0, elapsedNanos));
        maxTickNanos.accumulateAndGet(Math.max(0, elapsedNanos), Math::max);
    }

    public void recordAction(boolean succeeded) {
        if (succeeded) actionSucceeded.increment(); else actionFailed.increment();
    }

    public void pendingActions(long value) { pendingActions.set(Math.max(0, value)); }

    public Snapshot snapshot() {
        long count = ticks.sum();
        return new Snapshot(count, count == 0 ? 0 : tickNanos.sum() / count, maxTickNanos.get(),
                actionSucceeded.sum(), actionFailed.sum(), pendingActions.get());
    }

    public record Snapshot(long ticks, long averageTickNanos, long maxTickNanos,
                           long actionSucceeded, long actionFailed, long pendingActions) { }
}
