package com.roleplay.engine.service.world;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/** 一次地图生成请求；attributes 由具体地图生成器解释。 */
public record MapGenerationRequest(String idempotencyKey, Map<String, Object> attributes) {

    public MapGenerationRequest {
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
