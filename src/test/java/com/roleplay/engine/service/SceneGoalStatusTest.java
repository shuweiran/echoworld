package com.roleplay.engine.service;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import com.roleplay.engine.service.ArbiterService.UserInputCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0810-09：目标判定状态机（未开始→完成）+ scene_target_update SSE 广播 + Agent 隐藏目标注入。
 *
 * <p>直接构造 RouterService（mock LLM/Arbiter，RouterRenameTest 同款 harness），走并行路径：
 * 一轮对话后异步判定（mock LLM callJson 返回状态机结果），断言：
 * ① 角色目标状态 NOT_STARTED→COMPLETED 落回目标集；② scene_target_update 事件广播
 * （role_goal_status + revealed 揭示全文）；③ 状态无变化不广播（静默）；④ Agent.buildContext
 * 系统提示注入「隐藏目标」且不暴露给玩家。
 */
class SceneGoalStatusTest {

    private static final String SESSION_ID = "scene-goal-status-test";
    private static final String SCENE = "夜晚的庄园，管家与女仆在客厅。";

    /** 捕获 scene_target_update 的 SSE 假实现（定向广播重载）。 */
    static class CaptureSSE extends SSEController {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<Map<String, Object>> events = new ArrayList<>();

        @Override
        public void broadcastToSession(String sessionId, String eventType, Object data) {
            if ("scene_target_update".equals(eventType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) data;
                events.add(m);
                latch.countDown();
            }
            // 其余定向事件（script_*/werewolf_*）测试无消费，忽略
        }
    }

    private Map<String, Object> goalsFixture() {
        Map<String, Object> goals = new LinkedHashMap<>();
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("desc", "庄园隐藏的真相逐渐浮出水面");
        global.put("status", "NOT_STARTED");
        goals.put("global_goal", global);
        Map<String, Object> roles = new LinkedHashMap<>();
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("desc", "小铃要找到丢失的怀表");
        r1.put("status", "NOT_STARTED");
        roles.put("小铃", r1);
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("desc", "凯尔要保守自己的秘密");
        r2.put("status", "NOT_STARTED");
        roles.put("凯尔", r2);
        goals.put("role_goals", roles);
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("desc", "查明怀表的去向");
        player.put("status", "NOT_STARTED");
        goals.put("player_goal", player);
        return goals;
    }

