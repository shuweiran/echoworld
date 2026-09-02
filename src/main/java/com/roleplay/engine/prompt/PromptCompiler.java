package com.roleplay.engine.prompt;

import com.roleplay.engine.core.Persona;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色提示词的单一装配点：固定人格只进入稳定 SYSTEM 前缀，场景与运行态进入动态尾部。
 */
public final class PromptCompiler {

    private PromptCompiler() { }

    public static String stablePersona(Persona persona) {
        return persona.buildSystemPrompt();
    }

    public static String dynamicSystem(String scene, String summary, String trackMode,
                                       String hiddenGoal, String schedule, String reminder) {
        List<String> blocks = new ArrayList<>();
        add(blocks, "【当前场景】\n", scene);
        addRaw(blocks, summary);
        add(blocks, "【轨道模式】\n", trackMode == null || trackMode.isBlank() ? "merged" : trackMode);
        if (hiddenGoal != null && !hiddenGoal.isBlank()) {
            blocks.add("【隐藏目标】\n你的目标：" + hiddenGoal
                    + "\n（不要主动暴露给玩家，用行为和言语自然引导剧情推进）");
        }
        addRaw(blocks, schedule);
        add(blocks, "【系统提醒】", reminder);
        return String.join("\n\n", blocks);
    }

    /** 兼容旧策略：只移除上下文开头由策略重复拼入的完整/轻量 Persona。 */
    public static String withoutRepeatedPersona(Persona persona, String context) {
        String value = context == null ? "" : context;
        for (String prefix : List.of(persona.buildSystemPrompt(), persona.buildLightweightPrompt())) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length()).stripLeading();
            }
        }
        return value;
    }

    private static void add(List<String> blocks, String label, String value) {
        if (value != null && !value.isBlank()) blocks.add(label + value);
    }

    private static void addRaw(List<String> blocks, String value) {
        if (value != null && !value.isBlank()) blocks.add(value);
    }
}
