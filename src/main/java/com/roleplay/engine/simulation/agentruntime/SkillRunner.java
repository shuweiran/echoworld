package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.action.ActionResult;
import com.roleplay.engine.simulation.action.ActionSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Executes registered skill steps through the tick-owned Action queue without blocking. */
public final class SkillRunner {
    private final ActionSink actionSink;
    private final long intentLifetimeMillis;
    private ActiveExecution active;

    public SkillRunner(ActionSink actionSink, long intentLifetimeMillis) {
        this.actionSink = Objects.requireNonNull(actionSink, "actionSink");
        if (intentLifetimeMillis < 1) throw new IllegalArgumentException("intent lifetime must be positive");
        this.intentLifetimeMillis = intentLifetimeMillis;
    }

    public boolean active() {
        return active != null;
    }

    public RunnerUpdate start(SkillPlan plan, long nowMillis) {
        if (active != null) throw new IllegalStateException("a skill is already active");
        if (plan == null) throw new IllegalArgumentException("plan required");
        active = new ActiveExecution(plan, 0, null);
        return submitCurrent(plan.worldVersion(), plan.perceptionVersion(), nowMillis);
    }

    public RunnerUpdate advance(long currentWorldVersion, long currentPerceptionVersion, long nowMillis) {
        if (active == null) return RunnerUpdate.idle();
        SkillPlan plan = active.plan;
        CompletableFuture<ActionResult> pending = active.pending;
        if (pending == null) return fail("RUNNER_STATE_INVALID", null);
        if (!pending.isDone()) return running("ACTION_PENDING");

        ActionResult result;
        try {
            result = pending.join();
        } catch (RuntimeException failure) {
            return fail("ACTION_FUTURE_ERROR", null);
        }
        if (result == null || result.status() != ActionResult.Status.SUCCEEDED) {
            return fail(result == null ? "NULL_ACTION_RESULT" : result.code(), result);
        }

        int nextIndex = active.stepIndex + 1;
        if (nextIndex >= plan.skill().steps().size()) {
            RunnerUpdate completed = new RunnerUpdate(RunnerUpdate.Status.SUCCEEDED, plan.goal().id(),
                    plan.skill().id(), currentStep().action(), result.code(), result);
            active = null;
            return completed;
        }
        active = new ActiveExecution(plan, nextIndex, null);
        return submitCurrent(currentWorldVersion, currentPerceptionVersion, nowMillis);
    }

    private RunnerUpdate submitCurrent(long worldVersion, long perceptionVersion, long nowMillis) {
        SkillPlan plan = active.plan;
        SkillStep step = currentStep();
        String targetId = resolveTarget(plan, step);
        Map<String, Object> parameters = new LinkedHashMap<>(step.parameters());
        parameters.putAll(plan.parameterOverrides());
        parameters.put("perceptionVersion", perceptionVersion);
        String intentId = plan.id() + ":" + active.stepIndex;
        ActionIntent intent = new ActionIntent(intentId, plan.agentId(), ActionSource.AI_PLANNER,
                step.action(), targetId, worldVersion, nowMillis + intentLifetimeMillis, parameters);
        try {
            CompletableFuture<ActionResult> pending = Objects.requireNonNull(actionSink.submit(intent),
                    "action sink returned null future");
            active = new ActiveExecution(plan, active.stepIndex, pending);
            return running("ACTION_SUBMITTED");
        } catch (RuntimeException failure) {
            return fail("ACTION_SUBMIT_ERROR", null);
        }
    }

    private String resolveTarget(SkillPlan plan, SkillStep step) {
        return switch (step.targetSource()) {
            case NONE -> "";
            case GOAL_TARGET -> plan.groundedTargetId();
            case GOAL_PARAMETER -> step.resolveTarget(plan.goal(), plan.parameterOverrides());
        };
    }

    private SkillStep currentStep() {
        return active.plan.skill().steps().get(active.stepIndex);
    }

    private RunnerUpdate running(String code) {
        return new RunnerUpdate(RunnerUpdate.Status.RUNNING, active.plan.goal().id(),
                active.plan.skill().id(), currentStep().action(), code, null);
    }

    private RunnerUpdate fail(String code, ActionResult result) {
        ActiveExecution failed = active;
        RunnerUpdate update = new RunnerUpdate(RunnerUpdate.Status.FAILED, failed.plan.goal().id(),
                failed.plan.skill().id(), currentStep().action(), code, result);
        active = null;
        return update;
    }

    private record ActiveExecution(SkillPlan plan, int stepIndex, CompletableFuture<ActionResult> pending) { }
}
