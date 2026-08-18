package com.roleplay.engine.simulation.structure;

import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * LLM 语义蓝图 L0（P-0817-L P4，docs/结构树契约与生成API设计.md §5）——kind=custom 长尾主题。
 *
 * <p>原则：LLM 只出语义（结构树：节点/关系/模板键），几何全部程序化
 * （StructureLayoutGenerator）——P-0804-H 教训延续：不让 LLM 出坐标/网格，只输出结构 JSON。
 * 失败（空输出/超时/结构非法）由 StructureMapService 走 BSP 兜底，不 500。
 */
public final class StructureLlmBlueprint {

    private static final Logger log = LoggerFactory.getLogger(StructureLlmBlueprint.class);

    /**
     * LLM 结构树 token 预算（P-0818-B/E 修正：生成回 DeepSeek 主链路，预算 8000 富余、无副作用）。
     */
    public static final int LLM_MAX_TOKENS = 8000;
    /** 单次 LLM 调用超时（秒，P-0818-B/E 修正：DeepSeek 快，恢复 45s 快速降级）。 */
    public static final int LLM_TIMEOUT_SECONDS = 45;

    private StructureLlmBlueprint() {
    }

    /**
     * 生成结构树（已归一，kind=custom、seed/name 已写入；未校验，调用方负责
     * {@link StructureValidator#validate}）。LLM 失败/空输出/异常 → null。
     */
    public static Map<String, Object> blueprint(LLMClient llm, String theme, String style, long seed) {
        if (llm == null) return null;
        try {
            Map<String, Object> raw = llm.callJson(buildPrompt(theme, style, seed), LLM_MAX_TOKENS, LLM_TIMEOUT_SECONDS);
            if (raw == null || raw.isEmpty()) {
                log.warn("StructureLlmBlueprint: LLM 结构树输出为空");
                return null;
            }
            Map<String, Object> structure = StructureContract.normalize(raw);
            structure.put("kind", "custom");
            structure.put("seed", seed);
            if (theme != null && !theme.isBlank()) structure.put("name", theme.trim());
            return structure;
        } catch (Exception e) {
            log.warn("StructureLlmBlueprint: LLM 结构树生成失败: {}", e.getMessage());
            return null;
        }
    }

    /** 结构树 prompt：只要求语义 JSON，明确禁止坐标/网格/瓦片等几何数据。 */
    public static String buildPrompt(String theme, String style, long seed) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是大型角色扮演地图的结构设计师。根据主题输出一份「结构树」JSON，描述一个可探索的大型区域");
        sb.append("（城堡/庄园/街区/地牢/飞船/小镇等）由哪些部分组成、如何连接。\n");
        sb.append("主题：").append(theme == null || theme.isBlank() ? "未指定（请自行设计一个吸引人的主题）" : theme.trim()).append("\n");
        if (style != null && !style.isBlank()) sb.append("风格：").append(style.trim()).append("\n");
        sb.append("硬性要求：\n");
        sb.append("1. 只输出语义结构，绝不输出坐标/网格/瓦片/像素等几何数据（几何由程序生成）；\n");
        sb.append("2. 顶层 root 的 children 为 8-14 个节点：type ∈ building/zone（开放区域，open=true）/room；\n");
        sb.append("   叶子节点必须带 template 键（如 great_hall/gatehouse/kitchen/gu_bedroom/garden/storage/treasury/");
        sb.append("shop/house/dungeon_cell/entrance/boss_room…）或 type=zone；\n");
        sb.append("3. 每个节点字段：id（英文短名，全局唯一）、type、name（中文）、template（尽量复用模板键）、");
        sb.append("size [w,h]（4-40 的整数）；\n");
        sb.append("4. relations：8-14 条 {from, to, kind}，kind ∈ adjacent/connects；整棵树必须连通");
        sb.append("（每个节点都能经关系链到达第一个节点）；\n");
        sb.append("5. 输出纯 JSON（不要 markdown 代码块）：{\"version\":1,\"kind\":\"custom\",\"seed\":");
        sb.append(seed).append(",\"root\":{\"id\":\"<主题>\"");
        sb.append(",\"type\":\"structure\",\"name\":\"<中文名>\",\"children\":[...]},\"relations\":[...]}\n");
        sb.append("6. 请直接输出 JSON，不要任何推理过程/解释/前言（不要 reasoning，先给结论再给结构）；\n");
        return sb.toString();
    }
}
