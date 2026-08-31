package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.action.ActionResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure Java cognition loop. It selects goals and registered skills, while all
 * world mutation remains behind ActionSink/ActionDispatcher.
 */
public final class AgentRuntime {
    private final String agentId;
    private final GoalManager goalManager;
    private final SkillRegistry skillRegistry;
    private final PlannerGateway plannerGateway;
    private final CognitiveLodPolicy lodPolicy;
    private final CandidateSkillGenerator candidateGenerator;
    private final UtilityScorer utilityScorer;
    private final FailureRecovery failureRecovery;
    private final SkillRunner skillRunner;

    private long lastDecisionTick = -1;
    private long planSequence;
    private boolean forceReplan;
    private CognitiveLod lastLod = CognitiveLod.MACRO;

    public AgentRuntime(String agentId,
                        GoalManager goalManager,
                        SkillRegistry skillRegistry,
                        PlannerGateway plannerGateway,
                        ActionSink actionSink,
                        CognitiveLodPolicy lodPolicy) {
        this(agentId, goalManager, skillRegistry, plannerGateway, actionSink, lodPolicy,
                new CandidateSkillGenerator(), new UtilityScorer(), FailureRecovery.defaults());
    }

    AgentRuntime(String agentId,
                 GoalManager goalManager,
                 SkillRegistry skillRegistry,
                 PlannerGateway plannerGateway,
                 ActionSink actionSink,
                 CognitiveLodPolicy lodPolicy,
                 CandidateSkillGenerator candidateGenerator,
                 UtilityScorer utilityScorer,
                 FailureRecovery failureRecovery) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        this.agentId = agentId;
        this.goalManager = Objects.requireNonNull(goalManager, "goalManager");
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
        this.plannerGateway = plannerGateway == null ? PlannerGateway.NONE : plannerGateway;
        this.lodPolicy = Objects.requireNonNull(lodPolicy, "lodPolicy");
        this.candidateGenerator = Objects.requireNonNull(candidateGenerator, "candidateGenerator");
        this.utilityScorer = Objects.requireNonNull(utilityScorer, "utilityScorer");
        this.failureRecovery = Objects.requireNonNull(failureRecovery, "failureRecovery");
        this.skillRunner = new SkillRunner(Objects.requireNonNull(actionSink, "actionSink"), 5_000);
    }

    public RuntimeDecision tick(RuntimeInput input) {
        Objects.requireNonNull(input, "input");
        validateSnapshot(input);
        CognitiveLod lod = lodPolicy.classify(input.distanceToPlayer(), input.storyCritical());
        lastLod = lod;

        if (skillRunner.active()) {
            RunnerUpdate update = skillRunner.advance(input.perception().worldVersion(),
                    input.perception().perceptionVersion(), input.nowMillis());
            if (update.status() == RunnerUpdate.Status.RUNNING) {
                return decision(RuntimeDecision.Status.ACTION_RUNNING, lod, update.goalId(), update.skillId(),
                        update.code(), false, List.of());
            }
            if (update.status() == RunnerUpdate.Status.SUCCEEDED) {
                goalManager.completeActive();
                return decision(RuntimeDecision.Status.SKILL_SUCCEEDED, lod, update.goalId(), update.skillId(),
                        update.code(), false, List.of());
            }
            if (update.status() == RunnerUpdate.Status.FAILED) {
                recordFailure(update, input);
                forceReplan = true;
                return decision(RuntimeDecision.Status.REPLAN_REQUIRED, lod, update.goalId(), update.skillId(),
                        update.code(), false, List.of());
            }
        }

        if (!forceReplan && !lodPolicy.shouldThink(lod, input.tick(), lastDecisionTick)) {
            return decision(RuntimeDecision.Status.DEFERRED, lod, "", "", "LOD_INTERVAL", false, List.of());
        }

        Optional<Goal> selectedGoal = goalManager.select(List.copyOf(input.needs()), input.tick());
        if (selectedGoal.isEmpty()) {
            lastDecisionTick = input.tick();
            forceReplan = false;
            return decision(RuntimeDecision.Status.NO_GOAL, lod, "", "", "NO_GOAL", false, List.of());
        }
        Goal goal = selectedGoal.get();

        if (!lodPolicy.skillExecutionAllowed(lod)) {
            lastDecisionTick = input.tick();
            forceReplan = false;
            return decision(RuntimeDecision.Status.MACRO_SIMULATED, lod, goal.id(), "",
                    "MACRO_GOAL_ONLY", false, List.of());
        }

        WorkingMemory memory = input.memory().withFailures(failureRecovery.recent());
        List<SkillCandidate> candidates = candidateGenerator.generate(goal, input.perception(), memory,
                skillRegistry, lod);
        List<ScoredSkill> ranked = utilityScorer.rank(candidates, goal, input.needs(), input.perception(),
                memory, failureRecovery);
        if (ranked.isEmpty()) {
            lastDecisionTick = input.tick();
            forceReplan = false;
            return decision(RuntimeDecision.Status.NO_CANDIDATE, lod, goal.id(), "",
                    "NO_GROUNDED_SKILL", false, ranked);
        }

        ScoredSkill selected = ranked.getFirst();
        boolean plannerUsed = false;
        java.util.Map<String, Object> overrides = java.util.Map.of();
        if (lodPolicy.plannerAllowed(lod) && input.plannerTrigger() != PlannerTrigger.NONE) {
            PlanningRequest request = new PlanningRequest(agentId, goal, ranked,
                    input.perception().worldVersion(), input.perception().perceptionVersion(),
                    input.plannerTrigger());
            Optional<PlannerProposal> proposal;
            try {
                proposal = Optional.ofNullable(plannerGateway.propose(request)).orElse(Optional.empty());
            } catch (RuntimeException plannerFailure) {
                proposal = Optional.empty();
            }
            if (proposal.isPresent()) {
                PlannerProposal value = proposal.get();
                String versionError = validateProposalVersion(value, input.perception());
                if (!versionError.isEmpty()) {
                    forceReplan = true;
                    lastDecisionTick = input.tick();
                    return decision(RuntimeDecision.Status.REPLAN_REQUIRED, lod, goal.id(), value.skillId(),
                            versionError, true, ranked);
                }
                Optional<ScoredSkill> proposedSkill = ranked.stream()
                        .filter(item -> item.skillId().equals(value.skillId()))
                        .findFirst();
                if (proposedSkill.isEmpty()) {
                    forceReplan = true;
                    lastDecisionTick = input.tick();
                    return decision(RuntimeDecision.Status.REPLAN_REQUIRED, lod, goal.id(), value.skillId(),
                            "UNREGISTERED_OR_UNGROUNDED_SKILL", true, ranked);
                }
                selected = proposedSkill.get();
                overrides = value.parameterOverrides();
                plannerUsed = true;
            }
        }

        String planId = agentId + "-plan-" + input.tick() + "-" + (++planSequence);
        SkillPlan plan = new SkillPlan(planId, agentId, goal, selected.candidate().definition(),
                selected.candidate().targetId(), input.perception().worldVersion(),
                input.perception().perceptionVersion(), overrides);
        RunnerUpdate started = skillRunner.start(plan, input.nowMillis());
        lastDecisionTick = input.tick();
        forceReplan = false;
        return decision(RuntimeDecision.Status.ACTION_RUNNING, lod, goal.id(), selected.skillId(),
                started.code(), plannerUsed, ranked);
    }

    public FailureRecovery failureRecovery() {
        return failureRecovery;
    }

    public CognitiveLod lastLod() {
        return lastLod;
    }

    private void validateSnapshot(RuntimeInput input) {
        PerceptionSnapshot snapshot = input.perception();
        if (!agentId.equals(snapshot.agentId())) {
            throw new IllegalArgumentException("perception belongs to another agent");
        }
        if (snapshot.capturedAtTick() > input.tick()) {
            throw new IllegalArgumentException("perception snapshot is from a future tick");
        }
    }

    private String validateProposalVersion(PlannerProposal proposal, PerceptionSnapshot current) {
        if (proposal.basedOnWorldVersion() != current.worldVersion()) return "STALE_WORLD_VERSION";
        if (proposal.basedOnPerceptionVersion() != current.perceptionVersion()) {
            return "STALE_PERCEPTION_VERSION";
        }
        return "";
    }

    private void recordFailure(RunnerUpdate update, RuntimeInput input) {
        ActionResult result = update.actionResult();
        String message = result == null ? "" : result.message();
        failureRecovery.record(new FailureFeedback(update.goalId(), update.skillId(), update.action(),
                update.code(), message, input.perception().worldVersion(),
                input.perception().perceptionVersion(), input.tick()));
    }

    private RuntimeDecision decision(RuntimeDecision.Status status,
                                     CognitiveLod lod,
                                     String goalId,
                                     String skillId,
                                     String code,
                                     boolean plannerUsed,
                                     List<ScoredSkill> ranked) {
        return new RuntimeDecision(status, lod, goalId, skillId, code, plannerUsed, ranked);
    }
}
