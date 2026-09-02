package com.roleplay.engine.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelRequestProfileTest {

    @Test
    void deepSeekDialogueDisablesThinkingButGenericProviderGetsNoPrivateFields() {
        Map<String, Object> deepSeek = ModelRequestProfile.extras(
                "https://api.deepseek.com/v1/chat/completions", "deepseek-v4-pro",
                ModelRequestProfile.Task.DIALOGUE_RENDER);
        assertEquals(Map.of("type", "disabled"), deepSeek.get("thinking"));

        assertTrue(ModelRequestProfile.extras(
                "https://openrouter.ai/api/v1", "deepseek/deepseek-v4-pro",
                ModelRequestProfile.Task.DIALOGUE_RENDER).isEmpty());
    }

    @Test
    void deepSeekProPlanningEnablesReasoningWhileFlashKeepsItOff() {
        Map<String, Object> pro = ModelRequestProfile.extras(
                "https://api.deepseek.com", "deepseek-v4-pro", ModelRequestProfile.Task.PLANNING);
        assertEquals(Map.of("type", "enabled"), pro.get("thinking"));
        assertEquals("high", pro.get("reasoning_effort"));

        Map<String, Object> flash = ModelRequestProfile.extras(
                "https://api.deepseek.com", "deepseek-v4-flash", ModelRequestProfile.Task.PLANNING);
        assertEquals(Map.of("type", "disabled"), flash.get("thinking"));
        assertFalse(flash.containsKey("reasoning_effort"));
    }
}
