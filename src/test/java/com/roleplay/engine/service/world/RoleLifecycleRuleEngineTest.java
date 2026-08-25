package com.roleplay.engine.service.world;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoleLifecycleRuleEngineTest {
    private static final Instant BASE = Instant.parse("2026-08-24T00:00:00Z");
    private static final RoleLifecyclePolicy POLICY = new RoleLifecyclePolicy(
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(20),
            Duration.ofMinutes(40), 2, 6);
    private final RoleLifecycleRuleEngine engine = new RoleLifecycleRuleEngine(POLICY);

    @Test
    void ambientInteractionPromotesBeforeTtlRetirement() {
        RoleLifecycleSnapshot role = role(RoleTier.AMBIENT, RoleLifecycleStatus.ACTIVE,
                BASE, BASE, 2, false);

        WorldCommand command = engine.evaluate(role, BASE.plus(Duration.ofHours(1))).orElseThrow();

        assertEquals(WorldCommandType.PROMOTE_ROLE, command.type());
        assertEquals("TEMPORARY", command.payload().get("targetTier"));
        assertEquals(2, command.preconditions().size());
        assertEquals(RoleLifecycleStatus.ACTIVE, role.status(), "规则评估不得修改输入世界快照");
    }

    @Test
    void untriggeredAmbientRoleExpiresAfterTtl() {
        WorldCommand command = engine.evaluate(role(RoleTier.AMBIENT, RoleLifecycleStatus.ACTIVE,
                BASE, BASE, 0, false), BASE.plusSeconds(301)).orElseThrow();

        assertEquals(WorldCommandType.RETIRE_ROLE, command.type());
        assertEquals("EXITED", command.payload().get("targetStatus"));
    }

    @Test
    void attentionRefreshesIdleTimeButDoesNotIncreasePromotionScore() {
        RoleLifecycleManager manager = new RoleLifecycleManager();
        manager.register("s1", "extra-1", RoleTier.AMBIENT, BASE);

        manager.recordInteraction("s1", "extra-1", RoleInteractionKind.ATTENTION.score(), BASE.plusSeconds(10));

        RoleLifecycleSnapshot observed = manager.get("s1", "extra-1").orElseThrow();
        assertEquals(0, observed.interactionCount());
        assertEquals(BASE.plusSeconds(10), observed.lastInteractionAt());
        assertTrue(engine.evaluate(observed, BASE.plusSeconds(11)).isEmpty());
    }

    @Test
    void attentionJustBeforeAmbientTtlPreventsImmediateRetirement() {
        RoleLifecycleSnapshot focused = role(RoleTier.AMBIENT, RoleLifecycleStatus.ACTIVE,
                BASE, BASE.plusSeconds(299), 0, false);

        assertTrue(engine.evaluate(focused, BASE.plusSeconds(301)).isEmpty());
    }

    @Test
    void idleRoleDegradesOneStateAtATime() {
        RoleLifecycleSnapshot active = role(RoleTier.SUPPORTING, RoleLifecycleStatus.ACTIVE,
                BASE, BASE, 0, false);
        assertEquals("PASSIVE", engine.evaluate(active, BASE.plus(Duration.ofMinutes(11)))
                .orElseThrow().payload().get("targetStatus"));
        assertEquals("DORMANT", engine.evaluate(active, BASE.plus(Duration.ofMinutes(21)))
                .orElseThrow().payload().get("targetStatus"));
        assertEquals(WorldCommandType.RETIRE_ROLE, engine.evaluate(active, BASE.plus(Duration.ofMinutes(41)))
                .orElseThrow().type());
    }

    @Test
    void coreRoleNeverAutomaticallyArchivesOrExits() {
        RoleLifecycleSnapshot core = role(RoleTier.CORE, RoleLifecycleStatus.ACTIVE,
                BASE, BASE, 0, false);
        WorldCommand command = engine.evaluate(core, BASE.plus(Duration.ofDays(30))).orElseThrow();

        assertEquals(WorldCommandType.SUSPEND_ROLE, command.type());
        assertEquals("DORMANT", command.payload().get("targetStatus"));

        RoleLifecycleSnapshot dormant = role(RoleTier.CORE, RoleLifecycleStatus.DORMANT,
                BASE, BASE, 0, false);
        assertTrue(engine.evaluate(dormant, BASE.plus(Duration.ofDays(30))).isEmpty());
    }

    @Test
    void pendingPlotWorkPreventsAutomaticDegradation() {
        RoleLifecycleSnapshot role = role(RoleTier.SUPPORTING, RoleLifecycleStatus.ACTIVE,
                BASE, BASE, 0, true);
        assertTrue(engine.evaluate(role, BASE.plus(Duration.ofDays(30))).isEmpty());
    }

    @Test
    void recentInteractionProposesResumeForDormantRole() {
        RoleLifecycleSnapshot role = role(RoleTier.SUPPORTING, RoleLifecycleStatus.DORMANT,
                BASE, BASE.plus(Duration.ofHours(1)), 1, false);
        WorldCommand command = engine.evaluate(role, BASE.plus(Duration.ofHours(1)).plusSeconds(1)).orElseThrow();
        assertEquals(WorldCommandType.RESUME_ROLE, command.type());
        assertEquals("ACTIVE", command.payload().get("targetStatus"));
    }

    @Test
    void dormantTemporaryRoleResumesBeforeAnyFurtherPromotion() {
        RoleLifecycleSnapshot role = role(RoleTier.TEMPORARY, RoleLifecycleStatus.DORMANT,
                BASE, BASE.plus(Duration.ofHours(1)), 10, false);

        WorldCommand command = engine.evaluate(role, BASE.plus(Duration.ofHours(1)).plusSeconds(1)).orElseThrow();

        assertEquals(WorldCommandType.RESUME_ROLE, command.type());
    }

    @Test
    void managerIsThreadSafeAndOnlyAppliesExplicitAcceptedTransition() throws Exception {
        RoleLifecycleManager manager = new RoleLifecycleManager();
        manager.register("s1", "extra-1", RoleTier.AMBIENT, BASE);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> executor.submit(() -> manager.recordInteraction(
                            "s1", "extra-1", BASE.plusSeconds(i + 1))))
                    .toList();
            for (var future : futures) future.get();
        }

        RoleLifecycleSnapshot observed = manager.get("s1", "extra-1").orElseThrow();
        assertEquals(100, observed.interactionCount());
        WorldCommand candidate = engine.evaluate(observed, BASE.plusSeconds(200)).orElseThrow();
        assertEquals(WorldCommandType.PROMOTE_ROLE, candidate.type());
        assertEquals(RoleTier.AMBIENT, manager.get("s1", "extra-1").orElseThrow().tier(),
                "候选命令不得自动改仓库");

        manager.applyAccepted("s1", "extra-1", RoleTier.TEMPORARY, RoleLifecycleStatus.ACTIVE);
        assertEquals(RoleTier.TEMPORARY, manager.get("s1", "extra-1").orElseThrow().tier());
    }

    private static RoleLifecycleSnapshot role(RoleTier tier, RoleLifecycleStatus status,
                                               Instant created, Instant interacted,
                                               int interactions, boolean pending) {
        return new RoleLifecycleSnapshot("s1", "r1", tier, status,
                created, interacted, interactions, pending);
    }
}
