package com.roleplay.engine.simulation.navigation;

import java.util.List;

/** Immutable authoritative path result. */
public record PathPlan(Status status, String backend, List<PathStep> steps, String reason) {
    public enum Status { READY, UNREACHABLE, REJECTED }

    public PathPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        backend = backend == null ? "" : backend;
        reason = reason == null ? "" : reason;
    }

    public static PathPlan ready(String backend, List<PathStep> steps) {
        return new PathPlan(Status.READY, backend, steps, "");
    }

    public static PathPlan unreachable(String backend, String reason) {
        return new PathPlan(Status.UNREACHABLE, backend, List.of(), reason);
    }
}
