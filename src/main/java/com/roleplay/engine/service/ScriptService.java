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
        return generateScriptChecked(theme, characters, null);
    }

    /**
     * 阶段 1（P-0810-17）：带概略约束的完整剧本生成——两阶段生成的第二阶段（generate_full）。
     *
     * <p>概略先行时，完整剧本 prompt 注入概略（locations/roles 名字+人设/clues 标题/storyline/killer_hint）
     * 作为一致性约束：地点/人物/线索集合以概略为准（可补充细节，防两阶段矛盾，方案 §7 决策点 3）；
     * outline 为 null（非两阶段路径）时与 {@link #generateScriptChecked(theme, characters)} 行为逐字节一致。
     */
    public ScriptGeneration generateScriptChecked(String theme, List<String> characters, Map<String, Object> outline) {
        List<String> players = characters == null ? List.of() : characters;
        String prompt = ScriptSchemaV1.buildPrompt(theme, players);
        if (outline != null && !outline.isEmpty()) {
            prompt = ScriptSchemaV1.buildPrompt(theme, players)
                    + "\n\n【概略约束】以下为建局时已展示给玩家的剧本概略，完整剧本的"
                    + "地点集合、角色名、线索标题/地点必须与概略一致（可补充细节与完整文案，不得推翻概略设定）：\n"
                    + outlineSummary(outline);
        }
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

    /**
     * 阶段 1（P-0810-17）：两阶段生成第一阶段 —— 只生成概略剧本（轻量 prompt，目标 &lt;10s）。
     *
     * <p>概略含 locations[] / roles[]（名字+一句话人设）/ clues[]（标题+地点）/ storyline / killer_hint，
     * 不含完整角色秘密/线索内容/真相/凶手——完整剧本由 POST /api/script/generate_full 后台异步补齐。
     * LLM 失败/空输出走 {@link ScriptSchemaV1#defaultOutline} 兜底（降级不置 degraded——概略仅为展示
     * 用途，不参与判定，完整剧本阶段才标记 llmDegraded）。
     */
    public Map<String, Object> generateOutline(String theme, List<String> characters) {
        List<String> players = characters == null ? List.of() : characters;
        String prompt = ScriptSchemaV1.buildOutlinePrompt(theme, players);
        // 轻量输出（800-1200 tokens 足够）：概略 JSON 仅地点/角色一句话人设/线索标题/剧情线
        Map<String, Object> raw = llmClient.callJson(prompt, 1200);
        if (raw == null || raw.isEmpty()) {
            raw = ScriptSchemaV1.defaultOutline(theme, players);
        }
        return ScriptSchemaV1.normalizeOutline(raw, players, theme);
    }

    /** 概略 → prompt 约束文本（紧凑单行摘要，防 prompt 过长）。 */
    private static String outlineSummary(Map<String, Object> outline) {
        StringBuilder sb = new StringBuilder();
        if (outline.get("locations") instanceof List<?> locs) {
            sb.append("地点：").append(locs).append("；\n");
        }
        if (outline.get("roles") instanceof List<?> roles) {
            sb.append("角色：");
            for (Object o : roles) {
                if (o instanceof Map<?, ?> rm) {
                    sb.append(rm.get("name")).append("（").append(rm.get("intro")).append("），");
                } else {
                    sb.append(o).append("，");
                }
            }
            sb.append("\n");
        }
        if (outline.get("clues") instanceof List<?> clues) {
            sb.append("线索：");
            for (Object o : clues) {
                if (o instanceof Map<?, ?> cm) {
                    sb.append(cm.get("title")).append("（").append(cm.get("location")).append("），");
                }
            }
            sb.append("\n");
        }
        if (outline.get("storyline") != null) {
            sb.append("剧情梗概：").append(outline.get("storyline")).append("\n");
        }
        if (outline.get("killer_hint") != null && !outline.get("killer_hint").toString().isBlank()) {
            sb.append("凶手提示：").append(outline.get("killer_hint"));
        }
        return sb.toString();
    }

    /** P1：剧本生成结果（schema + 是否走 LLM 兜底降级）。 */
    public record ScriptGeneration(Map<String, Object> schema, boolean degraded) {}
}
