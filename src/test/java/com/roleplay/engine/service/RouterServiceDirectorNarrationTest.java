package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.model.Session;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 主控隐藏：director 导演模式不再输出可见主控旁白（主人报告 P0-4 拍板）。
 *
 * <p>背景：P-0811-G 删除了 free/protagonist/multi_track 的主控叙事，P-0815-E 曾恢复
 * director 可见旁白；P0 改造将主控彻底从可见会话剥离 —— 所有一般模式（free/protagonist/
 * multi_track/director）均不入史、不推 arbiter_integrate SSE；导演意图改为后台
 * DirectorDirective（WorldRuntimeService 每轮后推送），在 Agent 生成前注入上下文。
 *
 * <p>验证：
 * ① director 双人 → 直接对话短路（不调 integrate），旁白不入史 + 无 SSE 推送；
 * ② free 双人 → 仍短路不调用 + 无旁白入史 + 无 SSE 推送（保持）；
 * ③ director 多人 → integrateOutputs 被调用（预测保留），旁白不入史 + 无 SSE 推送；
 * ④ 狼人杀 → 不受影响（GM 推进保留 + 旁白入史 + SSE 推送，会话定向 3 参）；
 * ⑤ 后台导演指令 → 注入 Agent 上下文（【主控导演指令】），且不入史、不可见。
 */
class RouterServiceDirectorNarrationTest {

    private static final String SESSION_ID = "director-narration-test";
    private static final String SCENE = "夜幕下的古堡。";

    private ArbiterService arbiter;
    private SSEController sse;
    private MemoryStore memory;

