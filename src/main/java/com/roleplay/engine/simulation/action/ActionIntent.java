package com.roleplay.engine.simulation.action;

import java.util.Map;

/** Creative intent only; it never asserts that the requested consequence already happened. */
public record ActionIntent(String intentId,
                           String actorId,
                           ActionSource source,
                           ActionType action,
                           String targetId,
                           long basedOnWorldVersion,
                           long expiresAtMillis,
                           Map<String, Object> parameters) {
    public ActionIntent {
        if (intentId == null || intentId.isBlank()) throw new IllegalArgumentException("intentId required");
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("actorId required");
        if (source == null || action == null) throw new IllegalArgumentException("source/action required");
        targetId = targetId == null ? "" : targetId;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