    /**
     * 构建 RouterService（同 RouterRenameTest harness）：
     * - LLM mock：callSync → "AI回应"（对话生成）；callJson → judgeJson（目标判定）
     * - Arbiter mock：configureTracks 单条 MERGED 轨道 / processUserInput 固定旁白
     * - 注入 SceneGoalService + 目标集 + CaptureSSE
     */
    private RouterService newRouter(Map<String, Object> judgeJson, CaptureSSE sse,
                                    Map<String, Object> goals) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        when(llm.callJson(anyString(), anyInt())).thenReturn(judgeJson);

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
        when(arbiter.classifyUserInput(anyString(), anyString(), anyList()))
                .thenReturn(UserInputCategory.SUPPLEMENT);
        when(arbiter.processUserInput(anyString(), any(), anyString(), anyList(), anyList()))
                .thenReturn("主控旁白文本");

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
                SCENE, "free", "", "");
        router.setSceneGoalService(new SceneGoalService(llm, null));
        router.setSceneGoals(goals);
        return router;
    }

    // ── ① 状态机：未开始 → 完成（含 SSE 广播 + 揭示全文） ──

    @Test
    @DisplayName("① 判定状态机：角色目标 NOT_STARTED→COMPLETED → 落回目标集 + scene_target_update 广播 + revealed 揭示全文")
    void goalStateMachine_notStartedToCompleted_broadcasts() throws Exception {
        Map<String, Object> judgeJson = new LinkedHashMap<>();
        judgeJson.put("role_goals", Map.of("小铃", "COMPLETED", "凯尔", "IN_PROGRESS"));
        judgeJson.put("global_goal", "IN_PROGRESS");
        judgeJson.put("player_goal", "NOT_STARTED");

        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(judgeJson, sse, goalsFixture());

        RouterService.RoundResult result = router.runRound("小铃在书房翻找", null, null);
        assertFalse(result.status.startsWith("error"), "round should complete: " + result.status);

        assertTrue(sse.latch.await(5, TimeUnit.SECONDS), "判定后应收到 scene_target_update 事件");

        // ① 目标集状态更新落回
        Map<?, ?> roles = (Map<?, ?>) router.getSceneGoalsRaw().get("role_goals");
        assertEquals("COMPLETED", String.valueOf(((Map<?, ?>) roles.get("小铃")).get("status")),
                "小铃 未开始→完成");
        assertEquals("IN_PROGRESS", String.valueOf(((Map<?, ?>) roles.get("凯尔")).get("status")),
                "凯尔 未开始→进行中");
        assertEquals("IN_PROGRESS", String.valueOf(((Map<?, ?>) router.getSceneGoalsRaw().get("global_goal")).get("status")));

        // ② SSE 事件结构（契约：session_id / role_goal_status / global_goal_status / player_goal_status / revealed）
        assertEquals(1, sse.events.size());
        Map<String, Object> event = sse.events.get(0);
        assertEquals(SESSION_ID, event.get("session_id"));
        Map<?, ?> status = (Map<?, ?>) event.get("role_goal_status");
        assertEquals("COMPLETED", String.valueOf(status.get("小铃")));
        assertEquals("IN_PROGRESS", String.valueOf(status.get("凯尔")));
        assertEquals("IN_PROGRESS", event.get("global_goal_status"));
        assertEquals("NOT_STARTED", event.get("player_goal_status"));
        // ③ revealed：完成/失败的目标揭示全文（进行中的不揭示）
        List<?> revealed = (List<?>) event.get("revealed");
        assertEquals(1, revealed.size(), "仅完成/失败目标揭示全文");
        assertEquals("小铃要找到丢失的怀表", String.valueOf(revealed.get(0)));
    }

    // ── ② 状态无变化 → 不广播（静默） ──

    @Test
    @DisplayName("② 判定状态无变化 → 不广播 scene_target_update（静默降级）")
    void goalStateMachine_noChange_noBroadcast() throws Exception {
        Map<String, Object> judgeJson = new LinkedHashMap<>();
        judgeJson.put("role_goals", Map.of("小铃", "NOT_STARTED", "凯尔", "NOT_STARTED"));
        judgeJson.put("global_goal", "NOT_STARTED");
        judgeJson.put("player_goal", "NOT_STARTED");

        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(judgeJson, sse, goalsFixture());

        RouterService.RoundResult result = router.runRound("大家随意聊聊", null, null);
        assertFalse(result.status.startsWith("error"));

        assertFalse(sse.latch.await(800, TimeUnit.MILLISECONDS),
                "状态无变化不应广播事件");
        assertEquals(0, sse.events.size());
        // 目标集状态保持未开始
        Map<?, ?> roles = (Map<?, ?>) router.getSceneGoalsRaw().get("role_goals");
        assertEquals("NOT_STARTED", String.valueOf(((Map<?, ?>) roles.get("小铃")).get("status")));
    }

    // ── ③ Agent 隐藏目标注入（buildContext 系统提示） ──

    @Test
    @DisplayName("③ Agent 隐藏目标注入：系统提示含「你的目标：xxx（不要主动暴露给玩家）」")
    void agentHiddenGoal_injectedIntoSystemPrompt() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList())).thenReturn("回复");

        Agent agent = new Agent(new Persona("小铃", "温柔的女仆"), "agent", llm);
        agent.setHiddenGoal("小铃要找到丢失的怀表");

        agent.generateWithContext("你好");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> cap = ArgumentCaptor.forClass(List.class);
        verify(llm).callSync(cap.capture());
        List<Message> messages = cap.getValue();
        Message sys = messages.get(0);
        assertEquals(Message.Role.SYSTEM, sys.getRole());
        assertTrue(sys.getContent().contains("【隐藏目标】"), "系统提示应含隐藏目标块");
        assertTrue(sys.getContent().contains("你的目标：小铃要找到丢失的怀表"));
        assertTrue(sys.getContent().contains("不要主动暴露给玩家"), "应含不暴露纪律");

        // 未设置隐藏目标 → 无隐藏目标块（向后兼容零变化）
        Agent plain = new Agent(new Persona("凯尔", "沉默的管家"), "agent", llm);
        when(llm.callSync(anyList())).thenReturn("回复2");
        plain.generateWithContext("你好");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> cap2 = ArgumentCaptor.forClass(List.class);
        verify(llm, org.mockito.Mockito.times(2)).callSync(cap2.capture());
        List<Message> second = cap2.getAllValues().get(1);
        assertFalse(second.get(0).getContent().contains("【隐藏目标】"), "无隐藏目标时零变化");
    }
}
