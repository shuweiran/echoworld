package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.controller.RoundController;
import com.roleplay.engine.controller.SceneController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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
import static org.mockito.Mockito.when;

/**
 * P-0810-21-D：玩家发言候选话术（任务 D1）——suggestPlayerLines + POST /api/round/suggest。
 *
 * <p>验证：①LLM 成功 → 返回候选（条数钳制 2-4、空/超长过滤）；②LLM 失败 → 规则兜底
 * 恒返回 ≥2 条（零崩溃）；③count 钳制（1→2、9→4）；④RoundController.suggest 接线 200。
 */
class RouterServiceSuggestTest {

    private static final String SCENE = "夜晚的咖啡馆，小铃与凯尔相对而坐。";

    private RouterService newRouter(LLMClient llm) {
        ArbiterService arbiter = mock(ArbiterService.class);
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

        InterruptManager im = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(im, new AgentTaskManager(im));
        CharacterRepository repo = mock(CharacterRepository.class);
        return new RouterService(arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, im, new WorldEventBus(), null, new PlayerIdentityService(repo));
    }

    private void init(RouterService router, String sessionId) {
        router.initSession(sessionId, List.of(new Persona("小铃", "温柔的女仆"), new Persona("凯尔", "沉默的管家")),
                SCENE, "free", "", "");
    }

    private LLMClient mockLlm(Map<String, Object> jsonOut, boolean throwOnJson) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        if (throwOnJson) {
            when(llm.callJson(anyString(), anyInt())).thenThrow(new RuntimeException("llm down"));
        } else {
            when(llm.callJson(anyString(), anyInt())).thenReturn(jsonOut);
        }
        return llm;
    }

    // ── ① LLM 成功路径 ─────────────────────────────────────────

    @Test
    @DisplayName("① LLM 生成候选：返回 suggestions（条数钳制 + 空/超长过滤）")
    void llmSuccess_returnsSuggestions() {
        LLMClient llm = mockLlm(Map.of("suggestions", List.of("询问小铃今晚为何心不在焉", "   ", "换个话题聊聊天气", "这段文本长度超过八十个字显然不该作为玩家候选话术出现因为太长了会撑爆选项条所以过滤掉它", "看看窗外发生了什么")), false);
        RouterService router = newRouter(llm);
        init(router, "s1");

        List<String> out = router.suggestPlayerLines(3);
        assertEquals(3, out.size(), "应返回 3 条（默认 count=3）：" + out);
        assertTrue(out.get(0).startsWith("询问小铃"), "LLM 候选原样返回: " + out);
        // 空串与超长被过滤
        assertFalse(out.contains("   "), "空白候选应被过滤");
        assertFalse(out.stream().anyMatch(s -> s.length() > 80), "超长候选应被过滤");
    }

    // ── ② LLM 失败 → 规则兜底 ──────────────────────────────────

    @Test
    @DisplayName("② LLM 失败 → 规则兜底恒返回 ≥2 条（零崩溃）")
    void llmFailure_fallsBackToRules() {
        LLMClient llm = mockLlm(Map.of(), true);
        RouterService router = newRouter(llm);
        init(router, "s1");

        List<String> out = router.suggestPlayerLines(3);
        assertTrue(out.size() >= 2, "兜底至少 2 条: " + out);
        assertTrue(out.stream().anyMatch(s -> s.contains("小铃") || s.contains("凯尔")),
                "兜底候选应引用在场角色（首角色名）: " + out);
    }

    // ── ③ count 钳制 ───────────────────────────────────────────

    @Test
    @DisplayName("③ count 钳制：1→2、9→4（LLM 失败路径验证钳制下限）")
    void countClamped() {
        LLMClient llm = mockLlm(Map.of(), true);
        RouterService router = newRouter(llm);
        init(router, "s1");

        assertEquals(2, router.suggestPlayerLines(1).size(), "count=1 钳到 2");
        assertEquals(4, router.suggestPlayerLines(9).size(), "count=9 钳到 4");
    }

    // ── ④ RoundController 接线 ─────────────────────────────────

    @Test
    @DisplayName("④ POST /api/round/suggest → 200 + suggestions")
    void roundSuggest_endpointWired() {
        RouterService sessionRouter = mock(RouterService.class);
        when(sessionRouter.suggestPlayerLines(anyInt())).thenReturn(List.of("a", "b", "c"));
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.get(anyString())).thenReturn(sessionRouter);

        RoundController ctrl = new RoundController(sessions);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", "s1");
        body.put("count", 3);
        ResponseEntity<Map<String, Object>> resp = ctrl.suggest(body);
        assertNotNull(resp.getBody());
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("s1", resp.getBody().get("session_id"));
        assertEquals(List.of("a", "b", "c"), resp.getBody().get("suggestions"));
    }
}
