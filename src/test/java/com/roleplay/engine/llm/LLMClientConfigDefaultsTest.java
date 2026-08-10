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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0810-19：LLM 配置默认值生效化 + seed 支持测试。
 *
 * <p>本地 {@link HttpServer} 捕获请求体，验证：
 * <ul>
 *   <li>callJson maxTokens 传 null → 请求 max_tokens = 配置默认（roleplay.llm.max-tokens）</li>
 *   <li>callJson 显式传 maxTokens → 显式值优先（既有调用点行为逐字节不变）</li>
 *   <li>callJson temperature 读配置（roleplay.llm.temperature），非硬编码</li>
 *   <li>seed 默认 null → 请求体不含 seed 字段（行为不变）</li>
 *   <li>seed 非空 → 请求体携带 seed 字段</li>
 * </ul>
 */
class LLMClientConfigDefaultsTest {

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
            String body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}";
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

    @Test
    @DisplayName("callJson maxTokens=null → 请求 max_tokens=配置默认（4000）")
    void callJsonNullMaxTokens_usesConfigDefault() {
        LLMClient client = newClient();
        Map<String, Object> out = client.callJson("test", null);
        assertNotNull(out, "callJson must return parsed map");
        assertTrue(lastRequestBody.get().contains("\"max_tokens\":4000"),
                "null maxTokens 必须用配置默认 4000: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("callJson 显式 maxTokens → 显式值优先（既有行为不变）")
    void callJsonExplicitMaxTokens_preserved() {
        LLMClient client = newClient();
        client.callJson("test", 1200);
        assertTrue(lastRequestBody.get().contains("\"max_tokens\":1200"),
                "显式 1200 必须原样发送: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("callJson temperature 读配置（roleplay.llm.temperature），非硬编码")
    void callJsonTemperature_fromConfig() {
        AppConfig cfg = new AppConfig();
        cfg.getLlm().setApiBase(apiBase);
        cfg.getLlm().setApiKey("test-key");
        cfg.getLlm().setModel("test-model");
        cfg.getLlm().setTemperature(0.5);
        LLMClient client = new LLMClient(cfg);
        client.callJson("test", 300);
        assertTrue(lastRequestBody.get().contains("\"temperature\":0.5"),
                "temperature 必须读配置 0.5: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("seed 默认 null → 请求体不含 seed 字段（行为不变）")
    void seedNull_notInRequestBody() {
        LLMClient client = newClient();
        client.callJson("test", 300);
        assertFalse(lastRequestBody.get().contains("\"seed\""),
                "seed null 时请求体不得包含 seed: " + lastRequestBody.get());
    }

    @Test
    @DisplayName("seed 非空 → 请求体携带 seed 字段")
    void seedSet_carriedInRequestBody() {
        AppConfig cfg = new AppConfig();
        cfg.getLlm().setApiBase(apiBase);
        cfg.getLlm().setApiKey("test-key");
        cfg.getLlm().setModel("test-model");
        cfg.getLlm().setSeed(42);
        LLMClient client = new LLMClient(cfg);
        client.callJson("test", 300);
        assertTrue(lastRequestBody.get().contains("\"seed\":42"),
                "seed=42 必须携带: " + lastRequestBody.get());
    }
}
