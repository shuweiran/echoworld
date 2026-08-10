package com.roleplay.engine.debug;

import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TaskCancelledException;
import com.roleplay.engine.llm.LLMClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0809-B（阶段② API 逻辑链追踪）打点钩子单元测试（纯 JUnit，无 Spring）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>① LLMClient.callSync 打点（本地 HttpServer mock，模型/耗时入链路）</li>
 *   <li>② LLMClient.callJson 打点（经内部 callSync 单次记录，不重复）</li>
 *   <li>③ LLMClient.callStream 打点（普通 JSON 响应按单块兜底解析）</li>
 *   <li>④ 取消令牌路径：异常上抛前 finally 仍打点（失败也如实记录）</li>
 *   <li>⑤ SSEController.broadcast / broadcastToSession 打点（事件名/会话入链路）</li>
 *   <li>⑥ 环形缓冲容量上限（超限淘汰最旧）</li>
 *   <li>⑦ 开关关闭 → recordLlm/recordSse 零记录</li>
 * </ul>
 */
class TraceHooksUnitTest {

    private HttpServer server;
    private String apiBase;
    private TraceService traceService;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        apiBase = "http://localhost:" + server.getAddress().getPort() + "/v1";
        traceService = new TraceService(true, 1000);
        traceService.setEnabled(true);
        TraceContext.register(traceService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        TraceContext.clear();
        TraceContext.unregister();
    }

    private void serveChatCompletions(String content) {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":" + content + "}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }

    private LLMClient newClient() {
        AppConfig cfg = new AppConfig();
        cfg.getLlm().setApiBase(apiBase);
        cfg.getLlm().setApiKey("test-key");
        cfg.getLlm().setModel("test-model");
        return new LLMClient(cfg);
    }

    private List<Message> messages() {
        return List.of(
                new Message(Message.Role.SYSTEM, "system", "test"),
                new Message(Message.Role.USER, "user", "hi"));
    }

    /** 开启一条测试链路并返回条目（模拟 TraceFilter.start 的绑定语义；
     *  操作完成后需 enqueue 入缓冲——真实流程由 TraceFilter finally 执行）。 */
    private TraceEntry begin(String requestId) {
        return traceService.start(requestId, "GET", "/api/test", null, "", "frontend");
    }

    @Test
    @DisplayName("① LLMClient.callSync 打点：模型 + 耗时入链路")
    void callSync_recordsLlmCall() {
        serveChatCompletions("\"ok\"");
        TraceEntry entry = begin("req-sync");
        LLMClient llm = newClient();
        String out = llm.callSync(messages());
        traceService.enqueue(entry);

        assertEquals("ok", out);
        TraceEntry found = traceService.get("req-sync");
        assertNotNull(found, "链路应入缓冲可按 requestId 查回");
        assertEquals(1, found.llmCalls.size(), "callSync 应记录 1 条 LLM 子调用");
        Map<String, Object> call = found.llmCalls.get(0);
        assertEquals("test-model", call.get("model"));
        assertTrue((Long) call.get("ms") >= 0);
    }

    @Test
    @DisplayName("② LLMClient.callJson 打点：经内部 callSync 单次记录（不重复）")
    void callJson_recordsSingleLlmCall() {
        serveChatCompletions("\"{\\\"ok\\\":1}\"");
        TraceEntry entry = begin("req-json");
        LLMClient llm = newClient();
        Map<String, Object> out = llm.callJson("make json", 100);
        traceService.enqueue(entry);

        assertEquals(1, out.get("ok"));
        TraceEntry found = traceService.get("req-json");
        assertNotNull(found);
        assertEquals(1, found.llmCalls.size(), "callJson 内部仅一次 callSync，应单条记录");
    }

    @Test
    @DisplayName("③ LLMClient.callStream 打点：流式调用同样记录")
    void callStream_recordsLlmCall() {
        serveChatCompletions("\"streamed\"");
        TraceEntry entry = begin("req-stream");
        LLMClient llm = newClient();
        StringBuilder sb = new StringBuilder();
        String out = llm.callStream(messages(), null, sb::append);
        traceService.enqueue(entry);

        assertEquals("streamed", out);
        TraceEntry found = traceService.get("req-stream");
        assertNotNull(found);
        assertEquals(1, found.llmCalls.size());
        assertEquals("test-model", found.llmCalls.get(0).get("model"));
    }

    @Test
    @DisplayName("④ 取消令牌路径：异常上抛前 finally 仍打点（失败也如实记录）")
    void cancelledToken_stillRecords() {
        serveChatCompletions("\"ok\"");
        TraceEntry entry = begin("req-cancel");
        LLMClient llm = newClient();
        CancellationToken token = new CancellationToken();
        token.cancel(StopType.HARD, "test-cancel");

        assertThrows(TaskCancelledException.class, () -> llm.callSync(messages(), token));
        traceService.enqueue(entry);
        TraceEntry found = traceService.get("req-cancel");
        assertNotNull(found);
        assertEquals(1, found.llmCalls.size(), "取消路径也应记录（finally 语义）");
    }

    @Test
    @DisplayName("⑤ SSEController.broadcast / broadcastToSession 打点：事件名 + 会话入链路")
    void sseBroadcast_recordsEvents() {
        TraceEntry entry = begin("req-sse");
        SSEController sse = new SSEController();
        sse.broadcast("announcement", Map.of("text", "hello"));
        sse.broadcastToSession("session-1", "script_phase", Map.of("phase", "VOTE"));
        traceService.enqueue(entry);

        TraceEntry found = traceService.get("req-sse");
        assertNotNull(found);
        assertEquals(2, found.sseEvents.size());
        assertEquals("announcement", found.sseEvents.get(0).get("event_type"));
        assertEquals("script_phase", found.sseEvents.get(1).get("event_type"));
        assertEquals("session-1", found.sseEvents.get(1).get("session_id"));
    }

    @Test
    @DisplayName("⑥ 环形缓冲容量上限：超限淘汰最旧，保留最新")
    void buffer_capsAtCapacity() {
        TraceService small = new TraceService(true, 10);
        TraceContext.register(small);
        for (int i = 0; i < 25; i++) {
            small.enqueue(new TraceEntry("req-" + i, i, "GET", "/p"));
        }
        assertTrue(small.count() <= 10, "缓冲应被裁剪到容量上限 10");
        assertEquals("req-24", small.list(10).get(0).requestId, "最新条目应保留");
    }

    @Test
    @DisplayName("⑦ 开关关闭：recordLlm / recordSse 零记录")
    void disabled_recordsNothing() {
        traceService.setEnabled(false);
        TraceEntry entry = begin("req-off");
        TraceContext.recordLlm("test-model", 123);
        TraceContext.recordSse("agent_output", null);
        traceService.enqueue(entry);

        TraceEntry found = traceService.get("req-off");
        assertNotNull(found);
        assertEquals(0, found.llmCalls.size());
        assertEquals(0, found.sseEvents.size());
    }
}
