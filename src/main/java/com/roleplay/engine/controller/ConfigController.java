package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.aiimage.AiImageProperties;
import com.roleplay.engine.aiimage.ImageGenService;
import com.roleplay.engine.service.MimoTtsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration endpoints — API Key, language, models, voice config.
 * Maps from Python api/routes_config.py.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final AppConfig appConfig;
    private final AiImageProperties imageProperties;
    private final MimoTtsService ttsService;
    private final ImageGenService imageService;
    private final ObjectMapper mapper = new ObjectMapper();
    // 延迟创建：纯本地测试/启动不应因 JDK 网络选择器初始化失败而影响配置页。
    private volatile HttpClient modelHttp;

    // In-memory config overrides (replaces api_key.json)
    private final Map<String, Object> runtimeConfig = new ConcurrentHashMap<>();

    public ConfigController(AppConfig appConfig) {
        this(appConfig, null, null);
    }

    public ConfigController(AppConfig appConfig, AiImageProperties imageProperties, MimoTtsService ttsService) {
        this(appConfig, imageProperties, ttsService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConfigController(AppConfig appConfig, AiImageProperties imageProperties, MimoTtsService ttsService,
                            ImageGenService imageService) {
        this.appConfig = appConfig;
        this.imageProperties = imageProperties;
        this.ttsService = ttsService;
        this.imageService = imageService;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return key;
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    @GetMapping("/apikey")
    public ResponseEntity<Map<String, Object>> getApiKey() {
        String key = runtimeConfig.containsKey("api_key")
            ? (String) runtimeConfig.get("api_key")
            : appConfig.getLlm().getApiKey();
        String apiBase = runtimeConfig.containsKey("api_base")
            ? (String) runtimeConfig.get("api_base")
            : appConfig.getLlm().getApiBase();
        String model = runtimeConfig.containsKey("model")
            ? (String) runtimeConfig.get("model")
            : appConfig.getLlm().getModel();
        return ResponseEntity.ok(Map.of(
            "api_key", maskKey(key),
            "configured", !key.isEmpty(),
            "api_base", apiBase,
            "model", model));
    }

    /**
     * 设置 LLM 配置（D20 修复）。
     *
     * <p>完整保存 api_key / api_base / model 到 {@link AppConfig.LLMConfig}；
     * LLMClient 每次请求时读取该配置 → 运行时立即生效（重启丢失，README 已说明）。
     * api_key 允许传空串清除；api_base / model 为空时保持现值，避免误清。
     */
    @PostMapping("/apikey")
    public ResponseEntity<Void> setApiKey(@RequestBody Map<String, String> body) {
        String apiKey = body.getOrDefault("api_key", "");
        String apiBase = body.getOrDefault("api_base", "");
        String model = body.getOrDefault("model", "");

        runtimeConfig.put("api_key", apiKey);
        appConfig.getLlm().setApiKey(apiKey);
        if (apiBase != null && !apiBase.isBlank()) {
            runtimeConfig.put("api_base", apiBase);
            appConfig.getLlm().setApiBase(apiBase.trim());
        }
        if (model != null && !model.isBlank()) {
            runtimeConfig.put("model", model);
            appConfig.getLlm().setModel(model.trim());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 三类外部能力的统一配置读写入口。GET 永远只返回密钥是否已配置与掩码，
     * POST 只更新请求中出现的字段，配置立即作用于后续请求（重启后回到环境变量/YAML）。
     */
    @GetMapping("/integrations")
    public ResponseEntity<Map<String, Object>> getIntegrations() {
        Map<String, Object> out = new LinkedHashMap<>();
        AppConfig.LLMConfig llm = appConfig.getLlm();
        AppConfig.MapLlmConfig map = appConfig.getMapLlm();
        AppConfig.ArbiterLlmConfig arbiter = appConfig.getArbiterLlm();
        Map<String, Object> llmView = providerView(llm.getApiBase(), llm.getModel(), llm.getApiKey());
        llmView.put("dialogue_model", llm.getDialogueModel());
        llmView.put("planner_model", llm.getPlannerModel());
        out.put("llm", llmView);
        out.put("arbiter_llm", providerView(arbiter.getApiBase(), arbiter.getModel(), arbiter.getApiKey()));
        out.put("map_llm", providerView(map.getBaseUrl(), map.getModel(), map.getApiKey()));
        if (ttsService != null) out.put("tts", ttsService.statusMap());
        else out.put("tts", providerView(appConfig.getTts().getMimo().getBaseUrl(),
                appConfig.getTts().getMimo().getModelBasic(), appConfig.getTts().getMimo().getApiKey()));
        if (imageProperties != null) {
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("provider", imageProperties.getProvider());
            image.put("base_url", imageProperties.getComfyuiBaseUrl());
            image.put("external_base_url", imageProperties.getExternalBaseUrl());
            image.put("external_model", imageProperties.getExternalModel());
            image.put("external_endpoint", imageProperties.getExternalEndpoint());
            image.put("lora_name", imageProperties.getLoraName());
            image.put("rmbg_enabled", imageProperties.isRmbgEnabled());
            image.put("img2img_denoise", imageProperties.getImg2imgDenoise());
            image.put("configured", imageProperties.getComfyuiBaseUrl() != null && !imageProperties.getComfyuiBaseUrl().isBlank());
            out.put("image", image);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/integrations")
    public ResponseEntity<Void> setIntegrations(@RequestBody Map<String, Object> body) {
        updateLlm(body.get("llm"), appConfig.getLlm());
        updateArbiterLlm(body.get("arbiter_llm"), appConfig.getArbiterLlm());
        updateMapLlm(body.get("map_llm"), appConfig.getMapLlm());
        Object tts = body.get("tts");
        if (tts instanceof Map<?, ?> m) {
            AppConfig.TtsConfig.MimoConfig cfg = appConfig.getTts().getMimo();
            setString(m, "provider", cfg::setProvider, false);
            setString(m, "api_key", cfg::setApiKey, true);
            setString(m, "base_url", cfg::setBaseUrl, false);
            setString(m, "model", cfg::setModelBasic, false);
            setBoolean(m, "enabled", cfg::setEnabled);
            setString(m, "voice", cfg::setDefaultVoice, false);
        }
        Object image = body.get("image");
        if (image instanceof Map<?, ?> m && imageProperties != null) {
            setString(m, "provider", imageProperties::setProvider, false);
            setString(m, "base_url", imageProperties::setComfyuiBaseUrl, false);
            setString(m, "external_base_url", imageProperties::setExternalBaseUrl, false);
            setString(m, "external_api_key", imageProperties::setExternalApiKey, true);
            setString(m, "external_model", imageProperties::setExternalModel, false);
            setString(m, "external_endpoint", imageProperties::setExternalEndpoint, false);
            setString(m, "lora_name", imageProperties::setLoraName, false);
            if (m.containsKey("rmbg_enabled")) imageProperties.setRmbgEnabled(bool(m.get("rmbg_enabled"), imageProperties.isRmbgEnabled()));
            if (m.containsKey("img2img_denoise")) {
                double d = number(m.get("img2img_denoise"), imageProperties.getImg2imgDenoise());
                imageProperties.setImg2imgDenoise(Math.max(0, Math.min(1, d)));
            }
            if (imageService != null) imageService.applyRuntimeSettings(imageProperties);
        }
        return ResponseEntity.ok().build();
    }

    private Map<String, Object> providerView(String base, String model, String key) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("base_url", base);
        view.put("model", model);
        view.put("configured", key != null && !key.isBlank());
        view.put("api_key_masked", maskKey(key));
        return view;
    }

    private void updateLlm(Object raw, AppConfig.LLMConfig cfg) {
        if (!(raw instanceof Map<?, ?> m)) return;
        setString(m, "api_key", cfg::setApiKey, true);
        setString(m, "base_url", cfg::setApiBase, false);
        setString(m, "model", cfg::setModel, false);
        setString(m, "dialogue_model", cfg::setDialogueModel, true);
        setString(m, "planner_model", cfg::setPlannerModel, true);
        if (m.containsKey("temperature")) cfg.setTemperature(number(m.get("temperature"), cfg.getTemperature()));
        if (m.containsKey("max_tokens")) cfg.setMaxTokens((int) number(m.get("max_tokens"), cfg.getMaxTokens()));
    }

    private void updateMapLlm(Object raw, AppConfig.MapLlmConfig cfg) {
        if (!(raw instanceof Map<?, ?> m)) return;
        setString(m, "api_key", cfg::setApiKey, true);
        setString(m, "base_url", cfg::setBaseUrl, false);
        setString(m, "model", cfg::setModel, false);
    }

    private void updateArbiterLlm(Object raw, AppConfig.ArbiterLlmConfig cfg) {
        if (!(raw instanceof Map<?, ?> m)) return;
        setString(m, "api_key", cfg::setApiKey, true);
        setString(m, "base_url", cfg::setApiBase, false);
        setString(m, "model", cfg::setModel, false);
    }

    private void setString(Map<?, ?> map, String key, java.util.function.Consumer<String> setter, boolean allowEmpty) {
        if (!map.containsKey(key) || map.get(key) == null) return;
        String value = String.valueOf(map.get(key)).trim();
        if (allowEmpty || !value.isBlank()) setter.accept(value);
    }

    private void setBoolean(Map<?, ?> map, String key, java.util.function.Consumer<Boolean> setter) {
        if (map.containsKey(key)) setter.accept(bool(map.get(key), false));
    }

    private boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private double number(Object value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException e) { return fallback; }
    }

    @GetMapping("/language")
    public ResponseEntity<Map<String, String>> getLanguage() {
        return ResponseEntity.ok(Map.of("language", appConfig.getMode().getLanguage()));
    }

    @PostMapping("/language")
    public ResponseEntity<Void> setLanguage(@RequestBody Map<String, String> body) {
        appConfig.getMode().setLanguage(body.getOrDefault("language", "zh"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/models")
    public ResponseEntity<List<Map<String, String>>> getModels() {
        return ResponseEntity.ok(List.of(
            Map.of("id", "deepseek-v4-flash", "name", "DeepSeek V4 Flash"),
            Map.of("id", "deepseek-v4-pro", "name", "DeepSeek V4 Pro"),
            Map.of("id", "gpt-4o-mini", "name", "GPT-4o Mini"),
            Map.of("id", "gpt-4o", "name", "GPT-4o")
        ));
    }

    /**
     * 从用户填写的 OpenAI-compatible API 地址读取模型列表。密钥只用于本次请求，
     * 不写入响应或运行时配置；地址允许带 /v1、/v1/models 或完整 /models 路径。
     */
    @PostMapping("/models/discover")
    public ResponseEntity<Map<String, Object>> discoverModels(@RequestBody Map<String, Object> body) {
        String base = String.valueOf(body.getOrDefault("base_url", "")).trim();
        String key = String.valueOf(body.getOrDefault("api_key", "")).trim();
        try {
            URI endpoint = modelsEndpoint(base);
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET();
            if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
            HttpResponse<String> response = modelHttp().send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ResponseEntity.status(502).body(Map.of("error", "模型服务返回 HTTP " + response.statusCode()));
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root == null ? null : root.get("data");
            List<Map<String, String>> models = new ArrayList<>();
            if (data != null && data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").asText("").trim();
                    if (!id.isBlank()) models.add(Map.of("id", id, "name", item.path("name").asText(id)));
                }
            }
            models.sort(Comparator.comparing(m -> m.get("id"), String.CASE_INSENSITIVE_ORDER));
            return ResponseEntity.ok(Map.of("models", models, "endpoint", endpoint.toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "无法连接模型服务，请检查地址和密钥"));
        }
    }

    private URI modelsEndpoint(String raw) {
        if (raw.isBlank()) throw new IllegalArgumentException("请先填写 API 地址");
        URI input;
        try { input = URI.create(raw); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("API 地址格式不正确"); }
        if (!("http".equalsIgnoreCase(input.getScheme()) || "https".equalsIgnoreCase(input.getScheme()))
                || input.getHost() == null || input.getUserInfo() != null) {
            throw new IllegalArgumentException("API 地址必须是 http/https 地址");
        }
        String path = input.getPath() == null ? "" : input.getPath().replaceAll("/+$", "");
        if (path.endsWith("/chat/completions")) path = path.substring(0, path.length() - "/chat/completions".length());
        if (!path.endsWith("/models")) path += path.endsWith("/v1") ? "/models" : "/v1/models";
        try { return new URI(input.getScheme(), input.getUserInfo(), input.getHost(), input.getPort(), path, null, null); }
        catch (Exception e) { throw new IllegalArgumentException("API 地址格式不正确"); }
    }

    private HttpClient modelHttp() {
        HttpClient current = modelHttp;
        if (current == null) {
            synchronized (this) {
                current = modelHttp;
                if (current == null) {
                    current = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                            .followRedirects(HttpClient.Redirect.NEVER).build();
                    modelHttp = current;
                }
            }
        }
        return current;
    }

    @GetMapping("/voice")
    public ResponseEntity<Map<String, Object>> getVoiceConfig() {
        AppConfig.VoiceConfig vc = appConfig.getVoice();
        return ResponseEntity.ok(Map.of(
            "enabled", vc.isEnabled(),
            "engine", vc.getEngine(),
            "auto_select", vc.isAutoSelect(),
            "voice", vc.getVoice()));
    }

    /**
     * 设置语音配置（D20 修复）：落地到 {@link AppConfig.VoiceConfig}，
     * TtsService 读取同一配置 → 运行时立即生效（重启丢失，README 已说明）。
     */
    @PostMapping("/voice")
    public ResponseEntity<Void> setVoiceConfig(@RequestBody Map<String, Object> body) {
        AppConfig.VoiceConfig vc = appConfig.getVoice();
        if (body.containsKey("enabled")) {
            vc.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }
        if (body.containsKey("engine") && body.get("engine") != null) {
            vc.setEngine(String.valueOf(body.get("engine")).trim().toLowerCase());
        }
        if (body.containsKey("auto_select")) {
            vc.setAutoSelect(Boolean.TRUE.equals(body.get("auto_select")));
        }
        if (body.containsKey("voice") && body.get("voice") != null) {
            vc.setVoice(String.valueOf(body.get("voice")));
        }
        runtimeConfig.put("voice", vc);
        return ResponseEntity.ok().build();
    }
}
