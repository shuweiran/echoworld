package com.roleplay.engine.llm;

import com.roleplay.engine.config.AppConfig;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0810-21-D：对话主链路 max_tokens 提升（callSync 无参/带 token + callStream）——
 * 原硬编码 300 偏短（AI 发言常截断）；改为配置 roleplay.llm.dialogue-max-tokens（默认 700）。
 *
 * <p>本地 {@link HttpServer} 捕获请求体断言：\n * <ul>\n *   <li>callSync(messages) → max_tokens=配置默认（700）</li>\n *   <li>callSync(messages, token) → 700</li>\n *   <li>callStream → 700</li>\n *   <li>配置改 800 → 800（可配）</li>\n *   <li>显式传值 4 参 callSync(messages, model, 300, temp) → 300 保持（既有调用点零变化）</li>\n * </ul>\n */
class LLMDialogueMaxTokensTest {

    private HttpServer server;
    private String apiBase;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        apiBase = "http://localhost:" + server.getAddress().getPort() + "/v1";
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private LLMClient newClient() {
        AppConfig cfg = new AppConfig();
        cfg.getLlm().setApiBase(apiBase);
        cfg.getLlm().setApiKey("test-key");
        cfg.getLlm().setModel("test-model");
        return new LLMClient(cfg);
    }

    private static com.roleplay.engine.core.Message msg(String content) {
        return new com.roleplay.engine.core.Message(com.roleplay.engine.core.Message.Role.USER, "user", content);
    }

    @Test
    @DisplayName("① callSync(messages) → max_tokens=配置默认 700（对话主链路提升）")
    void callSync_usesDialogueMaxTokensDefault() {
        LLMClient client = newClient();
        client.callSync(List.of(msg("hi")));
        assertTrue(lastRequestBody.get().contains("\"max_tokens\":700"),
                "callSync 无参入口应使用 dialogue-max-tokens=700: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("② callSync(messages, token) → 700（可中断对话主链路同步提升）")
    void callSyncWithToken_usesDialogueMaxTokens() {
        LLMClient client = newClient();
        // 2 参重载唯一匹配 (List, CancellationToken)；null 走同一 callSyncInternal 路径
        client.callSync(List.of(msg("hi")), null);
        assertTrue(lastRequestBody.get().contains("\"max_tokens\":700"),
                "callSync 带 token 入口应使用 700: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("③ callStream → 700（流式打字机主链路同步提升）")
    void callStream_usesDialogueMaxTokens() throws Exception {
        LLMClient client = newClient();
        client.callStream(List.of(msg("hi")), null, delta -> { });
        assertTrue(lastRequestBody.get().contains("\"max_tokens\":700"),
                "callStream 应使用 700: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("④ 配置改 800 → 请求 800（可配）")
    void dialogueMaxTokens_configurable() {
        AppConfig cfg = new AppConfig();
        cfg.getLlm().setApiBase(apiBase);
        cfg.getLlm().setApiKey("test-key");
        cfg.getLlm().setModel("test-model");
        cfg.getLlm().setDialogueMaxTokens(800);
        LLMClient client = new LLMClient(cfg);
        client.callSync(List.of(msg("hi")));
        assertTrue(lastRequestBody.get().contains("\"max_tokens\":800"),
                "dialogue-max-tokens=800 应生效: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("⑤ 显式传值 4 参 callSync(…, 300, …) → 300 保持（既有调用点零变化）")
    void callSyncExplicitMaxTokens_preserved() {
        LLMClient client = newClient();
        client.callSync(List.of(msg("hi")), "test-model", 300, 0.7);
        assertTrue(lastRequestBody.get().contains("\"max_tokens\":300"),
                "显式 300 应原样发送（地图/剧本/审批等调用点不受 dialogue 默认影响）: " + lastRequestBody.get());
    }
}
