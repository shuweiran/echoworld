package com.roleplay.engine.service.world;

import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SessionRegistry;
import com.roleplay.engine.service.StructureMapService;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldRuntimeServiceTest {

    private WorldRuntimeService runtime;

    @AfterEach
    void closeRuntime() {
        if (runtime != null) runtime.close();
    }

    @Test
    void manyAmbientRolesStayLightweightUntilInteractionPromotesOne() {
        SimulationService simulation = mock(SimulationService.class);
        when(simulation.hasAgent(anyString())).thenReturn(false);
        when(simulation.addSocialAgent(anyString(), anyString()))
                .thenReturn(Map.of("status", "ok"));
        runtime = runtime(simulation, mock(SessionRegistry.class));

        for (int i = 0; i < 40; i++) {
            runtime.spawnExtra("simulation", "extra-" + i, null, null, null, null, null);
        }

        Map<String, Object> before = runtime.state("simulation");
        assertEquals(40, ((List<?>) before.get("ambient_agents")).size());
        verify(simulation, never()).addSocialAgent(anyString(), anyString());

        runtime.interact("simulation", "extra-7", RoleInteractionKind.DIALOGUE);
        runtime.scanLifecycle();
        runtime.dispatchCommands();

        verify(simulation, times(1)).addSocialAgent(anyString(), anyString());
        Map<String, Object> after = runtime.state("simulation");
        assertEquals(39, ((List<?>) after.get("ambient_agents")).size());
        RoleLifecycleSnapshot promoted = ((List<RoleLifecycleSnapshot>) after.get("roles")).stream()
                .filter(role -> role.roleId().equals("extra-7")).findFirst().orElseThrow();
        assertEquals(RoleTier.TEMPORARY, promoted.tier());
    }

    @Test
    void structuredCommandChecksPreconditionsBeforeChangingRole() {
        SimulationService simulation = mock(SimulationService.class);
        runtime = runtime(simulation, mock(SessionRegistry.class));
        runtime.spawnExtra("simulation", "witness", "目击者", "我什么也没看清。", "谨慎", 100d, 100d);
        WorldCommand invalid = new WorldCommand("cmd-1", WorldCommandType.SUSPEND_ROLE, "simulation",
                Map.of("roleId", "witness", "targetStatus", "DORMANT"),
                List.of(new WorldPrecondition("role.witness.tier", "EQ", "CORE")),
                "错误前置条件", Instant.now());

        assertTrue(runtime.propose(invalid));
        runtime.dispatchCommands();

        RoleLifecycleSnapshot role = ((List<RoleLifecycleSnapshot>) runtime.state("simulation").get("roles"))
                .getFirst();
        assertEquals(RoleLifecycleStatus.ACTIVE, role.status());
        verify(simulation, never()).addSocialAgent(anyString(), anyString());
        verify(simulation, never()).removeSocialAgent(anyString());
    }

    @Test
    void asyncMailboxReturnsBeforeRouterConsumesAndKeepsSessionOrderingGate() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        SimulationService simulation = mock(SimulationService.class);
        RouterService router = mock(RouterService.class);
        CountDownLatch consumed = new CountDownLatch(1);
        when(sessions.get("s-1")).thenReturn(router);
        when(router.runRound(eq("玩家突然打断"), isNull(), eq("主角"), eq("p-1")))
                .thenAnswer(ignored -> {
                    consumed.countDown();
                    return new RouterService.RoundResult("ok", List.of(), Map.of(), "", Map.of());
                });
        runtime = runtime(simulation, sessions);

        InputMailbox.OfferResult offered = runtime.enqueueInput(new InputMailbox.MailboxInput(
                "s-1", "input-1", "玩家突然打断", InputMailbox.Priority.CRITICAL,
                Instant.now(), Map.of("speaker", "主角", "player_id", "p-1")));

        assertTrue(offered.accepted());
        runtime.dispatchInputs();
        assertTrue(consumed.await(2, TimeUnit.SECONDS));
        verify(router).runRound("玩家突然打断", null, "主角", "p-1");
    }

    @Test
    void delayedOldGenerationCleanupCannotEraseRecreatedSession() {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RouterService oldRouter = mock(RouterService.class);
        RouterService newRouter = mock(RouterService.class);
        when(sessions.get("same-id")).thenReturn(oldRouter, oldRouter, newRouter, newRouter);
        runtime = runtime(mock(SimulationService.class), sessions);

        assertTrue(runtime.enqueueInput(new InputMailbox.MailboxInput(
                "same-id", "old-input", "旧代输入", InputMailbox.Priority.NORMAL,
                Instant.now(), Map.of())).accepted());
        assertTrue(runtime.enqueueInput(new InputMailbox.MailboxInput(
                "same-id", "new-input", "新代输入", InputMailbox.Priority.NORMAL,
                Instant.now(), Map.of())).accepted());

        runtime.removeSessionGeneration("same-id", oldRouter);

        Map<String, Object> state = assertDoesNotThrow(() -> runtime.state("same-id"));
        assertEquals(1, ((InputMailbox.SessionMetrics) state.get("mailbox")).pending(),
                "新代首次绑定应清掉旧代邮箱，但迟到的旧回调不得再清新代输入");
    }

    @Test
    void non2dGroupChatTargetsMembersAndCountsEachEffectiveInteractionOnce() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RouterService router = mock(RouterService.class);
        when(sessions.get("group-chat")).thenReturn(router);
        when(router.hasAgent(anyString())).thenReturn(false);
        when(router.runRoundTargeted(eq("大家聊聊"), isNull(), eq("玩家"), isNull(),
                eq(List.of("路人甲", "路人乙"))))
                .thenReturn(new RouterService.RoundResult("ok", List.of(), Map.of(), "", Map.of()));
        runtime = runtime(mock(SimulationService.class), sessions);
        runtime.spawnExtra("group-chat", "extra-a", "路人甲", "你好", "", null, null);
        runtime.spawnExtra("group-chat", "extra-b", "路人乙", "欢迎", "", null, null);

        runtime.enqueueInput(new InputMailbox.MailboxInput("group-chat", "group-input", "大家聊聊",
                InputMailbox.Priority.CRITICAL, Instant.now(), Map.of(
                "speaker", "玩家",
                "conversation_members", List.of("路人甲", "路人乙"),
                "focused_role_ids", List.of("extra-a", "extra-b"))));
        runtime.dispatchInputs();

        await(() -> rolesFor("group-chat").stream().allMatch(role -> role.interactionCount() == 2));
        verify(router).runRoundTargeted("大家聊聊", null, "玩家", null, List.of("路人甲", "路人乙"));
        assertEquals(2, rolesFor("group-chat").size());
    }

    @Test
    void rejectsAmbientNameCollisionAndForeignSessionWorldWrites() {
        SimulationService simulation = mock(SimulationService.class);
        when(simulation.hasAgent("主角")).thenReturn(true);
        runtime = runtime(simulation, mock(SessionRegistry.class));

        assertThrows(IllegalStateException.class, () -> runtime.spawnExtra(
                "simulation", "fake", "主角", "路过", "伪装", 10d, 10d));
        assertThrows(IllegalArgumentException.class, () -> runtime.spawnExtra(
                "foreign", "x", "路人", "路过", "路人", 10d, 10d));
        assertFalse(runtime.propose(new WorldCommand("foreign-cmd", WorldCommandType.SPAWN_EXTRA,
                "foreign", Map.of("roleId", "x"), List.of(), "", Instant.now())));
        verify(simulation, never()).removeSocialAgent(anyString());
    }

    @Test
    void rejectsTierSkippingAndRoleMutationWithoutPreconditions() {
        SimulationService simulation = mock(SimulationService.class);
        when(simulation.hasAgent(anyString())).thenReturn(false);
        runtime = runtime(simulation, mock(SessionRegistry.class));
        runtime.spawnExtra("simulation", "extra", "路人甲", "一句话", "路人", 20d, 20d);

        assertFalse(runtime.propose(new WorldCommand("no-pre", WorldCommandType.PROMOTE_ROLE, "simulation",
                Map.of("roleId", "extra", "targetTier", "CORE"), List.of(), "", Instant.now())));
        assertTrue(runtime.propose(roleCommand("skip", WorldCommandType.PROMOTE_ROLE, "extra",
                "AMBIENT", "ACTIVE", Map.of("targetTier", "CORE"))));
        runtime.dispatchCommands();

        RoleLifecycleSnapshot role = roles().getFirst();
        assertEquals(RoleTier.AMBIENT, role.tier());
        verify(simulation, never()).addSocialAgent(anyString(), anyString());
    }

    @Test
    void failedInputIsRequeuedWithSameIdAndEventuallyConsumed() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RouterService router = mock(RouterService.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        when(sessions.get("retry-session")).thenReturn(router);
        when(router.runRound(anyString(), isNull(), isNull(), isNull())).thenAnswer(ignored -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("temporary");
            completed.countDown();
            return new RouterService.RoundResult("ok", List.of(), Map.of(), "", Map.of());
        });
        runtime = runtime(mock(SimulationService.class), sessions);
        assertTrue(runtime.enqueueInput(new InputMailbox.MailboxInput(
                "retry-session", "same-id", "retry me", InputMailbox.Priority.NORMAL,
                Instant.now(), Map.of())).accepted());

        runtime.dispatchInputs();
        await(() -> runtime.state("retry-session").get("mailbox") != null
                && ((InputMailbox.SessionMetrics) runtime.state("retry-session").get("mailbox")).pending() == 1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (completed.getCount() > 0 && System.nanoTime() < deadline) {
            runtime.dispatchInputs();
            Thread.sleep(10);
        }
        assertEquals(0, completed.getCount());
        verify(router, times(2)).runRound("retry me", null, null, null);
    }

    @Test
    void archivedPromotedRoleCanResumeButCoreCannotRetire() {
        SimulationService simulation = mock(SimulationService.class);
        when(simulation.hasAgent(anyString())).thenReturn(false, false, true, false);
        when(simulation.addSocialAgent(anyString(), anyString())).thenReturn(Map.of("status", "ok"));
        when(simulation.removeSocialAgent(anyString())).thenReturn(Map.of("status", "ok"));
        when(simulation.suspendSocialAgent(anyString())).thenReturn(Map.of("status", "ok"));
        when(simulation.resumeSocialAgent(anyString())).thenReturn(Map.of("status", "ok"));
        when(simulation.isSuspendedAgent(anyString())).thenReturn(false, true);
        runtime = runtime(simulation, mock(SessionRegistry.class));
        runtime.spawnExtra("simulation", "arch", "档案员", "稍后见", "谨慎", 20d, 20d);
        runtime.interact("simulation", "arch", RoleInteractionKind.DIALOGUE);
        runtime.scanLifecycle();
        runtime.dispatchCommands();

        assertTrue(runtime.propose(roleCommand("archive", WorldCommandType.RETIRE_ROLE, "arch",
                "TEMPORARY", "ACTIVE", Map.of("targetStatus", "ARCHIVED"))));
        runtime.dispatchCommands();
        assertEquals(RoleLifecycleStatus.ARCHIVED, roles().getFirst().status(),
                String.valueOf(runtime.state("simulation").get("recent_results")));
        assertTrue(runtime.propose(roleCommand("resume", WorldCommandType.RESUME_ROLE, "arch",
                "TEMPORARY", "ARCHIVED", Map.of())));
        runtime.dispatchCommands();
        assertEquals(RoleLifecycleStatus.ACTIVE, roles().getFirst().status());
        verify(simulation).addSocialAgent(eq("档案员"), anyString());
        verify(simulation).suspendSocialAgent("档案员");
        verify(simulation).resumeSocialAgent("档案员");
    }

    @Test
    void existingSimulationAgentsAreRegisteredAsProtectedCoreRoles() {
        SimulationService simulation = mock(SimulationService.class);
        when(simulation.getState()).thenReturn(Map.of("agents", List.of(
                Map.of("agentName", "原有主角", "inConversation", false, "playerControlled", false),
                Map.of("agentName", "玩家本人", "inConversation", false, "playerControlled", true))));
        runtime = runtime(simulation, mock(SessionRegistry.class));

        runtime.scanLifecycle();

        assertEquals(1, roles().size());
        assertEquals(RoleTier.CORE, roles().getFirst().tier());
        assertTrue(runtime.isManagedAgentName("原有主角"));
        assertFalse(runtime.isManagedAgentName("玩家本人"));
    }

    @Test
    void resetRemovesOldWorldLifecycleOwnership() {
        SimulationService simulation = mock(SimulationService.class);
        when(simulation.hasAgent(anyString())).thenReturn(false);
        runtime = runtime(simulation, mock(SessionRegistry.class));
        runtime.spawnExtra("simulation", "old", "旧路人", "再见", "旧世界", 10d, 10d);
        assertFalse(roles().isEmpty());

        runtime.resetSimulationMetadata();

        assertTrue(roles().isEmpty());
        assertFalse(runtime.isManagedAgentName("旧路人"));
    }

    @Test
    void oldPlannerCannotWriteIntoReusedSessionId() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RouterService router = mock(RouterService.class);
        WorldCommandPlanner planner = mock(WorldCommandPlanner.class);
        CountDownLatch plannerStarted = new CountDownLatch(1);
        CountDownLatch releasePlanner = new CountDownLatch(1);
        when(sessions.get("reused")).thenReturn(router);
        when(router.runRound(anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new RouterService.RoundResult("ok", List.of(), Map.of(), "", Map.of()));
        when(planner.planDetailed(eq("reused"), anyString(), anyString(), anyInt(), anyList())).thenAnswer(ignored -> {
            plannerStarted.countDown();
            releasePlanner.await(2, TimeUnit.SECONDS);
            return new WorldCommandPlanner.PlanResult(List.of(new WorldCommand("old-plan",
                    WorldCommandType.GENERATE_MAP, "reused", Map.of("theme", "旧会话"),
                    List.of(), "old", Instant.now())), null);
        });
        runtime = runtime(mock(SimulationService.class), sessions, planner);
        runtime.enqueueInput(new InputMailbox.MailboxInput(
                "reused", "old-input", "旧输入", InputMailbox.Priority.NORMAL, Instant.now(), Map.of()));
        runtime.dispatchInputs();
        assertTrue(plannerStarted.await(2, TimeUnit.SECONDS));

        runtime.removeSession("reused");
        runtime.enqueueInput(new InputMailbox.MailboxInput(
                "reused", "new-input", "新输入", InputMailbox.Priority.NORMAL, Instant.now(), Map.of()));
        releasePlanner.countDown();
        Thread.sleep(100);

        assertEquals(0, runtime.state("reused").get("command_queue"));
    }

    @Test
    void textModeUsesLlmSceneBudgetAndOnlyMeaningfulInteractionPromotes() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RouterService router = mock(RouterService.class);
        SimulationService simulation = mock(SimulationService.class);
        WorldCommandPlanner planner = mock(WorldCommandPlanner.class);
        when(sessions.get("text-scene")).thenReturn(router);
        when(router.runRound(anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new RouterService.RoundResult("ok", List.of(),
                        Map.of("narration", "玩家走进一间安静卧室", "scene_progress", "室内"), "", Map.of()));
        AtomicBoolean textAgentActive = new AtomicBoolean(false);
        when(router.hasAgent(anyString())).thenAnswer(ignored -> textAgentActive.get());
        doAnswer(ignored -> { textAgentActive.set(true); return null; })
                .when(router).addWorldAgent(anyString(), any(Persona.class));
        when(router.suspendWorldAgent(anyString())).thenAnswer(ignored -> {
            textAgentActive.set(false); return true;
        });
        when(router.resumeWorldAgent(anyString())).thenAnswer(ignored -> {
            textAgentActive.set(true); return true;
        });
        when(simulation.getState()).thenReturn(Map.of("running", false));
        when(planner.planDetailed(eq("text-scene"), anyString(), contains("卧室"), anyInt(), anyList()))
                .thenReturn(new WorldCommandPlanner.PlanResult(List.of(),
                        new WorldCommandPlanner.ScenePopulationSuggestion(
                                ScenePopulationCategory.PRIVATE_INDOOR, "卧室", 30, 0.95,
                                "私人室内应保持少量人物")));
        when(planner.enrichRole(anyString(), anyString(), anyString(), eq("卧室")))
                .thenReturn(new GeneratedRoleCard("留宿的客人", "等待屋主回来", "轻声简短",
                        "刚刚认识玩家", "只知道屋内公开情况"));
        runtime = new WorldRuntimeService(new InputMailbox(), sessions, simulation,
                mock(SSEController.class), mock(StructureMapService.class), planner,
                true, 0, 80, 8, 1, 60_000);
        runtime.enqueueInput(new InputMailbox.MailboxInput(
                "text-scene", "scene-input", "我走进卧室", InputMailbox.Priority.NORMAL,
                Instant.now(), Map.of()));

        runtime.dispatchInputs();
        await(() -> runtime.state("text-scene").get("scene_population") != null);
        runtime.scanLifecycle();

        Map<String, Object> state = runtime.state("text-scene");
        assertEquals(5, ((List<?>) state.get("ambient_agents")).size(), "私人室内建议 30 会被限幅为 5");
        String roleId = ((RoleLifecycleSnapshot) ((List<?>) state.get("roles")).getFirst()).roleId();
        runtime.interact("text-scene", roleId, RoleInteractionKind.ATTENTION);
        runtime.scanLifecycle();
        runtime.dispatchCommands();
        verify(router, never()).addWorldAgent(anyString(), any(Persona.class));

        runtime.interact("text-scene", roleId, RoleInteractionKind.DIALOGUE);
        await(() -> ((Number) runtime.state("text-scene").get("command_queue")).intValue() > 0);
        runtime.dispatchCommands();

        verify(router).addWorldAgent(anyString(), any(Persona.class));
        verify(planner).enrichRole(anyString(), anyString(), anyString(), eq("卧室"));

        assertTrue(runtime.propose(new WorldCommand("text-archive", WorldCommandType.RETIRE_ROLE,
                "text-scene", Map.of("roleId", roleId, "targetStatus", "ARCHIVED"), List.of(
                new WorldPrecondition("role." + roleId + ".tier", "EQ", "TEMPORARY"),
                new WorldPrecondition("role." + roleId + ".lifecycleStatus", "EQ", "ACTIVE")),
                "test", Instant.now())));
        runtime.dispatchCommands();
        verify(router).suspendWorldAgent(anyString());
        assertTrue(runtime.propose(new WorldCommand("text-resume", WorldCommandType.RESUME_ROLE,
                "text-scene", Map.of("roleId", roleId), List.of(
                new WorldPrecondition("role." + roleId + ".tier", "EQ", "TEMPORARY"),
                new WorldPrecondition("role." + roleId + ".lifecycleStatus", "EQ", "ARCHIVED")),
                "test", Instant.now())));
        runtime.dispatchCommands();
        verify(router).resumeWorldAgent(anyString());
    }

    @Test
    void olderScenePlanCannotOverwriteNewerPlanInSameSession() throws Exception {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RouterService router = mock(RouterService.class);
        WorldCommandPlanner planner = mock(WorldCommandPlanner.class);
        CountDownLatch oldPlanStarted = new CountDownLatch(1);
        CountDownLatch releaseOldPlan = new CountDownLatch(1);
        when(sessions.get("ordered")).thenReturn(router);
        when(router.runRound(anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new RouterService.RoundResult("ok", List.of(), Map.of(), "", Map.of()));
        when(router.getState()).thenReturn(Map.of("scene", "变化中的场景"));
        when(planner.planDetailed(eq("ordered"), eq("old"), anyString(), anyInt(), anyList()))
                .thenAnswer(ignored -> {
                    oldPlanStarted.countDown();
                    releaseOldPlan.await(2, TimeUnit.SECONDS);
                    return new WorldCommandPlanner.PlanResult(List.of(),
                            new WorldCommandPlanner.ScenePopulationSuggestion(
                                    ScenePopulationCategory.OUTDOOR_BUSY, "旧广场", 30, 0.9, "旧"));
                });
        when(planner.planDetailed(eq("ordered"), eq("new"), anyString(), anyInt(), anyList()))
                .thenReturn(new WorldCommandPlanner.PlanResult(List.of(),
                        new WorldCommandPlanner.ScenePopulationSuggestion(
                                ScenePopulationCategory.PRIVATE_INDOOR, "新卧室", 2, 0.9, "新")));
        runtime = runtime(mock(SimulationService.class), sessions, planner);

        runtime.enqueueInput(new InputMailbox.MailboxInput(
                "ordered", "old-input", "old", InputMailbox.Priority.NORMAL, Instant.now(), Map.of()));
        runtime.dispatchInputs();
        assertTrue(oldPlanStarted.await(2, TimeUnit.SECONDS));
        runtime.enqueueInput(new InputMailbox.MailboxInput(
                "ordered", "new-input", "new", InputMailbox.Priority.NORMAL, Instant.now(), Map.of()));
        await(() -> {
            runtime.dispatchInputs();
            Object profile = runtime.state("ordered").get("scene_population");
            return profile instanceof Map<?, ?> p && "新卧室".equals(p.get("scene_label"));
        });

        releaseOldPlan.countDown();
        Thread.sleep(100);

        Map<?, ?> profile = (Map<?, ?>) runtime.state("ordered").get("scene_population");
        assertEquals("新卧室", profile.get("scene_label"));
        assertEquals(2, profile.get("target_count"));
    }

    @Test
    void externalPromotionCannotBypassPendingRoleCardEnrichment() throws Exception {
        SimulationService simulation = mock(SimulationService.class);
        WorldCommandPlanner planner = mock(WorldCommandPlanner.class);
        CountDownLatch enrichmentStarted = new CountDownLatch(1);
        CountDownLatch releaseEnrichment = new CountDownLatch(1);
        when(simulation.hasAgent(anyString())).thenReturn(false);
        when(simulation.addSocialAgent(anyString(), anyString())).thenReturn(Map.of("status", "ok"));
        when(planner.enrichRole(anyString(), anyString(), anyString(), anyString())).thenAnswer(ignored -> {
            enrichmentStarted.countDown();
            releaseEnrichment.await(2, TimeUnit.SECONDS);
            return GeneratedRoleCard.fallback("路人", "你好", "薄设定");
        });
        runtime = runtime(simulation, mock(SessionRegistry.class), planner);
        runtime.spawnExtra("simulation", "pending-card", "路人", "你好", "薄设定", 20d, 20d);

        runtime.interact("simulation", "pending-card", RoleInteractionKind.DIALOGUE);
        assertTrue(enrichmentStarted.await(2, TimeUnit.SECONDS));
        assertTrue(runtime.propose(roleCommand("bypass", WorldCommandType.PROMOTE_ROLE, "pending-card",
                "AMBIENT", "ACTIVE", Map.of("targetTier", "TEMPORARY"))));
        runtime.dispatchCommands();

        verify(simulation, never()).addSocialAgent(anyString(), anyString());
        releaseEnrichment.countDown();
        await(() -> ((Number) runtime.state("simulation").get("command_queue")).intValue() > 0);
        runtime.dispatchCommands();
        verify(simulation).addSocialAgent(anyString(), contains("认知边界"));
    }

    @Test
    void acceptedInteractionEventIsIdempotent() {
        SimulationService simulation = mock(SimulationService.class);
        when(simulation.hasAgent(anyString())).thenReturn(false);
        runtime = runtime(simulation, mock(SessionRegistry.class));
        runtime.spawnExtra("simulation", "idempotent-role", "旅客", "你好", "普通旅客", 20d, 20d);

        runtime.interactOnce("simulation", "idempotent-role", RoleInteractionKind.DIALOGUE, "message-1");
        RoleLifecycleSnapshot duplicate = runtime.interactOnce(
                "simulation", "idempotent-role", RoleInteractionKind.DIALOGUE, "message-1");

        assertEquals(2, duplicate.interactionCount());
    }

    @Test
    void enqueueCannotReviveSessionClosedDuringBinding() {
        SessionRegistry sessions = mock(SessionRegistry.class);
        RouterService router = mock(RouterService.class);
        when(sessions.get("closing")).thenReturn(router)
                .thenThrow(new IllegalArgumentException("closed"));
        runtime = runtime(mock(SimulationService.class), sessions);

        assertThrows(IllegalArgumentException.class, () -> runtime.enqueueInput(new InputMailbox.MailboxInput(
                "closing", "late-input", "太晚了", InputMailbox.Priority.NORMAL,
                Instant.now(), Map.of())));

        assertNull(runtime.state("closing").get("mailbox"));
        assertEquals(0, runtime.state("closing").get("command_queue"));
    }

    @SuppressWarnings("unchecked")
    private List<RoleLifecycleSnapshot> roles() {
        return (List<RoleLifecycleSnapshot>) runtime.state("simulation").get("roles");
    }

    @SuppressWarnings("unchecked")
    private List<RoleLifecycleSnapshot> rolesFor(String sessionId) {
        return (List<RoleLifecycleSnapshot>) runtime.state(sessionId).get("roles");
    }

    private static WorldCommand roleCommand(String id, WorldCommandType type, String roleId,
                                             String tier, String status, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(extraPayload);
        payload.put("roleId", roleId);
        return new WorldCommand(id, type, "simulation", payload, List.of(
                new WorldPrecondition("role." + roleId + ".tier", "EQ", tier),
                new WorldPrecondition("role." + roleId + ".lifecycleStatus", "EQ", status)), "test", Instant.now());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }

    private WorldRuntimeService runtime(SimulationService simulation, SessionRegistry sessions) {
        return runtime(simulation, sessions, null);
    }

    private WorldRuntimeService runtime(SimulationService simulation, SessionRegistry sessions,
                                        WorldCommandPlanner planner) {
        return new WorldRuntimeService(new InputMailbox(), sessions, simulation,
                mock(SSEController.class), mock(StructureMapService.class), planner,
                false, 0, 80, 8, 1, 60_000);
    }
}
