package com.roleplay.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0817-A（MiMo TTS）：服务层测试 —— 本地 {@link HttpServer} 桩模拟 MiMo /chat/completions，
 * 零真实 API 调用（禁 spring-boot:run / 零成本）。
 *
 * <p>覆盖：三模式请求形态（basic 内置音色 / clone 上传 data URL / design 无 voice 字段）、
 * 默认值（tone/voice）、响应解析（base64 WAV 字节 + transcript）、异步合成、HTTP 错误/空数据/
 * 缺 key/未启用 异常、openclaw.json key 兜底、参考音频格式/大小/时长边界。
 */
class MimoTtsServiceTest {

    private HttpServer server;
    private AppConfig appConfig;
    private MimoTtsService service;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int statusCode = 200;
    private volatile String responseBody;

    private static final byte[] WAV_BYTES = validWav(8000);

    private static byte[] validWav(int dataBytes) {
        byte[] b = new byte[44 + dataBytes];
        System.arraycopy(new byte[]{'R','I','F','F'}, 0, b, 0, 4);
        putLe(b, 4, b.length - 8);
        System.arraycopy(new byte[]{'W','A','V','E','f','m','t',' '}, 0, b, 8, 8);
        putLe(b, 16, 16); putLeShort(b, 20, 1); putLeShort(b, 22, 1);
        putLe(b, 24, 8000); putLe(b, 28, 16000); putLeShort(b, 32, 2); putLeShort(b, 34, 16);
        System.arraycopy(new byte[]{'d','a','t','a'}, 0, b, 36, 4); putLe(b, 40, dataBytes);
        return b;
    }

    private static void putLe(byte[] b, int p, int n) {
        b[p] = (byte) n; b[p + 1] = (byte) (n >>> 8); b[p + 2] = (byte) (n >>> 16); b[p + 3] = (byte) (n >>> 24);
    }

    private static void putLeShort(byte[] b, int p, int n) {
        b[p] = (byte) n; b[p + 1] = (byte) (n >>> 8);
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        appConfig = new AppConfig();
        appConfig.getTts().getMimo().setApiKey("test-key");
        appConfig.getTts().getMimo().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        appConfig.getTts().getMimo().setTimeoutSeconds(10);
        service = new MimoTtsService(appConfig);

        statusCode = 200;
        responseBody = okResponse("你好，欢迎。");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        service.shutdown();
    }

    // ── 桩响应 ────────────────────────────────────────────────

    private static String okResponse(String transcript) {
        String b64 = Base64.getEncoder().encodeToString(WAV_BYTES);
        return "{\"id\":\"chatcmpl-test\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\","
                + "\"audio\":{\"id\":\"audio-1\",\"data\":\"" + b64 + "\",\"expires_at\":1893456000,"
                + "\"transcript\":\"" + transcript + "\"}}}],\"created\":1,\"model\":\"mimo-v2.5-tts\","
                + "\"object\":\"chat.completion\",\"usage\":{}}";
    }

