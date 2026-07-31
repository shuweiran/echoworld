package com.roleplay.engine.controller;

import com.roleplay.engine.config.AppConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Configuration endpoints — API Key, language, models, voice config.
 * Maps from Python api/routes_config.py.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final AppConfig appConfig;

    // In-memory config overrides (replaces api_key.json)
    private final Map<String, Object> runtimeConfig = new HashMap<>();

    public ConfigController(AppConfig appConfig) {
        this.appConfig = appConfig;
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
