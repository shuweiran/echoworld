package com.roleplay.engine.simulation.agentruntime;

import java.util.Collection;
import java.util.List;

/** Immutable input sampled by one AgentRuntime decision tick. */
public record RuntimeInput(long tick,
                           long nowMillis,
                           double distanceToPlayer,
                           boolean storyCritical,
                           Collection<Need> needs,
                           PerceptionSnapshot perception,
                           WorkingMemory memory,
                           PlannerTrigger plannerTrigger) {
    public RuntimeInput {
        if (tick < 0 || nowMillis < 0) throw new IllegalArgumentException("tick/time must be non-negative");
        if (!Double.isFinite(distanceToPlayer) || distanceToPlayer < 0) {
            throw new IllegalArgumentException("distance must be finite and non-negative");
        }
        if (perception == null) throw new IllegalArgumentException("perception required");
        needs = needs == null ? List.of() : List.copyOf(needs);
        memory = memory == null ? WorkingMemory.empty() : memory;
        plannerTrigger = plannerTrigger == null ? PlannerTrigger.NONE : plannerTrigger;
    }
}
