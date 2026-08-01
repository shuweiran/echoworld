package com.roleplay.engine.simulation.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图 JSON 契约 v1（docs/地图JSON契约-v1.md）—— 常量 + 宽容解析归一（对齐 D-014 版本纪律）。
 *
 * <p>阶段 2 定位：LLM 生成路径与 BSP 备选生成器的共同输出契约；前端 Phaser 按本契约渲染。
 * 宽容解析规则（契约 §3）：map_version 缺失按 1、tile_size 缺失按 32、
 * rooms/corridors/zones/spawn_points 缺失视为空数组、zone radius 缺失按 1。
 *
 * <p>本类与前端 phaser_validate/js/bsp.js 的契约保持一致（阶段 0 定稿 v1，字段表冻结）。
 */
public final class MapContract {

    /** 契约版本（内嵌 JSON，不依赖表结构列，D-014 纪律）。 */
    public static final int CURRENT_VERSION = 1;

    /** 缺省瓦片像素边长。 */
    public static final int DEFAULT_TILE_SIZE = 32;

    /** tiles.png 瓦片数（1=木地板 2=墙 3=草地 4=地毯 5=石板）。 */
    public static final int DEFAULT_TILE_COUNT = 5;

    /** 瓦片 id 常量（与契约示例/phaser_validate 素材一致）。 */
    public static final int TILE_FLOOR = 1;
    public static final int TILE_WALL = 2;
    public static final int TILE_GRASS = 3;
    public static final int TILE_CARPET = 4;
    public static final int TILE_STONE = 5;

    private MapContract() {
    }

    // ═══════════════════════════════════════════════════════════
    //  宽容解析 → v1 规范结构（对齐 D-014：缺省兜底、不崩）
    // ═══════════════════════════════════════════════════════════

    /**
     * 将 LLM 原始输出（或任意契约 JSON）归一为 v1 规范结构。
     * 仅做「缺失兜底 + 类型规整」，不做业务校验（校验走 {@link MapValidator}）。
     */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;

        m.put("map_version", intOf(raw.get("map_version"), CURRENT_VERSION));
        m.put("map_id", str(raw.get("map_id"), "map_" + System.currentTimeMillis() % 100000));
        m.put("name", str(raw.get("name"), "未命名地图"));
        m.put("theme", str(raw.get("theme"), ""));
        m.put("tile_size", intOf(raw.get("tile_size"), DEFAULT_TILE_SIZE));
        m.put("width", intOf(raw.get("width"), 0));
        m.put("height", intOf(raw.get("height"), 0));

        if (raw.get("tileset") instanceof Map<?, ?> ts) {
            m.put("tileset", ts);
        } else {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("src", "assets/tiles.png");
            def.put("first_gid", 1);
            def.put("tile_count", DEFAULT_TILE_COUNT);
            m.put("tileset", def);
        }

        Map<String, Object> layers = new LinkedHashMap<>();
        Object g = raw.get("layers") instanceof Map<?, ?> lm ? lm.get("ground") : null;
        Object c = raw.get("layers") instanceof Map<?, ?> lm2 ? lm2.get("collision") : null;
        layers.put("ground", g instanceof List<?> ? g : List.of());
        layers.put("collision", c instanceof List<?> ? c : List.of());
        m.put("layers", layers);

        m.put("rooms", listOf(raw.get("rooms")));
        m.put("corridors", listOf(raw.get("corridors")));
        m.put("zones", listOf(raw.get("zones")));
        m.put("spawn_points", listOf(raw.get("spawn_points")));

        if (raw.get("generator") instanceof Map<?, ?> gen) {
            m.put("generator", gen);
        } else {
            m.put("generator", Map.of("kind", "llm", "note", "宽容解析归一"));
        }
        return m;
    }

    /** 纯地形地图的规范空结构（校验器可直接消费；zones/spawn_points 为空数组合法）。 */
    public static Map<String, Object> emptyMap(int width, int height, int tileSize) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", CURRENT_VERSION);
        m.put("map_id", "empty");
        m.put("name", "空地图");
        m.put("theme", "");
        m.put("tile_size", tileSize > 0 ? tileSize : DEFAULT_TILE_SIZE);
        m.put("width", width);
        m.put("height", height);
        Map<String, Object> tileset = new LinkedHashMap<>();
        tileset.put("src", "assets/tiles.png");
        tileset.put("first_gid", 1);
        tileset.put("tile_count", DEFAULT_TILE_COUNT);
        m.put("tileset", tileset);
        Map<String, Object> layers = new LinkedHashMap<>();
        int[][] ground = new int[height][width];
        int[][] collision = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                ground[y][x] = TILE_FLOOR;
                collision[y][x] = 0;
            }
        }
        layers.put("ground", toIntList(ground));
        layers.put("collision", toIntList(collision));
        m.put("layers", layers);
        m.put("rooms", List.of());
        m.put("corridors", List.of());
        m.put("zones", List.of());
        m.put("spawn_points", List.of());
        m.put("generator", Map.of("kind", "empty", "note", "空地图兜底"));
        return m;
    }

    // ── 类型规整辅助 ──

    /** int[][] → List<List<Integer>>（Jackson 友好）。 */
    public static List<List<Integer>> toIntList(int[][] grid) {
        List<List<Integer>> out = new ArrayList<>();
        for (int[] row : grid) {
            List<Integer> r = new ArrayList<>();
            for (int v : row) r.add(v);
            out.add(r);
        }
        return out;
    }

    /** 读取二维层为 int[][]（非规范形状返回 null，由校验器报错）。 */
    public static int[][] intGrid(Object o) {
        if (!(o instanceof List<?> rows)) return null;
        List<List<Integer>> grid = new ArrayList<>();
        for (Object r : rows) {
            if (!(r instanceof List<?> row)) return null;
            List<Integer> rr = new ArrayList<>();
            for (Object v : row) {
                if (v instanceof Number n) rr.add(n.intValue());
                else if (v instanceof String s) {
                    try {
                        rr.add(Integer.parseInt(s.trim()));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else return null;
            }
            grid.add(rr);
        }
        int[][] out = new int[grid.size()][];
        for (int i = 0; i < grid.size(); i++) {
            List<Integer> row = grid.get(i);
            out[i] = new int[row.size()];
            for (int j = 0; j < row.size(); j++) out[i][j] = row.get(j);
        }
        return out;
    }

    public static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listOf(Object o) {
        if (o instanceof List<?> l) return new ArrayList<>(l);
        return new ArrayList<>();
    }
}
