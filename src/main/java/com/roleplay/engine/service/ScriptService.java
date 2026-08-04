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
        return generateScriptChecked(theme, characters).schema();
    }

    /**
     * P1（剧本杀可玩性修复，任务 3）：生成剧本并返回是否走了 LLM 降级兜底。
     *
     * <p>用于开局时检测 LLM key 是否可用（或无 key/LLM 失败时 generateScript 走了
     * defaultScript 兜底）——调用方（ScriptGameService.initGame）据此注入
     * gameState.llmDegraded=true，前端 ScriptStatePanel 显示「当前为离线模板模式」提示条。
     * 生成逻辑与 {@link #generateScript} 完全一致（同一调用路径），仅多返回一个降级标记；
     * 既有调用方/测试（直接调 generateScript 取 schema）零变化。
     */
    public ScriptGeneration generateScriptChecked(String theme, List<String> characters) {
        List<String> players = characters == null ? List.of() : characters;
        String prompt = ScriptSchemaV1.buildPrompt(theme, players.size());
        // P1 缺陷修复（D-023）：4 角色完整剧本 JSON（metadata+roles[]×4+clues[]+secrets+killer_id+truth）
        // 真实输出需 2000-4000 tokens，旧值 600 被硬截断 → LLM 输出 Unexpected end-of-input → 3/3 走 defaultScript 兜底。
        // 4000 远低于 DeepSeek 单次输出建议上限 8192，且仅剧本生成这类大 JSON 调用使用。
        Map<String, Object> raw = llmClient.callJson(prompt, 4000);
        boolean degraded = (raw == null || raw.isEmpty());
        if (degraded) {
            raw = ScriptSchemaV1.defaultScript(theme, players);
        }
        return new ScriptGeneration(ScriptSchemaV1.normalize(raw, players, theme), degraded);
    }

    /** P1：剧本生成结果（schema + 是否走 LLM 兜底降级）。 */
    public record ScriptGeneration(Map<String, Object> schema, boolean degraded) {}
}
