package com.roleplay.engine.simulation.structure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.MapValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0817-L（结构树契约 + 生成 API）：StructureLayoutGenerator 布局验证。
 * 覆盖：4 kinds 单图（rooms 覆盖叶子 + MapValidator 通过 + 全房间出口连通）、
 * 同 seed 同输出（确定性）、超预算自动拆多图 + warps 双向、L2 家具内容、挡路家具、
 * exterior 外部/内部分离（P-0817-Q）。
 */
class StructureLayoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("4 kinds 单图：rooms 覆盖结构叶子 + 契约校验通过 + 全房间出口连通")
    void singleMapAllKinds() {
        for (String kind : List.of("castle", "mansion", "city_block", "dungeon")) {
            Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template(kind));
            structure.put("seed", 20260817L);
            StructureLayoutGenerator.Result r = StructureLayoutGenerator.layout(
                    structure, 20260817L, 128, 128, false, false, List.of(), List.of());
            assertEquals(1, r.maps().size(), kind + " 默认预算应为单图");
            Map<String, Object> map = r.maps().get("map_1");
            assertNotNull(map, kind + " map_1 缺失");

            MapValidator.Result mv = MapValidator.validateMap(map);
            assertTrue(mv.ok(), kind + " MapValidator 错误: " + mv.errors());
            StructureValidator.Result sv = StructureValidator.validateMap(map);
            assertTrue(sv.ok(), kind + " 结构校验错误: " + sv.errors());
            assertTrue(sv.warnings().isEmpty(), kind + " 结构警告应为空（叶子应全部映射）: " + sv.warnings());

            Set<String> leaves = new LinkedHashSet<>(StructureContract.leafIds(structure));
            Set<String> roomIds = roomIds(map);
            assertEquals(leaves.size(), roomIds.size(), kind + " 房间数应等于结构叶子数");
            assertTrue(roomIds.containsAll(leaves), kind + " rooms 未覆盖全部叶子");
            assertConnected(map, kind);
            assertRoomContent(map, kind);
        }
    }

    @Test
    @DisplayName("L2 房间风格差异：城堡不同房间按配方摆不同家具")
    void roomStyleDifferentiation() {
        Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template("castle"));
        structure.put("seed", 20260817L);
        Map<String, Object> map = StructureLayoutGenerator.layout(
                structure, 20260817L, 128, 128, false, false, List.of(), List.of()).maps().get("map_1");
        Set<String> kitchen = roomTypes(map, "kitchen");
        Set<String> hall = roomTypes(map, "great_hall");
        Set<String> bed = roomTypes(map, "tower_bed");
        Set<String> garden = roomTypes(map, "inner_garden");
        assertTrue(kitchen.stream().anyMatch(t -> "stove".equals(t) || "sink".equals(t)),
                "厨房应有灶台/水槽: " + kitchen);
        assertTrue(hall.contains("table_rect"), "大厅应有长桌: " + hall);
        assertTrue(bed.contains("bed"), "卧室应有床: " + bed);
        assertTrue(garden.stream().anyMatch(t -> "tree".equals(t) || "fountain".equals(t)),
                "花园应有树/喷泉: " + garden);
        assertFalse(kitchen.equals(hall), "厨房与大厅家具应不同");
        assertFalse(hall.equals(bed), "大厅与卧室家具应不同");
    }

    @Test
    @DisplayName("P-0817-O 挡路家具：blocked 家具格 collision=1，note 格可通行，校验通过且房间连通")
    void blockedFurnitureCollision() {
        for (String kind : List.of("castle", "mansion", "city_block", "dungeon")) {
            Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template(kind));
            structure.put("seed", 20260817L);
            StructureLayoutGenerator.Result r = StructureLayoutGenerator.layout(
                    structure, 20260817L, 128, 128, false, false, List.of(), List.of());
            Map<String, Object> map = r.maps().get("map_1");
            int[][] col = MapContract.intGrid(((Map<?, ?>) map.get("layers")).get("collision"));
            Map<?, ?> tileProps = (Map<?, ?>) map.get("tileProps");
            int blockedCount = 0;
            int noteWalkable = 0;
            if (map.get("decor") instanceof List<?> decor) {
                for (Object o : decor) {
                    if (!(o instanceof Map<?, ?> d)) continue;
                    Object tile = d.get("tile");
                    if (!(tile instanceof List<?> t) || t.size() != 2
                            || !(t.get(0) instanceof Number) || !(t.get(1) instanceof Number)) continue;
                    int tx = ((Number) t.get(0)).intValue();
                    int ty = ((Number) t.get(1)).intValue();
                    String type = MapContract.str(d.get("type"), "");
                    if (StructureRoomTemplates.isBlocked(type)) {
                        blockedCount++;
                        assertEquals(1, col[ty][tx], kind + " 挡路家具 " + type + " 格应 collision=1");
                        assertTrue(tileProps.containsKey(tx + "," + ty),
                                kind + " 挡路家具 " + type + " 缺 tileProps.blocked");
                    } else if ("note".equals(type)) {
                        assertEquals(0, col[ty][tx], kind + " note 便条格应可通行");
                        noteWalkable++;
                    }
                }
            }
            assertTrue(blockedCount > 0, kind + " 应有挡路家具");
            assertTrue(noteWalkable > 0, kind + " 应有可通行便条");
            assertTrue(MapValidator.validateMap(map).ok(), kind + " 校验应通过");
            assertConnected(map, kind);
        }
    }

    @Test
    @DisplayName("校验器检查项 13：blocked=true 声明与碰撞层不一致 → 错误")
    void blockedConsistencyValidation() {
        Map<String, Object> m = minimalMap(6, 6);
        m.put("tileProps", Map.of("2,2", Map.of("blocked", true)));
        assertFalse(MapValidator.validateMap(m).ok(), "collision=0 却声明 blocked 应报错");

        int[][] col = MapContract.intGrid(((Map<?, ?>) m.get("layers")).get("collision"));
        col[2][2] = 1;
        Map<String, Object> layers = new LinkedHashMap<>((Map<String, Object>) m.get("layers"));
        layers.put("collision", MapContract.toIntList(col));
        m.put("layers", layers);
        m.put("tileProps", Map.of("2,2", Map.of("blocked", true)));
        assertTrue(MapValidator.validateMap(m).ok(), "collision=1 与 blocked 声明一致应通过");
    }

    /** 最小合法地图（地面全地板、无碰撞；供校验器单测）。 */
    private static Map<String, Object> minimalMap(int w, int h) {
        int[][] ground = new int[h][w];
        int[][] col = new int[h][w];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", 1);
        m.put("width", w);
        m.put("height", h);
        m.put("tile_size", 32);
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("ground", MapContract.toIntList(ground));
        layers.put("collision", MapContract.toIntList(col));
        m.put("layers", layers);
        m.put("rooms", List.of());
        m.put("corridors", List.of());
        m.put("zones", List.of());
        m.put("spawn_points", List.of());
        return m;
    }

    @Test
    @DisplayName("同 seed 同输出：城堡两遍布局地图 JSON 完全一致（确定性）")
    void determinism() throws Exception {
        Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template("castle"));
        structure.put("seed", 20260817L);
        StructureLayoutGenerator.Result a = StructureLayoutGenerator.layout(
                structure, 20260817L, 128, 128, false, false, List.of(), List.of());
        StructureLayoutGenerator.Result b = StructureLayoutGenerator.layout(
                structure, 20260817L, 128, 128, false, false, List.of(), List.of());
        assertEquals(MAPPER.writeValueAsString(a.maps()), MAPPER.writeValueAsString(b.maps()));
    }

    @Test
    @DisplayName("超预算自动拆多图：街区 40×40 → 2+ 图，每图校验通过 + warps 双向")
    void multiMapOverBudget() {
        Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template("city_block"));
        structure.put("seed", 20260817L);
        StructureLayoutGenerator.Result r = StructureLayoutGenerator.layout(
                structure, 20260817L, 40, 40, false, false, List.of(), List.of());
        assertTrue(r.multi(), "40×40 预算应触发自动拆多图");
        assertTrue(r.maps().size() >= 2, "应拆出 ≥2 张图");

        Set<String> mapIds = new LinkedHashSet<>(r.maps().keySet());
        for (Map.Entry<String, Map<String, Object>> e : r.maps().entrySet()) {
            MapValidator.Result mv = MapValidator.validateMap(e.getValue());
            assertTrue(mv.ok(), e.getKey() + " MapValidator 错误: " + mv.errors());
            StructureValidator.Result sv = StructureValidator.validateMap(e.getValue());
            assertTrue(sv.ok(), e.getKey() + " 结构校验错误: " + sv.errors());
            assertConnected(e.getValue(), e.getKey());
            if (e.getValue().get("warps") instanceof List<?> warps) {
                for (Object o : warps) {
                    Map<?, ?> w = (Map<?, ?>) o;
                    List<?> to = (List<?>) w.get("to");
                    assertTrue(mapIds.contains(String.valueOf(to.get(0))), "warp 目标地图不存在");
                }
            }
        }
        assertTrue(r.connections().stream().anyMatch(c -> "warp".equals(c.get("type"))),
                "多图连接表应含 warp");
        StructureValidator.Result sw = StructureValidator.validateWarps(r.maps());
        assertTrue(sw.ok(), "warps 校验错误: " + sw.errors());
        assertTrue(sw.warnings().isEmpty(), "双向 warp 不应有缺反向警告: " + sw.warnings());
    }

    @Test
    @DisplayName("map_mode=multi 强制拆图：城堡强制多图仍每图可校验")
    void forceMulti() {
        Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template("castle"));
        structure.put("seed", 99L);
        StructureLayoutGenerator.Result r = StructureLayoutGenerator.layout(
                structure, 99L, 128, 128, true, false, List.of(), List.of());
        assertTrue(r.multi());
        for (Map<String, Object> map : r.maps().values()) {
            assertTrue(MapValidator.validateMap(map).ok());
        }
    }

    @Test
    @DisplayName("P-0817-Q exterior：外部地图建筑外观块 + 门格 warp + 每栋内部地图可校验")
    void exteriorSeparation() {
        for (String kind : List.of("castle", "city_block")) {
            Map<String, Object> structure = StructureContract.normalize(StructureTemplates.template(kind));
            structure.put("seed", 20260817L);
            StructureLayoutGenerator.Result r = StructureLayoutGenerator.layout(
                    structure, 20260817L, 128, 128, false, true, List.of(), List.of());
            assertTrue(r.exteriors().size() >= 2, kind + " 应有 ≥2 栋可进入建筑");
            Map<String, Object> exterior = r.maps().get("map_1");
            assertNotNull(exterior, kind + " 外部地图 map_1 缺失");
            // 外部地图：建筑外观块（roof decor + collision=1）、门格可通行、无房间（整图漫游）
            int[][] col = MapContract.intGrid(((Map<?, ?>) exterior.get("layers")).get("collision"));
            int roofCount = 0;
            boolean doorWalkable = true;
            if (exterior.get("decor") instanceof List<?> decor) {
                for (Object o : decor) {
                    if (!(o instanceof Map<?, ?> d)) continue;
                    Object tile = d.get("tile");
                    if (!(tile instanceof List<?> t) || t.size() != 2
                            || !(t.get(0) instanceof Number) || !(t.get(1) instanceof Number)) continue;
                    int tx = ((Number) t.get(0)).intValue();
                    int ty = ((Number) t.get(1)).intValue();
                    if ("roof".equals(d.get("type"))) {
                        roofCount++;
                        assertEquals(1, col[ty][tx], kind + " 屋顶格应碰撞（外观不可进入）");
                    } else if ("door".equals(d.get("type"))) {
                        if (col[ty][tx] != 0) doorWalkable = false;
                    }
                }
            }
            assertTrue(roofCount > 0, kind + " 外部地图应有屋顶外观");
            assertTrue(doorWalkable, kind + " 外部门格应可通行");
            MapValidator.Result extMv = MapValidator.validateMap(exterior);
            assertTrue(extMv.ok(), kind + " 外部地图校验应通过: " + extMv.errors());
            // 每栋内部地图：单房间 + 校验通过 + 双向 warp
            Set<String> mapIds = new LinkedHashSet<>(r.maps().keySet());
            for (Map<String, Object> ext : r.exteriors()) {
                String interiorId = String.valueOf(ext.get("interior_map_id"));
                Map<String, Object> interior = r.maps().get(interiorId);
                assertNotNull(interior, kind + " 内部地图 " + interiorId + " 缺失");
                assertTrue(MapValidator.validateMap(interior).ok(), interiorId + " 校验应通过");
                assertEquals(1, roomIds(interior).size(), interiorId + " 应为单房间内部图");
                assertTrue(interior.get("warps") instanceof List<?> iw && !((List<?>) iw).isEmpty(),
                        interiorId + " 应有出口 warp");
                assertTrue(ext.containsKey("door") && ext.containsKey("interior_door"),
                        interiorId + " exteriors 元数据应含 door/interior_door");
            }
            StructureValidator.Result sw = StructureValidator.validateWarps(r.maps());
            assertTrue(sw.ok(), kind + " warps 校验错误: " + sw.errors());
            assertTrue(sw.warnings().isEmpty(), kind + " 双向 warp 不应有缺反向警告: " + sw.warnings());
        }
    }

    // ── 辅助 ──

    private static Set<String> roomIds(Map<String, Object> map) {
        Set<String> ids = new LinkedHashSet<>();
        if (map.get("rooms") instanceof List<?> rooms) {
            for (Object o : rooms) {
                if (o instanceof Map<?, ?> rm) {
                    String id = MapContract.str(rm.get("id"), "");
                    if (!id.isBlank()) ids.add(id);
                }
            }
        }
        return ids;
    }

    /** 以 exits（无向）为边做 BFS：全部房间必须从第一房间可达（结构连通性）。 */
    private static void assertConnected(Map<String, Object> map, String label) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (String id : roomIds(map)) graph.put(id, new ArrayList<>());
        if (map.get("exits") instanceof List<?> exits) {
            for (Object o : exits) {
                if (!(o instanceof Map<?, ?> ex)) continue;
                String from = MapContract.str(ex.get("from"), "");
                String to = MapContract.str(ex.get("to"), "");
                if (graph.containsKey(from) && graph.containsKey(to)) {
                    graph.get(from).add(to);
                    graph.get(to).add(from);
                }
            }
        }
        String start = graph.keySet().iterator().next();
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        visited.add(start);
        q.add(start);
        while (!q.isEmpty()) {
            String cur = q.poll();
            for (String next : graph.getOrDefault(cur, List.of())) {
                if (visited.add(next)) q.add(next);
            }
        }
        assertEquals(graph.size(), visited.size(), label + " 房间未全部连通: " + visited);
    }

    /** L2（P-0817-N）：每房间 ≥1 家具 + 全图至少一个搜证便条。 */
    private static void assertRoomContent(Map<String, Object> map, String label) {
        List<?> rooms = (List<?>) map.get("rooms");
        List<?> decor = (List<?>) map.get("decor");
        boolean hasNote = false;
        for (Object o : rooms) {
            if (!(o instanceof Map<?, ?> rm)) continue;
            int x = MapContract.intOf(rm.get("x"), -1);
            int y = MapContract.intOf(rm.get("y"), -1);
            int w = MapContract.intOf(rm.get("w"), 0);
            int h = MapContract.intOf(rm.get("h"), 0);
            int count = 0;
            for (Object d : decor) {
                if (!(d instanceof Map<?, ?> dd)) continue;
                Object tile = dd.get("tile");
                if (!(tile instanceof List<?> t) || t.size() != 2
                        || !(t.get(0) instanceof Number) || !(t.get(1) instanceof Number)) continue;
                int tx = ((Number) t.get(0)).intValue();
                int ty = ((Number) t.get(1)).intValue();
                if (tx >= x && tx < x + w && ty >= y && ty < y + h) {
                    count++;
                    if ("note".equals(dd.get("type"))) hasNote = true;
                }
            }
            assertTrue(count >= 1, label + " 房间 " + rm.get("id") + " 无家具");
        }
        assertTrue(hasNote, label + " 应含搜证便条（note）");
    }

    /** 房间内 decor 类型集合（tile 落在房间 rect）。 */
    private static Set<String> roomTypes(Map<String, Object> map, String roomId) {
        Map<String, int[]> rects = new LinkedHashMap<>();
        if (map.get("rooms") instanceof List<?> rooms) {
            for (Object o : rooms) {
                if (o instanceof Map<?, ?> rm) {
                    rects.put(MapContract.str(rm.get("id"), ""), new int[]{
                            MapContract.intOf(rm.get("x"), 0), MapContract.intOf(rm.get("y"), 0),
                            MapContract.intOf(rm.get("w"), 0), MapContract.intOf(rm.get("h"), 0)});
                }
            }
        }
        int[] r = rects.get(roomId);
        Set<String> types = new LinkedHashSet<>();
        if (r == null || !(map.get("decor") instanceof List<?> decor)) return types;
        for (Object d : decor) {
            if (!(d instanceof Map<?, ?> dd)) continue;
            Object tile = dd.get("tile");
            if (!(tile instanceof List<?> t) || t.size() != 2
                    || !(t.get(0) instanceof Number) || !(t.get(1) instanceof Number)) continue;
            int tx = ((Number) t.get(0)).intValue();
            int ty = ((Number) t.get(1)).intValue();
            if (tx >= r[0] && tx < r[0] + r[2] && ty >= r[1] && ty < r[1] + r[3]) {
                types.add(MapContract.str(dd.get("type"), ""));
            }
        }
        return types;
    }
}
