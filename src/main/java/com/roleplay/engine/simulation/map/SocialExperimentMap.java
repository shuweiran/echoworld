package com.roleplay.engine.simulation.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 晨雾镇：为一般模式 AI 社会实验准备的确定性 48×32 单地图。
 * 重点不是美术，而是「可走的公共空间 + 有阻挡的建筑/自然边界 + 可互动社交地点」。
 */
public final class SocialExperimentMap {
    public static final String MAP_ID = "dawn-town-social";
    public static final int WIDTH = 48;
    public static final int HEIGHT = 32;

    private SocialExperimentMap() {}

    public static Map<String, Object> generate() {
        int[][] ground = new int[HEIGHT][WIDTH];
        int[][] collision = new int[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++) Arrays.fill(ground[y], MapContract.TILE_GRASS);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("map_version", MapContract.CURRENT_VERSION);
        map.put("map_id", MAP_ID);
        map.put("name", "晨雾镇");
        map.put("theme", "一个适合 AI 自主社交的小型河畔城镇");
        map.put("tile_size", MapContract.DEFAULT_TILE_SIZE);
        map.put("width", WIDTH);
        map.put("height", HEIGHT);
        map.put("tileset", Map.of("src", "assets/tiles.png", "first_gid", 1, "tile_count", 5));

        // 公共道路：横向主街、纵向居民街，以及连接河两岸的桥。
        pave(ground, 1, 15, 46, 2, MapContract.TILE_STONE);
        pave(ground, 13, 1, 2, 30, MapContract.TILE_STONE);
        pave(ground, 30, 1, 2, 30, MapContract.TILE_STONE);

        // 河流把城镇切成两侧，但中央石桥保持社会流动。
        for (int y = 1; y < 31; y++) {
            for (int x = 28; x <= 29; x++) {
                ground[y][x] = MapContract.TILE_STONE;
                collision[y][x] = (y == 15 || y == 16) ? 0 : 1;
            }
        }

        List<Map<String, Object>> rooms = new ArrayList<>();
        List<Map<String, Object>> decor = new ArrayList<>();
        building(rooms, decor, ground, collision, "cafe", "河畔咖啡馆", 3, 4, 9, 8,
                "cafe", List.of("cafe_table", "cafe_counter"));
        building(rooms, decor, ground, collision, "shop", "杂货铺", 16, 4, 9, 7,
                "shop", List.of("shop_counter", "shop_shelf"));
        building(rooms, decor, ground, collision, "station", "南站", 35, 4, 9, 7,
                "station", List.of("station_board", "station_bench"));
        building(rooms, decor, ground, collision, "home_a", "居民小屋 A", 3, 21, 8, 7,
                "house", List.of("home_a_table"));
        building(rooms, decor, ground, collision, "home_b", "居民小屋 B", 15, 21, 8, 7,
                "house", List.of("home_b_bed"));
        building(rooms, decor, ground, collision, "warehouse", "旧仓库", 35, 21, 10, 7,
                "warehouse", List.of("warehouse_crate", "warehouse_notice"));

        // 北郊小林地：自然阻挡制造绕路和偶遇。
        for (int y = 2; y <= 11; y += 2) {
            for (int x = 2 + (y % 3); x <= 10; x += 3) {
                if (ground[y][x] == MapContract.TILE_GRASS && collision[y][x] == 0) {
                    collision[y][x] = 1;
                    decor.add(decor("tree_north_" + x + "_" + y, "tree_oak", x, y, false,
                            Map.of("mood", "quiet"), Map.of()));
                }
            }
        }

        // 外边界封口，避免角色从实验场景边缘走出地图。
        for (int x = 0; x < WIDTH; x++) {
            collision[0][x] = 1;
            collision[HEIGHT - 1][x] = 1;
        }
        for (int y = 0; y < HEIGHT; y++) {
            collision[y][0] = 1;
            collision[y][WIDTH - 1] = 1;
        }

        // 公共社交物件与少量可改变状态的地点。
        decor.add(decor("town_fountain", "fountain", 22, 15, false,
                Map.of("water", "fresh"), Map.of("action", "observe", "text", "喷泉边很适合等人")));
        decor.add(decor("town_notice", "notice_board", 19, 14, false,
                Map.of("bulletin", "今天的镇上还没有公告"), Map.of("action", "read", "text", "你读了公告栏")));
        decor.add(decor("river_bench", "bench", 25, 18, false,
                Map.of("seat", "empty"), Map.of("action", "sit", "text", "你在河边坐了一会儿")));
        decor.add(decor("old_cabin_secret", "cabin_marker", 8, 2, true,
                Map.of("secret", "有人最近来过这里"), Map.of("action", "discover", "text", "你发现了旧小屋留下的痕迹")));
        decor.add(decor("warehouse_notice", "notice", 39, 20, false,
                Map.of("task", "仓库缺少看守"), Map.of("action", "read", "text", "仓库门上贴着一张便条")));

        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("ground", MapContract.toIntList(ground));
        layers.put("collision", MapContract.toIntList(collision));
        layers.put("objects", List.of());
        layers.put("overlay", List.of());
        map.put("layers", layers);
        map.put("rooms", rooms);
        map.put("corridors", List.of(
                corridor("main_street", straightPoints(1, 15, 46, 15)),
                corridor("west_crossing", straightPoints(13, 2, 13, 29)),
                corridor("east_crossing", straightPoints(30, 2, 30, 29))));
        map.put("zones", zones());
        map.put("spawn_points", spawnPoints());
        map.put("tileProps", waterProps());
        map.put("decor", decor);
        map.put("spawnMarkers", Map.of("grass", List.of(List.of(1, 2), List.of(45, 2)),
                "debris", List.of(List.of(26, 24), List.of(32, 12))));
        map.put("warps", List.of());
        map.put("generator", Map.of("kind", "social_experiment", "seed", 20260820,
                "note", "晨雾镇公共空间优先布局"));
        return map;
    }

