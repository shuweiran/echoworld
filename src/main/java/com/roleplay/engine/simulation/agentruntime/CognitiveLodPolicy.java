package com.roleplay.engine.simulation.agentruntime;

/** Deterministic distance/relevance policy for scaling hundreds of agents. */
public final class CognitiveLodPolicy {
    private final double fullDistance;
    private final double reducedDistance;
    private final long reducedIntervalTicks;
    private final long macroIntervalTicks;

    public CognitiveLodPolicy(double fullDistance,
                              double reducedDistance,
                              long reducedIntervalTicks,
                              long macroIntervalTicks) {
        if (!Double.isFinite(fullDistance) || fullDistance < 0
                || !Double.isFinite(reducedDistance) || reducedDistance < fullDistance) {
            throw new IllegalArgumentException("invalid cognitive LOD distances");
        }
        if (reducedIntervalTicks < 1 || macroIntervalTicks < reducedIntervalTicks) {
            throw new IllegalArgumentException("invalid cognitive LOD intervals");
        }
        this.fullDistance = fullDistance;
        this.reducedDistance = reducedDistance;
        this.reducedIntervalTicks = reducedIntervalTicks;
        this.macroIntervalTicks = macroIntervalTicks;
    }

    public static CognitiveLodPolicy defaults() {
        return new CognitiveLodPolicy(20, 80, 10, 100);
    }

    public CognitiveLod classify(double distanceToPlayer, boolean storyCritical) {
        if (!Double.isFinite(distanceToPlayer) || distanceToPlayer < 0) {
            throw new IllegalArgumentException("distance must be finite and non-negative");
        }
        if (storyCritical || distanceToPlayer <= fullDistance) return CognitiveLod.FULL;
        if (distanceToPlayer <= reducedDistance) return CognitiveLod.REDUCED;
        return CognitiveLod.MACRO;
    }

    public boolean shouldThink(CognitiveLod lod, long tick, long lastDecisionTick) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        if (lastDecisionTick < 0) return true;
        long interval = switch (lod) {
            case FULL -> 1;
            case REDUCED -> reducedIntervalTicks;
            case MACRO -> macroIntervalTicks;
        };
        return tick - lastDecisionTick >= interval;
    }

    public boolean plannerAllowed(CognitiveLod lod) {
        return lod == CognitiveLod.FULL;
    }

    public boolean skillExecutionAllowed(CognitiveLod lod) {
        return lod != CognitiveLod.MACRO;
    }
}
