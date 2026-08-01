package com.roleplay.engine.simulation.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 2 验收测试：地图 JSON 契约校验器（bsp.js validateMap 的 Java 等价移植）。
 *
 * <p>覆盖契约 §4 检查项：版本号/尺寸/层结构/碰撞值/瓦片 id 范围/房间越界与重叠/走廊越界与
 * 四邻接/热点与出生点可通行性。LLM 输出防线（生成 → 校验 → 不过则重试/降级）。
 */
class MapValidatorTest {

    /** 合法最小地图（2×2，全可通行，1 个热点 + 1 个出生点）。 */
    private Map<String, Object> validMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", 1);
        m.put("width", 3);
        m.put("height", 3);
        m.put("tile_size", 32);
        m.put("layers", Map.of(
            "ground", List.of(
                List.of(1, 1, 1),
                List.of(1, 1, 1),
                List.of(1, 1, 1)),
            "collision", List.of(
                List.of(0, 0, 0),
                List.of(0, 0, 0),
                List.of(0, 0, 0))));
        m.put("zones", List.of(Map.of("id", "z1", "type", "search", "x", 1, "y", 1, "radius", 1)));
        m.put("spawn_points", List.of(Map.of("id", "sp1", "type", "player", "x", 0, "y", 0)));
        return m;
    }

    @Test
    @DisplayName("合法地图通过（无错误无警告）")
    void validMapPasses() {
        MapValidator.Result r = MapValidator.validateMap(validMap());
        assertTrue(r.ok(), "errors=" + r.errors());
        assertTrue(r.warnings().isEmpty(), "warnings=" + r.warnings());
    }

    @Test
    @DisplayName("map_version 缺失 → 警告；类型非法 → 错误")
    void versionChecks() {
        Map<String, Object> m = validMap();
        m.remove("map_version");
        MapValidator.Result r = MapValidator.validateMap(m);
        assertTrue(r.ok());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("map_version")));

        m.put("map_version", "v1");
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("map_version")));
    }

    @Test
    @DisplayName("width/height 非正数 → 错误")
    void dimensionChecks() {
        Map<String, Object> m = validMap();
        m.put("width", 0);
        assertFalse(MapValidator.validateMap(m).ok());
        m = validMap();
        m.put("height", -3);
        assertFalse(MapValidator.validateMap(m).ok());
    }

    @Test
    @DisplayName("tile_size 缺失 → 警告（宽容按 32）")
    void tileSizeWarning() {
        Map<String, Object> m = validMap();
        m.remove("tile_size");
        MapValidator.Result r = MapValidator.validateMap(m);
        assertTrue(r.ok());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("tile_size")));
    }

    @Test
    @DisplayName("ground/collision 形状与尺寸不符 → 错误")
    void layerShapeChecks() {
        Map<String, Object> m = validMap();
        m.put("layers", Map.of(
            "ground", List.of(List.of(1, 1), List.of(1, 1)),
            "collision", List.of(List.of(0, 0, 0), List.of(0, 0, 0), List.of(0, 0, 0))));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("行数") || e.contains("列数")));
    }

    @Test
    @DisplayName("ground 缺失 / collision 缺失 → 错误（碰撞语义必须显式）")
    void layerMissingChecks() {
        Map<String, Object> m = validMap();
        m.put("layers", Map.of("ground", List.of(List.of(1, 1, 1))));
        assertFalse(MapValidator.validateMap(m).ok());

        Map<String, Object> m2 = validMap();
        m2.put("layers", Map.of("collision", List.of(List.of(0, 0, 0))));
        assertFalse(MapValidator.validateMap(m2).ok());
    }

    @Test
    @DisplayName("collision 值非 0/1 → 错误")
    void collisionValueChecks() {
        Map<String, Object> m = validMap();
        m.put("layers", Map.of(
            "ground", List.of(List.of(1, 1, 1), List.of(1, 1, 1), List.of(1, 1, 1)),
            "collision", List.of(List.of(0, 0, 2), List.of(0, 0, 0), List.of(0, 0, 0))));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("非 0/1")));
    }

    @Test
    @DisplayName("瓦片 id 越界 → 警告（LLM 自定义装饰瓦片宽容）")
    void tileIdOutOfRangeWarning() {
        Map<String, Object> m = validMap();
        m.put("layers", Map.of(
            "ground", List.of(List.of(9, 1, 1), List.of(1, 1, 1), List.of(1, 1, 1)),
            "collision", List.of(List.of(0, 0, 0), List.of(0, 0, 0), List.of(0, 0, 0))));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertTrue(r.ok());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("超出 tileset 范围")));
    }

    @Test
    @DisplayName("房间越界 → 错误；房间重叠 → 警告")
    void roomChecks() {
        Map<String, Object> m = validMap();
        m.put("rooms", List.of(Map.of("id", "r1", "x", 0, "y", 0, "w", 5, "h", 2)));
        assertFalse(MapValidator.validateMap(m).ok());

        Map<String, Object> m2 = validMap();
        m2.put("rooms", List.of(
            Map.of("id", "r1", "x", 0, "y", 0, "w", 2, "h", 2),
            Map.of("id", "r2", "x", 1, "y", 1, "w", 2, "h", 2)));
        MapValidator.Result r = MapValidator.validateMap(m2);
        assertTrue(r.ok());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("重叠")));
    }

    @Test
    @DisplayName("走廊点越界 → 错误；非四邻接 → 警告")
    void corridorChecks() {
        Map<String, Object> m = validMap();
        m.put("corridors", List.of(Map.of("id", "c1", "from", "a", "to", "b",
            "points", List.of(List.of(0, 0), List.of(9, 9)))));
        assertFalse(MapValidator.validateMap(m).ok());

        Map<String, Object> m2 = validMap();
        m2.put("corridors", List.of(Map.of("id", "c1", "from", "a", "to", "b",
            "points", List.of(List.of(0, 0), List.of(2, 0)))));
        MapValidator.Result r = MapValidator.validateMap(m2);
        assertTrue(r.ok());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("非四邻接")));
    }

    @Test
    @DisplayName("热点落在不可通行格 → 错误（搜证点不能埋在墙里）")
    void zoneOnWallIsError() {
        Map<String, Object> m = validMap();
        m.put("layers", Map.of(
            "ground", List.of(List.of(1, 2, 1), List.of(1, 1, 1), List.of(1, 1, 1)),
            "collision", List.of(List.of(0, 1, 0), List.of(0, 0, 0), List.of(0, 0, 0))));
        m.put("zones", List.of(Map.of("id", "z_wall", "type", "search", "x", 1, "y", 0, "radius", 1)));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("不可通行格")));
    }

    @Test
    @DisplayName("热点/出生点越界 → 错误")
    void zoneSpawnOutOfBounds() {
        Map<String, Object> m = validMap();
        m.put("zones", List.of(Map.of("id", "z1", "type", "search", "x", 5, "y", 5)));
        assertFalse(MapValidator.validateMap(m).ok());

        Map<String, Object> m2 = validMap();
        m2.put("spawn_points", List.of(Map.of("id", "sp1", "type", "player", "x", -1, "y", 0)));
        assertFalse(MapValidator.validateMap(m2).ok());
    }

    @Test
    @DisplayName("出生点落在不可通行格 → 错误")
    void spawnOnWallIsError() {
        Map<String, Object> m = validMap();
        m.put("layers", Map.of(
            "ground", List.of(List.of(2, 1, 1), List.of(1, 1, 1), List.of(1, 1, 1)),
            "collision", List.of(List.of(1, 0, 0), List.of(0, 0, 0), List.of(0, 0, 0))));
        m.put("spawn_points", List.of(Map.of("id", "sp1", "type", "player", "x", 0, "y", 0)));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("不可通行格")));
    }

    @Test
    @DisplayName("null / 非对象 → 错误")
    void nullMapIsError() {
        assertFalse(MapValidator.validateMap(null).ok());
        assertFalse(MapValidator.validateMap(Map.of()).ok());
    }

    @Test
    @DisplayName("zone radius 缺失 → 警告（宽容按 1）；rooms/zones/spawn_points 缺失 → 纯地形地图合法")
    void lenientDefaults() {
        Map<String, Object> m = validMap();
        m.put("zones", List.of(Map.of("id", "z1", "type", "search", "x", 1, "y", 1)));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertTrue(r.ok());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("radius")));

        Map<String, Object> terrain = Map.of(
            "map_version", 1, "width", 4, "height", 4,
            "layers", Map.of(
                "ground", List.of(List.of(1, 1, 1, 1), List.of(1, 1, 1, 1), List.of(1, 1, 1, 1), List.of(1, 1, 1, 1)),
                "collision", List.of(List.of(0, 0, 0, 0), List.of(0, 0, 0, 0), List.of(0, 0, 0, 0), List.of(0, 0, 0, 0))));
        assertTrue(MapValidator.validateMap(terrain).ok());
    }

    @Test
    @DisplayName("BSP 生成地图可通过校验（降级路径自洽）")
    void bspMapPassesValidation() {
        Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.defaults(20260801L));
        MapValidator.Result r = MapValidator.validateMap(bsp);
        assertTrue(r.ok(), "errors=" + r.errors());
    }

    @Test
    @DisplayName("空地图兜底可通过校验")
    void emptyMapPassesValidation() {
        Map<String, Object> empty = MapContract.emptyMap(8, 8, 32);
        assertTrue(MapValidator.validateMap(empty).ok());
    }

    @Test
    @DisplayName("宽容解析归一：缺省兜底 + 类型规整")
    void normalizeLenient() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("width", "10");          // 字符串尺寸 → int
        raw.put("height", 8);
        raw.put("layers", Map.of("ground", new ArrayList<>(), "collision", new ArrayList<>()));
        Map<String, Object> m = MapContract.normalize(raw);
        assertEquals(1, m.get("map_version"));
        assertEquals(10, m.get("width"));
        assertEquals(8, m.get("height"));
        assertEquals(32, m.get("tile_size"));
        assertEquals(List.of(), m.get("zones"));
        assertTrue(m.get("layers") instanceof Map);
    }
}
