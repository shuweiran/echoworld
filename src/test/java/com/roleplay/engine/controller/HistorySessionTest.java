package com.roleplay.engine.controller;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import com.roleplay.engine.service.Compressor;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.MemoryStore;
import com.roleplay.engine.service.Monitor;
import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.PrivateChatService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SceneGoalService;
import com.roleplay.engine.service.ScriptService;
import com.roleplay.engine.service.SessionRegistry;
import com.roleplay.engine.service.TrackRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * P-0810-21：GET /api/history 支持 session_id 参数 + POST /api/init 玩家角色回传。
 *
 * <p>背景（主会话已实证）：一般模式 protagonist 起局 → POST /api/send 正常（round 0→2、
 * message_count 0→5），但 GET /api/history?session_id=XXX 仍返回 0 条——getHistory 读默认
 * 单例 router，session_id 参数无效 → Gal 历史抽屉查空；POST /api/init 响应只有
 * {session_id,status,agents,goals}，无玩家角色回传字段（your_role/players/player_name 均 null）
 * → 前端无法确认玩家角色注入。
 *
 * <p>验证：①history 带 session_id → 返回该会话真实消息（含 round_logs）；无 session_id /
 * 未知 session_id → 默认单例行为不变（空消息）；②多会话消息隔离（s1 与 s2 互不串）；
 * ③init mode=protagonist → 回传 your_role/player_name/protagonist（与 state 一致）；
 * ④init 非主角模式 → 不回传 your_role/player_name，protagonist 附加键零破坏。
 */
class HistorySessionTest {

    private static final String SCENE = "夜晚的咖啡馆，小铃与凯尔相对而坐。";

    // ── Harness（同 RouterServiceRoundHistoryTest / RouterServiceAutoFirstRoundTest 模式） ──

    /** 构建真实 RouterService：mock LLM/Arbiter，单条 MERGED 轨道；sse=null（runRound null 守卫）。 */
    private static RouterService newRouter(LLMClient llm, ArbiterService arbiter,
                                    InterruptManager im, AgentExecutor executor,
                                    PlayerIdentityService identity) {
        return new RouterService(
                arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, im, new WorldEventBus(), null, identity);
    }

