package com.roleplay.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.llm.MapLlmClient;
import com.roleplay.engine.simulation.map.MapValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0817-L（结构树契约 + 生成 API）：StructureMapService 生成管线。
 * 覆盖：同 seed 同输出、单图默认、超预算自动拆多图 + warps 双向、custom → BSP 兜底、未知 kind 400。
 */
class StructureMapServiceTest {

    private final StructureMapService svc = new StructureMapService(true, "template", 128, 128);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("同 seed 同输出：城堡两次生成 maps JSON 完全一致（确定性）")
    void determinism() throws Exception {
        Map<String, Object> a = svc.generate(req("晨曦城堡", "castle", 20260817L, 0, 0, "single"));
        Map<String, Object> b = svc.generate(req("晨曦城堡", "castle", 20260817L, 0, 0, "single"));
        assertEquals(mapper.writeValueAsString(a.get("maps")), mapper.writeValueAsString(b.get("maps")));
        assertEquals(20260817L, ((Number) ((Map<?, ?>) a.get("generator")).get("seed")).longValue());
    }

    @Test
    @DisplayName("默认单图：城堡 1 图 + validation ok + 无 fallback")
    void singleMap() {
        Map<String, Object> r = svc.generate(req("晨曦城堡", "castle", 20260817L, 0, 0, "single"));
        Map<?, ?> maps = (Map<?, ?>) r.get("maps");
        assertEquals(1, maps.size());
        assertEquals("map_1", r.get("current_map_id"));
        assertTrue(((List<?>) r.get("fallback")).isEmpty());
        Map<?, ?> gen = (Map<?, ?>) r.get("generator");
        assertEquals("single", gen.get("map_mode"));
        assertTrue(((Map<?, ?>) gen.get("validation")).get("ok").equals(true));
        Map<?, ?> map = (Map<?, ?>) maps.get("map_1");
        assertNotNull(map.get("structure"));
        assertTrue(MapValidator.validateMap((Map<String, Object>) map).ok());
    }

    @Test
    @DisplayName("超预算自动拆多图：街区 40×40 → ≥2 图 + warp 双向 + validation ok")
    void multiMapOverBudget() {
        Map<String, Object> r = svc.generate(req("城市街区", "city_block", 20260817L, 40, 40, "single"));
        Map<?, ?> maps = (Map<?, ?>) r.get("maps");
        assertTrue(maps.size() >= 2, "40×40 预算应自动拆多图，实际 " + maps.size());
        Map<?, ?> gen = (Map<?, ?>) r.get("generator");
        assertEquals("multi", gen.get("map_mode"));
        assertTrue(((Map<?, ?>) gen.get("validation")).get("ok").equals(true));
        // 每图校验通过 + warp 双向
        for (Object o : maps.values()) {
            Map<?, ?> map = (Map<?, ?>) o;
            assertTrue(MapValidator.validateMap((Map<String, Object>) map).ok());
            assertFalse(((List<?>) map.get("warps")).isEmpty());
        }
        assertTrue(((List<?>) r.get("connections")).stream()
                .anyMatch(c -> "warp".equals(((Map<?, ?>) c).get("type"))));
    }

    @Test
    @DisplayName("custom：LLM 蓝图 P4 未落地 → BSP 兜底，fallback 记录原因，不 500")
    void customFallsBack() {
        Map<String, Object> r = svc.generate(req("自定义主题", "custom", 123L, 0, 0, "single"));
        assertFalse(((List<?>) r.get("fallback")).isEmpty());
        Map<?, ?> gen = (Map<?, ?>) r.get("generator");
        assertEquals("bsp", gen.get("l0"));
        assertEquals("custom", gen.get("kind"));
        assertEquals("map_1", r.get("current_map_id"));
    }

    @Test
    @DisplayName("custom + l0-source=llm：LLM 语义蓝图成功 → 单图 + validation ok + l0=llm")
    void llmCustomSuccess() {
        MapLlmClient llm = mock(MapLlmClient.class);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(sampleLlmStructure());
        StructureMapService llmSvc = new StructureMapService(llm, null, true, "llm", 128, 128);
        Map<String, Object> r = llmSvc.generate(req("雾隐研究所", "custom", 20260817L, 0, 0, "single"));
        Map<?, ?> gen = (Map<?, ?>) r.get("generator");
        assertEquals("llm", gen.get("l0"));
        assertEquals("custom", gen.get("kind"));
        assertEquals("single", gen.get("map_mode"));
        assertTrue(((Map<?, ?>) gen.get("validation")).get("ok").equals(true));
        assertEquals(1, ((Map<?, ?>) r.get("maps")).size());
        assertTrue(((List<?>) r.get("fallback")).isEmpty());
        Map<?, ?> map = (Map<?, ?>) ((Map<?, ?>) r.get("maps")).get("map_1");
        assertNotNull(map.get("structure"));
        assertTrue(MapValidator.validateMap((Map<String, Object>) map).ok());
    }

