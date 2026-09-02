package com.roleplay.engine.llm;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将供应商私有请求字段隔离在 OpenAI-compatible 传输层边缘。 */
final class ModelRequestProfile {

    enum Task { DIALOGUE_RENDER, PLANNING }

    private ModelRequestProfile() { }

    static Map<String, Object> extras(String apiBase, String model, Task task) {
        if (!isOfficialDeepSeek(apiBase) || model == null
                || !model.toLowerCase().startsWith("deepseek-v4")) {
            return Map.of();
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        boolean reasoning = task == Task.PLANNING && !model.toLowerCase().contains("flash");
        extras.put("thinking", Map.of("type", reasoning ? "enabled" : "disabled"));
        if (reasoning) extras.put("reasoning_effort", "high");
        return extras;
    }

    private static boolean isOfficialDeepSeek(String apiBase) {
        if (apiBase == null || apiBase.isBlank()) return false;
        try {
            return "api.deepseek.com".equalsIgnoreCase(URI.create(apiBase.trim()).getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
