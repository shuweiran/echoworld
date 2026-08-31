package com.roleplay.engine.simulation.action;

import java.util.List;
import java.util.Map;

/** Deterministic execution feedback returned to the planner/player. */
public record ActionResult(String intentId,
                           Status status,
                           String code,
                           String message,
                           long worldVersion,
                           List<Map<String, Object>> worldEvents) {
    public enum Status { ACCEPTED, RUNNING, SUCCEEDED, FAILED, REJECTED }

    public ActionResult {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
        worldEvents = worldEvents == null ? List.of() : List.copyOf(worldEvents);
    }

    public static ActionResult rejected(ActionIntent intent, String code, String message, long version) {
        return new ActionResult(intent.intentId(), Status.REJECTED, code, message, version, List.of());
    }
}