    @Test
    @DisplayName("custom + l0-source=llm：LLM 空输出 → BSP 兜底，fallback 记录原因")
    void llmCustomEmptyFallsBack() {
        MapLlmClient llm = mock(MapLlmClient.class);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(Map.of());
        StructureMapService llmSvc = new StructureMapService(llm, null, true, "llm", 128, 128);
        Map<String, Object> r = llmSvc.generate(req("雾隐研究所", "custom", 20260817L, 0, 0, "single"));
        assertFalse(((List<?>) r.get("fallback")).isEmpty());
        assertEquals("bsp", ((Map<?, ?>) r.get("generator")).get("l0"));
        assertEquals("map_1", r.get("current_map_id"));
    }

    @Test
    @DisplayName("custom + l0-source=llm：LLM 结构树非法（building 缺 template）→ BSP 兜底")
    void llmCustomInvalidFallsBack() {
        MapLlmClient llm = mock(MapLlmClient.class);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("root", Map.of("id", "x", "type", "structure", "name", "x",
                "children", List.of(Map.of("id", "a", "type", "building", "name", "a"))));
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(bad);
        StructureMapService llmSvc = new StructureMapService(llm, null, true, "llm", 128, 128);
        Map<String, Object> r = llmSvc.generate(req("雾隐研究所", "custom", 20260817L, 0, 0, "single"));
        assertFalse(((List<?>) r.get("fallback")).isEmpty());
        assertEquals("bsp", ((Map<?, ?>) r.get("generator")).get("l0"));
    }

    @Test
    @DisplayName("未知 kind → IllegalArgumentException（控制器转 400）")
    void unknownKind() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.generate(req("x", "pyramid", 1L, 0, 0, "single")));
    }

    @Test
    @DisplayName("map_mode=multi 强制拆图：地牢（无 zone）按预算切图仍全部校验通过")
    void forceMultiNoZones() {
        Map<String, Object> r = svc.generate(req("地下城", "dungeon", 7L, 0, 0, "multi"));
        Map<?, ?> maps = (Map<?, ?>) r.get("maps");
        assertTrue(maps.size() >= 2, "multi 应强制拆图");
        Map<?, ?> gen = (Map<?, ?>) r.get("generator");
        assertEquals("multi", gen.get("map_mode"));
        assertTrue(((Map<?, ?>) gen.get("validation")).get("ok").equals(true));
    }

    @Test
    @DisplayName("P-0817-Q map_mode=exterior：外部地图 + 每栋内部地图 + exteriors 元数据 + warps 双向")
    void exteriorSeparation() {
        Map<String, Object> r = svc.generate(req("城市街区", "city_block", 20260817L, 0, 0, "exterior"));
        Map<?, ?> gen = (Map<?, ?>) r.get("generator");
        assertEquals("exterior", gen.get("map_mode"));
        assertTrue(((Map<?, ?>) gen.get("validation")).get("ok").equals(true));
        Map<?, ?> maps = (Map<?, ?>) r.get("maps");
        assertTrue(maps.containsKey("map_1"), "应含外部地图 map_1");
        assertTrue(maps.size() >= 5, "外部 + 每栋内部应 ≥5 图，实际 " + maps.size());
        List<?> exteriors = (List<?>) r.get("exteriors");
        assertTrue(exteriors.size() >= 5, "街区应 ≥5 栋可进入建筑");
        for (Object o : exteriors) {
            Map<?, ?> ext = (Map<?, ?>) o;
            assertTrue(maps.containsKey(String.valueOf(ext.get("interior_map_id"))),
                    "内部地图应存在于注册表");
            assertTrue(MapValidator.validateMap((Map<String, Object>) maps.get(
                    String.valueOf(ext.get("interior_map_id")))).ok());
        }
        assertTrue(((List<?>) r.get("connections")).stream()
                .anyMatch(c -> "warp".equals(((Map<?, ?>) c).get("type"))));
    }

    private static StructureMapService.GenerateRequest req(String theme, String kind, long seed,
                                                           int width, int height, String mapMode) {
        return new StructureMapService.GenerateRequest(theme, kind, seed, width, height,
                mapMode, "", List.of(), List.of(), false);
    }

    /** 小型连通结构（LLM 蓝图 mock 输出：城门→大厅→花园/样本库）。 */
    private static Map<String, Object> sampleLlmStructure() {
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
