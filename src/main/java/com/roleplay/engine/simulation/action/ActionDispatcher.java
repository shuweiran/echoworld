package com.roleplay.engine.simulation.action;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tick-owned action boundary. Producers only enqueue intents; the world tick
 * is the sole place that validates and executes them.
 */
public final class ActionDispatcher {
    private final ActionIntentValidator validator;
    private final Map<ActionType, ActionExecutor> executors = new EnumMap<>(ActionType.class);
    private final Queue<PendingAction> pending = new ConcurrentLinkedQueue<>();
    private final Map<String, ActiveAction> active = new ConcurrentHashMap<>();
    private final Map<String, ActionState> states = new ConcurrentHashMap<>();
    private final java.util.List<java.util.function.Consumer<ActionState>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ActionDispatcher() {
        this(new ActionIntentValidator());
    }

    public ActionDispatcher(ActionIntentValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public synchronized void register(ActionType type, ActionExecutor executor) {
        executors.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(executor, "executor"));
    }

    public CompletableFuture<ActionResult> enqueue(ActionIntent intent) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        ActionIntent required = Objects.requireNonNull(intent, "intent");
        ActionState previous = states.putIfAbsent(required.intentId(), ActionState.proposed(required, System.currentTimeMillis()));
        if (previous != null) {
            result.complete(ActionResult.rejected(required, "DUPLICATE_INTENT", "intent id already exists",
                    previous.intent().basedOnWorldVersion()));
            return result;
        }
        pending.add(new PendingAction(required, result));
        return result;
    }

    /** Called from the deterministic world tick, never from an HTTP/LLM thread. */
    public int drain(ActionWorldView world, long nowMillis) {
        int count = 0;
        for (ActiveAction running : java.util.List.copyOf(active.values())) {
            count++;
            advance(running, world, nowMillis);
        }
        PendingAction item;
        while ((item = pending.poll()) != null) {
            count++;
            ActionIntent intent = item.intent();
            ActionResult validation = validator.validate(intent, world, nowMillis);
            if (validation.status() != ActionResult.Status.ACCEPTED) {
                transition(intent.intentId(), ActionPhase.BLOCKED, nowMillis, validation.code(), validation.message());
                item.result().complete(validation);
                continue;
            }
            transition(intent.intentId(), ActionPhase.VALIDATED, nowMillis, validation.code(), validation.message());
            ActionExecutor executor;
            synchronized (this) {
                executor = executors.get(intent.action());
            }
            if (executor == null) {
                ActionResult failed = new ActionResult(intent.intentId(), ActionResult.Status.FAILED,
                        "NO_EXECUTOR", "action is not registered", world.worldVersion(), java.util.List.of());
                transition(intent.intentId(), ActionPhase.FAILED, nowMillis, failed.code(), failed.message());
                item.result().complete(failed);
                continue;
            }
            transition(intent.intentId(), ActionPhase.READY, nowMillis, "READY", "executor selected");
            transition(intent.intentId(), ActionPhase.EXECUTING, nowMillis, "EXECUTING", "action started");
            try {
                ActionResult result = executor.execute(intent, world);
                if (result.status() == ActionResult.Status.RUNNING && executor instanceof TickActionExecutor tickExecutor) {
                    active.put(intent.intentId(), new ActiveAction(intent, tickExecutor, nowMillis, item.result()));
                } else {
                    finish(intent.intentId(), result, nowMillis);
                    item.result().complete(result);
                }
            } catch (RuntimeException e) {
                ActionResult failed = new ActionResult(intent.intentId(), ActionResult.Status.FAILED,
                        "EXECUTOR_ERROR", e.getMessage() == null ? "executor failed" : e.getMessage(),
                        world.worldVersion(), java.util.List.of());
                transition(intent.intentId(), ActionPhase.FAILED, nowMillis, failed.code(), failed.message());
                item.result().complete(failed);
            }
        }
        return count;
    }

    public int pendingCount() { return pending.size(); }
    public int activeCount() { return active.size(); }
    public ActionState state(String intentId) { return states.get(intentId); }
    public java.util.List<ActionState> states() { return java.util.List.copyOf(states.values()); }
    public void addStateListener(java.util.function.Consumer<ActionState> listener) {
        if (listener != null) listeners.add(listener);
    }

    public boolean interrupt(String intentId, String reason, long nowMillis) {
        ActiveAction running = active.remove(intentId);
        if (running == null) return false;
        ActionResult result = new ActionResult(intentId, ActionResult.Status.FAILED, "INTERRUPTED",
                reason == null ? "interrupted" : reason, 0, java.util.List.of());
        transition(intentId, ActionPhase.INTERRUPTED, nowMillis, result.code(), result.message());
        running.result().complete(result);
        return true;
    }

    /** Cancels queued and active work owned by an entity before it leaves or changes identity. */
    public int interruptActor(String actorId, String reason, long nowMillis) {
        if (actorId == null || actorId.isBlank()) return 0;
        int interrupted = 0;
        for (PendingAction item : java.util.List.copyOf(pending)) {
            if (actorId.equals(item.intent().actorId()) && pending.remove(item)) {
                cancel(item.intent(), item.result(), reason, nowMillis);
                interrupted++;
            }
        }
        for (ActiveAction item : java.util.List.copyOf(active.values())) {
            if (actorId.equals(item.intent().actorId()) && active.remove(item.intent().intentId(), item)) {
                cancel(item.intent(), item.result(), reason, nowMillis);
                interrupted++;
            }
        }
        return interrupted;
    }

    /** World reset boundary: no future or intent state may leak into the next session. */
    public void reset(String reason, long nowMillis) {
        PendingAction pendingItem;
        while ((pendingItem = pending.poll()) != null) {
            cancel(pendingItem.intent(), pendingItem.result(), reason, nowMillis);
        }
        for (ActiveAction item : java.util.List.copyOf(active.values())) {
            if (active.remove(item.intent().intentId(), item)) {
                cancel(item.intent(), item.result(), reason, nowMillis);
            }
        }
        states.clear();
    }

    private void advance(ActiveAction running, ActionWorldView world, long nowMillis) {
        ActionResult validation = validator.validate(running.intent(), world, nowMillis);
        if (validation.status() != ActionResult.Status.ACCEPTED) {
            active.remove(running.intent().intentId());
            finish(running.intent().intentId(), validation, nowMillis);
            running.result().complete(validation);
            return;
        }
        ActionResult result;
        try {
            result = running.executor().advance(running.intent(), world, running.startedAtMillis(), nowMillis);
        } catch (RuntimeException e) {
            result = new ActionResult(running.intent().intentId(), ActionResult.Status.FAILED, "EXECUTOR_ERROR",
                    e.getMessage() == null ? "executor failed" : e.getMessage(), world.worldVersion(), java.util.List.of());
        }
        if (result.status() == ActionResult.Status.RUNNING) {
            transition(running.intent().intentId(), ActionPhase.EXECUTING, nowMillis, result.code(), result.message());
            return;
        }
        active.remove(running.intent().intentId());
        finish(running.intent().intentId(), result, nowMillis);
        running.result().complete(result);
    }

    private void cancel(ActionIntent intent, CompletableFuture<ActionResult> future,
                        String reason, long nowMillis) {
        String message = reason == null || reason.isBlank() ? "action interrupted" : reason;
        ActionResult result = new ActionResult(intent.intentId(), ActionResult.Status.FAILED,
                "INTERRUPTED", message, 0, java.util.List.of());
        transition(intent.intentId(), ActionPhase.INTERRUPTED, nowMillis, result.code(), result.message());
        future.complete(result);
    }

    private void finish(String intentId, ActionResult result, long nowMillis) {
        ActionPhase phase = switch (result.status()) {
            case SUCCEEDED -> ActionPhase.SUCCESS;
            case REJECTED -> ActionPhase.BLOCKED;
            case FAILED -> ActionPhase.FAILED;
            default -> ActionPhase.FAILED;
        };
        transition(intentId, phase, nowMillis, result.code(), result.message());
    }

    private void transition(String intentId, ActionPhase next, long nowMillis, String code, String message) {
        ActionState updated = states.computeIfPresent(intentId,
                (id, current) -> current.transition(next, nowMillis, code, message));
        if (updated != null) for (java.util.function.Consumer<ActionState> listener : listeners) {
            try { listener.accept(updated); } catch (RuntimeException ignored) { /* observers cannot break world commits */ }
        }
    }

    private record PendingAction(ActionIntent intent, CompletableFuture<ActionResult> result) { }
    private record ActiveAction(ActionIntent intent, TickActionExecutor executor, long startedAtMillis,
                                CompletableFuture<ActionResult> result) { }
}
