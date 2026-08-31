package com.roleplay.engine.simulation.navigation;

/** Authoritative navigation boundary. Movement consumes plans and never creates them. */
public interface NavigationService {
    PathPlan plan(PathRequest request);

    default boolean isReachable(PathRequest request) {
        return plan(request).status() == PathPlan.Status.READY;
    }
}