    private static JsonNode parse(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    // ── 三模式请求形态 ─────────────────────────────────────────

    @Test
    @DisplayName("basic 模式：model/audio.voice=默认内置音色/user 消息=默认语气/响应字节还原")
    void basicModeBuildsCorrectRequestAndReturnsBytes() throws Exception {
        MimoTtsService.TtsResult r = service.synthesize("你好，欢迎来到这个小镇。", MimoTtsService.VoiceSpec.basic(null));

        assertArrayEquals(WAV_BYTES, r.audio());
        assertEquals("wav", r.format());
        assertEquals("mimo-v2.5-tts", r.model());
        assertEquals("你好，欢迎。", r.transcript());
        assertEquals("Bearer test-key", lastAuth.get());

        JsonNode req = parse(lastRequest.get());
        assertEquals("mimo-v2.5-tts", req.get("model").asText());
        assertEquals("自然温柔的语气", req.get("messages").get(0).get("content").asText());
        assertEquals("你好，欢迎来到这个小镇。", req.get("messages").get(1).get("content").asText());
        assertEquals("wav", req.get("audio").get("format").asText());
        assertEquals("mimo_default", req.get("audio").get("voice").asText());
        assertTrue(req.get("audio").get("voice").isTextual());
    }

    @Test
    @DisplayName("clone 模式：服务器本地路径被拒绝")
    void cloneModeWithFilePathIsRejected() throws Exception {
        Path ref = Files.createTempFile("mimo_ref_", ".wav");
        Files.write(ref, WAV_BYTES);
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.synthesize("克隆声音测试", MimoTtsService.VoiceSpec.cloneOf(ref.toString())));
            assertTrue(e.getMessage().contains("不接受服务器文件路径"));
        } finally {
            Files.deleteIfExists(ref);
        }
    }

    @Test
    @DisplayName("clone 模式：已带 data URL 的 voice_data 原样透传")
    void cloneModeWithDataUrlPassesThrough() throws Exception {
        String dataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(WAV_BYTES);
        service.synthesize("克隆测试2", MimoTtsService.VoiceSpec.cloneOf(dataUrl));

        JsonNode req = parse(lastRequest.get());
        assertEquals(dataUrl, req.get("audio").get("voice").asText());
    }

    @Test
    @DisplayName("design 模式：无 voice 字段，user 消息=音色描述")
    void designModeOmitsVoiceFieldAndUsesDescriptionAsUserMessage() throws Exception {
        service.synthesize("用设计声线说话", MimoTtsService.VoiceSpec.design("低沉沙哑的男声，带点疲惫感"));

        JsonNode req = parse(lastRequest.get());
        assertEquals("mimo-v2.5-tts-voicedesign", req.get("model").asText());
        assertFalse(req.get("audio").has("voice"), "design 模式不应携带 voice 字段");
        assertEquals("低沉沙哑的男声，带点疲惫感", req.get("messages").get(0).get("content").asText());
        assertEquals("用设计声线说话", req.get("messages").get(1).get("content").asText());
    }

    @Test
    @DisplayName("basic 模式显式 voice/tone 优先于默认值")
    void explicitVoiceAndToneOverrideDefaults() throws Exception {
        service.synthesize("显式参数测试", new MimoTtsService.VoiceSpec(
                MimoTtsService.Mode.BASIC, "mimo_female_warm", null, "俏皮可爱的语气", "wav"));

        JsonNode req = parse(lastRequest.get());
        assertEquals("mimo_female_warm", req.get("audio").get("voice").asText());
        assertEquals("俏皮可爱的语气", req.get("messages").get(0).get("content").asText());
    }

    // ── 异步 ──────────────────────────────────────────────────

    @Test
    @DisplayName("异步合成（虚拟线程）返回与同步一致的结果")
    void asyncSynthesizeCompletesWithSameResult() throws Exception {
        CompletableFuture<MimoTtsService.TtsResult> future =
                service.synthesizeAsync("异步测试", MimoTtsService.VoiceSpec.basic(null));
        MimoTtsService.TtsResult r = future.get();
        assertArrayEquals(WAV_BYTES, r.audio());
        assertEquals("mimo_default",
                parse(lastRequest.get()).get("audio").get("voice").asText());
    }

    // ── 异常路径 ──────────────────────────────────────────────

    @Test
    @DisplayName("HTTP 非 200 → 抛错含状态码")
    void httpErrorThrowsWithStatus() {
        statusCode = 500;
        responseBody = "{\"error\":\"internal\"}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.synthesize("会失败吗", MimoTtsService.VoiceSpec.basic(null)));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
    }

    @Test
    @DisplayName("响应无 audio.data → 抛错")
    void emptyAudioDataThrows() {
        responseBody = "{\"choices\":[{\"message\":{\"audio\":{\"data\":\"\"}}}]}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.synthesize("空响应", MimoTtsService.VoiceSpec.basic(null)));
        assertTrue(e.getMessage().contains("无 audio.data"), e.getMessage());
    }

    @Test
    @DisplayName("data 非合法 base64 → 抛错")
    void invalidBase64Throws() {
        responseBody = "{\"choices\":[{\"message\":{\"audio\":{\"data\":\"!!!not-base64!!!\"}}}]}";
        assertThrows(IllegalStateException.class,
                () -> service.synthesize("坏响应", MimoTtsService.VoiceSpec.basic(null)));
    }

    @Test
    @DisplayName("未启用 → 抛错")
    void disabledThrows() {
        appConfig.getTts().getMimo().setEnabled(false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.synthesize("禁用测试", MimoTtsService.VoiceSpec.basic(null)));
        assertTrue(e.getMessage().contains("未启用"), e.getMessage());
    }

    @Test
    @DisplayName("text 为空 → 抛 IllegalArgumentException")
    void blankTextThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.synthesize("   ", MimoTtsService.VoiceSpec.basic(null)));
    }

    @Test
    @DisplayName("clone 参考音频旧路径 → 抛 IllegalArgumentException")
    void cloneMissingReferenceFileThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.synthesize("克隆", MimoTtsService.VoiceSpec.cloneOf("Z:/no_such_file_xyz.wav")));
        assertTrue(e.getMessage().contains("不接受服务器文件路径"), e.getMessage());
    }

    @Test
    @DisplayName("clone 参考音频超过 10 秒 → 拒绝")
    void cloneAudioLongerThanTenSecondsThrows() {
        String data = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(validWav(160001));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.synthesize("克隆", MimoTtsService.VoiceSpec.cloneOf(data)));
        assertTrue(e.getMessage().contains("10 秒"), e.getMessage());
    }

    // ── key 解析链 ────────────────────────────────────────────

    @Test
    @DisplayName("api-key 全空 → 抛「未配置」")
    void missingApiKeyThrows() {
        appConfig.getTts().getMimo().setApiKey("");
        appConfig.getTts().getMimo().setOpenclawConfig("");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.synthesize("缺 key", MimoTtsService.VoiceSpec.basic(null)));
        assertTrue(e.getMessage().contains("API Key 未配置"), e.getMessage());
        assertNull(service.resolveApiKey());
    }

    @Test
    @DisplayName("openclaw.json 兜底：models.providers.xiaomimimo-tp.apiKey 被读取")
    void keyFromOpenclawConfigFallback() throws Exception {
        appConfig.getTts().getMimo().setApiKey("");
        Path cfg = Files.createTempFile("openclaw_", ".json");
        try {
            Files.writeString(cfg, "{\"models\":{\"providers\":{\"xiaomimimo-tp\":{\"apiKey\":\"oc-key-123\"}}}}",
                    StandardCharsets.UTF_8);
            appConfig.getTts().getMimo().setOpenclawConfig(cfg.toString());
            assertEquals("oc-key-123", service.resolveApiKey());
            // 实际合成也走通（Authorization 带兜底 key）
            service.synthesize("兜底 key 测试", MimoTtsService.VoiceSpec.basic(null));
            assertEquals("Bearer oc-key-123", lastAuth.get());
        } finally {
            Files.deleteIfExists(cfg);
        }
    }

    @Test
    @DisplayName("openclaw.json 缺失/解析失败 → null 不炸")
    void openclawConfigMissingReturnsNull() {
        appConfig.getTts().getMimo().setApiKey("");
        appConfig.getTts().getMimo().setOpenclawConfig("Z:/no_such_openclaw.json");
        assertNull(service.resolveApiKey());
    }

    // ── 内置音色 / 状态 ────────────────────────────────────────

    @Test
    @DisplayName("builtinVoices 解析逗号分隔清单")
    void builtinVoicesParses() {
        assertEquals(java.util.List.of("mimo_default"), service.builtinVoices());
        appConfig.getTts().getMimo().setBuiltinVoices("mimo_default,mimo_female,mimo_male");
        assertEquals(java.util.List.of("mimo_default", "mimo_female", "mimo_male"), service.builtinVoices());
        appConfig.getTts().getMimo().setBuiltinVoices("");
        assertTrue(service.builtinVoices().isEmpty());
    }

    @Test
    @DisplayName("statusMap 不含 apiKey，含 configured 标志")
    void statusMapDoesNotLeakKey() {
        java.util.Map<String, Object> m = service.statusMap();
        assertFalse(m.containsKey("api_key"));
        assertEquals(Boolean.TRUE, m.get("configured"));
        assertEquals("xiaomimimo", m.get("provider"));
    }
}
