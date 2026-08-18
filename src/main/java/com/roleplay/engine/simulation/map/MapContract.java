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
 *
 * <p><b>v0.2 扩展键（P-0814-F，依据调研《星露谷地图数据物品交互》M1/M2/M3）</b>：
 * 以下键全部<b>可选</b>，缺失一律兜底为空（旧 v1 数据照常通过，零破坏）：
 * <ul>
 *   <li>{@code layers.objects}：Front 层静态装饰类型名二维数组（List&lt;List&lt;String&gt;&gt;，元素可 null），
 *       与 ground 同尺寸（如 "tree_oak"/"fence"/"flower_bed"）——渲染层使用，无碰撞语义；</li>
 *   <li>{@code layers.overlay}：AlwaysFront 前景遮罩二维数组（可 null 元素，如 "canopy"）；</li>
 *   <li>{@code tileProps}：每格属性字典 Map&lt;String,Object&gt;，键为 "x,y" 字符串、值为属性字典
 *       （blocked/water/action/args 等，宽容解析不做白名单）——星露谷「瓦片挂字符串属性」范式（M3）；</li>
 *   <li>{@code decor}：显式装饰/交互物 List&lt;Map&gt;：{id, type, tile:[x,y], state?{...}, onInteract?{...}, once?, radius?}；</li>
 *   <li>{@code spawnMarkers}：生成器指示 Map&lt;String,List&lt;List&lt;Integer&gt;&gt;&gt;，如 {"grass":[[2,2]],"debris":[[30,40]]}
 *       ——LLM 低成本铺装饰（M2）；</li>
 *   <li>{@code warps}：传送点 List&lt;Map&gt;：{from:[x,y], to:[mapId,x,y]}（契约支持 + 校验，生成器暂不产出）。</li>
 * </ul>
 * map_version 策略：<b>保持 CURRENT_VERSION=1 不变</b>（扩展兼容模式——旧前端按 v1 渲染忽略新键；
 * 新键是增强不是破坏性变更，D-014 版本纪律）。
 * 瓦片 id 约束：tiles.png 实际只有 5 格，<b>不允许引入 tiles.png 没有的瓦片 id</b>（渲染会花屏）；
 * 装饰用字符串类型键表达（objects/overlay/decor.type），瓦片 id 保持 1-5。
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
        // v0.2 扩展键（可选，缺失兜底为空）：Front 静态装饰层 / AlwaysFront 前景遮罩层
        Object objs = raw.get("layers") instanceof Map<?, ?> lm3 ? lm3.get("objects") : null;
        Object ovl = raw.get("layers") instanceof Map<?, ?> lm4 ? lm4.get("overlay") : null;
        layers.put("objects", objs instanceof List<?> ? objs : List.of());
        layers.put("overlay", ovl instanceof List<?> ? ovl : List.of());
        m.put("layers", layers);

        m.put("rooms", listOf(raw.get("rooms")));
        m.put("corridors", listOf(raw.get("corridors")));
        m.put("zones", listOf(raw.get("zones")));
        m.put("spawn_points", listOf(raw.get("spawn_points")));
        // P-0817-G（房间模式）：房间出口表（可选，缺失兜底为空；由 MapExits 确定性推导）
        m.put("exits", listOf(raw.get("exits")));

        // v0.2 扩展键（可选，缺失兜底为空；宽容解析不做白名单）
        Object tp = raw.get("tileProps");
        m.put("tileProps", tp instanceof Map<?, ?> ? tp : Map.of());
        m.put("decor", listOf(raw.get("decor")));
        Object sm = raw.get("spawnMarkers");
        m.put("spawnMarkers", sm instanceof Map<?, ?> ? sm : Map.of());
        m.put("warps", listOf(raw.get("warps")));
        // P-0817-L（结构树契约）：structure 可选键——缺失 = 普通地图（零破坏）；
        // 存在则原样透传（生成语义元数据），业务校验走 StructureValidator（宽容解析不做白名单）
        if (raw.get("structure") instanceof Map<?, ?> st) {
            m.put("structure", st);
        }

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
        // v0.2 扩展键空默认（与 normalize 兜底一致）
        layers.put("objects", List.of());
        layers.put("overlay", List.of());
        m.put("layers", layers);
        m.put("rooms", List.of());
        m.put("corridors", List.of());
        m.put("zones", List.of());
        m.put("spawn_points", List.of());
        m.put("exits", List.of());
        m.put("tileProps", Map.of());
        m.put("decor", List.of());
        m.put("spawnMarkers", Map.of());
        m.put("warps", List.of());
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

    /** 读取字符串二维层为 List&lt;List&lt;String&gt;&gt;（元素可为 null；非规范形状返回 null，由校验器报错）。 */
    @SuppressWarnings("unchecked")
    public static List<List<String>> strGrid(Object o) {
        if (!(o instanceof List<?> rows)) return null;
        List<List<String>> grid = new ArrayList<>();
        for (Object r : rows) {
            if (!(r instanceof List<?> row)) return null;
            List<String> rr = new ArrayList<>();
            for (Object v : row) {
                rr.add(v == null ? null : String.valueOf(v));
            }
            grid.add(rr);
        }
        return grid;
    }

    /**
     * 解析 tileProps 的 "x,y" 键为 [x, y] 整数对；非法（非数字/缺逗号/多逗号）返回 null。
     * 不做坐标越界校验（越界由校验器报错）。
     */
    public static int[] tileKey(String key) {
        if (key == null) return null;
        int comma = key.indexOf(',');
        if (comma <= 0 || comma == key.length() - 1) return null;
        if (key.indexOf(',', comma + 1) >= 0) return null;
        try {
            int x = Integer.parseInt(key.substring(0, comma).trim());
            int y = Integer.parseInt(key.substring(comma + 1).trim());
            return new int[]{x, y};
        } catch (NumberFormatException e) {
            return null;
        }
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
