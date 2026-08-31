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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {
    @Test
    void rejectsPlannerProposalWithStaleWorldVersionBeforeActionSubmission() {
        List<ActionIntent> submitted = new ArrayList<>();
        AgentRuntime runtime = runtime(request -> java.util.Optional.of(
                new PlannerProposal("wait", request.worldVersion() - 1, request.perceptionVersion(),
                        Map.of(), "stale")), successfulSink(submitted), oneSkillRegistry());

        RuntimeDecision decision = runtime.tick(input(1, 10, 5, 5, PlannerTrigger.GOAL_ACTIVATED));

        assertEquals(RuntimeDecision.Status.REPLAN_REQUIRED, decision.status());
        assertEquals("STALE_WORLD_VERSION", decision.code());
        assertTrue(decision.plannerUsed());
        assertEquals(0, submitted.size());
    }

    @Test
    void rejectsPlannerProposalWithStalePerceptionVersionBeforeActionSubmission() {
        List<ActionIntent> submitted = new ArrayList<>();
        AgentRuntime runtime = runtime(request -> java.util.Optional.of(
                new PlannerProposal("wait", request.worldVersion(), request.perceptionVersion() - 1,
                        Map.of(), "stale")), successfulSink(submitted), oneSkillRegistry());

        RuntimeDecision decision = runtime.tick(input(1, 10, 5, 5, PlannerTrigger.GOAL_ACTIVATED));

        assertEquals(RuntimeDecision.Status.REPLAN_REQUIRED, decision.status());
        assertEquals("STALE_PERCEPTION_VERSION", decision.code());
        assertEquals(0, submitted.size());
    }

    @Test
    void rejectsPlannerInventedSkillEvenWhenVersionsAreCurrent() {
        List<ActionIntent> submitted = new ArrayList<>();
        AgentRuntime runtime = runtime(request -> java.util.Optional.of(
                new PlannerProposal("hallucinated", request.worldVersion(), request.perceptionVersion(),
                        Map.of(), "invented")), successfulSink(submitted), oneSkillRegistry());

        RuntimeDecision decision = runtime.tick(input(1, 10, 5, 5, PlannerTrigger.GOAL_ACTIVATED));

        assertEquals(RuntimeDecision.Status.REPLAN_REQUIRED, decision.status());
        assertEquals("UNREGISTERED_OR_UNGROUNDED_SKILL", decision.code());
        assertEquals(0, submitted.size());
    }

    @Test
    void cognitiveLodDisablesPlannerForReducedAndActionsForMacro() {
        AtomicInteger plannerCalls = new AtomicInteger();
        PlannerGateway planner = request -> {
            plannerCalls.incrementAndGet();
            return java.util.Optional.empty();
        };
        List<ActionIntent> reducedActions = new ArrayList<>();
        AgentRuntime reduced = runtime(planner, successfulSink(reducedActions), oneSkillRegistry());

        RuntimeDecision reducedDecision = reduced.tick(input(1, 10, 5, 50, PlannerTrigger.WORLD_EVENT));
        assertEquals(CognitiveLod.REDUCED, reducedDecision.lod());
        assertEquals(RuntimeDecision.Status.ACTION_RUNNING, reducedDecision.status());
        assertEquals(0, plannerCalls.get());
        assertEquals(1, reducedActions.size());

        List<ActionIntent> macroActions = new ArrayList<>();
        AgentRuntime macro = runtime(planner, successfulSink(macroActions), oneSkillRegistry());
        RuntimeDecision macroDecision = macro.tick(input(1, 10, 5, 120, PlannerTrigger.WORLD_EVENT));
        assertEquals(CognitiveLod.MACRO, macroDecision.lod());
        assertEquals(RuntimeDecision.Status.MACRO_SIMULATED, macroDecision.status());
        assertEquals(0, macroActions.size());
        assertEquals(0, plannerCalls.get());
    }

    @Test
    void actionFailureFeedsBackAndNextTickReplansToAlternativeSkill() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("risky", ActionType.WAIT, 2.0));
        registry.register(skill("fallback", ActionType.STAND, 1.6));
        List<ActionIntent> submitted = new ArrayList<>();
        ActionSink sink = intent -> {
            submitted.add(intent);
            if (intent.action() == ActionType.WAIT) {
                return CompletableFuture.completedFuture(new ActionResult(intent.intentId(),
                        ActionResult.Status.FAILED, "BLOCKED", "path blocked", 11, List.of()));
            }
            return CompletableFuture.completedFuture(new ActionResult(intent.intentId(),
                    ActionResult.Status.SUCCEEDED, "DONE", "", 12, List.of()));
        };
        AgentRuntime runtime = runtime(PlannerGateway.NONE, sink, registry);

        assertEquals("risky", runtime.tick(input(1, 10, 5, 5, PlannerTrigger.NONE)).skillId());
        RuntimeDecision failed = runtime.tick(input(2, 11, 6, 5, PlannerTrigger.NONE));
        assertEquals(RuntimeDecision.Status.REPLAN_REQUIRED, failed.status());
        assertEquals("BLOCKED", failed.code());
        assertEquals(1, runtime.failureRecovery().recent().size());

        RuntimeDecision replanned = runtime.tick(input(3, 11, 6, 5, PlannerTrigger.NONE));
        assertEquals(RuntimeDecision.Status.ACTION_RUNNING, replanned.status());
        assertEquals("fallback", replanned.skillId());
        assertEquals(ActionType.STAND, submitted.get(1).action());

        RuntimeDecision completed = runtime.tick(input(4, 12, 7, 5, PlannerTrigger.NONE));
        assertEquals(RuntimeDecision.Status.SKILL_SUCCEEDED, completed.status());
        assertEquals(GoalManager.Status.COMPLETED, latestGoalManager.status("goal"));
    }

    @Test
    void fullLodPlannerMayChooseLowerUtilityButLegalSkill() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("high", ActionType.WAIT, 2.0));
        registry.register(skill("semantic", ActionType.STAND, 1.0));
        List<ActionIntent> submitted = new ArrayList<>();
        PlannerGateway planner = request -> java.util.Optional.of(new PlannerProposal("semantic",
                request.worldVersion(), request.perceptionVersion(), Map.of(), "context fit"));
        AgentRuntime runtime = runtime(planner, successfulSink(submitted), registry);

        RuntimeDecision decision = runtime.tick(input(1, 10, 5, 5, PlannerTrigger.WORLD_EVENT));

        assertEquals("semantic", decision.skillId());
        assertTrue(decision.plannerUsed());
        assertEquals(ActionType.STAND, submitted.getFirst().action());
        assertFalse(decision.rankedCandidates().isEmpty());
        assertEquals("high", decision.rankedCandidates().getFirst().skillId());
    }

    private GoalManager latestGoalManager;

    private AgentRuntime runtime(PlannerGateway planner,
                                 ActionSink sink,
                                 SkillRegistry registry) {
        GoalManager goals = new GoalManager();
        goals.add(new Goal("goal", "IDLE", "", 0.5, Map.of(), 0, 0, Map.of()));
        latestGoalManager = goals;
        return new AgentRuntime("agent", goals, registry, planner, sink,
                new CognitiveLodPolicy(20, 80, 10, 100));
    }

    private SkillRegistry oneSkillRegistry() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("wait", ActionType.WAIT, 1));
        return registry;
    }

    private SkillDefinition skill(String id, ActionType action, double utility) {
        return new SkillDefinition(id, Set.of("IDLE"), null, false, utility, Map.of(),
                Set.of(CognitiveLod.FULL, CognitiveLod.REDUCED),
                List.of(new SkillStep(action, SkillStep.TargetSource.NONE, "", Map.of())));
    }

    private RuntimeInput input(long tick,
                               long worldVersion,
                               long perceptionVersion,
                               double distance,
                               PlannerTrigger trigger) {
        PerceptionSnapshot perception = new PerceptionSnapshot("agent", worldVersion, perceptionVersion,
                tick, Set.of(), Map.of(), Map.of());
        return new RuntimeInput(tick, tick * 100, distance, false, List.of(), perception,
                WorkingMemory.empty(), trigger);
    }

    private ActionSink successfulSink(List<ActionIntent> submitted) {
        return intent -> {
            submitted.add(intent);
            return CompletableFuture.completedFuture(new ActionResult(intent.intentId(),
                    ActionResult.Status.SUCCEEDED, "DONE", "", intent.basedOnWorldVersion() + 1, List.of()));
        };
    }
}
