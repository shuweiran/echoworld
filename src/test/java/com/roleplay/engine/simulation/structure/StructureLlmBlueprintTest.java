package com.roleplay.engine.simulation.structure;

import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0817-L P4（LLM 语义蓝图 L0）：StructureLlmBlueprint prompt 约束 + 归一 + 失败路径。
 * 原则：LLM 只出语义（结构树 JSON），几何全程序化——prompt 明确禁止坐标/网格。
 */
class StructureLlmBlueprintTest {

    @Test
    @DisplayName("buildPrompt：明确只出语义、禁坐标、要求连通与节点规模")
    void promptConstraints() {
        String p = StructureLlmBlueprint.buildPrompt("雾隐研究所", "科幻", 20260817L);
        assertTrue(p.contains("只输出语义"));
        assertTrue(p.contains("绝不输出坐标/网格/瓦片"));
        assertTrue(p.contains("整棵树必须连通"));
        assertTrue(p.contains("8-14 个节点"));
        assertTrue(p.contains("雾隐研究所"));
        assertTrue(p.contains("科幻"));
        assertTrue(p.contains(String.valueOf(20260817L)));
    }

    @Test
    @DisplayName("blueprint：LLM 返回合法结构 → kind=custom + seed/name 归一")
    void blueprintNormalizes() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(sampleStructure());
        Map<String, Object> s = StructureLlmBlueprint.blueprint(llm, "雾隐研究所", "科幻", 20260817L);
        assertNotNull(s);
        assertEquals("custom", s.get("kind"));
        assertEquals(20260817L, s.get("seed"));
        assertEquals("雾隐研究所", s.get("name"));
        assertTrue(StructureContract.leafIds(s).size() >= 4);
        assertTrue(StructureValidator.validate(s).ok());
    }

    @Test
    @DisplayName("blueprint：空输出 → null（服务层 BSP 兜底）")
    void blueprintEmpty() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(Map.of());
        assertNull(StructureLlmBlueprint.blueprint(llm, "主题", "", 1L));
    }

    @Test
    @DisplayName("blueprint：LLM 抛异常 → null（服务层 BSP 兜底，不 500）")
    void blueprintException() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenThrow(new RuntimeException("timeout"));
        assertNull(StructureLlmBlueprint.blueprint(llm, "主题", "", 1L));
    }

    /** 小型连通结构（4 叶子：城门→大厅→花园/储藏室）。 */
    private static Map<String, Object> sampleStructure() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("version", 1);
        raw.put("kind", "custom");
        raw.put("root", Map.of(
                "id", "lab",
                "type", "structure",
                "name", "雾隐研究所",
                "children", List.of(
                        Map.of("id", "gate", "type", "building", "name", "入口闸门",
                                "template", "gatehouse", "size", List.of(8, 8)),
                        Map.of("id", "hall", "type", "building", "name", "中央大厅",
                                "template", "great_hall", "size", List.of(14, 8)),
                        Map.of("id", "garden", "type", "zone", "name", "生态园",
                                "template", "garden", "open", true, "size", List.of(16, 8)),
                        Map.of("id", "storage", "type", "building", "name", "样本库",
                                "template", "storage", "size", List.of(8, 8)))));
        raw.put("relations", List.of(
                Map.of("from", "gate", "to", "hall", "kind", "adjacent"),
                Map.of("from", "hall", "to", "garden", "kind", "adjacent"),
                Map.of("from", "hall", "to", "storage", "kind", "adjacent")));
        return raw;
    }
}
