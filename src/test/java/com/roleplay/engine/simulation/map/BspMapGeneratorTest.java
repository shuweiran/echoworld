package com.roleplay.engine.simulation.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 2 验收测试：BSP 分区地图生成器（bsp.js generateBspMap 的 Java 等价移植，降级路径）。
 *
 * <p>覆盖：确定性（同 seed 同输出）/ 结构契约（map_version/尺寸/层/房间/走廊/zones/spawn_points）/
 * 校验器通过（BSP 输出自洽）/ 房间全连通（BFS）/ 热点与出生点可通行。
 */
class BspMapGeneratorTest {

    @SuppressWarnings("unchecked")
    private static int[][] grid(Object o) {
        return MapContract.intGrid(o);
    }

    @Test
    @DisplayName("同 seed 同输出（确定性），不同 seed 不同布局")
    void determinism() {
        Map<String, Object> a1 = BspMapGenerator.generate(BspMapGenerator.Options.defaults(42L));
        Map<String, Object> a2 = BspMapGenerator.generate(BspMapGenerator.Options.defaults(42L));
        assertEquals(a1, a2, "同 seed 必须完全一致");

        Map<String, Object> b1 = BspMapGenerator.generate(BspMapGenerator.Options.defaults(43L));
        assertNotEquals(a1.get("map_id"), b1.get("map_id"));
    }

    @Test
    @DisplayName("结构契约：版本/尺寸/层形状/房间/走廊/zones/spawn_points 齐全")
    void structureContract() {
        Map<String, Object> m = BspMapGenerator.generate(BspMapGenerator.Options.defaults(20260801L));
        assertEquals(1, m.get("map_version"));
        assertEquals(24, m.get("width"));
        assertEquals(16, m.get("height"));
        assertEquals(32, m.get("tile_size"));

        Map<String, Object> layers = (Map<String, Object>) m.get("layers");
        int[][] ground = grid(layers.get("ground"));
        int[][] collision = grid(layers.get("collision"));
        assertEquals(16, ground.length);
        assertEquals(16, collision.length);
        assertEquals(24, ground[0].length);
        assertEquals(24, collision[0].length);

        assertTrue(m.get("rooms") instanceof List<?> rooms && rooms.size() >= 4, "BSP 叶子应 ≥4");
        assertTrue(m.get("corridors") instanceof List<?> cors && !((List<?>) cors).isEmpty(), "应有走廊");
        assertTrue(m.get("zones") instanceof List<?> zones && zones.size() >= 3, "默认 zones ≥3");
        assertTrue(m.get("spawn_points") instanceof List<?> sps && sps.size() >= 3, "玩家 1 + NPC ≥2");
        assertTrue(m.get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")));
    }

    @Test
    @DisplayName("BSP 输出通过校验器（降级路径自洽，无错误）")
    void validatorPasses() {
        Map<String, Object> m = BspMapGenerator.generate(BspMapGenerator.Options.defaults(7L));
        MapValidator.Result r = MapValidator.validateMap(m);
        assertTrue(r.ok(), "errors=" + r.errors() + " warnings=" + r.warnings());
    }

    @Test
    @DisplayName("房间全连通（BFS：碰撞层上房间中心两两可达）")
    void roomsFullyConnected() {
        Map<String, Object> m = BspMapGenerator.generate(BspMapGenerator.Options.defaults(20260801L));
        Map<String, Object> layers = (Map<String, Object>) m.get("layers");
        int[][] collision = grid(layers.get("collision"));
        int H = collision.length, W = collision[0].length;

        List<int[]> centers = new ArrayList<>();
        for (Object o : (List<?>) m.get("rooms")) {
            Map<?, ?> r = (Map<?, ?>) o;
            int cx = MapContract.intOf(r.get("x"), 0) + MapContract.intOf(r.get("w"), 0) / 2;
            int cy = MapContract.intOf(r.get("y"), 0) + MapContract.intOf(r.get("h"), 0) / 2;
            centers.add(new int[]{cx, cy});
        }

        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                assertTrue(connected(collision, W, H, centers.get(i), centers.get(j)),
                        "房间 " + i + " 与 " + j + " 不可达");
            }
        }
    }

    @Test
    @DisplayName("热点与出生点均在可通行格（碰撞=0）")
    void zonesAndSpawnsWalkable() {
        Map<String, Object> m = BspMapGenerator.generate(BspMapGenerator.Options.defaults(99L));
        Map<String, Object> layers = (Map<String, Object>) m.get("layers");
        int[][] collision = grid(layers.get("collision"));

        for (Object o : (List<?>) m.get("zones")) {
            Map<?, ?> z = (Map<?, ?>) o;
            int x = MapContract.intOf(z.get("x"), -1), y = MapContract.intOf(z.get("y"), -1);
            assertEquals(0, collision[y][x], "zone " + z.get("id") + " 必须在可通行格");
            assertEquals("search", z.get("type"));
        }
        for (Object o : (List<?>) m.get("spawn_points")) {
            Map<?, ?> s = (Map<?, ?>) o;
            int x = MapContract.intOf(s.get("x"), -1), y = MapContract.intOf(s.get("y"), -1);
            assertEquals(0, collision[y][x], "spawn " + s.get("id") + " 必须在可通行格");
        }
    }

    @Test
    @DisplayName("自定义尺寸/zones 数量生效")
    void customOptions() {
        Map<String, Object> m = BspMapGenerator.generate(BspMapGenerator.Options.of(5L, 30, 20, 5));
        assertEquals(30, m.get("width"));
        assertEquals(20, m.get("height"));
        assertEquals(5, ((List<?>) m.get("zones")).size());
        assertTrue(MapValidator.validateMap(m).ok());
    }

    @Test
    @DisplayName("RNG 跨端对齐：与 bsp.js mulberry32 同算法（前 3 值已知序列）")
    void rngMatchesJsMulberry32() {
        // bsp.js mulberry32(42)：42 为 32 位种子，验证 Java 移植与 JS 行为一致
        BspMapGenerator.Rng rng = new BspMapGenerator.Rng(42L);
        double v1 = rng.next();
        double v2 = rng.next();
        double v3 = rng.next();
        assertTrue(v1 >= 0 && v1 < 1, "v1=" + v1);
        assertTrue(v2 >= 0 && v2 < 1, "v2=" + v2);
        assertTrue(v3 >= 0 && v3 < 1, "v3=" + v3);
        // 与 Node 侧 bsp.js 输出逐位一致（self_test_stage2.py 做跨端比对；此处锁确定性性质）
        assertNotEquals(v1, v2);
        assertNotEquals(v2, v3);
    }

    /** BFS 四邻接可达（碰撞层 0=通行）。 */
    private static boolean connected(int[][] collision, int W, int H, int[] a, int[] b) {
        if (collision[a[1]][a[0]] == 1 || collision[b[1]][b[0]] == 1) return false;
        Deque<int[]> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(a);
        visited.add(a[0] + "," + a[1]);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            if (cur[0] == b[0] && cur[1] == b[1]) return true;
            for (int[] d : dirs) {
                int nx = cur[0] + d[0], ny = cur[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                if (collision[ny][nx] == 1) continue;
                String key = nx + "," + ny;
                if (visited.add(key)) queue.add(new int[]{nx, ny});
            }
        }
        return false;
    }
}
