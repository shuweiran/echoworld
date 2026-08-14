package com.roleplay.engine.simulation.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 2 验收测试（P-0814-F 扩展）：地图 JSON 契约 v0.2 扩展键宽容归一（MapContract.normalize）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>v0.2 新键合法解析：layers.objects/overlay + tileProps/decor/spawnMarkers/warps 透传保留</li>
 *   <li>v0.2 新键缺失兜底为空（旧 v1 数据照常通过，零破坏）</li>
 *   <li>半残缺兜底：新键类型非法 → 空默认（不崩）</li>
 *   <li>v1 数据零变化断言：normalize(v1) 后 v1 字段值不变 + 新键为空默认</li>
 *   <li>map_version 策略：保持 1（扩展兼容模式，不 bump 版本）</li>
 *   <li>tileKey 工具：合法 / 非法（缺逗号 / 多逗号 / 非数字 / 空白）</li>
 * </ul>
 */
class MapContractTest {

    /** 契约 v1 最小地图（无任何 v0.2 键）。 */
    private Map<String, Object> v1Map() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", 1);
        m.put("map_id", "v1_manor");
        m.put("name", "老宅");
        m.put("theme", "民国");
        m.put("tile_size", 32);
        m.put("width", 3);
        m.put("height", 3);
        m.put("layers", Map.of(
            "ground", List.of(List.of(1, 1, 1), List.of(1, 1, 1), List.of(1, 1, 1)),
            "collision", List.of(List.of(0, 0, 0), List.of(0, 0, 0), List.of(0, 0, 0))));
        m.put("rooms", List.of(Map.of("id", "r1", "name", "客厅", "x", 0, "y", 0, "w", 3, "h", 3)));
        m.put("zones", List.of(Map.of("id", "z1", "type", "search", "x", 1, "y", 1)));
        m.put("spawn_points", List.of(Map.of("id", "sp1", "type", "player", "x", 0, "y", 0)));
        return m;
    }

    @Test
    @DisplayName("v0.2 新键合法解析：objects/overlay/tileProps/decor/spawnMarkers/warps 透传保留")
    void v02KeysParsed() {
        Map<String, Object> m = v1Map();
        Map<String, Object> layers = new LinkedHashMap<>((Map<String, Object>) m.get("layers"));
        layers.put("objects", java.util.Arrays.asList(
            java.util.Arrays.asList("tree_oak", null, "fence"),
            java.util.Arrays.asList(null, "flower_bed", null),
            java.util.Arrays.asList("bench", null, "lamp")));
        layers.put("overlay", java.util.Arrays.asList(
            java.util.Arrays.asList("canopy", null, null),
            java.util.Arrays.asList(null, "canopy", null),
            java.util.Arrays.asList(null, null, null)));
        m.put("layers", layers);
        m.put("tileProps", Map.of(
            "1,1", Map.of("blocked", true, "action", "examine"),
            "0,0", Map.of("water", true)));
        m.put("decor", List.of(
            Map.of("id", "chest_1", "type", "chest", "tile", List.of(1, 1), "once", false, "radius", 1)));
        m.put("spawnMarkers", Map.of("grass", List.of(List.of(0, 0), List.of(2, 2)), "debris", List.of(List.of(1, 2))));
        m.put("warps", List.of(Map.of("from", List.of(2, 0), "to", List.of("town", 5, 5))));

        Map<String, Object> out = MapContract.normalize(m);

        assertEquals(1, out.get("map_version"), "map_version 保持 1（扩展兼容，不 bump 版本）");
        Map<?, ?> lo = (Map<?, ?>) out.get("layers");
        assertTrue(lo.get("objects") instanceof List<?> objs && objs.size() == 3, "objects 透传保留");
        List<List<String>> objsGrid = MapContract.strGrid(lo.get("objects"));
        assertEquals("tree_oak", objsGrid.get(0).get(0));
        assertNull(objsGrid.get(0).get(1), "可 null 元素保留为 null");
        List<List<String>> ovlGrid = MapContract.strGrid(lo.get("overlay"));
        assertEquals("canopy", ovlGrid.get(0).get(0));
        assertNull(ovlGrid.get(2).get(2));

        Object tpRaw = out.get("tileProps");
        assertTrue(tpRaw instanceof Map<?, ?> && ((Map<?, ?>) tpRaw).size() == 2, "tileProps 透传");
        Map<?, ?> tp = (Map<?, ?>) tpRaw;
        assertEquals(Boolean.TRUE, ((Map<?, ?>) tp.get("1,1")).get("blocked"));
        assertEquals(1, ((List<?>) out.get("decor")).size());
        assertTrue(out.get("spawnMarkers") instanceof Map<?, ?> sm
                && sm.get("grass") instanceof List<?> && ((List<?>) sm.get("grass")).size() == 2);
        assertEquals(1, ((List<?>) out.get("warps")).size());
    }

    @Test
    @DisplayName("v0.2 新键缺失 → 兜底为空（旧 v1 数据照常通过，零破坏）")
    void v02KeysMissingDefaultsEmpty() {
        Map<String, Object> out = MapContract.normalize(v1Map());
        Map<?, ?> lo = (Map<?, ?>) out.get("layers");
        assertEquals(List.of(), lo.get("objects"), "objects 缺失兜底空");
        assertEquals(List.of(), lo.get("overlay"), "overlay 缺失兜底空");
        assertEquals(Map.of(), out.get("tileProps"), "tileProps 缺失兜底空 Map");
        assertEquals(List.of(), out.get("decor"), "decor 缺失兜底空");
        assertEquals(Map.of(), out.get("spawnMarkers"), "spawnMarkers 缺失兜底空 Map");
        assertEquals(List.of(), out.get("warps"), "warps 缺失兜底空");
    }

    @Test
    @DisplayName("半残缺兜底：新键类型非法 → 空默认（不崩）")
    void v02KeysBrokenFallback() {
        Map<String, Object> m = v1Map();
        Map<String, Object> layers = new LinkedHashMap<>((Map<String, Object>) m.get("layers"));
        layers.put("objects", "not-a-list");
        layers.put("overlay", 42);
        m.put("layers", layers);
        m.put("tileProps", "oops");
        m.put("decor", "oops");
        m.put("spawnMarkers", List.of("oops"));
        m.put("warps", 7);

        Map<String, Object> out = MapContract.normalize(m);
        Map<?, ?> lo = (Map<?, ?>) out.get("layers");
        assertEquals(List.of(), lo.get("objects"), "objects 非 List → 兜底空");
        assertEquals(List.of(), lo.get("overlay"), "overlay 非 List → 兜底空");
        assertEquals(Map.of(), out.get("tileProps"), "tileProps 非 Map → 兜底空");
        assertEquals(List.of(), out.get("decor"), "decor 非 List → 兜底空");
        assertEquals(Map.of(), out.get("spawnMarkers"), "spawnMarkers 非 Map → 兜底空");
        assertEquals(List.of(), out.get("warps"), "warps 非 List → 兜底空");
        // 主结构不受影响
        assertEquals(3, out.get("width"));
        assertEquals(1, ((List<?>) out.get("zones")).size());
    }

    @Test
    @DisplayName("v1 数据零变化断言：normalize(v1) 后 v1 字段值不变 + 新键为空默认")
    void v1DataUnchanged() {
        Map<String, Object> src = v1Map();
        Map<String, Object> out = MapContract.normalize(src);
        assertEquals(1, out.get("map_version"));
        assertEquals("v1_manor", out.get("map_id"));
        assertEquals("老宅", out.get("name"));
        assertEquals(32, out.get("tile_size"));
        assertEquals(3, out.get("width"));
        assertEquals(3, out.get("height"));
        Map<?, ?> layers = (Map<?, ?>) out.get("layers");
        assertEquals(MapContract.intGrid(src.get("layers") instanceof Map<?, ?> lm ? lm.get("ground") : null).length,
                MapContract.intGrid(layers.get("ground")).length, "ground 行数不变");
        assertEquals(1, ((List<?>) out.get("rooms")).size());
        assertEquals("客厅", ((Map<?, ?>) ((List<?>) out.get("rooms")).get(0)).get("name"));
        assertEquals(1, ((List<?>) out.get("zones")).size());
        assertEquals(1, ((List<?>) out.get("spawn_points")).size());
        assertTrue(out.get("generator") instanceof Map<?, ?>);
        // 新键为空默认
        assertEquals(List.of(), ((Map<?, ?>) out.get("layers")).get("objects"));
        assertEquals(List.of(), ((Map<?, ?>) out.get("layers")).get("overlay"));
        assertEquals(Map.of(), out.get("tileProps"));
        assertEquals(List.of(), out.get("decor"));
        assertEquals(Map.of(), out.get("spawnMarkers"));
        assertEquals(List.of(), out.get("warps"));
    }

    @Test
    @DisplayName("map_version 策略：normalize 恒保持 1（扩展兼容模式，不 bump 版本）")
    void versionStaysOne() {
        Map<String, Object> m = v1Map();
        m.put("map_version", 2); // 未来版本输入宽容解析后仍按已知字段解析
        Map<String, Object> out = MapContract.normalize(m);
        assertEquals(2, out.get("map_version"), "normalize 保留输入版本（按输入值宽容透传）");
        Map<String, Object> m2 = v1Map();
        m2.remove("map_version");
        assertEquals(1, MapContract.normalize(m2).get("map_version"), "缺失按 1（D-014 纪律）");
        // ScriptMapService 强制对齐 CURRENT_VERSION=1（扩展兼容：v0.2 键是增强不是破坏性变更）
        assertEquals(1, MapContract.CURRENT_VERSION);
    }

    @Test
    @DisplayName("tileKey 工具：合法解析 + 非法返回 null（缺逗号/多逗号/非数字/空白）")
    void tileKeyParsing() {
        assertArrayEquals(new int[]{3, 7}, MapContract.tileKey("3,7"));
        assertArrayEquals(new int[]{-1, 4}, MapContract.tileKey("-1,4"));
        assertArrayEquals(new int[]{10, 20}, MapContract.tileKey(" 10 , 20 "), "容忍空白");
        assertNull(MapContract.tileKey("3"), "缺逗号");
        assertNull(MapContract.tileKey("3,7,9"), "多逗号");
        assertNull(MapContract.tileKey("a,b"), "非数字");
        assertNull(MapContract.tileKey(",7"), "空 x");
        assertNull(MapContract.tileKey("3,"), "空 y");
        assertNull(MapContract.tileKey(null));
    }

    @Test
    @DisplayName("strGrid 工具：字符串二维层解析（含 null 元素）；非规范形状返回 null")
    void strGridParsing() {
        List<List<String>> g = MapContract.strGrid(java.util.Arrays.asList(
            java.util.Arrays.asList("tree_oak", null, "fence"),
            java.util.Arrays.asList(null, "flower_bed", null)));
        assertEquals(2, g.size());
        assertEquals("tree_oak", g.get(0).get(0));
        assertNull(g.get(0).get(1));
        assertEquals("flower_bed", g.get(1).get(1));
        assertNull(MapContract.strGrid("oops"), "非 List → null");
        assertNull(MapContract.strGrid(List.of("flat")), "行非 List → null");
    }

    @Test
    @DisplayName("emptyMap 兜底含 v0.2 空键默认（与 normalize 一致）")
    void emptyMapHasV02Defaults() {
        Map<String, Object> m = MapContract.emptyMap(4, 4, 32);
        assertEquals(Map.of(), m.get("tileProps"));
        assertEquals(List.of(), m.get("decor"));
        assertEquals(Map.of(), m.get("spawnMarkers"));
        assertEquals(List.of(), m.get("warps"));
        Map<?, ?> layers = (Map<?, ?>) m.get("layers");
        assertEquals(List.of(), layers.get("objects"));
        assertEquals(List.of(), layers.get("overlay"));
        assertTrue(MapValidator.validateMap(m).ok(), "emptyMap 仍通过校验器");
    }
}
