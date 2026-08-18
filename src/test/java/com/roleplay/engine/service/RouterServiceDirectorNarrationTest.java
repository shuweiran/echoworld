package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
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
 * P-0815-E：director 导演模式恢复主控整合旁白（主人 2026-08-15 拍板）。
 *
 * <p>背景：P-0811-G 删除了所有一般模式（free/protagonist/multi_track/director）的主控叙事，
 * P-0815-C 双人场景又短路了 integrateOutputs——导致导演模式完全没有旁白。本批：
 * ① 双人短路排除 director（director 无论几人恒走 integrateOutputs，旁白 + next_round 一体）；
 * ② 旁白入史/推送条件放开 director（仅 director 恢复，free/protagonist/multi_track 仍无旁白）。
 *
 * <p>验证：
 * ① director 双人（2 active AI）→ integrateOutputs 被调用 + narration 入史（Role.ARBITER "主控"）
 *    + SSE broadcastArbiterIntegrate 推送；
 * ② free 双人（2 active）→ 仍短路不调用 + 无旁白入史 + 无 SSE 推送（P-0815-C/P-0811-G 行为保持）；
 * ③ director 多人（3 active）→ 走原逻辑（integrateOutputs 被调用 + 旁白入史 + SSE 推送）；
 * ④ 狼人杀（2 active）→ 不受影响（integrateOutputs 仍被调用 + 旁白入史 + SSE 推送，GM 推进保留）。
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

    // ── ① director 双人 → integrateOutputs 被调用 + 旁白入史 + SSE 广播 ──

    @Test
    @DisplayName("① director 双人（2 active AI）→ integrateOutputs 被调用 + 旁白入史 + SSE 广播 arbiter_integrate")
    void directorDuo_restoresNarration() {
        RouterService router = newRouter("director", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size(), "双 AI 角色均应发言");
        // ①a 双人导演局不再短路：integrateOutputs 恒被调用（旁白 + next_round 一体）
        verify(arbiter, times(1)).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        // ①b narration 入史（Role.ARBITER "主控"）
        assertNarrationInMemory("整合旁白");
        // ①c SSE 广播 arbiter_integrate（{round, narration}）
        ArgumentCaptor<String> narrationCaptor = ArgumentCaptor.forClass(String.class);
        verify(sse, times(1)).broadcastArbiterIntegrate(anyInt(), narrationCaptor.capture());
        assertEquals("整合旁白", narrationCaptor.getValue(), "SSE 广播的 narration 应为整合旁白");
    }

    // ── ② free 双人 → 仍短路不调用 + 无旁白入史 + 无 SSE 推送（P-0815-C/P-0811-G 保持）──

    @Test
    @DisplayName("② free 双人（2 active AI）→ 仍短路不调用 + 无旁白入史 + 无 SSE 推送")
    void freeDuo_stillSkipsNarration() {
        RouterService router = newRouter("free", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size());
        verify(arbiter, never()).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        assertNoNarrationInMemory();
        verify(sse, never()).broadcastArbiterIntegrate(anyInt(), anyString());
    }

    // ── ③ director 多人 → 走原逻辑（integrateOutputs + 旁白入史 + SSE 推送）──

    @Test
    @DisplayName("③ director 多人（3 active）→ integrateOutputs 被调用 + 旁白入史 + SSE 广播")
    void directorMulti_restoresNarration() {
        RouterService router = newRouter("director", "", List.of("A", "B", "C"), List.of("A", "B", "C"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(3, result.agentOutputs.size());
        verify(arbiter, times(1)).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        assertNarrationInMemory("整合旁白");
        verify(sse, times(1)).broadcastArbiterIntegrate(anyInt(), anyString());
    }

    // ── ④ 狼人杀（2 active）→ 不受影响（GM 推进保留 + 旁白入史 + SSE 推送）──

    @Test
    @DisplayName("④ 狼人杀（2 active）→ integrateOutputs 仍被调用 + 旁白入史 + SSE 广播（GM 推进保留）")
    void werewolf_unchanged() {
        RouterService router = newRouter("werewolf", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size());
        verify(arbiter, times(1)).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
        assertNarrationInMemory("整合旁白");
        verify(sse, times(1)).broadcastArbiterIntegrate(anyInt(), anyString());
    }
}
