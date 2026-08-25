package com.roleplay.engine.controller;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import com.roleplay.engine.service.ArbiterService.UserInputCategory;
import com.roleplay.engine.service.Compressor;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.MemoryStore;
import com.roleplay.engine.service.Monitor;
import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SceneGoalService;
import com.roleplay.engine.service.SessionRegistry;
import com.roleplay.engine.service.TrackRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * P-0810-16：startScene 目标双层缺口后端修复验证。
 *
 * <p>根因（P-0810-12 走查 FAIL）：SceneController.startScene 走默认单例 router，
 * 其 sceneGoalService 恒 null（仅 SessionRegistry.createRouter 注入）→ ensureSceneGoals
 * 直接 return → 响应 goals.enabled=false。修复 = startScene 改走 SessionRegistry
 * getOrCreate 独立实例（与 /api/init P-0810-09 同构）。
 *
 * <p>用例：
 * <ol>
 *   <li>startScene 响应 goals.enabled=true + player_goal 明文 + AI ?? 占位 + ai_goal_count；
 *       会话注册进 SessionRegistry（独立实例非默认单例，hasSceneGoals=true）</li>
 *   <li>对话轮后异步判定 → scene_target_update SSE 广播（role_goal_status + revealed 揭示全文），
 *       契约与 P-0810-09 SceneGoalStatusTest 一致</li>
 * </ol>
 */
class SceneStartSceneGoalsTest {

    private static final String SCENE_ID = "scene-goal-start-test";
    private static final String SCENE_DESC = "夜晚的庄园，管家与女仆在客厅。";

    /** 捕获 scene_target_update 的 SSE 假实现（定向广播重载）。 */
    static class CaptureSSE extends SSEController {
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch goalsReadyLatch = new CountDownLatch(1);
        final List<Map<String, Object>> events = new ArrayList<>();
        final List<Map<String, Object>> goalsReadyEvents = new ArrayList<>();

        @Override
        public void broadcastToSession(String sessionId, String eventType, Object data) {
            if ("scene_target_update".equals(eventType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) data;
                events.add(m);
                latch.countDown();
            } else if ("scene_goals_ready".equals(eventType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) data;
                goalsReadyEvents.add(m);
                goalsReadyLatch.countDown();
            }
            // 其余定向事件（script_*/werewolf_*）测试无消费，忽略
        }
    }

    /** 目标生成 LLM 输出（生成 prompt 的 callJson 首个返回值）。 */
    private Map<String, Object> generationJson() {
        Map<String, Object> goals = new LinkedHashMap<>();
        goals.put("global_goal", Map.of("desc", "庄园隐藏的真相逐渐浮出水面", "status", "NOT_STARTED"));
        goals.put("role_goals", Map.of(
                "小铃", Map.of("desc", "小铃要找到丢失的怀表", "status", "NOT_STARTED"),
                "凯尔", Map.of("desc", "凯尔要保守自己的秘密", "status", "NOT_STARTED")));
        goals.put("player_goal", Map.of("desc", "查明怀表的去向", "status", "NOT_STARTED"));
        return goals;
    }

    /** 目标判定 LLM 输出（judge prompt 的 callJson 后续返回值；Mockito 末值重复生效）。 */
    private Map<String, Object> judgmentJson() {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("role_goals", Map.of("小铃", "COMPLETED", "凯尔", "IN_PROGRESS"));
        j.put("global_goal", "IN_PROGRESS");
        j.put("player_goal", "NOT_STARTED");
        return j;
    }

