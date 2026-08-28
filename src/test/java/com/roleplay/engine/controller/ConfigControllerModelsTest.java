package com.roleplay.engine.controller;

import com.roleplay.engine.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigControllerModelsTest {

    @Test
    void rejectsMissingOrUnsafeModelServiceAddress() {
        ConfigController controller = new ConfigController(new AppConfig());

        var missing = controller.discoverModels(Map.of("base_url", ""));
        assertEquals(400, missing.getStatusCode().value());
        assertTrue(String.valueOf(missing.getBody().get("error")).contains("API 地址"));

        var unsafe = controller.discoverModels(Map.of("base_url", "file:///etc/passwd"));
        assertEquals(400, unsafe.getStatusCode().value());
    }
}
