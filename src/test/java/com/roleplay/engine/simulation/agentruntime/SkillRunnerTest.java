package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.action.ActionResult;
import com.roleplay.engine.simulation.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkillRunnerTest {
    @Test
    void executesRegisteredStepsInOrderAndRefreshesWorldVersion() {
        List<ActionIntent> submitted = new ArrayList<>();
        ActionSink sink = intent -> {
            submitted.add(intent);
            return CompletableFuture.completedFuture(success(intent, intent.basedOnWorldVersion() + 1));
        };
        SkillRunner runner = new SkillRunner(sink, 1_000);
        SkillPlan plan = new SkillPlan("plan", "alice", goal(), skill(), "door", 5, 9, Map.of());

        assertEquals(RunnerUpdate.Status.RUNNING, runner.start(plan, 100).status());
        assertEquals(ActionType.APPROACH, submitted.get(0).action());
        assertEquals(5, submitted.get(0).basedOnWorldVersion());

        assertEquals(RunnerUpdate.Status.RUNNING, runner.advance(6, 10, 110).status());
        assertEquals(ActionType.OPEN, submitted.get(1).action());
        assertEquals(6, submitted.get(1).basedOnWorldVersion());
        assertEquals(10L, submitted.get(1).parameters().get("perceptionVersion"));
        assertEquals("door", submitted.get(1).targetId());

        assertEquals(RunnerUpdate.Status.SUCCEEDED, runner.advance(7, 11, 120).status());
        assertFalse(runner.active());
    }

    @Test
    void returnsDeterministicFailureFeedbackWithoutSubmittingLaterSteps() {
        List<ActionIntent> submitted = new ArrayList<>();
        ActionSink sink = intent -> {
            submitted.add(intent);
            ActionResult failed = new ActionResult(intent.intentId(), ActionResult.Status.FAILED,
                    "DOOR_LOCKED", "locked", 6, List.of());
            return CompletableFuture.completedFuture(failed);
        };
        SkillRunner runner = new SkillRunner(sink, 1_000);

        runner.start(new SkillPlan("plan", "alice", goal(), skill(), "door", 5, 9, Map.of()), 100);
        RunnerUpdate update = runner.advance(6, 10, 110);

        assertEquals(RunnerUpdate.Status.FAILED, update.status());
        assertEquals("DOOR_LOCKED", update.code());
        assertEquals(1, submitted.size());
    }

    private Goal goal() {
        return new Goal("private-talk", "PRIVATE_TALK", "door", 1, Map.of(), 0, 0, Map.of());
    }

    private SkillDefinition skill() {
        return new SkillDefinition("enter-room", Set.of("PRIVATE_TALK"), null, true, 1,
                Map.of(), Set.of(CognitiveLod.FULL), List.of(
                new SkillStep(ActionType.APPROACH, SkillStep.TargetSource.GOAL_TARGET, "", Map.of()),
                new SkillStep(ActionType.OPEN, SkillStep.TargetSource.GOAL_TARGET, "", Map.of())));
    }

    private ActionResult success(ActionIntent intent, long version) {
        return new ActionResult(intent.intentId(), ActionResult.Status.SUCCEEDED,
                "OK", "", version, List.of());
    }
}