    /** 构建 harness：真实 RouterService（mock LLM/Arbiter，SceneGoalStatusTest 同款）+ 真 SessionRegistry + SceneController。 */
    private Harness build() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        when(llm.callJson(anyString(), anyInt())).thenReturn(generationJson(), judgmentJson());

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
                anyString(), anyList(), anyList(), anySet(), any()))
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
        CaptureSSE sse = new CaptureSSE();

        RouterService defaultRouter = new RouterService(
                arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, interruptManager, new WorldEventBus(), sse,
                new PlayerIdentityService(repo));

        DatabaseService db = mock(DatabaseService.class);
        when(db.getScene(anyString())).thenReturn(Optional.empty());
        SceneGoalService goalService = new SceneGoalService(llm, db);

        SessionRegistry registry = new SessionRegistry(defaultRouter, arbiter, executor,
                mock(Compressor.class), mock(Monitor.class), mock(GeneratorService.class),
                mock(TrackRequestService.class), llm, null, null, interruptManager,
                new WorldEventBus(), sse, new PlayerIdentityService(repo), goalService);

        SceneController ctrl = new SceneController(mock(GeneratorService.class), defaultRouter,
                mock(CharacterController.class), mock(DatabaseService.class), null, registry);
        return new Harness(ctrl, registry, defaultRouter, sse, llm);
    }

    record Harness(SceneController ctrl, SessionRegistry registry, RouterService defaultRouter, CaptureSSE sse,
                   LLMClient llm) {}

    // ── ① startScene 响应 goals.enabled=true + 会话注册独立实例 ──

    @Test
    @DisplayName("① startScene 响应 goals.enabled=true（player_goal 明文 / AI ?? + ai_goal_count）；会话注册 SessionRegistry 独立实例")
    void startScene_responseCarriesEnabledGoals_andSessionRegistered() {
        Harness h = build();

        ResponseEntity<Map<String, Object>> resp = h.ctrl().startScene(SCENE_ID, "小铃,凯尔", "", null);
        assertNotNull(resp.getBody());
        Map<String, Object> body = resp.getBody();
        String sessionId = String.valueOf(body.get("session_id"));
        assertFalse(sessionId.isBlank(), "startScene 应返回 session_id");

        // ① goals.enabled=true（P-0810-12 走查 FAIL 项：旧实现恒 false）
        @SuppressWarnings("unchecked")
        Map<String, Object> goals = (Map<String, Object>) body.get("goals");
        assertNotNull(goals, "startScene 响应应含 goals 键");
        assertEquals(Boolean.TRUE, goals.get("enabled"), "goals.enabled 应为 true");

        // 玩家目标明文
        @SuppressWarnings("unchecked")
        Map<String, Object> playerGoal = (Map<String, Object>) goals.get("player_goal");
        assertNotNull(playerGoal);
        assertEquals("与场景中的角色互动，推动故事发展，达成自己的目标", playerGoal.get("desc"),
                "起局响应先返回规则玩家目标，避免等待后台 LLM");

        // AI 目标 ?? 占位 + 数量
        assertEquals(2, ((Number) goals.get("ai_goal_count")).intValue(), "ai_goal_count=2（小铃/凯尔）");
        @SuppressWarnings("unchecked")
        Map<String, Object> roleGoals = (Map<String, Object>) goals.get("role_goals");
        assertEquals(SceneGoalService.MASK, ((Map<?, ?>) roleGoals.get("小铃")).get("desc"), "AI 角色目标 ?? 占位");
        assertEquals(SceneGoalService.MASK, ((Map<?, ?>) roleGoals.get("凯尔")).get("desc"), "AI 角色目标 ?? 占位");
        assertEquals(SceneGoalService.MASK, ((Map<?, ?>) goals.get("global_goal")).get("desc"), "全局目标 ?? 占位");

        // ② 会话注册进 SessionRegistry 独立实例（非默认单例，场景目标就绪）
        RouterService sessionRouter = h.registry().get(sessionId);
        assertNotSame(h.defaultRouter(), sessionRouter, "startScene 会话应走 SessionRegistry 独立实例");
        assertTrue(sessionRouter.hasSceneGoals(), "独立实例应已生成场景目标集");
        // P-0810-25：无 me → 导演模式（AI 角色们自己对话；旧实现硬编码 free）
        assertEquals("director", sessionRouter.getMode());
    }

    @Test
    @DisplayName("起局不等待场景目标 LLM：先返回规则目标，后台完成后推送正式目标")
    void startScene_returnsBeforeBackgroundGoalGenerationCompletes() throws Exception {
        Harness h = build();
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch allowLlm = new CountDownLatch(1);
        doAnswer(invocation -> {
            llmStarted.countDown();
            assertTrue(allowLlm.await(5, TimeUnit.SECONDS), "test must release background LLM");
            return generationJson();
        }).when(h.llm()).callJson(anyString(), anyInt());

        final ResponseEntity<Map<String, Object>>[] response = new ResponseEntity[1];
        assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> response[0] = h.ctrl().startScene(SCENE_ID, "小铃,凯尔", "", null));
        @SuppressWarnings("unchecked")
        Map<String, Object> initialGoals = (Map<String, Object>) response[0].getBody().get("goals");
        @SuppressWarnings("unchecked")
        Map<String, Object> initialPlayerGoal = (Map<String, Object>) initialGoals.get("player_goal");
        assertEquals("与场景中的角色互动，推动故事发展，达成自己的目标", initialPlayerGoal.get("desc"));
        assertTrue(llmStarted.await(1, TimeUnit.SECONDS), "LLM 应已转入后台任务");

        allowLlm.countDown();
        assertTrue(h.sse().goalsReadyLatch.await(5, TimeUnit.SECONDS), "后台目标完成应定向通知前端");
        @SuppressWarnings("unchecked")
        Map<String, Object> readyGoals = (Map<String, Object>) h.sse().goalsReadyEvents.get(0).get("goals");
        @SuppressWarnings("unchecked")
        Map<String, Object> readyPlayerGoal = (Map<String, Object>) readyGoals.get("player_goal");
        assertEquals("查明怀表的去向", readyPlayerGoal.get("desc"));
    }

    // ── ② 对话轮后 scene_target_update SSE 广播（revealed 揭示全文） ──

    @Test
    @DisplayName("② startScene 起局后对话轮 → scene_target_update 广播（role_goal_status + revealed 揭示全文）")
    void startScene_thenRound_broadcastsTargetUpdate() throws Exception {
        Harness h = build();

        ResponseEntity<Map<String, Object>> resp = h.ctrl().startScene(SCENE_ID, "小铃,凯尔", "", null);
        String sessionId = String.valueOf(resp.getBody().get("session_id"));
        RouterService sessionRouter = h.registry().get(sessionId);

        // 对话轮（触发 runRound 末尾异步目标判定；起局自动第一轮亦走同一判定链）
        RouterService.RoundResult result = sessionRouter.runRound("小铃在书房翻找", null, null);
        assertFalse(result.status.startsWith("error"), "round should complete: " + result.status);

        assertTrue(h.sse().latch.await(5, TimeUnit.SECONDS), "判定后应收到 scene_target_update 事件");

        // 契约（P-0810-09）：session_id / role_goal_status / global_goal_status / player_goal_status / revealed
        assertEquals(1, h.sse().events.size());
        Map<String, Object> event = h.sse().events.get(0);
        assertEquals(sessionId, event.get("session_id"));
        @SuppressWarnings("unchecked")
        Map<?, ?> status = (Map<?, ?>) event.get("role_goal_status");
        assertEquals("COMPLETED", String.valueOf(status.get("小铃")), "小铃 未开始→完成");
        assertEquals("IN_PROGRESS", String.valueOf(status.get("凯尔")), "凯尔 未开始→进行中");
        assertEquals("IN_PROGRESS", event.get("global_goal_status"));
        assertEquals("NOT_STARTED", event.get("player_goal_status"));
        // revealed：完成/失败的目标揭示全文（进行中的不揭示）
        assertEquals(List.of("小铃要找到丢失的怀表"), event.get("revealed"), "仅完成/失败目标揭示全文");
    }
}
