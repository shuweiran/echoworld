package com.roleplay.engine.simulation.action;

import java.util.Map;

/** Narrow mutation port implemented only by the authoritative world runtime. */
public interface ActionMutationPort extends ActionWorldView {
    boolean setMovementTarget(String actorId, double worldX, double worldZ);
    boolean setObjectState(String objectId, String key, String value);
    String objectState(String objectId, String key);
    boolean setCarriedBy(String objectId, String actorId);
    String carriedBy(String objectId);
    Map<String, Object> applyObjectUse(String actorId, String objectId, Map<String, Object> parameters);
    Map<String, Object> adjustMetric(String actorId, Map<String, Object> parameters);
    void emitActionEvent(String actorId, ActionType type, Map<String, Object> payload);
}
