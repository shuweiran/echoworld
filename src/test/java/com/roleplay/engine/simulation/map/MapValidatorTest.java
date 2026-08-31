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

    // ═══════════════════════════════════════════════════════════
    //  v0.2 扩展键校验（P-0814-F）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("tileProps：合法通过 / 越界拒绝 / 键格式非法拒绝 / 值非对象拒绝")
    void tilePropsChecks() {
        Map<String, Object> m = validMap();
        // P-0817-O：blocked 已是「挡路声明」语义（须 collision=1 一致，见检查项 13），
        // 结构合法性用例改用中性属性键 water
        m.put("tileProps", Map.of("1,1", Map.of("water", true)));
        assertTrue(MapValidator.validateMap(m).ok(), "合法 tileProps 通过");

        m = validMap();
        m.put("tileProps", Map.of("5,1", Map.of("water", true)));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("tileProps") && e.contains("越界")));

        // blocked 一致性（P-0817-O 检查项 13）：collision=1 与声明一致 → 通过
        m = validMap();
        int[][] col = MapContract.intGrid(((Map<?, ?>) m.get("layers")).get("collision"));
        col[2][2] = 1; // (2,2) 非热点非出生点
        Map<String, Object> layers = new LinkedHashMap<>((Map<String, Object>) m.get("layers"));
        layers.put("collision", MapContract.toIntList(col));
        m.put("layers", layers);
        m.put("tileProps", Map.of("2,2", Map.of("blocked", true)));
        assertTrue(MapValidator.validateMap(m).ok(), "blocked=true 与 collision=1 一致应通过");

        m = validMap();
        m.put("tileProps", Map.of("abc", Map.of()));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("不是 \"x,y\" 坐标格式")));

        m = validMap();
        m.put("tileProps", Map.of("1,1", "not-a-dict"));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("值必须为对象")));

        m = validMap();
        m.put("tileProps", List.of(1, 2));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("tileProps 必须为对象")));
    }

    @Test
    @DisplayName("decor：合法通过 / id 重复拒绝 / 缺 id 拒绝 / 嵌墙拒绝 / tile 越界拒绝 / 缺 type 拒绝")
    void decorChecks() {
        Map<String, Object> m = validMap();
        m.put("decor", List.of(
            Map.of("id", "d1", "type", "bench", "tile", List.of(1, 1)),
            Map.of("id", "d2", "type", "lamp", "tile", List.of(0, 2), "once", true, "radius", 1)));
        assertTrue(MapValidator.validateMap(m).ok(), "合法 decor 通过");

        // id 重复
        m = validMap();
        m.put("decor", List.of(
            Map.of("id", "d1", "type", "bench", "tile", List.of(1, 1)),
            Map.of("id", "d1", "type", "lamp", "tile", List.of(0, 2))));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("重复")));

        // 缺 id
        m = validMap();
        m.put("decor", List.of(Map.of("type", "bench", "tile", List.of(1, 1))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("缺少 id")));

        // 嵌墙（ground=2）
        m = validMap();
        m.put("layers", Map.of(
            "ground", List.of(List.of(2, 1, 1), List.of(1, 1, 1), List.of(1, 1, 1)),
            "collision", List.of(List.of(1, 0, 0), List.of(0, 0, 0), List.of(0, 0, 0))));
        m.put("decor", List.of(Map.of("id", "d1", "type", "bench", "tile", List.of(0, 0))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("不能嵌墙")), "errors=" + r.errors());

        // tile 越界
        m = validMap();
        m.put("decor", List.of(Map.of("id", "d1", "type", "bench", "tile", List.of(9, 9))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("越界")));

        // 缺 type
        m = validMap();
        m.put("decor", List.of(Map.of("id", "d1", "tile", List.of(1, 1))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("缺少 type")));

        // tile 非 [x,y] 整数对
        m = validMap();
        m.put("decor", List.of(Map.of("id", "d1", "type", "bench", "tile", List.of("a", 1))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("tile 必须为")));
    }

    @Test
    @DisplayName("spawnMarkers：合法通过 / 越界拒绝 / 坐标格式非法拒绝 / 值非数组拒绝")
    void spawnMarkerChecks() {
        Map<String, Object> m = validMap();
        m.put("spawnMarkers", Map.of("grass", List.of(List.of(0, 0), List.of(2, 2)), "debris", List.of(List.of(1, 1))));
        assertTrue(MapValidator.validateMap(m).ok(), "合法 spawnMarkers 通过");

        m = validMap();
        m.put("spawnMarkers", Map.of("grass", List.of(List.of(0, 0), List.of(9, 9))));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("越界")));

        m = validMap();
        m.put("spawnMarkers", Map.of("grass", List.of(List.of(0), List.of(1, 1))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("整数对")));

        m = validMap();
        m.put("spawnMarkers", Map.of("grass", "oops"));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("坐标数组")));

        m = validMap();
        m.put("spawnMarkers", List.of(1, 2));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("spawnMarkers 必须为对象")));
    }

    @Test
    @DisplayName("warps：合法通过 / from 越界拒绝 / from 格式非法拒绝 / to 格式非法拒绝")
    void warpChecks() {
        Map<String, Object> m = validMap();
        m.put("warps", List.of(Map.of("from", List.of(2, 2), "to", List.of("town", 10, 30))));
        assertTrue(MapValidator.validateMap(m).ok(), "合法 warps 通过");

        m = validMap();
        m.put("warps", List.of(Map.of("from", List.of(9, 9), "to", List.of("town", 10, 30))));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("from 越界")));

        m = validMap();
        m.put("warps", List.of(Map.of("from", List.of(1), "to", List.of("town", 10, 30))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("from 必须为")));

        m = validMap();
        m.put("warps", List.of(Map.of("from", List.of(1, 1), "to", List.of(10, 30))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("to 必须为")));

        m = validMap();
        m.put("warps", List.of(Map.of("from", List.of(1, 1), "to", List.of(10, 30, 40))));
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("to 必须为")), "mapId 必须为字符串：errors=" + r.errors());

        m = validMap();
        m.put("warps", "oops");
        r = MapValidator.validateMap(m);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("warps 必须为数组")));
    }

    @Test
    @DisplayName("v0.2 新键缺失 → 不校验 → 通过（保持 v1 语义）")
    void v02KeysMissingPass() {
        // validMap() 本身无任何 v0.2 键 → 纯 v1 语义照常通过
        assertTrue(MapValidator.validateMap(validMap()).ok());
        // 显式空值（normalize 兜底形状）同样通过
        Map<String, Object> m = validMap();
        m.put("tileProps", Map.of());
        m.put("decor", List.of());
        m.put("spawnMarkers", Map.of());
        m.put("warps", List.of());
        Map<String, Object> layers = new LinkedHashMap<>((Map<String, Object>) m.get("layers"));
        layers.put("objects", List.of());
        layers.put("overlay", List.of());
        m.put("layers", layers);
        assertTrue(MapValidator.validateMap(m).ok());
    }

    @Test
    @DisplayName("BSP v0.2 输出可通过校验（spawnMarkers/decor/warps 自洽）")
    void bspV02PassesValidation() {
        Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.defaults(20260801L));
        assertTrue(bsp.get("spawnMarkers") instanceof Map<?, ?>);
        assertTrue(bsp.get("decor") instanceof List<?>);
        assertTrue(bsp.get("warps") instanceof List<?>);
        MapValidator.Result r = MapValidator.validateMap(bsp);
        assertTrue(r.ok(), "errors=" + r.errors());
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

    @Test
    @DisplayName("旧地图归一为 ground；三层 connector 合法且非法引用/越界/阻挡被拒绝")
    void multiFloorConnectorContract() {
        Map<String, Object> legacy = MapContract.normalize(validMap());
        assertEquals("ground", ((Map<?, ?>) ((List<?>) legacy.get("floors")).getFirst()).get("id"));
        assertEquals(List.of(), legacy.get("connectors"));

        Map<String, Object> map = validMap();
        List<List<Integer>> open = List.of(List.of(0, 0, 0), List.of(0, 0, 0), List.of(0, 0, 0));
        map.put("floors", List.of(
                Map.of("id", "f1", "width", 3, "height", 3, "collision", open),
                Map.of("id", "f2", "width", 3, "height", 3, "collision", open),
                Map.of("id", "f3", "width", 3, "height", 3, "collision", open)));
        map.put("connectors", List.of(
                Map.of("id", "s12", "sourceFloor", "f1", "source", List.of(1, 1),
                        "targetFloor", "f2", "target", List.of(1, 1), "bidirectional", true),
                Map.of("id", "s23", "sourceFloor", "f2", "source", List.of(2, 1),
                        "targetFloor", "f3", "target", List.of(2, 1), "bidirectional", true)));
        assertTrue(MapValidator.validateMap(map).ok(), () -> MapValidator.validateMap(map).errors().toString());

        Map<String, Object> badFloor = new LinkedHashMap<>(map);
        badFloor.put("connectors", List.of(Map.of("id", "bad", "sourceFloor", "missing", "source", List.of(1, 1),
                "targetFloor", "f2", "target", List.of(1, 1), "bidirectional", true)));
        assertFalse(MapValidator.validateMap(badFloor).ok());

        Map<String, Object> outOfBounds = new LinkedHashMap<>(map);
        outOfBounds.put("connectors", List.of(Map.of("id", "bad", "sourceFloor", "f1", "source", List.of(9, 1),
                "targetFloor", "f2", "target", List.of(1, 1), "bidirectional", true)));
        assertTrue(MapValidator.validateMap(outOfBounds).errors().stream().anyMatch(e -> e.contains("越界")));

        List<List<Integer>> blocked = List.of(List.of(0, 0, 0), List.of(0, 1, 0), List.of(0, 0, 0));
        Map<String, Object> blockedEntrance = new LinkedHashMap<>(map);
        blockedEntrance.put("floors", List.of(
                Map.of("id", "f1", "width", 3, "height", 3, "collision", blocked),
                Map.of("id", "f2", "width", 3, "height", 3, "collision", open)));
        blockedEntrance.put("connectors", List.of(Map.of("id", "bad", "sourceFloor", "f1", "source", List.of(1, 1),
                "targetFloor", "f2", "target", List.of(1, 1), "bidirectional", true)));
        assertTrue(MapValidator.validateMap(blockedEntrance).errors().stream().anyMatch(e -> e.contains("不可通行")));
    }
}
