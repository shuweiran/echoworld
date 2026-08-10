package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.controller.SceneController;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.controller.SessionController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0810-14：起局后自动第一轮（AI 开场白）。
 *
 * <p>直接构造 RouterService（mock LLM/Arbiter，SceneGoalStatusTest 同款 harness），验证：
 * ① 一般模式 triggerAutoFirstRound → 后台自动完成一轮：round=1、agent 消息入史、
 *    SSE 事件序列 round_start → arbiter_task → agent_output ×N → arbiter_integrate → round_complete；
 * ② 非一般模式（werewolf/script）不触发；
 * ③ 重复触发幂等（仅一轮）；
 * ④ 异步语义（trigger 立即返回，不阻塞调用方）；
 * ⑤ 接线：POST /api/init 与 POST /api/scenes/{id}/start 起局后调用 triggerAutoFirstRound。
 */
class RouterServiceAutoFirstRoundTest {

    private static final String SESSION_ID = "auto-first-round-test";
    private static final String SCENE = "夜晚的咖啡馆，小铃与凯尔相对而坐。";

    /** 捕获全局广播事件（round_start/agent_output/arbiter_integrate/round_complete 等）。 */
    static class CaptureSSE extends SSEController {
        final CountDownLatch roundComplete = new CountDownLatch(1);
        final List<String> eventTypes = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            eventTypes.add(eventType);
            payloads.add(data);
            if ("round_complete".equals(eventType)) {
                roundComplete.countDown();
            }
        }
    }

    /** 构建一般模式 RouterService（mock LLM/Arbiter，同 SceneGoalStatusTest harness）。 */
    private RouterService newRouter(CaptureSSE sse, String mode) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());

        ArbiterService arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", new ArrayList<>(List.of("小铃", "凯尔")));
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("小铃", "active");
        actions.put("凯尔", "active");
        track.put("agent_actions", actions);
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet()))
                .thenReturn(new TrackConfigResult(List.of(track), "test"));
        when(arbiter.integrateOutputs(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(Map.of("narration", "整合旁白"));

        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));
        CharacterRepository repo = mock(CharacterRepository.class);

        RouterService router = new RouterService(
                arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, interruptManager, new WorldEventBus(), sse,
                new PlayerIdentityService(repo));
        router.initSession(SESSION_ID,
                List.of(new Persona("小铃", "温柔的女仆"), new Persona("凯尔", "沉默的管家")),
                SCENE, mode, "", "");
        return router;
    }

    // ── ① 一般模式：触发后自动完成第一轮 ──

    @Test
    @DisplayName("① 一般模式 triggerAutoFirstRound → 异步完成第一轮：round=1 + agent 消息入史 + SSE 事件序列")
    void generalMode_autoFirstRound_completesRound() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "free");

        router.triggerAutoFirstRound();

        assertTrue(sse.roundComplete.await(10, TimeUnit.SECONDS), "自动第一轮应在后台完成");
        assertEquals(1, router.getState().get("round"), "起局后自动第一轮 round=1");

        // agent 消息入史（小铃/凯尔各一条）+ 主控整合旁白 → message_count ≥ 3
        assertTrue((Integer) router.getState().get("message_count") >= 3,
                "消息应含 2 条 agent + 1 条主控整合, count=" + router.getState().get("message_count"));

        // SSE 事件序列：round_start → arbiter_task → agent_output×2 → arbiter_integrate → round_complete
        List<String> types = sse.eventTypes;
        assertTrue(types.contains("round_start"), "应推 round_start: " + types);
        assertTrue(types.contains("arbiter_task"), "应推 arbiter_task: " + types);
        long agentOutputs = types.stream().filter("agent_output"::equals).count();
        assertEquals(2, agentOutputs, "2 个 agent 各推一条 agent_output: " + types);
        assertTrue(types.contains("arbiter_integrate"), "应推 arbiter_integrate: " + types);
        assertTrue(types.contains("round_complete"), "应推 round_complete: " + types);
        // 顺序：round_start 在 agent_output 之前、round_complete 在最后
        assertTrue(types.indexOf("round_start") < types.indexOf("agent_output"));
        assertEquals("round_complete", types.get(types.size() - 1));

        // round_start 载荷 round=1
        Object roundStartPayload = payloadOf(sse, "round_start");
        assertEquals(1, ((Map<?, ?>) roundStartPayload).get("round"));
        // agent_output 载荷含 agent_name/content
        Object agentOutPayload = payloadOf(sse, "agent_output");
        Map<?, ?> first = (Map<?, ?>) agentOutPayload;
        assertTrue(first.containsKey("agent_name"));
        assertFalse(String.valueOf(first.get("content")).isBlank());
    }

    // ── ② 非一般模式不触发 ──

    @Test
    @DisplayName("② 非一般模式（werewolf/script）triggerAutoFirstRound 不触发")
    void nonGeneralMode_noAutoRound() throws Exception {
        for (String mode : new String[]{"werewolf", "script"}) {
            CaptureSSE sse = new CaptureSSE();
            RouterService router = newRouter(sse, mode);
            router.triggerAutoFirstRound();
            assertFalse(sse.roundComplete.await(400, TimeUnit.MILLISECONDS),
                    "狼人杀/剧本杀不应自动开场 (mode=" + mode + ")");
            assertEquals(0, router.getState().get("round"), "mode=" + mode + " round 应保持 0");
            assertFalse(sse.eventTypes.contains("round_start"), "mode=" + mode + " 不应推 round_start");
        }
    }

    // ── ③ 重复触发幂等 ──

    @Test
    @DisplayName("③ 重复触发只跑一轮（autoFirstRoundFired 幂等）")
    void repeatedTrigger_singleRound() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "free");

        router.triggerAutoFirstRound();
        router.triggerAutoFirstRound();
        router.triggerAutoFirstRound();

        assertTrue(sse.roundComplete.await(10, TimeUnit.SECONDS));
        assertEquals(1, router.getState().get("round"), "重复触发后仍只跑一轮");
        long roundStarts = sse.eventTypes.stream().filter("round_start"::equals).count();
        assertEquals(1, roundStarts, "round_start 只推一次");
        long agentOutputs = sse.eventTypes.stream().filter("agent_output"::equals).count();
        assertEquals(2, agentOutputs, "agent_output 只推一轮的量（2 条）");
    }

    // ── ④ 异步语义：trigger 立即返回 ──

    @Test
    @DisplayName("④ 异步：triggerAutoFirstRound 立即返回，不阻塞调用方等待整轮完成")
    void triggerIsAsync() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "free");

        long t0 = System.nanoTime();
        router.triggerAutoFirstRound();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 2_000, "trigger 应毫秒级返回（不等待整轮 LLM/生成）, elapsed=" + elapsedMs + "ms");
        // 整轮在后台线程完成
        assertTrue(sse.roundComplete.await(10, TimeUnit.SECONDS));
    }

    // ── ⑤ 接线：/api/init 与 /api/scenes/{id}/start 起局后触发 ──

    @Test
    @DisplayName("⑤ POST /api/init（一般模式）起局后调用 triggerAutoFirstRound")
    void sessionInit_wiresAutoFirstRound() {
        RouterService sessionRouter = mock(RouterService.class);
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.getOrCreate(anyString())).thenReturn(sessionRouter);
        RouterService defaultRouter = mock(RouterService.class);
        CharacterController cc = mock(CharacterController.class);

        SessionController ctrl = new SessionController(defaultRouter, mock(ScriptService.class),
                mock(PrivateChatService.class), cc, mock(SceneController.class),
                mock(InterruptManager.class), sessions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("characters", List.of(Map.of("name", "小铃", "persona", "温柔的女仆")));
        body.put("scene", "夜晚的咖啡馆");
        body.put("mode", "free");
        ctrl.initialize(body);

        verify(sessionRouter).initSession(anyString(), anyList(), anyString(), eq("free"), anyString(), anyString());
        verify(sessionRouter).triggerAutoFirstRound();
    }

    @Test
    @DisplayName("⑥ POST /api/scenes/{id}/start 起局后调用 triggerAutoFirstRound")
    void startScene_wiresAutoFirstRound() {
        RouterService router = mock(RouterService.class);
        CharacterController cc = mock(CharacterController.class);
        when(cc.getAll()).thenReturn(List.of());
        SceneController ctrl = new SceneController(mock(GeneratorService.class), router, cc,
                mock(DatabaseService.class), null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", List.of("小铃"));
        ctrl.startScene("scene-1", "", "", body);

        // P-0810-25：无 me → 导演模式（AI 角色们自己对话）
        verify(router).initSession(anyString(), anyList(), anyString(), eq("director"), anyString(), anyString());
        verify(router).triggerAutoFirstRound();
    }

    @Test
    @DisplayName("⑦ 一般模式 init 后未调用 trigger 前不自动跑轮（触发是显式的）")
    void noTrigger_noRound() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "free");
        // 不调用 triggerAutoFirstRound —— 仅 init 不自动跑（调用方负责接线，测试防误触发回归）
        assertFalse(sse.roundComplete.await(400, TimeUnit.MILLISECONDS));
        assertEquals(0, router.getState().get("round"));
    }

    // ── 工具 ──

    private static Object payloadOf(CaptureSSE sse, String type) {
        for (int i = 0; i < sse.eventTypes.size(); i++) {
            if (type.equals(sse.eventTypes.get(i))) return sse.payloads.get(i);
        }
        return null;
    }
}