    /** mock LLM（固定发言）+ mock Arbiter（单 MERGED 轨道）——所有 router 共享同一批无状态 mock。 */
    private static LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());
        return llm;
    }

    private static ArbiterService mockArbiter() {
        ArbiterService arbiter = mock(ArbiterService.class);
        // 按调用实际传入的 agentNames 构建单 MERGED 轨道（每会话 router 只含自己的角色 →
        // 消息天然隔离，避免共享 mock 把两局角色全塞进同一轨道）
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> names = inv.getArgument(1);
                    Map<String, Object> track = new LinkedHashMap<>();
                    track.put("id", "main");
                    track.put("mode", "merged");
                    track.put("label", "主线");
                    track.put("agents", new ArrayList<>(names));
                    Map<String, String> actions = new LinkedHashMap<>();
                    for (String n : names) actions.put(n, "active");
                    track.put("agent_actions", actions);
                    return new TrackConfigResult(List.of(track), "test");
                });
        when(arbiter.integrateOutputs(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(Map.of("narration", "整合旁白"));
        return arbiter;
    }

    private static class Harness {
        final LLMClient llm;
        final ArbiterService arbiter;
        final InterruptManager im;
        final AgentExecutor executor;
        final PlayerIdentityService identity;
        final RouterService defaultRouter;
        final SessionRegistry registry;

        Harness() {
            llm = mockLlm();
            arbiter = mockArbiter();
            im = new InterruptManager(new WorldEventBus());
            executor = new AgentExecutor(im, new AgentTaskManager(im));
            identity = new PlayerIdentityService(mock(CharacterRepository.class));
            defaultRouter = newRouter(llm, arbiter, im, executor, identity);
            registry = new SessionRegistry(defaultRouter, arbiter, executor,
                    mock(Compressor.class), mock(Monitor.class), mock(GeneratorService.class),
                    mock(TrackRequestService.class), llm, null, null, im,
                    new WorldEventBus(), null, identity, mock(SceneGoalService.class));
        }

        HistoryController newHistoryController() {
            return new HistoryController(defaultRouter, llm, mock(CharacterController.class), registry);
        }

        RouterService newSession(String sessionId, String agentName) {
            RouterService r = registry.getOrCreate(sessionId);
            r.initSession(sessionId, List.of(new Persona(agentName, "你是一个角色。")), SCENE, "free", "", "");
            return r;
        }
    }

    // ── ① history session_id 定向 ──────────────────────────────

    @Test
    @DisplayName("① GET /api/history?session_id= 返回该会话真实消息；空 id 走默认会话，未知非空 id 返回 404")
    void history_withSessionId_returnsSessionMessages() {
        Harness h = new Harness();
        RouterService s1 = h.newSession("s1", "小铃");
        assertFalse(s1.runRound(null, null).status.startsWith("error"));

        HistoryController ctrl = h.newHistoryController();

        // 带 session_id=s1 → 有消息 + round_logs 键
        Map<String, Object> res = ctrl.getHistory(100, 0, "", 0, "", "s1").getBody();
        assertFalse(((List<?>) res.get("messages")).isEmpty(),
                "session_id 定向应返回该会话消息");
        assertTrue(res.containsKey("round_logs"), "响应应含 round_logs 键（契约对齐）");

        // 无 session_id → 默认单例（s1 未初始化到默认单例上）→ 空消息（旧行为不变）
        Map<String, Object> resDefault = ctrl.getHistory(100, 0, "", 0, "", "").getBody();
        assertTrue(((List<?>) resDefault.get("messages")).isEmpty(),
                "无 session_id 走默认单例，应保持 0 条（向后兼容）");

        // 未知非空 session_id 必须明确 404，不能静默读取默认会话造成串线。
        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> ctrl.getHistory(100, 0, "", 0, "", "no-such-session"));
        assertEquals(404, unknown.getStatusCode().value());
    }

    // ── ② 多会话消息隔离 ──────────────────────────────────────

    @Test
    @DisplayName("② 多会话隔离：s1/s2 各自返回自己的消息，互不串")
    void history_sessionsAreIsolated() {
        Harness h = new Harness();
        RouterService s1 = h.newSession("s1", "小铃");
        assertFalse(s1.runRound(null, null).status.startsWith("error"));
        RouterService s2 = h.newSession("s2", "凯尔");
        assertFalse(s2.runRound(null, null).status.startsWith("error"));

        HistoryController ctrl = h.newHistoryController();

        List<?> msgs1 = (List<?>) ctrl.getHistory(100, 0, "", 0, "", "s1").getBody().get("messages");
        List<?> msgs2 = (List<?>) ctrl.getHistory(100, 0, "", 0, "", "s2").getBody().get("messages");
        assertFalse(msgs1.isEmpty());
        assertFalse(msgs2.isEmpty());

        // s1 消息只含小铃、s2 消息只含凯尔（agent 消息 name 字段精确隔离）
        assertTrue(msgs1.stream().anyMatch(m -> "小铃".equals(((Map<?, ?>) m).get("name"))),
                "s1 消息应含小铃");
        assertTrue(msgs2.stream().noneMatch(m -> "小铃".equals(((Map<?, ?>) m).get("name"))),
                "s2 消息不得含小铃（跨会话串扰）");
        assertTrue(msgs2.stream().anyMatch(m -> "凯尔".equals(((Map<?, ?>) m).get("name"))),
                "s2 消息应含凯尔");
    }

    // ── ③ init mode=protagonist 玩家角色回传 ──────────────────

    @Test
    @DisplayName("③ POST /api/init mode=protagonist → 回传 your_role/player_name/protagonist（与 state 一致）")
    void sessionInit_protagonistMode_returnsPlayerRole() {
        RouterService sessionRouter = mock(RouterService.class);
        when(sessionRouter.getState()).thenReturn(Map.of("protagonist", "小铃", "round", 0, "message_count", 0));
        when(sessionRouter.getSceneGoalsView()).thenReturn(Map.of("enabled", false));
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.getOrCreate(anyString())).thenReturn(sessionRouter);

        SessionController ctrl = new SessionController(mock(RouterService.class),
                mock(ScriptService.class), mock(PrivateChatService.class),
                mock(CharacterController.class), mock(SceneController.class),
                mock(InterruptManager.class), sessions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("characters", List.of(Map.of("name", "小铃", "persona", "温柔的女仆")));
        body.put("scene", "夜晚的咖啡馆");
        body.put("mode", "protagonist");
        body.put("protagonist", "小铃");

        ResponseEntity<Map<String, Object>> resp = ctrl.initialize(body);
        Map<String, Object> b = resp.getBody();
        assertNotNull(b);
        assertEquals("initialized", b.get("status"));
        assertEquals("小铃", b.get("your_role"), "主角模式应回传 your_role");
        assertEquals("小铃", b.get("player_name"), "主角模式应回传 player_name");
        assertEquals("小铃", b.get("protagonist"), "protagonist 与 state 一致");

        // initSession 收到 protagonist（玩家角色注入后端）
        verify(sessionRouter).initSession(anyString(), anyList(), anyString(),
                eq("protagonist"), eq("小铃"), anyString());
    }

    // ── ④ init 非主角模式 ─────────────────────────────────────

    @Test
    @DisplayName("④ POST /api/init 非主角模式 → 不回传 your_role/player_name；protagonist 附加键零破坏")
    void sessionInit_nonProtagonist_noYourRole() {
        RouterService sessionRouter = mock(RouterService.class);
        when(sessionRouter.getState()).thenReturn(Map.of("protagonist", "", "round", 0, "message_count", 0));
        when(sessionRouter.getSceneGoalsView()).thenReturn(Map.of("enabled", false));
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.getOrCreate(anyString())).thenReturn(sessionRouter);

        SessionController ctrl = new SessionController(mock(RouterService.class),
                mock(ScriptService.class), mock(PrivateChatService.class),
                mock(CharacterController.class), mock(SceneController.class),
                mock(InterruptManager.class), sessions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("characters", List.of(Map.of("name", "小铃", "persona", "温柔的女仆")));
        body.put("scene", "夜晚的咖啡馆");
        body.put("mode", "free");

        ResponseEntity<Map<String, Object>> resp = ctrl.initialize(body);
        Map<String, Object> b = resp.getBody();
        assertNotNull(b);
        assertNull(b.get("your_role"), "free 模式不应回传 your_role");
        assertNull(b.get("player_name"), "free 模式不应回传 player_name");
        assertEquals("", b.get("protagonist"), "protagonist 附加键存在且为空（零破坏）");
    }

    // ── ⑤ init 空 characters → 400（P-0810-21-C：移除「助手」兜底） ─────────────────

    @Test
    @DisplayName("⑤ POST /api/init 空 characters → 400「至少需要一个角色」（不再兜底助手）")
    void sessionInit_emptyCharacters_returns400() {
        RouterService sessionRouter = mock(RouterService.class);
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.getOrCreate(anyString())).thenReturn(sessionRouter);

        SessionController ctrl = new SessionController(mock(RouterService.class),
                mock(ScriptService.class), mock(PrivateChatService.class),
                mock(CharacterController.class), mock(SceneController.class),
                mock(InterruptManager.class), sessions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("characters", List.of()); // 空角色（用户实测「没选助手却出现助手」根因路径）
        body.put("scene", "默认场景");
        body.put("mode", "free");

        ResponseEntity<Map<String, Object>> resp = ctrl.initialize(body);
        assertEquals(400, resp.getStatusCode().value(), "空 characters 应返回 400");
        assertEquals("至少需要一个角色", resp.getBody().get("error"), "错误信息与 startScene 一致");
        // 未触发 initSession / 自动首轮（400 短路）
        verify(sessionRouter, org.mockito.Mockito.never()).initSession(anyString(), anyList(), anyString(), anyString(), anyString(), anyString());
        verify(sessionRouter, org.mockito.Mockito.never()).triggerAutoFirstRound();
    }

    @Test
    @DisplayName("⑥ POST /api/session/close 显式释放会话；缺 id=400、未知 id=404")
    void closeSession_hasExplicitLifecycleSemantics() {
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.remove("live-session")).thenReturn(true);
        SessionController ctrl = new SessionController(mock(RouterService.class),
                mock(ScriptService.class), mock(PrivateChatService.class),
                mock(CharacterController.class), mock(SceneController.class),
                mock(InterruptManager.class), sessions);

        assertEquals(400, ctrl.closeSession(Map.of()).getStatusCode().value());
        assertEquals(404, ctrl.closeSession(Map.of("session_id", "missing")).getStatusCode().value());
        ResponseEntity<Map<String, Object>> closed = ctrl.closeSession(Map.of("session_id", "live-session"));
        assertEquals(200, closed.getStatusCode().value());
        assertEquals("closed", closed.getBody().get("status"));
        verify(sessions).remove("live-session");
    }
}
