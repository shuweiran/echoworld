package com.roleplay.engine.simulation.agentruntime;

/** Normalized urgency derived from a need without consulting an LLM. */
public record Drive(NeedType type, double urgency, double weight) {
    public Drive {
        if (type == null) throw new IllegalArgumentException("drive type required");
        if (!Double.isFinite(urgency) || urgency < 0 || urgency > 1) {
            throw new IllegalArgumentException("urgency must be between 0 and 1");
        }
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("weight must be finite and non-negative");
        }
    }

    public double score() {
        return urgency * weight;
    }
}
