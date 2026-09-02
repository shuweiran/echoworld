package com.roleplay.engine.prompt;

import com.roleplay.engine.core.Persona;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptCompilerTest {

    @Test
    void stablePrefixDoesNotContainRuntimeState() {
        Persona persona = new Persona("小铃", "谨慎但真诚");
        String stable = PromptCompiler.stablePersona(persona);
        String dynamic = PromptCompiler.dynamicSystem("车站", "【记忆】旧约定", "weak",
                "找到钥匙", "【当前行为窗口】等待", "本轮精简");

        assertFalse(stable.contains("车站"));
        assertFalse(stable.contains("找到钥匙"));
        assertTrue(dynamic.contains("车站"));
        assertTrue(dynamic.contains("找到钥匙"));
        assertTrue(dynamic.contains("本轮精简"));
    }

    @Test
    void removesOnlyPersonaDuplicatedAtContextStart() {
        Persona persona = new Persona("小铃", "谨慎但真诚");
        String context = persona.buildLightweightPrompt() + "\n\n【场景】月台";
        assertEquals("【场景】月台", PromptCompiler.withoutRepeatedPersona(persona, context));
        assertEquals("旁白提到你是小铃", PromptCompiler.withoutRepeatedPersona(persona, "旁白提到你是小铃"));
    }
}
