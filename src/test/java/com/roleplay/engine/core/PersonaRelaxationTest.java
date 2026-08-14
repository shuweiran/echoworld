package com.roleplay.engine.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0813-B：角色卡约束松绑（Persona 措辞与示例处理）。
 *
 * <p>覆盖：① layer0 标题措辞松绑（「不可违背最高优先级」→「核心行为准则…可随剧情合理演变」）；
 * ② 完整版 sampleLines 前加防复读指令行（「禁止逐字复述」）且示例最多输出 3 条（存量数据不动，仅渲染截断）；
 * ③ 轻量版不再输出 sampleLines 原句（只保留其他表达风格字段）；④ 完整版与轻量版末尾均追加正向收尾指令
 * （允许即兴/不完美回应，不必重复固定动作或口头禅）；⑤ 旧 4 字段回退路径不受影响（无行动收尾、无防复读指令）。
 */
class PersonaRelaxationTest {

    /** 五层卡（6 条示例 —— 验证渲染截断到 3 条）。 */
    private Persona newFiveLayerPersona() {
        Persona p = new Persona("小铃");
        p.setLayers(Map.of(
                "layer0", List.of("当客人说咖啡不好喝时，先道歉再重做，不辩解。", "第二铁律", "第三铁律"),
                "layer1", Map.of("identity", "咖啡馆店主"),
                "layer2", Map.of("catchphrases", List.of("「欢迎回来」"),
                        "sentenceStyle", "短句为主",
                        "sampleLines", List.of("示例一", "示例二", "示例三", "示例四", "示例五", "示例六")),
                "layer3", Map.of("care", "用行动表达"),
                "layer4", Map.of("triggers", List.of("被质疑咖啡"))
        ));
        return p;
    }

    // ── ① layer0 标题措辞松绑 ───────────────────────────────────

    @Test
    @DisplayName("① layer0 标题改为「核心行为准则」措辞（不再写死「不可违背最高优先级」）")
    void layer0TitleSoftened() {
        String prompt = newFiveLayerPersona().buildSystemPrompt();
        assertTrue(prompt.contains("【Layer 0 核心行为准则（自然融入对话，可随剧情合理演变，但不得违背核心身份）】"),
                "layer0 新标题（正向措辞 + 允许合理演变）");
        assertFalse(prompt.contains("不可违背，最高优先级"), "不再出现旧「不可违背最高优先级」标签");
        assertTrue(prompt.contains("当客人说咖啡不好喝时，先道歉再重做，不辩解。"), "layer0 规则内容保留");
    }

    // ── ② 完整版：防复读指令 + 示例截断 3 条 ─────────────────────

    @Test
    @DisplayName("② 完整版 sampleLines 前加防复读指令行（禁止逐字复述）")
    void fullPromptHasAntiRepeatInstruction() {
        String prompt = newFiveLayerPersona().buildSystemPrompt();
        assertTrue(prompt.contains("使用提示：以下示例仅提示语气节奏与说话习惯，禁止逐字复述，对话中应结合当下情境即兴发挥。"),
                "示例区块前有防复读指令行");
        assertTrue(prompt.contains("原话示例："), "原话示例标签保留");
    }

    @Test
    @DisplayName("②b 完整版示例最多输出 3 条（6 条存量数据渲染截断，第 4 条起不出现）")
    void fullPromptSamplesCappedAtThree() {
        String prompt = newFiveLayerPersona().buildSystemPrompt();
        assertTrue(prompt.contains("示例一") && prompt.contains("示例二") && prompt.contains("示例三"),
                "前 3 条示例输出");
        assertFalse(prompt.contains("示例四"), "第 4 条示例被截断（存量数据不动，仅渲染截断）");
        assertFalse(prompt.contains("示例五"));
        assertFalse(prompt.contains("示例六"));
    }

    // ── ③ 轻量版：不含 sampleLines 原句 ─────────────────────────

    @Test
    @DisplayName("③ 轻量版不再输出 sampleLines 原句（只保留其他表达风格字段）")
    void lightweightDropsSampleLines() {
        String light = newFiveLayerPersona().buildLightweightPrompt();
        assertTrue(light.contains("【说话风格】"), "Layer2 风格段保留");
        assertTrue(light.contains("「欢迎回来」"), "口头禅（其他表达风格字段）保留");
        assertTrue(light.contains("短句为主"), "句长与句式保留");
        assertFalse(light.contains("示例一"), "轻量版不含 sampleLines 原句");
        assertFalse(light.contains("原话示例"), "轻量版不含「原话示例」区块");
    }

    // ── ④ 正向收尾指令（完整版 + 轻量版） ───────────────────────

    @Test
    @DisplayName("④ 完整版与轻量版末尾均追加正向收尾指令（允许即兴/不完美，不必重复固定动作或口头禅）")
    void positiveClosureInBothTemplates() {
        String full = newFiveLayerPersona().buildSystemPrompt();
        String light = newFiveLayerPersona().buildLightweightPrompt();

        String closureMarker = "基于以上人设自然行动：说话结合当下场景与对象，允许情绪波动、即兴发挥与不完美回应；"
                + "风格可随情境变化，不必重复固定动作或口头禅。";
        assertTrue(full.contains("【行动收尾】"), "完整版含行动收尾段");
        assertTrue(full.contains(closureMarker), "完整版含正向收尾指令原文");
        assertTrue(light.contains("【行动收尾】"), "轻量版含行动收尾段");
        assertTrue(light.contains(closureMarker), "轻量版含正向收尾指令原文");
    }

    // ── ⑤ 旧 4 字段回退路径不受影响 ─────────────────────────────

    @Test
    @DisplayName("⑤ 旧 4 字段回退路径保持不动（无行动收尾、无防复读指令、无 layer0 新标题）")
    void legacyFallbackUntouched() {
        Persona p = new Persona("小明", "开朗外向的年轻人");
        p.setVoice("说话轻快");
        p.setBackground("程序员");

        String prompt = p.buildSystemPrompt();
        assertTrue(prompt.contains("【人格设定】"), "旧格式路径正常");
        assertFalse(prompt.contains("【行动收尾】"), "旧格式路径不加正向收尾（保持不动）");
        assertFalse(prompt.contains("禁止逐字复述"), "旧格式路径无防复读指令（保持不动）");
        assertFalse(prompt.contains("【Layer 0"), "旧格式路径无五层段");

        String light = p.buildLightweightPrompt();
        assertFalse(light.contains("【行动收尾】"), "旧格式轻量路径同样保持不动");
    }
}
