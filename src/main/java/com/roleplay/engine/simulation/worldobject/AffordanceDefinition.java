package com.roleplay.engine.simulation.worldobject;

import com.roleplay.engine.simulation.action.ActionType;

import java.util.Map;

public record AffordanceDefinition(ActionType action,
                                   double requiredDistance,
                                   long durationMillis,
                                   int maxConcurrentUsers,
                                   Map<String, String> requiredState) {
    public AffordanceDefinition {
        if (requiredDistance < 0 || durationMillis < 0 || maxConcurrentUsers < 1) {
            throw new IllegalArgumentException("invalid affordance constraints");
        }
        requiredState = requiredState == null ? Map.of() : Map.copyOf(requiredState);
    }
}