    /**
     * 构建 RouterService（与 P-0815-C RouterServiceDuoSkipIntegrateTest 同款 mock 方式，
     * 另注入 mock SSEController + 保留 memory 引用供入史断言）：
     * - mock LLM/Arbiter（configureTracks 返回固定单轨道；integrateOutputs 返回固定旁白）
     * - 轨道 agent_actions 由 {@code agents} 与 {@code activeAgents} 决定（其余 silent）
     */
    private RouterService newRouter(String mode, String protagonist,
                                    List<String> agents, List<String> activeAgents) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("测试发言");
        return newRouterWithLlm(llm, mode, protagonist, agents, activeAgents);
    }

    private RouterService newRouterWithLlm(LLMClient llm, String mode, String protagonist,
                                           List<String> agents, List<String> activeAgents) {
        arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", new ArrayList<>(agents));
        Map<String, String> actions = new LinkedHashMap<>();
        for (String n : agents) {
            actions.put(n, activeAgents.contains(n) ? "active" : "silent");
        }
        track.put("agent_actions", actions);
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any()))
                .thenReturn(new TrackConfigResult(List.of(track), "test"));
        when(arbiter.integrateOutputs(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(Map.of("narration", "整合旁白"));

        sse = mock(SSEController.class);
        memory = new MemoryStore();

        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));

        RouterService router = new RouterService(
                arbiter,
                executor,
                memory,
                mock(Compressor.class),
                mock(Monitor.class),
                mock(GeneratorService.class),
                mock(TrackRequestService.class),
                llm,
                null,            // historyController
                null,            // lorebookService
                interruptManager,
                new WorldEventBus(),
                sse,
                null);           // identityService
        List<Persona> personas = agents.stream().map(n -> new Persona(n, "你是一个角色。")).toList();
        router.initSession(SESSION_ID, personas, SCENE, mode, protagonist, "");
        return router;
    }

    /** 断言 memory 会话中出现「主控」ARBITER 旁白（P-0815-E 恢复的入史路径）。 */
    private void assertNarrationInMemory(String narration) {
        assertNotNull(memory.getSession(), "memory session 应已建立");
        boolean found = memory.getSession().getMessages().stream()
                .anyMatch(m -> m.getRole() == Message.Role.ARBITER
                        && "主控".equals(m.getName())
                        && narration.equals(m.getContent()));
        assertTrue(found, "memory 应包含主控 ARBITER 旁白: " + narration);
    }

    private void assertNoNarrationInMemory() {
        assertNotNull(memory.getSession(), "memory session 应已建立");
        boolean found = memory.getSession().getMessages().stream()
                .anyMatch(m -> m.getRole() == Message.Role.ARBITER);
        assertFalse(found, "memory 不应出现 ARBITER 主控旁白（一般模式对话驱动模式保持无旁白）");
    }

    // ── ① director 双人 → 直接对话短路（无 integrate），旁白不可见 ──

    @Test
    @DisplayName("① director 双人（2 active AI）→ 直接对话短路不调用 integrate，旁白不入史、无 SSE")
    void directorDuo_restoresNarration() {
        RouterService router = newRouter("director", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size(), "双 AI 角色均应发言");
        // ①a 直接对话短路（≤2 可回复者走确定性轨道，不调仲裁 LLM；与 free 双人同路径）
        verify(arbiter, never()).configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any());
        verify(arbiter, never()).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        // ①b P0 主控隐藏：narration 不入史
        assertNoNarrationInMemory();
        // ①c P0 主控隐藏：无 arbiter_integrate SSE（2 参旧重载与 3 参会话重载均不调用）
        verify(sse, never()).broadcastArbiterIntegrate(anyInt(), anyString());
        verify(sse, never()).broadcastArbiterIntegrate(anyString(), anyInt(), anyString());
    }

    // ── ② free 双人 → 仍短路不调用 + 无旁白入史 + 无 SSE 推送（P-0815-C/P-0811-G 保持）──

    @Test
    @DisplayName("② free 双人（2 active AI）→ 仍短路不调用 + 无旁白入史 + 无 SSE 推送")
    void freeDuo_stillSkipsNarration() {
        RouterService router = newRouter("free", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size());
        verify(arbiter, never()).configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any());
        verify(arbiter, never()).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        assertNoNarrationInMemory();
        verify(sse, never()).broadcastArbiterIntegrate(anyInt(), anyString());
    }

    // ── ③ director 多人 → integrateOutputs 被调用（预测保留），旁白不可见 ──

    @Test
    @DisplayName("③ director 多人（3 active）→ integrateOutputs 被调用，旁白不入史、无 SSE")
    void directorMulti_restoresNarration() {
        RouterService router = newRouter("director", "", List.of("A", "B", "C"), List.of("A", "B", "C"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(3, result.agentOutputs.size());
        verify(arbiter, times(1)).configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any());
        verify(arbiter, times(1)).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        // P0 主控隐藏：不入史、不推送
        assertNoNarrationInMemory();
        verify(sse, never()).broadcastArbiterIntegrate(anyInt(), anyString());
        verify(sse, never()).broadcastArbiterIntegrate(anyString(), anyInt(), anyString());
    }

    // ── ④ 狼人杀（2 active）→ 不受影响（GM 推进保留 + 旁白入史 + SSE 推送）──

    @Test
    @DisplayName("④ 狼人杀（2 active）→ integrateOutputs 仍被调用 + 旁白入史 + SSE 广播（GM 推进保留，会话定向）")
    void werewolf_unchanged() {
        RouterService router = newRouter("werewolf", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size());
        verify(arbiter, times(1)).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        assertNarrationInMemory("整合旁白");
        // 会话定向 3 参重载（P0-1 多会话隔离；载荷与旧 2 参与义一致，仅多 session_id 定向）
        ArgumentCaptor<String> narrationCaptor = ArgumentCaptor.forClass(String.class);
        verify(sse, times(1)).broadcastArbiterIntegrate(
                org.mockito.ArgumentMatchers.eq(SESSION_ID), anyInt(), narrationCaptor.capture());
        assertEquals("整合旁白", narrationCaptor.getValue(), "SSE 广播的 narration 应为整合旁白");
    }

    // ── ⑤ 后台导演指令注入 Agent 上下文（P0 主控隐藏配套） ──

    @Test
    @DisplayName("⑤ setDirectorDirective → Agent 上下文含【主控导演指令】，指令本身不入史、不可见")
    void directorDirective_injectedIntoAgentContext() {
        List<String> captured = new ArrayList<>();
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Message> msgs = inv.getArgument(0);
            String ctx = msgs.stream()
                    .map(m -> String.valueOf(m.getContent()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            captured.add(ctx);
            return "测试发言";
        });
        RouterService router = newRouterWithLlm(llm, "free", "", List.of("A", "B"), List.of("A", "B"));
        router.setDirectorDirective("下一拍：古堡停电，众人寻找光源");

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertFalse(captured.isEmpty(), "应有 LLM 调用");
        for (String ctx : captured) {
            assertTrue(ctx.contains("【主控导演指令】"), "Agent 上下文应含导演指令块");
            assertTrue(ctx.contains("下一拍：古堡停电"), "Agent 上下文应含指令内容");
        }
        // 指令只走上下文，不入史、不可见（无 ARBITER 消息）
        assertNoNarrationInMemory();
        assertTrue(memory.getSession().getMessages().stream()
                .noneMatch(m -> String.valueOf(m.getContent()).contains("下一拍：古堡停电")),
                "导演指令原文不应入史");
    }

    @Test
    @DisplayName("⑤b 角色定向导演信息只进入对应角色上下文")
    void directorRoleGuidance_isPrivateToTargetAgent() {
        List<String> captured = new ArrayList<>();
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked") List<Message> msgs = inv.getArgument(0);
            captured.add(msgs.stream().map(m -> String.valueOf(m.getContent()))
                    .collect(java.util.stream.Collectors.joining("\n")));
            return "测试发言";
        });
        RouterService router = newRouterWithLlm(llm, "free", "", List.of("A", "B"), List.of("A", "B"));
        router.setDirectorRoleGuidance(Map.of("A", "你知道一封信藏在钟楼地下室。", "陌生人", "不应进入"));

        // 直接对话轨道每轮只调度一个可回复角色；分别定向两轮，验证私密信息不串角色。
        router.runRoundTargeted(null, null, null, null, List.of("A"));
        router.runRoundTargeted(null, null, null, null, List.of("B"));

        assertTrue(captured.stream().anyMatch(ctx -> ctx.contains("你是 A") && ctx.contains("钟楼地下室")),
                "A 的私密信息未进入上下文: " + captured);
        assertTrue(captured.stream().anyMatch(ctx -> ctx.contains("你是 B") && !ctx.contains("钟楼地下室")),
                "B 的上下文错误泄露或未生成: " + captured);
    }

    @Test
    @DisplayName("⑤c 加载新会话或移除重加角色，不得遗留旧角色私密信息")
    void directorRoleGuidance_isClearedAcrossSessionLoadAndRoleReplacement() {
        List<String> captured = new ArrayList<>();
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked") List<Message> msgs = inv.getArgument(0);
            captured.add(msgs.stream().map(m -> String.valueOf(m.getContent()))
                    .collect(java.util.stream.Collectors.joining("\n")));
            return "测试发言";
        });
        RouterService router = newRouterWithLlm(llm, "free", "", List.of("A", "B"), List.of("A", "B"));
        router.setDirectorRoleGuidance(Map.of("A", "旧会话机密：钟楼地下室。"));

        Session loaded = new Session("loaded-director-session", List.of("A", "B"));
        loaded.setCurrentScene("新场景");
        loaded.getConfig().put("mode", "free");
        router.loadSession(loaded, List.of(
                new Agent(new Persona("A", "你是新角色 A。"), "agent", llm),
                new Agent(new Persona("B", "你是新角色 B。"), "agent", llm)));
        router.runRound(null, null);
        assertTrue(captured.stream().noneMatch(ctx -> ctx.contains("旧会话机密")), "加载会话后不应串入旧私密信息");

        captured.clear();
        router.setDirectorRoleGuidance(Map.of("A", "已离场角色的私密信息。"));
        router.removeAgent("A");
        router.addAgent("A", new Persona("A", "你是重建后的角色 A。"));
        router.runRound(null, null);
        assertTrue(captured.stream().noneMatch(ctx -> ctx.contains("已离场角色的私密信息")), "同名重加角色不得继承已删除角色信息");
    }

    // ── ⑥ 用户导演指令一次消费（P0 主控即时生效） ──

    @Test
    @DisplayName("⑥ setUserDirective → 下一轮 Agent 上下文可见且仅消费一次；再下一轮不再出现")
    void userDirective_consumedOnceByNextRound() {
        List<String> captured = new ArrayList<>();
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Message> msgs = inv.getArgument(0);
            String ctx = msgs.stream()
                    .map(m -> String.valueOf(m.getContent()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            captured.add(ctx);
            return "测试发言";
        });
        RouterService router = newRouterWithLlm(llm, "free", "", List.of("A", "B"), List.of("A", "B"));
        router.setUserDirective("玩家对主控的最新要求：下一句离开咖啡店");

        RouterService.RoundResult r1 = router.runRound(null, null); // 第 1 轮
        assertFalse(r1.status.startsWith("error"), "round should not error: " + r1.status);
        assertFalse(captured.isEmpty(), "应有 LLM 调用");
        int afterFirst = captured.size();
        for (String ctx : captured) {
            assertTrue(ctx.contains("【玩家导演要求】"), "下一轮上下文应含用户指令块");
            assertTrue(ctx.contains("离开咖啡店"), "下一轮上下文应含指令内容");
        }

        RouterService.RoundResult r2 = router.runRound(null, null); // 第 2 轮（无新指令）
        assertFalse(r2.status.startsWith("error"), "round should not error: " + r2.status);
        List<String> round2 = new ArrayList<>(captured.subList(afterFirst, captured.size()));
        assertFalse(round2.isEmpty(), "第 2 轮应有 LLM 调用");
        for (String ctx : round2) {
            assertFalse(ctx.contains("离开咖啡店"), "一次消费后下轮不得残留用户指令");
        }
        // 指令不入史
        assertTrue(memory.getSession().getMessages().stream()
                .noneMatch(m -> String.valueOf(m.getContent()).contains("离开咖啡店")),
                "用户指令原文不应入史");
    }

    // ── ⑦ POST /api/goals 接线：目标写入 + 指令即时进入 Router ──

    @Test
    @DisplayName("⑦ POST /api/goals → setGoals + setUserDirective（下轮 runRound 即见，不滞后）")
    void setGoals_wiresUserDirective() {
        RouterService sessionRouter = mock(RouterService.class);
        com.roleplay.engine.service.SessionRegistry sessions =
                mock(com.roleplay.engine.service.SessionRegistry.class);
        when(sessions.require("s1")).thenReturn(sessionRouter);
        com.roleplay.engine.controller.SessionController ctrl =
                new com.roleplay.engine.controller.SessionController(
                        mock(RouterService.class),
                        mock(com.roleplay.engine.service.ScriptService.class),
                        mock(com.roleplay.engine.service.PrivateChatService.class),
                        mock(com.roleplay.engine.controller.CharacterController.class),
                        mock(com.roleplay.engine.controller.SceneController.class),
                        mock(com.roleplay.engine.interrupt.InterruptManager.class),
                        sessions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", "s1");
        body.put("goals", List.of("下一句离开咖啡店"));
        ctrl.setGoals(body);

        verify(sessionRouter).setGoals(List.of("下一句离开咖啡店"));
        ArgumentCaptor<String> dirCap = ArgumentCaptor.forClass(String.class);
        verify(sessionRouter).setUserDirective(dirCap.capture());
        assertTrue(dirCap.getValue().contains("离开咖啡店"), "指令应即时进入 Router: " + dirCap.getValue());

        // 空目标=清除待消费指令
        Map<String, Object> clearBody = new LinkedHashMap<>();
        clearBody.put("session_id", "s1");
        clearBody.put("goals", List.of());
        ctrl.setGoals(clearBody);
        verify(sessionRouter).setUserDirective("");
    }
}
