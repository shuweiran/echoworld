package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Script murder mystery game — investigation and deduction.
 * Maps from Python core/script_runtime.py.
 *
 * <p>批次 C1：唯一剧本生成路径。输出剧本数据模型 Schema v1（对齐通用剧本杀范式
 * Chronos Script Schema v2 核心子集，见 docs/剧本-schema-v1.md）；ScriptGameService.initGame
 * 委托本服务生成，双生成器统一。
 */
@Service
public class ScriptService {

    private final LLMClient llmClient;

    public ScriptService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 生成谋杀之谜剧本（Schema v1 规范结构）。
     *
     * <p>宽容兼容：LLM 返回 v1 格式或旧格式（roles 字符串数组 / clues 带 public /
     * 无 metadata / 无 killer_id）均可，统一归一为 v1；LLM 失败/空输出走 defaultScript 兜底
     * （A1-3：兜底仍含 secrets 且键集合==roles）。
     *
     * @param theme      剧本主题
     * @param characters 玩家名单（决定角色数/兜底角色）
     */
    public Map<String, Object> generateScript(String theme, List<String> characters) {
        List<String> players = characters == null ? List.of() : characters;
        String prompt = ScriptSchemaV1.buildPrompt(theme, players.size());
        Map<String, Object> raw = llmClient.callJson(prompt, 600);
        if (raw == null || raw.isEmpty()) {
            raw = ScriptSchemaV1.defaultScript(theme, players);
        }
        return ScriptSchemaV1.normalize(raw, players, theme);
    }
}
