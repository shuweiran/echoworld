package com.roleplay.engine.controller;

import com.roleplay.engine.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigControllerArbiterLlmTest {

    @Test
    void integrationConfigSeparatelyUpdatesAndMasksArbiterProvider() {
        AppConfig config = new AppConfig();
        ConfigController controller = new ConfigController(config);

        controller.setIntegrations(Map.of("arbiter_llm", Map.of(
                "api_key", "arbiter-secret-key",
                "base_url", "https://arbiter.example/v1",
                "model", "logic-large")));

        assertEquals("arbiter-secret-key", config.getArbiterLlm().getApiKey());
        assertEquals("https://arbiter.example/v1", config.getArbiterLlm().getApiBase());
        assertEquals("logic-large", config.getArbiterLlm().getModel());

        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) controller.getIntegrations().getBody().get("arbiter_llm");
        assertTrue((Boolean) view.get("configured"));
        assertEquals("arbi...-key", view.get("api_key_masked"));
        assertFalse(String.valueOf(view).contains("arbiter-secret-key"));
    }
}
