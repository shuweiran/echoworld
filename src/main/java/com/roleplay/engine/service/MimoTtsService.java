package com.roleplay.engine.service;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 小米 MiMo TTS 服务（P-0817-A）：角色语音合成，调用 MiMo OpenAI 兼容
 * {@code /v1/chat/completions} 端点（audio 参数形态，响应 {@code choices[0].message.audio.data}
 * 为 base64 音频，实测 RIFF/WAVE）。
 *
 * <p>三种模式（已真机验证 3/3，2026-08-17）：
 * <ul>
 *   <li>{@link Mode#BASIC basic}：内置音色（audio.voice = 内置音色名，如 mimo_default）；</li>
 *   <li>{@link Mode#CLONE clone}：声音克隆（audio.voice = 参考音频 data URL，本地 wav 路径自动转）；</li>
 *   <li>{@link Mode#DESIGN design}：声线设计（无 voice 字段，user 消息即音色描述）。</li>
 * </ul>
 *
 * <p>API Key 解析链（{@link #resolveApiKey()}）：yml {@code roleplay.tts.mimo.api-key}
 * → 环境变量 {@code ROLEPLAY_MIMO_TTS_KEY} → openclaw.json
 * {@code models.providers.xiaomimimo-tp.apiKey}（路径 {@code roleplay.tts.mimo.openclaw-config}，
 * 空=不读）。配置为 {@link AppConfig.TtsConfig.MimoConfig}（yml {@code roleplay.tts.mimo.*}）。
 *
 * <p>异步：{@link #synthesizeAsync(String, VoiceSpec)} 用虚拟线程执行器，不阻塞主流程
 * （对话生成后可异步合成语音）。
 */
@Service
public class MimoTtsService {

    private static final Logger log = LoggerFactory.getLogger(MimoTtsService.class);
    /** 克隆参考音频服务端硬上限；前端校验不能作为安全边界。 */
    public static final int MAX_CLONE_AUDIO_BYTES = 10 * 1024 * 1024;
    public static final double MAX_CLONE_AUDIO_SECONDS = 10.0;
    /** 防止直接调用 TTS 接口提交超长文本拖垮上游。 */
    public static final int MAX_TEXT_CHARS = 4000;

    /** 三种合成模式。 */
    public enum Mode { BASIC, CLONE, DESIGN }

    /**
     * 一次合成的声线规格。
     *
     * @param mode      合成模式
     * @param voiceName basic 模式内置音色名（null → 配置默认）
     * @param voiceData clone=参考音频路径或 data URL；design=音色描述；basic=内置音色名
     * @param tone      user 消息语气/风格描述（null → 配置默认）
     * @param format    音频格式（默认 wav；经 API 透传）
     */
    public record VoiceSpec(Mode mode, String voiceName, String voiceData, String tone, String format) {
        public static VoiceSpec basic(String voiceName) {
            return new VoiceSpec(Mode.BASIC, voiceName, null, null, null);
        }

        public static VoiceSpec cloneOf(String voiceData) {
            return new VoiceSpec(Mode.CLONE, null, voiceData, null, null);
        }

        public static VoiceSpec design(String description) {
            return new VoiceSpec(Mode.DESIGN, null, description, null, null);
        }

        public String formatOrDefault(String def) {
            return format == null || format.isBlank() ? def : format;
        }
    }

    /** 合成结果。 */
    public record TtsResult(byte[] audio, String format, String transcript, String model, long elapsedMs) {
    }

    private final AppConfig appConfig;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final ExecutorService ttsExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MimoTtsService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(httpExecutor)
                .build();
    }

    // ── 配置 ──────────────────────────────────────────────────

    private AppConfig.TtsConfig.MimoConfig cfg() {
        return appConfig.getTts().getMimo();
    }

    /** 总开关（roleplay.tts.mimo.enabled）。 */
    public boolean isEnabled() {
        return cfg().isEnabled();
    }

    /**
     * API Key 解析链：yml {@code roleplay.tts.mimo.api-key} → 环境变量
     * {@code ROLEPLAY_MIMO_TTS_KEY} → openclaw.json（{@code models.providers.xiaomimimo-tp.apiKey}，
     * 路径由 {@code roleplay.tts.mimo.openclaw-config} 指定，空=不读）。
     */
    public String resolveApiKey() {
        String key = cfg().getApiKey();
        if (isBlank(key)) {
            key = System.getenv("ROLEPLAY_MIMO_TTS_KEY");
        }
        if (isBlank(key)) {
            key = readKeyFromOpenClawConfig();
        }
        return isBlank(key) ? null : key.trim();
    }

    /** 从 openclaw.json 读取 xiaomimimo-tp provider 的 apiKey（解析失败/文件缺失 → null）。 */
    String readKeyFromOpenClawConfig() {
        String path = cfg().getOpenclawConfig();
        if (isBlank(path)) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(Path.of(path)));
            JsonNode node = root.path("models").path("providers").path("xiaomimimo-tp").path("apiKey");
            String key = node.isMissingNode() ? null : node.asText(null);
            if (!isBlank(key)) {
                log.info("MiMo TTS API key 从 openclaw.json 读取（{}）", path);
            }
            return key;
        } catch (Exception e) {
            log.debug("openclaw.json 读取失败（忽略，走后续校验）: {}", e.toString());
            return null;
        }
    }

    // ── 同步合成 ──────────────────────────────────────────────

    /**
     * 同步合成：调 MiMo API，返回音频字节（WAV）。
     *
     * @throws IllegalStateException    未启用 / API Key 未配置 / API 调用失败
     * @throws IllegalArgumentException text 为空
     */
    public TtsResult synthesize(String text, VoiceSpec spec) {
        if (!isEnabled()) {
            throw new IllegalStateException("MiMo TTS 未启用（roleplay.tts.mimo.enabled=false）");
        }
        if (isBlank(text)) {
            throw new IllegalArgumentException("text 不能为空");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("text 不能超过 " + MAX_TEXT_CHARS + " 字符");
        }
        String apiKey = resolveApiKey();
        if (apiKey == null) {
            throw new IllegalStateException(
                    "MiMo TTS API Key 未配置（yml roleplay.tts.mimo.api-key / 环境变量 ROLEPLAY_MIMO_TTS_KEY / openclaw.json）");
        }
        VoiceSpec s = spec == null ? VoiceSpec.basic(null) : spec;
        Mode mode = s.mode() == null ? Mode.BASIC : s.mode();

        String format = s.formatOrDefault("wav");
        String model = modelFor(mode);
        String tone = isBlank(s.tone()) ? cfg().getDefaultTone() : s.tone();
        String voice = mode == Mode.BASIC
                ? (isBlank(s.voiceName()) ? cfg().getDefaultVoice() : s.voiceName())
                : null;

        String body = null;
        if (!isExternalProvider()) {
            try {
                body = buildRequestBody(mode, model, tone, text, format, voice, s.voiceData());
            } catch (IOException e) {
                throw new IllegalStateException("构建 MiMo TTS 请求失败: " + e.getMessage(), e);
            }
        }

        long start = System.currentTimeMillis();
        TtsResult result = isExternalProvider()
                ? postExternalSpeech(apiKey, text, model, format, voice, start)
                : postSynthesize(apiKey, body, model, format, start);
        log.info("MiMo TTS 完成: mode={}, model={}, format={}, {} chars → {} bytes, {}ms",
                mode, model, format, text.length(), result.audio().length, result.elapsedMs());
        return result;
    }

    /** 异步合成（虚拟线程），不阻塞调用方；异常经 CompletableFuture 传播。 */
    public CompletableFuture<TtsResult> synthesizeAsync(String text, VoiceSpec spec) {
        return CompletableFuture.supplyAsync(() -> synthesize(text, spec), ttsExecutor);
    }

    // ── 请求构建 ──────────────────────────────────────────────

    private String modelFor(Mode mode) {
        switch (mode) {
            case CLONE: return cfg().getModelClone();
            case DESIGN: return cfg().getModelDesign();
            default: return cfg().getModelBasic();
        }
    }

    private boolean isExternalProvider() {
        return "openai-compatible".equalsIgnoreCase(cfg().getProvider())
                || "external".equalsIgnoreCase(cfg().getProvider());
    }

    /**
     * 构建 /chat/completions 请求体（三模式形态，已真机验证）：
     * <ul>
     *   <li>basic：audio.voice = 内置音色名；user 消息 = 语气描述；</li>
     *   <li>clone：audio.voice = 参考音频 data URL（本地路径自动转 data URL）；</li>
     *   <li>design：audio 无 voice 字段；user 消息 = 音色描述。</li>
     * </ul>
     */
    private String buildRequestBody(Mode mode, String model, String tone, String text,
                                    String format, String voice, String voiceData) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", mode == Mode.DESIGN && !isBlank(voiceData) ? voiceData : tone);

        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", text);
        root.put("messages", java.util.List.of(userMsg, assistantMsg));

        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("format", format);
        switch (mode) {
            case CLONE -> {
                String dataUrl = toDataUrl(voiceData);
                audio.put("voice", dataUrl);
            }
            case BASIC -> audio.put("voice", voice);
            case DESIGN -> { /* design：无 voice 字段 */ }
        }
        root.put("audio", audio);

        return mapper.writeValueAsString(root);
    }

    /**
     * 参考音频 → 受控 data URL。服务端不再读取调用方提供的任意本地路径；
     * 只接受上传层产生的 wav/mp3 data URL，并重新校验大小、格式和时长。
     */
    String toDataUrl(String voiceData) throws IOException {
        if (isBlank(voiceData)) {
            throw new IllegalArgumentException("clone 模式需要上传参考音频");
        }
        if (!voiceData.startsWith("data:")) {
            throw new IllegalArgumentException("voice_data 必须是上传得到的 wav/mp3 data URL，不接受服务器文件路径");
        }
        int comma = voiceData.indexOf(',');
        if (comma <= 5 || comma == voiceData.length() - 1) {
            throw new IllegalArgumentException("参考音频 data URL 格式无效");
        }
        String header = voiceData.substring(0, comma).toLowerCase(java.util.Locale.ROOT);
        String mime;
        if (header.equals("data:audio/wav;base64") || header.equals("data:audio/x-wav;base64")) {
            mime = "audio/wav";
        } else if (header.equals("data:audio/mpeg;base64")) {
            mime = "audio/mpeg";
        } else {
            throw new IllegalArgumentException("仅支持 wav / mp3 音频文件");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(voiceData.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("参考音频 base64 内容无效", e);
        }
        if (bytes.length == 0 || bytes.length > MAX_CLONE_AUDIO_BYTES) {
            throw new IllegalArgumentException("参考音频大小必须在 1B 至 10MB 之间");
        }
        double seconds = audioDurationSeconds(bytes, mime);
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > MAX_CLONE_AUDIO_SECONDS) {
            throw new IllegalArgumentException("参考音频时长必须在 0 至 10 秒以内（当前约 "
                    + String.format(java.util.Locale.ROOT, "%.2f", seconds) + " 秒）");
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** 解析 WAV/常见 MPEG Layer III 时长；无法可靠解析时拒绝，避免绕过 10 秒限制。 */
    static double audioDurationSeconds(byte[] bytes, String mime) {
        return "audio/wav".equals(mime) ? wavDurationSeconds(bytes) : mp3DurationSeconds(bytes);
    }

    private static double wavDurationSeconds(byte[] b) {
        if (b.length < 44 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WAVE")) return -1;
        int p = 12, byteRate = 0, dataSize = 0;
        while (p + 8 <= b.length) {
            int size = leInt(b, p + 4);
            if (size < 0 || p + 8L + size > b.length) break;
            if (ascii(b, p, "fmt ") && size >= 16) byteRate = leInt(b, p + 12);
            if (ascii(b, p, "data")) { dataSize = size; break; }
            p += 8 + size + (size & 1);
        }
        return byteRate > 0 && dataSize > 0 ? dataSize / (double) byteRate : -1;
    }

    private static double mp3DurationSeconds(byte[] b) {
        int p = 0;
        if (b.length >= 10 && ascii(b, 0, "ID3")) {
            int tag = ((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14)
                    | ((b[8] & 0x7F) << 7) | (b[9] & 0x7F);
            p = Math.min(b.length, 10 + tag);
        }
        long samples = 0;
        int sampleRate = 0;
        int frames = 0;
        while (p + 4 <= b.length && frames < 20000) {
            int h = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                    | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            if ((h & 0xFFE00000) != 0xFFE00000) { p++; continue; }
            int version = (h >>> 19) & 3, layer = (h >>> 17) & 3;
            int bitrateIndex = (h >>> 12) & 15, rateIndex = (h >>> 10) & 3;
            int padding = (h >>> 9) & 1;
            if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) { p++; continue; }
            int kbps = mp3Bitrate(version, layer, bitrateIndex);
            int rate = mp3Rate(version, rateIndex);
            if (kbps <= 0 || rate <= 0) { p++; continue; }
            int layerNum = 4 - layer;
            int frameLen = layerNum == 1 ? (12 * kbps * 1000 / rate + padding) * 4
                    : ((version == 3 || layerNum != 3 ? 144 : 72) * kbps * 1000 / rate + padding);
            if (frameLen <= 0 || p + frameLen > b.length) break;
            samples += layerNum == 1 ? 384 : (layerNum == 3 && version != 3 ? 576 : 1152);
            sampleRate = rate; frames++; p += frameLen;
        }
        return frames > 0 && sampleRate > 0 ? samples / (double) sampleRate : -1;
    }

    private static int mp3Rate(int version, int index) {
        int[][] rates = {{11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}};
        return rates[version][index];
    }

    private static int mp3Bitrate(int version, int layer, int index) {
        int[][] mpeg1 = {
                {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},
                {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
                {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}};
        int[][] mpeg2 = {
                {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
                {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
                {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};
        return (version == 3 ? mpeg1 : mpeg2)[3 - layer][index];
    }

    private static boolean ascii(byte[] b, int offset, String s) {
        if (offset < 0 || offset + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[offset + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    private static int leInt(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | ((b[p + 3] & 255) << 24);
    }

    // ── HTTP 调用 ─────────────────────────────────────────────

    private TtsResult postSynthesize(String apiKey, String body, String model, String format, long start) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg().getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(1, cfg().getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MiMo TTS 请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("MiMo TTS 请求失败: " + e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - start;
        if (response.statusCode() != 200) {
            String excerpt = response.body() == null ? "" : response.body();
            if (excerpt.length() > 300) excerpt = excerpt.substring(0, 300);
            throw new IllegalStateException("MiMo TTS HTTP " + response.statusCode() + ": " + excerpt);
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode audioNode = root.path("choices").path(0).path("message").path("audio");
            String data = audioNode.path("data").asText("");
            if (data.isEmpty()) {
                throw new IllegalStateException("MiMo TTS 响应无 audio.data（choices 为空或字段缺失）: "
                        + excerpt(response.body()));
            }
            byte[] audio = Base64.getDecoder().decode(data);
            String transcript = audioNode.path("transcript").isMissingNode()
                    ? null : audioNode.path("transcript").asText(null);
            return new TtsResult(audio, format, transcript, model, elapsed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MiMo TTS 响应 audio.data 非合法 base64: " + excerpt(response.body()), e);
        } catch (IOException e) {
            throw new IllegalStateException("MiMo TTS 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** OpenAI-compatible TTS：POST /audio/speech，响应为音频二进制。 */
    private TtsResult postExternalSpeech(String apiKey, String text, String model, String format,
                                          String voice, long start) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("model", model);
            root.put("input", text);
            root.put("voice", isBlank(voice) ? cfg().getDefaultVoice() : voice);
            root.put("response_format", format);
            String endpoint = cfg().getSpeechEndpoint();
            if (isBlank(endpoint)) endpoint = "/audio/speech";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(joinUrl(cfg().getBaseUrl(), endpoint)))
                    .timeout(Duration.ofSeconds(Math.max(1, cfg().getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long elapsed = System.currentTimeMillis() - start;
            if (response.statusCode() != 200) {
                String detail = new String(response.body(), StandardCharsets.UTF_8);
                throw new IllegalStateException("外部 TTS HTTP " + response.statusCode() + ": " + excerpt(detail));
            }
            return new TtsResult(response.body(), format, text, model, elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("外部 TTS 请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("外部 TTS 请求失败: " + e.getMessage(), e);
        }
    }

    private static String joinUrl(String base, String path) {
        String b = base == null ? "" : base.replaceAll("/+$", "");
        String p = path == null ? "" : path.trim();
        return b + (p.startsWith("/") ? p : "/" + p);
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ── 诊断（不暴露 key） ─────────────────────────────────────

    /** 运行时状态摘要（不含 apiKey）。 */
    public Map<String, Object> statusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", cfg().getProvider());
        m.put("enabled", isEnabled());
        m.put("configured", resolveApiKey() != null);
        m.put("base_url", cfg().getBaseUrl());
        m.put("speech_endpoint", cfg().getSpeechEndpoint());
        m.put("models", Map.of(
                "basic", cfg().getModelBasic(),
                "clone", cfg().getModelClone(),
                "design", cfg().getModelDesign()));
        m.put("default_voice", cfg().getDefaultVoice());
        m.put("default_tone", cfg().getDefaultTone());
        m.put("builtin_voices", builtinVoices());
        return m;
    }

    /** 内置音色清单（逗号分隔配置 → List）。 */
    public java.util.List<String> builtinVoices() {
        String raw = cfg().getBuiltinVoices();
        if (isBlank(raw)) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String v : raw.split(",")) {
            if (!v.isBlank()) out.add(v.trim());
        }
        return out;
    }

    /** 关闭本服务拥有的两个虚拟线程执行器。 */
    @PreDestroy
    public void shutdown() {
        ttsExecutor.shutdown();
        httpExecutor.shutdown();
        try {
            if (!ttsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ttsExecutor.shutdownNow();
            }
            if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ttsExecutor.shutdownNow();
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
