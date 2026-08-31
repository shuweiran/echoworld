package com.roleplay.engine.simulation.agentruntime;

/** A pressure value where 0 means satisfied and 1 means urgent. */
public record Need(NeedType type, double pressure, double threshold, double weight) {
    public Need {
        if (type == null) throw new IllegalArgumentException("need type required");
        if (!inUnitRange(pressure)) throw new IllegalArgumentException("pressure must be between 0 and 1");
        if (!Double.isFinite(threshold) || threshold < 0 || threshold >= 1) {
            throw new IllegalArgumentException("threshold must be between 0 inclusive and 1 exclusive");
        }
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("weight must be finite and non-negative");
        }
    }

    public Drive drive() {
        double urgency = pressure <= threshold ? 0 : (pressure - threshold) / (1 - threshold);
        return new Drive(type, urgency, weight);
    }

    public boolean aboveThreshold() {
        return pressure > threshold;
    }

    private static boolean inUnitRange(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
