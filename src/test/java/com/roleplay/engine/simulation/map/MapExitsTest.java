package com.roleplay.engine.simulation.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0817-G（房间模式）：MapExits 出口表推导 + MapValidator exits 校验。
 * 覆盖：BSP 走廊贴边推导 / LLM 声明式围合门洞推导 / 开放花园 / 校验错误与警告。
 */
class MapExitsTest {

    /** 手搭小地图：A/B 两间围合房间 + 走廊 + 门洞；C 为开放花园接 A 底边。 */
    private Map<String, Object> buildRoomsMap() {
        int W = 16, H = 12;
        int[][] ground = new int[H][W];
        int[][] col = new int[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                ground[y][x] = 3;   // 草地
                col[y][x] = 1;      // 默认墙
            }
        }
        // 房间 A（2..6, 2..5）
        fill(ground, col, 2, 2, 6, 5, 1, 0);
        // 房间 B（9..13, 2..5）
        fill(ground, col, 9, 2, 13, 5, 1, 0);
        // 走廊 + 门洞（A 右门 (7,3) / B 左门 (8,3)）
        col[3][7] = 0; ground[3][7] = 5;
        col[3][8] = 0; ground[3][8] = 5;
        // 花园 C（2..6, 8..10）开放：A 底门 (4,6) + C 上门 (4,7)
        fill(ground, col, 2, 8, 6, 10, 3, 0);
        col[6][4] = 0; ground[6][4] = 5;
        col[7][4] = 0; ground[7][4] = 5;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", 1);
        m.put("width", W);
        m.put("height", H);
        m.put("tile_size", 32);
        m.put("rooms", List.of(
                Map.of("id", "a", "name", "房间A", "x", 2, "y", 2, "w", 5, "h", 4),
                Map.of("id", "b", "name", "房间B", "x", 9, "y", 2, "w", 5, "h", 4),
                Map.of("id", "c", "name", "花园", "x", 2, "y", 8, "w", 5, "h", 3)));
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("ground", MapContract.toIntList(ground));
        layers.put("collision", MapContract.toIntList(col));
        m.put("layers", layers);
        m.put("zones", List.of());
        m.put("spawn_points", List.of());
        m.put("corridors", List.of());
        return m;
    }

    private static void fill(int[][] ground, int[][] col, int x0, int y0, int x1, int y1, int tile, int blocked) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                ground[y][x] = tile;
                col[y][x] = blocked;
            }
        }
    }

    @Test
    @DisplayName("E1: 手搭地图推导出口 —— A↔B 走廊双向 + A↔C 开放花园双向，门洞在墙环上且可通行")
    void deriveBasic() {
        Map<String, Object> m = buildRoomsMap();
        List<Map<String, Object>> exits = MapExits.deriveExits(m);

        assertNotNull(exits);
        assertTrue(exits.size() >= 4, "应推导出 a→b / b→a / a→c / c→a 至少 4 条出口：" + exits);
        Map<String, String> byPair = new LinkedHashMap<>();
        for (Map<String, Object> e : exits) {
            String from = String.valueOf(e.get("from"));
            String to = String.valueOf(e.get("to"));
            byPair.put(from + "->" + to, String.valueOf(e.get("door")));
            assertTrue(e.containsKey("door") && e.get("door") instanceof List<?> l && l.size() == 2);
            assertNotNull(e.get("side"), "出口应带 side");
        }
        assertTrue(byPair.containsKey("a->b"), "应有 a→b：" + byPair.keySet());
        assertTrue(byPair.containsKey("b->a"), "应有 b→a：" + byPair.keySet());
        assertTrue(byPair.containsKey("a->c"), "应有 a→c（花园）：" + byPair.keySet());
        assertTrue(byPair.containsKey("c->a"), "应有 c→a：" + byPair.keySet());

        m.put("exits", exits);
        MapValidator.Result v = MapValidator.validateMap(m);
        assertTrue(v.ok(), "推导出口必须通过校验：" + v.errors());
    }

    @Test
    @DisplayName("E2: BSP 生成地图自带出口 —— 非空、双向、校验通过（含 exits 检查项）")
    void bspExits() {
        Map<String, Object> m = BspMapGenerator.generate(BspMapGenerator.Options.of(20260801L, 24, 16, -1));
        Object exits = m.get("exits");
        assertNotNull(exits, "BSP 生成器必须输出 exits");
        assertTrue(exits instanceof List<?> l && !l.isEmpty(), "BSP 走廊连通应推导出出口");
        MapValidator.Result v = MapValidator.validateMap(m);
        assertTrue(v.ok(), "BSP 地图含 exits 必须通过校验：" + v.errors());
    }

    @Test
    @DisplayName("E3: 校验器 exits 检查 —— from/to 未知房间报错、门洞不可通行报错、缺反向仅警告")
    void validateExits() {
        Map<String, Object> m = buildRoomsMap();
        List<Map<String, Object>> exits = new ArrayList<>(MapExits.deriveExits(m));
        // 篡改：from 未知房间 + 门洞落在墙格（(1,1) 是墙）
        Map<String, Object> bad = new LinkedHashMap<>(exits.get(0));
        bad.put("from", "ghost");
        bad.put("door", List.of(1, 1));
        exits.add(0, bad);
        // 删掉一条反向 → 触发警告
        exits.removeIf(e -> "b->a".equals(e.get("from") + "->" + e.get("to")));
        m.put("exits", exits);

        MapValidator.Result v = MapValidator.validateMap(m);
        assertFalse(v.ok(), "from 未知房间 + 门洞不可通行必须报错");
        assertTrue(v.errors().stream().anyMatch(s -> s.contains("ghost")), "应报 from 未知房间：" + v.errors());
        assertTrue(v.errors().stream().anyMatch(s -> s.contains("不可通行")), "应报门洞不可通行：" + v.errors());
        assertTrue(v.warnings().stream().anyMatch(s -> s.contains("反向")), "缺反向应给警告：" + v.warnings());
    }
}
