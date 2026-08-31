package com.roleplay.engine.simulation.action;

/** Immutable observable state of one action intent. */
public record ActionState(ActionIntent intent,
                          ActionPhase phase,
                          long proposedAtMillis,
                          long updatedAtMillis,
                          String code,
                          String message) {
    public ActionState {
        if (intent == null || phase == null) throw new IllegalArgumentException("intent/phase required");
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }

    public static ActionState proposed(ActionIntent intent, long nowMillis) {
        return new ActionState(intent, ActionPhase.PROPOSED, nowMillis, nowMillis, "PROPOSED", "queued");
    }

    public ActionState transition(ActionPhase next, long nowMillis, String code, String message) {
        if (!allowed(phase, next)) throw new IllegalStateException("invalid action transition " + phase + " -> " + next);
        return new ActionState(intent, next, proposedAtMillis, nowMillis, code, message);
    }

    private static boolean allowed(ActionPhase from, ActionPhase to) {
        if (from.terminal()) return false;
        return switch (from) {
            case PROPOSED -> to == ActionPhase.VALIDATED || to == ActionPhase.FAILED
                    || to == ActionPhase.INTERRUPTED || to == ActionPhase.BLOCKED;
            case VALIDATED -> to == ActionPhase.NAVIGATING || to == ActionPhase.READY || to == ActionPhase.FAILED
                    || to == ActionPhase.INTERRUPTED || to == ActionPhase.BLOCKED;
            case NAVIGATING -> to == ActionPhase.READY || to == ActionPhase.FAILED || to == ActionPhase.INTERRUPTED || to == ActionPhase.BLOCKED;
            case READY -> to == ActionPhase.EXECUTING || to == ActionPhase.FAILED
                    || to == ActionPhase.INTERRUPTED || to == ActionPhase.BLOCKED;
            case EXECUTING -> to == ActionPhase.EXECUTING || to == ActionPhase.SUCCESS || to == ActionPhase.FAILED
                    || to == ActionPhase.INTERRUPTED || to == ActionPhase.BLOCKED;
            default -> false;
        };
    }
}
