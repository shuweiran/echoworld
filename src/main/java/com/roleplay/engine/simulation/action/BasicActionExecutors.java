package com.roleplay.engine.simulation.action;

import com.roleplay.engine.simulation.spatial.Transform3D;

import java.util.List;
import java.util.Map;

/** Deterministic baseline executors; content-specific behavior stays data-driven through affordances. */
public final class BasicActionExecutors {
    private BasicActionExecutors() { }

    public static void register(ActionDispatcher dispatcher) {
        dispatcher.register(ActionType.WAIT, new WaitExecutor());
        dispatcher.register(ActionType.MOVE_TO, new MoveToExecutor());
        dispatcher.register(ActionType.OPEN, objectState("open", "true"));
        dispatcher.register(ActionType.CLOSE, objectState("open", "false"));
        dispatcher.register(ActionType.SIT, objectState("occupiedBy", "{actor}"));
        dispatcher.register(ActionType.STAND, objectState("occupiedBy", ""));
        dispatcher.register(ActionType.PICK_UP, transfer(true));
        dispatcher.register(ActionType.PUT_DOWN, transfer(false));
        dispatcher.register(ActionType.USE, eventOnly("USED"));
        dispatcher.register(ActionType.SPEAK, eventOnly("SPOKE"));
        dispatcher.register(ActionType.LOOK_AT, eventOnly("LOOKED"));
        dispatcher.register(ActionType.APPROACH, new MoveToExecutor());
        dispatcher.register(ActionType.FOLLOW, new MoveToExecutor());
        dispatcher.register(ActionType.LEAVE, new MoveToExecutor());
    }

    private static ActionExecutor objectState(String key, String configuredValue) {
        return (intent, world) -> {
            if (!(world instanceof ActionMutationPort mutations)) return failed(intent, world, "READ_ONLY_WORLD");
            String value = "{actor}".equals(configuredValue) ? intent.actorId() : configuredValue;
            if (!mutations.setObjectState(intent.targetId(), key, value)) return failed(intent, world, "STATE_REJECTED");
            Map<String, Object> event = Map.of("actorId", intent.actorId(), "targetId", intent.targetId(),
                    "action", intent.action().name(), "state", Map.of(key, value));
            mutations.emitActionEvent(intent.actorId(), intent.action(), event);
            return succeeded(intent, world, intent.action().name(), event);
        };
    }

    private static ActionExecutor transfer(boolean pickingUp) {
        return (intent, world) -> {
            if (!(world instanceof ActionMutationPort mutations)) return failed(intent, world, "READ_ONLY_WORLD");
            String holder = pickingUp ? intent.actorId() : "";
            if (!mutations.setCarriedBy(intent.targetId(), holder)) return failed(intent, world, "TRANSFER_REJECTED");
            Map<String, Object> event = Map.of("actorId", intent.actorId(), "targetId", intent.targetId(),
                    "action", intent.action().name(), "carriedBy", holder);
            mutations.emitActionEvent(intent.actorId(), intent.action(), event);
            return succeeded(intent, world, intent.action().name(), event);
        };
    }

    private static ActionExecutor eventOnly(String code) {
        return (intent, world) -> {
            Map<String, Object> event = Map.of("actorId", intent.actorId(), "targetId", intent.targetId(),
                    "action", intent.action().name(), "parameters", intent.parameters());
            if (world instanceof ActionMutationPort mutations) mutations.emitActionEvent(intent.actorId(), intent.action(), event);
            return succeeded(intent, world, code, event);
        };
    }

    private static final class WaitExecutor implements TickActionExecutor {
        @Override public ActionResult execute(ActionIntent intent, ActionWorldView world) {
            long duration = number(intent.parameters().get("durationMillis"), 0);
            if (duration <= 0) return succeeded(intent, world, "WAIT_COMPLETE", Map.of());
            return new ActionResult(intent.intentId(), ActionResult.Status.RUNNING, "WAITING", "wait in progress",
                    world.worldVersion(), List.of());
        }

        @Override public ActionResult advance(ActionIntent intent, ActionWorldView world, long startedAtMillis, long nowMillis) {
            long duration = number(intent.parameters().get("durationMillis"), 0);
            return nowMillis - startedAtMillis >= duration
                    ? succeeded(intent, world, "WAIT_COMPLETE", Map.of())
                    : new ActionResult(intent.intentId(), ActionResult.Status.RUNNING, "WAITING", "wait in progress",
                    world.worldVersion(), List.of());
        }
    }

    private static final class MoveToExecutor implements TickActionExecutor {
        @Override public ActionResult execute(ActionIntent intent, ActionWorldView world) {
            if (!(world instanceof ActionMutationPort mutations)) return failed(intent, world, "READ_ONLY_WORLD");
            Transform3D target = targetTransform(intent, world);
            if (target == null) return failed(intent, world, "DESTINATION_REQUIRED");
            if (!mutations.setMovementTarget(intent.actorId(), target.position().x(), target.position().z())) {
                return failed(intent, world, "MOVEMENT_REJECTED");
            }
            return new ActionResult(intent.intentId(), ActionResult.Status.RUNNING, "NAVIGATING", "movement started",
                    world.worldVersion(), List.of());
        }

        @Override public ActionResult advance(ActionIntent intent, ActionWorldView world, long startedAtMillis, long nowMillis) {
            Transform3D actor = world.transformOf(intent.actorId());
            Transform3D target = targetTransform(intent, world);
            if (actor == null || target == null) return failed(intent, world, "DESTINATION_LOST");
            double tolerance = doubleNumber(intent.parameters().get("tolerance"), 12.0);
            if (actor.position().groundDistance(target.position()) <= Math.max(1.0, tolerance)) {
                return succeeded(intent, world, "ARRIVED", Map.of("actorId", intent.actorId()));
            }
            if (intent.expiresAtMillis() > 0 && nowMillis > intent.expiresAtMillis()) {
                return failed(intent, world, "MOVEMENT_TIMEOUT");
            }
            return new ActionResult(intent.intentId(), ActionResult.Status.RUNNING, "NAVIGATING", "movement in progress",
                    world.worldVersion(), List.of());
        }

        private static Transform3D targetTransform(ActionIntent intent, ActionWorldView world) {
            if (!intent.targetId().isBlank()) {
                Transform3D target = world.transformOf(intent.targetId());
                if (target != null) return target;
            }
            Object x = intent.parameters().get("x"), z = intent.parameters().get("z");
            if (x instanceof Number nx && z instanceof Number nz) return Transform3D.ground(nx.doubleValue(), nz.doubleValue());
            Object y = intent.parameters().get("y");
            if (x instanceof Number nx && y instanceof Number ny) return Transform3D.ground(nx.doubleValue(), ny.doubleValue());
            return null;
        }
    }

    private static ActionResult succeeded(ActionIntent intent, ActionWorldView world, String code, Map<String, Object> event) {
        return new ActionResult(intent.intentId(), ActionResult.Status.SUCCEEDED, code, "action completed",
                world.worldVersion(), event.isEmpty() ? List.of() : List.of(event));
    }

    private static ActionResult failed(ActionIntent intent, ActionWorldView world, String code) {
        return new ActionResult(intent.intentId(), ActionResult.Status.FAILED, code, "action failed",
                world.worldVersion(), List.of());
    }

    private static long number(Object value, long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
    private static double doubleNumber(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
}