    private static void building(List<Map<String, Object>> rooms, List<Map<String, Object>> decor,
                                 int[][] ground, int[][] collision, String id, String name,
                                 int x, int y, int w, int h, String type, List<String> items) {
        rooms.add(Map.of("id", id, "name", name, "x", x, "y", y, "w", w, "h", h));
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                boolean wall = xx == x || yy == y || xx == x + w - 1 || yy == y + h - 1;
                ground[yy][xx] = wall ? MapContract.TILE_WALL : MapContract.TILE_FLOOR;
                collision[yy][xx] = wall ? 1 : 0;
            }
        }
        int doorX = x + w / 2;
        ground[y + h - 1][doorX] = MapContract.TILE_STONE;
        collision[y + h - 1][doorX] = 0;
        for (int i = 0; i < items.size(); i++) {
            int ix = Math.min(x + 1 + i * 2, x + w - 2);
            int iy = y + 2;
            decor.add(decor(id + "_item_" + i, items.get(i), ix, iy, false,
                    Map.of("building", id), Map.of("action", "observe", "text", name + "里留下了生活痕迹")));
        }
    }

    private static Map<String, Object> decor(String id, String type, int x, int y, boolean once,
                                             Map<String, Object> state, Map<String, Object> action) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("type", type);
        d.put("tile", List.of(x, y));
        d.put("state", state);
        d.put("onInteract", action);
        d.put("once", once);
        d.put("radius", 1);
        return d;
    }

    private static Map<String, Object> corridor(String id, List<List<Integer>> points) {
        return Map.of("id", id, "points", points);
    }

    private static List<List<Integer>> straightPoints(int x1, int y1, int x2, int y2) {
        List<List<Integer>> points = new ArrayList<>();
        int x = x1;
        int y = y1;
        points.add(List.of(x, y));
        while (x != x2) {
            x += Integer.compare(x2, x);
            points.add(List.of(x, y));
        }
        while (y != y2) {
            y += Integer.compare(y2, y);
            points.add(List.of(x, y));
        }
        return points;
    }

    private static List<Map<String, Object>> zones() {
        return List.of(
                zone("plaza", "中央广场", "social", 22, 15, 3),
                zone("cafe_zone", "咖啡馆门口", "social", 7, 13, 2),
                zone("river_zone", "河岸公园", "social", 25, 18, 2),
                zone("market_zone", "杂货铺街", "social", 20, 12, 2),
                zone("station_zone", "车站入口", "arrival", 39, 12, 2),
                zone("forest_zone", "北郊小林地", "quiet", 8, 2, 2),
                zone("warehouse_zone", "旧仓库门口", "work", 39, 19, 2));
    }

    private static Map<String, Object> zone(String id, String name, String type, int x, int y, int radius) {
        return Map.of("id", id, "name", name, "type", type, "x", x, "y", y, "radius", radius);
    }

    private static List<Map<String, Object>> spawnPoints() {
        List<Map<String, Object>> out = new ArrayList<>();
        int[][] points = {{18, 15}, {25, 15}, {34, 15}, {10, 15}, {24, 13}, {32, 18}, {27, 24}, {43, 17}};
        for (int i = 0; i < points.length; i++) {
            out.add(Map.of("id", "social_spawn_" + i, "type", i == 0 ? "player" : "npc",
                    "x", points[i][0], "y", points[i][1]));
        }
        return out;
    }

    private static Map<String, Object> waterProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int y = 1; y < 31; y++) {
            if (y == 15 || y == 16) continue;
            props.put("28," + y, Map.of("water", true, "blocked", true));
            props.put("29," + y, Map.of("water", true, "blocked", true));
        }
        return props;
    }

    private static void pave(int[][] ground, int x, int y, int w, int h, int tile) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (yy >= 0 && yy < ground.length && xx >= 0 && xx < ground[0].length) ground[yy][xx] = tile;
            }
        }
    }
}
