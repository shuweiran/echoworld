package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0815-C：一般模式双人场景取消 next_round 预测（跳过主控整合 LLM）。
 *
 * <p>验证：
 * ① 一般模式双人（2 active AI）→ integrateOutputs 不被调用；
 * ② 一般模式双人（AI + 玩家角色，protagonist 模式）→ integrateOutputs 不被调用；
 * ③ 一般模式多人（3 active）→ integrateOutputs 仍被调用（预测闭环零变化）；
 * ④ 狼人杀模式（2 active）→ integrateOutputs 仍被调用（GM 推进必须保留）；
 * ⑤ 双人场景跳过 → pendingNextRound 清空（下轮 configureTracks 收到 null 预测）。
 */
class RouterServiceDuoSkipIntegrateTest {

    private static final String SESSION_ID = "duo-skip-integrate-test";
    private static final String SCENE = "夜晚的咖啡馆。";

    /** 本测试实例的 ArbiterService mock（供 verify 断言 integrateOutputs 调用次数）。 */
    private ArbiterService arbiter;

    /**
     * 构建 RouterService：
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

        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));

        RouterService router = new RouterService(
                arbiter,
                executor,
                new MemoryStore(),
                mock(Compressor.class),
                mock(Monitor.class),
                mock(GeneratorService.class),
                mock(TrackRequestService.class),
                llm,
                null,            // historyController
                null,            // lorebookService
                interruptManager,
                new WorldEventBus(),
                null,            // sse
                null);           // identityService
        List<Persona> personas = agents.stream().map(n -> new Persona(n, "你是一个角色。")).toList();
        router.initSession(SESSION_ID, personas, SCENE, mode, protagonist, "");
        return router;
    }

    // ── ① 一般模式双人（2 active AI）→ 不调用 integrateOutputs ──

    @Test
    @DisplayName("① 一般模式双人（2 active AI）→ integrateOutputs 不被调用")
    void duoFree_skipsIntegrateOutputs() {
        RouterService router = newRouter("free", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size(), "双 AI 角色均应发言");
        verify(arbiter, never()).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
    }

    // ── ② 一般模式双人（AI + 玩家角色，protagonist 模式）→ 不调用 ──

    @Test
    @DisplayName("② 一般模式双人（AI + 玩家角色，protagonist 模式）→ integrateOutputs 不被调用")
    void duoProtagonist_skipsIntegrateOutputs() {
        // 轨道里玩家角色 P 被强制 active，但参与 LLM 生成的 active 只有 1 个 AI → 双人场景
        RouterService router = newRouter("protagonist", "P", List.of("A", "P"), List.of("A", "P"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(1, result.agentOutputs.size(), "仅 AI 角色发言（玩家角色被排除生成）");
        verify(arbiter, never()).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
    }

    // ── ③ 一般模式多人（3 active）→ 仍调用（预测闭环零变化）──

    @Test
    @DisplayName("③ 一般模式多人（3 active）→ integrateOutputs 仍被调用")
    void multiFree_stillCallsIntegrateOutputs() {
        RouterService router = newRouter("free", "", List.of("A", "B", "C"), List.of("A", "B", "C"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(3, result.agentOutputs.size());
        verify(arbiter, times(1)).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
    }

    // ── ④ 狼人杀模式（2 active）→ 仍调用（GM 推进必须保留）──

    @Test
    @DisplayName("④ 狼人杀模式（2 active）→ integrateOutputs 仍被调用（GM 推进保留）")
    void werewolf_stillCallsIntegrateOutputs() {
        RouterService router = newRouter("werewolf", "", List.of("A", "B"), List.of("A", "B"));

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size());
        verify(arbiter, times(1)).integrateOutputs(anyString(), anyList(), anyList(), anyBoolean());
    }

    // ── ⑤ 双人跳过 → pendingNextRound 清空（下轮 configureTracks 收 null 预测）──

    @Test
    @DisplayName("⑤ 双人场景跳过整合 → 下轮 configureTracks 收到 null 预测（pendingNextRound 清空）")
    void duoSkip_clearsPendingNextRound() {
        RouterService router = newRouter("free", "", List.of("A", "B"), List.of("A", "B"));

        router.runRound(null, null);
        router.runRound(null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> predictionCaptor = ArgumentCaptor.forClass(Map.class);
        verify(arbiter, times(2)).configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), predictionCaptor.capture());
        assertNull(predictionCaptor.getAllValues().get(1),
                "双人场景跳过整合后，下一轮 configureTracks 不应收到上轮预测");
    }
}
