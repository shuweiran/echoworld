package com.roleplay.engine.llm;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.CancellationToken;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0802-M：LLM 流式调用（callStream / Agent.generateWithContextStream）测试。
 *
 * <p>本地 {@link HttpServer} 模拟 DeepSeek 兼容 SSE 端点，验证：
 * <ul>
 *   <li>SSE 增量逐片回调（顺序）+ 完整文本返回</li>
 *   <li>非 SSE 响应（mock/忽略 stream 参数的服务返回普通 JSON）→ 整段作为单个增量回调</li>
 *   <li>Agent.generateWithContextStream：真实 LLMClient 流式链路（增量 + 完整内容）</li>
 *   <li>Agent 降级：调用方未实现流式（mock callStream 返回 null）→ 非流式兜底内容不丢</li>
 *   <li>Agent 降级：callStream 抛异常 → 非流式兜底内容不丢</li>
 * </ul>
 */
class LLMClientStreamTest {

    private HttpServer server;
    private String apiBase;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        apiBase = "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** 注册一个返回固定响应体的处理端点（记录请求体用于断言 stream=true）。 */
    private void serveOnce(String responseBody, String contentType) {
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
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
                new Message(Message.Role.SYSTEM, "system", "测试系统提示"),
                new Message(Message.Role.USER, "user", "测试请求"));
    }

    // ── SSE 增量解析 ───────────────────────────────────────────

    @Test
    @DisplayName("SSE 增量逐片回调（顺序）+ 完整文本返回 + 请求带 stream=true")
    void sseStream_deltasInOrderAndFullText() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
                + "data: [DONE]\n\n";
        serveOnce(sse, "text/event-stream");

        List<String> deltas = new ArrayList<>();
        String full = newClient().callStream(messages(), null, deltas::add);

        assertEquals(List.of("你好", "，世界"), deltas, "deltas must arrive in order, blank delta skipped");
        assertEquals("你好，世界", full, "full text = concatenated deltas");
        assertTrue(lastRequestBody.get().contains("\"stream\":true"),
                "request body must include stream=true: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("非 SSE 响应（普通 JSON）→ 整段作为单个增量回调 + 完整文本返回")
    void plainJsonResponse_singleDeltaFallback() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"完整回复\"}}]}";
        serveOnce(json, "application/json");

        List<String> deltas = new ArrayList<>();
        String full = newClient().callStream(messages(), null, deltas::add);

        assertEquals(List.of("完整回复"), deltas, "plain JSON → whole content as single delta");
        assertEquals("完整回复", full);
    }

    @Test
    @DisplayName("SSE 流中个别 data 行损坏 → 容错继续，完整内容不受影响")
    void sseStream_brokenLineTolerated() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"第一\"}}]}\n\n"
                + "data: {broken json\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"第二\"}}]}\n\n"
                + "data: [DONE]\n\n";
        serveOnce(sse, "text/event-stream");

        List<String> deltas = new ArrayList<>();
        String full = newClient().callStream(messages(), null, deltas::add);

        assertEquals(List.of("第一", "第二"), deltas);
        assertEquals("第一第二", full);
    }

    // ── Agent 层流式链路 ───────────────────────────────────────

    @Test
    @DisplayName("Agent.generateWithContextStream：真实 LLMClient 流式链路（增量 + 完整内容）")
    void agentStream_realLlmChain() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"我是\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"侦探\"}}]}\n\n"
                + "data: [DONE]\n\n";
        serveOnce(sse, "text/event-stream");

        LLMClient llm = newClient();
        Agent agent = new Agent(new Persona("侦探", "你是一个侦探。"), "agent", llm);

        List<String> deltas = new ArrayList<>();
        String full = agent.generateWithContextStream("查案", null, deltas::add);

        assertEquals(List.of("我是", "侦探"), deltas);
        assertEquals("我是侦探", full);
    }

    @Test
    @DisplayName("Agent 降级：callStream 未实现（mock 返回 null）→ 非流式兜底内容不丢")
    void agentStream_nullStreamFallsBackToSync() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callStream(anyList(), any(), any())).thenReturn(null);
        // token=null 时 generateWithContext 走 1 参 callSync（非流式兜底路径）
        when(llm.callSync(anyList())).thenReturn("非流式完整回复");

        Agent agent = new Agent(new Persona("A", "描述"), "agent", llm);
        List<String> deltas = new ArrayList<>();
        String full = agent.generateWithContextStream("上下文", null, deltas::add);

        assertEquals("非流式完整回复", full, "fallback must return full non-streaming content");
        assertTrue(deltas.isEmpty(), "no deltas when streaming unimplemented");
    }

    @Test
    @DisplayName("Agent 降级：callStream 抛异常 → 非流式兜底内容不丢")
    void agentStream_exceptionFallsBackToSync() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callStream(anyList(), any(), any()))
                .thenThrow(new RuntimeException("连接中断"));
        when(llm.callSync(anyList())).thenReturn("兜底完整回复");

        Agent agent = new Agent(new Persona("B", "描述"), "agent", llm);
        String full = agent.generateWithContextStream("上下文", null, d -> {});

        assertEquals("兜底完整回复", full);
    }

    @Test
    @DisplayName("取消令牌：流式读取期间 checkpoint 生效（已取消 → TaskCancelledException）")
    void sseStream_cancellationCheckpoint() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"开始\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"继续\"}}]}\n\n"
                + "data: [DONE]\n\n";
        serveOnce(sse, "text/event-stream");

        CancellationToken token = new CancellationToken();
        token.cancel(com.roleplay.engine.interrupt.StopType.SOFT, "测试停止");

        assertThrows(com.roleplay.engine.interrupt.TaskCancelledException.class,
                () -> newClient().callStream(messages(), token, d -> {}),
                "cancelled token must abort streaming at first checkpoint");
    }
}
