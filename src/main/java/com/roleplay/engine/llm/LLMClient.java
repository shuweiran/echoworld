package com.roleplay.engine.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TaskCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Unified LLM client — single HTTP connection pool for all Agent + Arbiter calls.
 *
 * <p>Consolidates retry logic, JSON parsing, and cost tracking.
 * Unlike Python's httpx + OpenAI SDK, this uses Java's built-in
 * {@link HttpClient} with Virtual Thread-friendly blocking calls.
 *
 * <p>Maps from Python {@code services/llm_client.py → LLMClient}.
 */
@Service
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);

    private final AppConfig appConfig;
    private final int timeoutSeconds;
    private final String fallbackModel;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public LLMClient(AppConfig appConfig) {
        this.appConfig = appConfig;
        // D20: api_base / model 不再构造期固定 —— 每次请求时读取 AppConfig（
        // 运行时 POST /api/config/apikey 设置的 api_base/model 立即生效，重启丢失）
        this.timeoutSeconds = appConfig.getMonitor().getTimeoutSeconds();
        this.fallbackModel = appConfig.getMonitor().getFallbackModel();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 运行时 api_base（D20：支持运行时配置热更新，见 ConfigController）。 */
    private String apiBase() { return appConfig.getLlm().getApiBase(); }

    /** 运行时默认模型（D20）。 */
    private String defaultModel() { return appConfig.getLlm().getModel(); }

    /**
     * 归一化 chat/completions 端点（D20）：容忍 api_base 传全路径
     * （…/chat/completions）、带 /v1（…/v1）或不带 /v1 三种写法。
     */
    private String chatEndpoint() {
        String base = apiBase();
        if (base == null || base.isBlank()) base = "https://api.deepseek.com";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        if (b.endsWith("/v1")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
    }

    // ── Sync call （for Virtual Threads） ────────────────────────

    /**
     * Call the LLM with retry logic （2 models × 2 retries）.
     * This is a BLOCKING call — designed to run in a Virtual Thread.
     */
    public String callSync(List<Message> messages) {
        return callSyncInternal(messages, defaultModel(), 300, 0.7, null);
    }

    /**
     * D1: 可中断调用 —— 携带 {@link CancellationToken}（需求文档第八条 §五）。
     *
     * <p>取消信号在三个检查点生效：每次尝试前 / HTTP 响应返回后解析前 / 重试等待前。
     * 已取消时抛 {@link TaskCancelledException}（不重试）；线程被中断（硬停止的
     * future.cancel(true)）也会立即 abort 进行中的 HTTP 调用并上抛取消信号。
     */
    public String callSync(List<Message> messages, CancellationToken token) {
        return callSyncInternal(messages, defaultModel(), 300, 0.7, token);
    }

    public String callSync(List<Message> messages, String modelOverride,
                           int maxTokens, double temperature) {
        return callSyncInternal(messages, modelOverride, maxTokens, temperature, null);
    }

    private String callSyncInternal(List<Message> messages, String modelOverride,
                                    int maxTokens, double temperature,
                                    CancellationToken token) {

        String[] modelsToTry = {modelOverride, fallbackModel};
        Set<String> seen = new LinkedHashSet<>(Arrays.asList(modelsToTry));

        Exception lastError = null;

        for (String currentModel : seen) {
            for (int retry = 0; retry < 2; retry++) {
                // 检查点 1：每次尝试前（取消 → 立即中断，不发起新请求）
                if (token != null) token.checkpoint();
                try {
                    String requestBody = buildChatRequest(messages, currentModel, maxTokens, temperature);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(chatEndpoint()))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + appConfig.getLlm().getApiKey())
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

                    HttpResponse<String> response = httpClient.send(request,
                            HttpResponse.BodyHandlers.ofString());

                    // 检查点 2：响应返回后、解析前（软停止：回复已完整生成但未提交）
                    if (token != null) token.checkpoint();

                    if (response.statusCode() == 200) {
                        return parseResponse(response.body());
                    } else {
                        lastError = new RuntimeException(
                                "HTTP " + response.statusCode() + ": " + response.body());
                        log.warn("LLM call failed (attempt {}/2, model {}): {}",
                                retry + 1, currentModel, response.statusCode());
                    }
                } catch (TaskCancelledException e) {
                    // 取消信号直接上抛，不做重试
                    throw e;
                } catch (InterruptedException ie) {
                    // 线程被中断（硬停止/状态停止的 future.cancel(true) abort 了 HTTP 调用）
                    Thread.currentThread().interrupt();
                    if (token != null && token.isCancelled()) {
                        throw new TaskCancelledException(token.getStopType(), token.getReason(), ie);
                    }
                    throw new TaskCancelledException(StopType.HARD, "LLM 调用线程被中断", ie);
                } catch (Exception e) {
                    if (token != null && token.isCancelled()) {
                        throw new TaskCancelledException(token.getStopType(), token.getReason(), e);
                    }
                    lastError = e;
                    log.warn("LLM call exception (attempt {}/2, model {}): {}",
                            retry + 1, currentModel, e.getMessage());
                }

                // Wait before retry
                if (retry == 0) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        if (token != null && token.isCancelled()) {
                            throw new TaskCancelledException(token.getStopType(), token.getReason(), ie);
                        }
                        throw new TaskCancelledException(StopType.HARD, "LLM 重试等待被中断", ie);
                    }
                }
            }
        }

        throw new RuntimeException("LLM call failed after all retries: " +
                (lastError != null ? lastError.getMessage() : "unknown"));
    }

    /**
     * Async variant — returns CompletableFuture.
     */
    public CompletableFuture<String> callAsync(List<Message> messages) {
        return CompletableFuture.supplyAsync(() -> callSync(messages));
    }

    /**
     * Simple text generation — no JSON parsing, returns raw text.
     * Uses BLOCKING call (designed for Virtual Threads).
     */
    public String callSimple(String prompt, int maxTokens) {
        Message sysMsg = new Message(Message.Role.SYSTEM, "system",
                "你是一个角色扮演主控，回复简洁的叙事旁白。");
        Message userMsg = new Message(Message.Role.USER, "user", prompt);
        try {
            return callSync(List.of(sysMsg, userMsg), defaultModel(), maxTokens, 0.1);
        } catch (Exception e) {
            log.warn("callSimple failed: {}", e.getMessage());
            return null;
        }
    }

    // ── JSON mode （structured output） ──────────────────────────

    /**
     * Call LLM and parse response as JSON.
     * Includes fuzzy extraction: strips markdown fences, extracts first {…}.
     */
    public Map<String, Object> callJson(String prompt, int maxTokens) {
        Message sysMsg = new Message(Message.Role.SYSTEM, "system",
                "你是一个角色扮演主控（DM）。必须严格按照要求的JSON格式回复。");
        Message userMsg = new Message(Message.Role.USER, "user", prompt);

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String content = callSync(List.of(sysMsg, userMsg), defaultModel(), maxTokens, 0.1);
                String json = extractJson(content);
                if (json != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = mapper.readValue(json, Map.class);
                    return result;
                }
            } catch (Exception e) {
                log.warn("callJson attempt {}/3 failed: {}", attempt + 1, e.getMessage());
            }
        }
        return Map.of();
    }

    // ── Internal helpers ───────────────────────────────────────

    private String buildChatRequest(List<Message> messages, String modelName,
                                    int maxTokens, double temperature)
            throws JsonProcessingException {
        return buildChatRequest(messages, modelName, maxTokens, temperature, false);
    }

    private String buildChatRequest(List<Message> messages, String modelName,
                                    int maxTokens, double temperature, boolean stream)
            throws JsonProcessingException {

        List<Map<String, String>> msgList = new ArrayList<>();
        for (Message m : messages) {
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", switch (m.getRole()) {
                case SYSTEM -> "system";
                case USER -> "user";
                default -> "assistant";
            });
            msg.put("content", "[" + m.getName() + "] " + m.getContent());
            msgList.add(msg);
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("messages", msgList);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        if (stream) requestBody.put("stream", true);

        return mapper.writeValueAsString(requestBody);
    }

    // ── Streaming call （P-0802-M：LLM 边生成边推送增量） ─────────────

    /**
     * 流式调用 —— stream=true，解析 OpenAI 兼容 SSE 增量（data: {…choices[].delta.content}），
     * 每收到一个内容片即回调 {@code onDelta}（可为 null），返回完整文本（与 {@link #callSync} 同语义，
     * 调用方无需感知流式差异）。
     *
     * <p>重试规则：仅当尚未发出任何增量时重试（已发出增量后出错 → 直接上抛，由调用方降级
     * 非流式完整调用，避免前端已渲染的增量被重复追加）。
     *
     * <p>兼容兜底：若服务端忽略 stream 参数返回普通 JSON（如测试 mock），整段内容作为单个增量回调。
     */
    public String callStream(List<Message> messages, CancellationToken token,
                             java.util.function.Consumer<String> onDelta) {
        String[] modelsToTry = {defaultModel(), fallbackModel};
        Set<String> seen = new LinkedHashSet<>(Arrays.asList(modelsToTry));
        Exception lastError = null;

        for (String currentModel : seen) {
            for (int retry = 0; retry < 2; retry++) {
                // 检查点：每次尝试前（取消 → 立即中断，不发起新请求）
                if (token != null) token.checkpoint();
                try {
                    String requestBody = buildChatRequest(messages, currentModel, 300, 0.7, true);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(chatEndpoint()))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + appConfig.getLlm().getApiKey())
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

                    HttpResponse<java.io.InputStream> response = httpClient.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (token != null) token.checkpoint();

                    if (response.statusCode() == 200) {
                        // 已发出的增量片数（用于重试判断：有增量后再出错不重试，避免前端重复追加）
                        int[] emitted = {0};
                        try {
                            return parseStreamResponse(response.body(), token, onDelta, emitted);
                        } catch (Exception e) {
                            if (emitted[0] > 0) {
                                // 部分增量已发出 → 上抛，调用方降级非流式完整调用（内容不丢）
                                throw e;
                            }
                            lastError = e;
                            log.warn("LLM stream parse failed (attempt {}/2, model {}): {}",
                                    retry + 1, currentModel, e.getMessage());
                        }
                    } else {
                        lastError = new RuntimeException(
                                "HTTP " + response.statusCode() + ": "
                                        + new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        log.warn("LLM stream call failed (attempt {}/2, model {}): {}",
                                retry + 1, currentModel, response.statusCode());
                    }
                } catch (TaskCancelledException e) {
                    // 取消信号直接上抛，不做重试
                    throw e;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (token != null && token.isCancelled()) {
                        throw new TaskCancelledException(token.getStopType(), token.getReason(), ie);
                    }
                    throw new TaskCancelledException(StopType.HARD, "LLM 流式调用线程被中断", ie);
                } catch (Exception e) {
                    if (token != null && token.isCancelled()) {
                        throw new TaskCancelledException(token.getStopType(), token.getReason(), e);
                    }
                    lastError = e;
                    log.warn("LLM stream call exception (attempt {}/2, model {}): {}",
                            retry + 1, currentModel, e.getMessage());
                }

                // Wait before retry
                if (retry == 0) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        if (token != null && token.isCancelled()) {
                            throw new TaskCancelledException(token.getStopType(), token.getReason(), ie);
                        }
                        throw new TaskCancelledException(StopType.HARD, "LLM 流式重试等待被中断", ie);
                    }
                }
            }
        }

        throw new RuntimeException("LLM stream call failed after all retries: " +
                (lastError != null ? lastError.getMessage() : "unknown"));
    }

    /**
     * 解析 stream=true 响应体（SSE 格式，OpenAI/DeepSeek 兼容）：
     * <pre>data: {"choices":[{"delta":{"content":"…"}}]}</pre>
     * 逐行解析，每行一个内容增量回调；遇 {@code data: [DONE]} 结束。
     *
     * <p>兼容兜底：首行为非 {@code data:} 前缀的普通 JSON（mock/忽略 stream 的服务）→
     * 整段重组按普通 chat/completions JSON 解析，内容作为单个增量回调。
     */
    private String parseStreamResponse(java.io.InputStream body, CancellationToken token,
                                       java.util.function.Consumer<String> onDelta, int[] emitted)
            throws Exception {
        StringBuilder full = new StringBuilder();
        StringBuilder plainBlock = null; // 非 SSE 时重组整个 body
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (token != null) token.checkpoint(); // 流式读取期间周期检查点（取消立即中断）
                if (line.isBlank()) continue;
                if (!line.startsWith("data:")) {
                    // 非 SSE 响应（测试 mock / 忽略 stream 参数的服务）：重组为普通 JSON
                    if (plainBlock == null) plainBlock = new StringBuilder(line);
                    else plainBlock.append('\n').append(line);
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                try {
                    JsonNode node = mapper.readTree(data);
                    JsonNode delta = node.path("choices").get(0).path("delta").path("content");
                    if (delta != null && !delta.isMissingNode()) {
                        String d = delta.asText("");
                        if (!d.isEmpty()) {
                            full.append(d);
                            emitted[0]++;
                            if (onDelta != null) onDelta.accept(d);
                        }
                    }
                } catch (Exception e) {
                    // 个别 data 行解析失败不中断（容错继续，完整内容以最终拼接为准）
                    log.warn("LLM stream line parse failed: {}", e.getMessage());
                }
            }
        }
        if (plainBlock != null && full.length() == 0) {
            // 整段非 SSE → 按普通 chat/completions JSON 解析
            JsonNode root = mapper.readTree(plainBlock.toString());
            String content = root.path("choices").get(0).path("message").path("content").asText("");
            if (!content.isEmpty()) {
                emitted[0]++;
                if (onDelta != null) onDelta.accept(content);
                return content;
            }
            return "";
        }
        return full.toString();
    }

    private String parseResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        return root.path("choices").get(0).path("message").path("content").asText("");
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;

        // Remove markdown fences
        String cleaned = text;
        if (cleaned.contains("```json")) {
            cleaned = cleaned.split("```json")[1].split("```")[0].trim();
        } else if (cleaned.contains("```")) {
            cleaned = cleaned.split("```")[1].split("```")[0].trim();
        }

        // Find first { to last }
        int start = cleaned.indexOf("{");
        int end = cleaned.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return null;
    }
}
