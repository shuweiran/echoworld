package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.entity.ChatMessageEntity;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.CancellationToken;
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
import java.util.function.Consumer;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 消息标识 + P1 消息持久化：
 * 后端为每条 Agent 输出生成稳定 messageId —— SSE token（message_id + event_seq递增）/
 * output 结算 / DB 落库（STREAMING→FINAL）/ 内存消息四方同源；用户发言 FINAL 即时落库。
 */
class ChatMessagePersistenceTest {

    private static final String SESSION_ID = "chat-persist-test";
    private static final String SCENE = "夜晚的咖啡馆。";

    /** 记录全部广播载荷（typed 重载最终都走 broadcast()）。 */
    static class CaptureSSE extends SSEController {
        final List<String> eventTypes = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public void broadcast(String eventType, Object data) {
            eventTypes.add(eventType);
            payloads.add(data instanceof Map ? (Map<String, Object>) data : Map.of());
        }

        List<Map<String, Object>> payloadsOf(String type) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < eventTypes.size(); i++) {
                if (eventTypes.get(i).equals(type)) out.add(payloads.get(i));
            }
            return out;
        }
    }

    private record Harness(RouterService router, CaptureSSE sse, DatabaseService db) {}

    /** 默认 LLM mock：同步固定回应，流式每角色 2 片增量后返回全文。 */
    private static LLMClient defaultLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        when(llm.callStream(anyList(), any(CancellationToken.class), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> cb = inv.getArgument(2);
            cb.accept("你");
            cb.accept("好");
            return "你好";
        });
        return llm;
    }

    private Harness newHarness() {
        return newHarnessWithLlm(defaultLlm());
    }

    private Harness newHarnessWithLlm(LLMClient llm) {

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
                .thenReturn(ArbiterService.UserInputCategory.SUPPLEMENT);
        when(arbiter.processUserInput(anyString(), any(ArbiterService.UserInputCategory.class),
                anyString(), anyList(), anyList())).thenReturn("主控旁白");

        CaptureSSE sse = new CaptureSSE();
        DatabaseService db = mock(DatabaseService.class);
        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));

        RouterService router = new RouterService(
                arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, interruptManager, new WorldEventBus(), sse,
                null);
        router.setSerialRound(true); // 串行 = 生产默认（唯一 token 流式路径）
        router.setDatabaseService(db);
        router.initSession(SESSION_ID,
                List.of(new Persona("小铃", "温柔的女仆"), new Persona("凯尔", "沉默的管家")),
                SCENE, "free", "", "");
        return new Harness(router, sse, db);
    }

    @Test
    @DisplayName("① 串行轮：token/output/内存/DB 四方 messageId 同源，event_seq 递增，STREAMING→FINAL")
    void serialRound_messageIdConsistentAcrossChannels() {
        Harness h = newHarness();

        RouterService.RoundResult result = h.router().runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size(), "两角色均应发言");

        // —— 内存消息 messageId 非空且互异 ——
        List<Message> agentMsgs = h.router().getConversationMessages().stream()
                .filter(m -> m.getRole() == Message.Role.AGENT).toList();
        assertEquals(2, agentMsgs.size());
        String midA = agentMsgs.get(0).getMessageId();
        String midB = agentMsgs.get(1).getMessageId();
        assertNotNull(midA);
        assertNotNull(midB);
        assertFalse(midA.isBlank() || midB.isBlank());
        // 场景2：同一轮两角色各一句 → messageId 互异（前端按 id 隔离，互不覆盖）
        assertFalse(midA.equals(midB), "不同消息的 messageId 必须互异");
        // —— SSE output 结算带同源 message_id ——
        List<Map<String, Object>> outputs = h.sse().payloadsOf("agent_output");
        assertEquals(2, outputs.size());
        List<String> outputMids = outputs.stream().map(p -> String.valueOf(p.get("message_id"))).toList();
        assertTrue(outputMids.contains(midA) && outputMids.contains(midB), "output 应结算对应流的 message_id");
        for (Map<String, Object> p : outputs) {
            assertEquals(SESSION_ID, p.get("session_id"), "output 应带会话定向");
        }
        // —— token 流按 message_id 隔离、event_seq 递增 ——
        List<Map<String, Object>> tokens = h.sse().payloadsOf("agent_token");
        assertFalse(tokens.isEmpty(), "串行路径应有 token 流");
        for (String mid : List.of(midA, midB)) {
            List<Integer> seqs = tokens.stream()
                    .filter(p -> mid.equals(String.valueOf(p.get("message_id"))))
                    .map(p -> ((Number) p.get("event_seq")).intValue()).toList();
            assertEquals(2, seqs.size(), "每消息应有 2 片 token");
            assertEquals(List.of(1, 2), seqs, "event_seq 应单调递增 1,2");
        }
        // —— DB：每消息先 STREAMING 后 FINAL，同源 id ——
        ArgumentCaptor<String> midCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCap = ArgumentCaptor.forClass(String.class);
        verify(h.db(), atLeast(4)).saveChatMessage(midCap.capture(), eq(SESSION_ID), eq(1),
                eq("agent"), anyString(), anyString(), statusCap.capture(), eq("main"));
        List<String> mids = midCap.getAllValues();
        List<String> statuses = statusCap.getAllValues();
        for (String mid : List.of(midA, midB)) {
            List<String> perMsg = new ArrayList<>();
            for (int i = 0; i < mids.size(); i++) {
                if (mid.equals(mids.get(i))) perMsg.add(statuses.get(i));
            }
            assertEquals(List.of(ChatMessageEntity.STATUS_STREAMING, ChatMessageEntity.STATUS_FINAL),
                    perMsg, "DB 应先 STREAMING 后 FINAL: " + mid);
        }
    }

    @Test
    @DisplayName("② 用户发言：USER 消息 FINAL 即时落库且带 messageId")
    void userMessage_persistedFinalImmediately() {
        Harness h = newHarness();

        RouterService.RoundResult result = h.router().runRound("玩家说你好", null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        List<Message> userMsgs = h.router().getConversationMessages().stream()
                .filter(m -> m.getRole() == Message.Role.USER).toList();
        assertFalse(userMsgs.isEmpty(), "应有 USER 消息入史");
        assertFalse(userMsgs.get(0).getMessageId().isBlank(), "USER 消息应有 messageId");
        verify(h.db(), atLeast(1)).saveChatMessage(eq(userMsgs.get(0).getMessageId()), eq(SESSION_ID), eq(1),
                eq("user"), anyString(), anyString(), eq(ChatMessageEntity.STATUS_FINAL), eq("main"));
    }

    @Test
    @DisplayName("③ toMap/fromMap 携带 message_id（历史补拉 SSE 对齐用）")
    void message_toMapCarriesMessageId() {
        Message m = new Message(Message.Role.AGENT, "小铃", "你好");
        m.setMessageId("M123");
        assertEquals("M123", m.toMap().get("message_id"));
        Message back = Message.fromMap(m.toMap());
        assertEquals("M123", back.getMessageId());
        // 旧数据无该键 → 自动生成可用 ID（非空）
        Message legacy = Message.fromMap(Map.of("role", "agent", "name", "A", "content", "hi"));
        assertNotNull(legacy.getMessageId());
        assertFalse(legacy.getMessageId().isBlank());
    }

    @Test
    @DisplayName("④ 场景3：token 中途故障 → agent_error + DB FAILED，不覆盖他句、不入史占位")
    void streamFailure_marksFailedWithoutOverwrite() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callStream(anyList(), any(CancellationToken.class), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> cb = inv.getArgument(2);
            cb.accept("半句话");
            throw new RuntimeException("stream broken");
        });
        when(llm.callSync(anyList(), any())).thenThrow(new RuntimeException("sync broken"));
        Harness h = newHarnessWithLlm(llm);

        RouterService.RoundResult result = h.router().runRound(null, null);

        assertFalse(result.status.startsWith("error"), "单角色失败不应中断整轮: " + result.status);
        // —— 两角色各一条 agent_error，同源 message_id，会话定向 ——
        List<Map<String, Object>> errors = h.sse().payloadsOf("agent_error");
        assertEquals(2, errors.size(), "两失败角色各一条 agent_error");
        List<String> errMids = new ArrayList<>();
        for (Map<String, Object> p : errors) {
            assertEquals(SESSION_ID, p.get("session_id"));
            String mid = String.valueOf(p.get("message_id"));
            assertFalse(mid == null || mid.isBlank() || "null".equals(mid), "error 应带 message_id");
            errMids.add(mid);
        }
        assertFalse(errMids.get(0).equals(errMids.get(1)), "失败流各有独立 id，不串扰");
        // —— DB：每条失败流 STREAMING → FAILED（同源 id），无 FINAL ——
        ArgumentCaptor<String> midCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCap = ArgumentCaptor.forClass(String.class);
        verify(h.db(), atLeast(2)).saveChatMessage(midCap.capture(), eq(SESSION_ID), eq(1),
                eq("agent"), anyString(), anyString(), statusCap.capture(), eq("main"));
        for (String mid : errMids) {
            List<String> perMsg = new ArrayList<>();
            for (int i = 0; i < midCap.getAllValues().size(); i++) {
                if (mid.equals(midCap.getAllValues().get(i))) perMsg.add(statusCap.getAllValues().get(i));
            }
            assertEquals(List.of(ChatMessageEntity.STATUS_STREAMING, ChatMessageEntity.STATUS_FAILED),
                    perMsg, "失败流应 STREAMING → FAILED: " + mid);
        }
        // —— 失败占位不入史（内存无新增 agent 消息，旧文本无处可被覆盖） ——
        List<Message> agentMsgs = h.router().getConversationMessages().stream()
                .filter(m -> m.getRole() == Message.Role.AGENT).toList();
        assertTrue(agentMsgs.isEmpty(), "失败输出不入史");
    }
}
