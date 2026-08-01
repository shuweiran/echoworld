package com.roleplay.engine.simulation.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 地图 JSON 契约校验器 —— bsp.js validateMap 的 Java 等价移植（阶段 2 LLM 输出防线）。
 *
 * <p>检查项（与 docs/地图JSON契约-v1.md §4 一一对应）：
 * <ol>
 *   <li>map_version 类型、width/height 正整数、tile_size 合法性</li>
 *   <li>layers.ground / layers.collision 为 height×width 二维数组、碰撞值 ∈ {0,1}</li>
 *   <li>瓦片 id 范围（越界 → 警告）</li>
 *   <li>房间越界（错误）、房间重叠（警告）</li>
 *   <li>走廊点越界（错误）、相邻点非四邻接（警告）</li>
 *   <li>热点落在不可通行格（碰撞=1）→ 错误</li>
 *   <li>出生点落在不可通行格 → 错误</li>
 * </ol>
 *
 * <p>输出 {@code {ok, errors[], warnings[]}}；LLM 输出不合法（ok=false）时走重试/兜底（ScriptMapService）。
 */
public final class MapValidator {

    /** 校验结果。 */
    public record Result(boolean ok, List<String> errors, List<String> warnings) {
    }

    private MapValidator() {
    }

    /** 对任意契约 JSON 校验（宽容解析后的结构同样适用）。 */
    public static Result validateMap(Map<String, Object> map) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (map == null) {
            return new Result(false, List.of("地图 JSON 不是对象"), List.of());
        }

        // 1) 版本
        Object ver = map.get("map_version");
        if (ver == null) {
            warnings.add("缺少 map_version，宽容解析按 1 处理（D-014 版本纪律：JSON 内嵌版本）");
        } else if (!(ver instanceof Number)) {
            errors.add("map_version 必须是数字");
        }

        // 2) 尺寸
        int W = MapContract.intOf(map.get("width"), -1);
        int H = MapContract.intOf(map.get("height"), -1);
        if (W <= 0) errors.add("width 必须为正整数");
        if (H <= 0) errors.add("height 必须为正整数");
        Object ts = map.get("tile_size");
        if (ts == null || (ts instanceof Number n && n.intValue() <= 0)) {
            warnings.add("tile_size 缺失或非法，宽容解析按 32 处理");
        }

        // 3) 层
        Object layers = map.get("layers");
        int[][] ground = layers instanceof Map<?, ?> lm ? MapContract.intGrid(lm.get("ground")) : null;
        int[][] collision = layers instanceof Map<?, ?> lm2 ? MapContract.intGrid(lm2.get("collision")) : null;

        if (ground == null) {
            errors.add("layers.ground 必须为二维数组");
        } else if (W > 0 && ground.length != H) {
            errors.add("layers.ground 行数 " + ground.length + " ≠ height " + H);
        } else {
            for (int y = 0; y < ground.length; y++) {
                if (ground[y] == null || (W > 0 && ground[y].length != W)) {
                    errors.add("layers.ground[" + y + "] 列数不为 " + W);
                    break;
                }
                for (int x = 0; x < ground[y].length; x++) {
                    int t = ground[y][x];
                    if (t < 0 || t > MapContract.DEFAULT_TILE_COUNT) {
                        warnings.add("ground[" + y + "][" + x + "] 瓦片 id " + t + " 超出 tileset 范围 0.."
                                + MapContract.DEFAULT_TILE_COUNT + "（可能是 LLM 自定义装饰瓦片，渲染按 id 直取；超出部分显示为空白）");
                    }
                }
            }
        }

        if (collision == null) {
            errors.add("layers.collision 必须为二维数组（1=阻挡 0=通行）");
        } else if (W > 0 && collision.length != H) {
            errors.add("layers.collision 行数 ≠ height");
        } else {
            for (int y = 0; y < collision.length; y++) {
                if (collision[y] == null || (W > 0 && collision[y].length != W)) {
                    errors.add("layers.collision[" + y + "] 列数不为 " + W);
                    break;
                }
                for (int x = 0; x < collision[y].length; x++) {
                    int v = collision[y][x];
                    if (v != 0 && v != 1) errors.add("collision[" + y + "][" + x + "] 值 " + v + " 非 0/1");
                }
            }
        }

        boolean inBounds = W > 0 && H > 0;
        int[][] col = collision;

        // 4) 房间
        Object rooms = map.get("rooms");
        if (rooms != null && !(rooms instanceof List<?>)) {
            errors.add("rooms 必须为数组");
        } else if (rooms instanceof List<?> roomList) {
            for (int i = 0; i < roomList.size(); i++) {
                Object o = roomList.get(i);
                if (!(o instanceof Map<?, ?> r)) {
                    errors.add("rooms[" + i + "] 不是对象");
                    continue;
                }
                int rx = MapContract.intOf(((Map<?, ?>) o).get("x"), Integer.MIN_VALUE);
                int ry = MapContract.intOf(((Map<?, ?>) o).get("y"), Integer.MIN_VALUE);
                int rw = MapContract.intOf(((Map<?, ?>) o).get("w"), Integer.MIN_VALUE);
                int rh = MapContract.intOf(((Map<?, ?>) o).get("h"), Integer.MIN_VALUE);
                if (rx == Integer.MIN_VALUE || ry == Integer.MIN_VALUE || rw == Integer.MIN_VALUE || rh == Integer.MIN_VALUE) {
                    errors.add("rooms[" + i + "] 缺少 x/y/w/h");
                    continue;
                }
                if (inBounds && (rx < 0 || ry < 0 || rx + rw > W || ry + rh > H)) {
                    errors.add("rooms[" + i + "] (" + MapContract.str(((Map<?, ?>) o).get("id"), "") + ") 越界");
                }
            }
            // 重叠（仅警告，BSP 允许相邻）
            for (int i = 0; i < roomList.size(); i++) {
                for (int j = i + 1; j < roomList.size(); j++) {
                    if (!(roomList.get(i) instanceof Map<?, ?> a) || !(roomList.get(j) instanceof Map<?, ?> b)) continue;
                    int ax = MapContract.intOf(a.get("x"), 0), ay = MapContract.intOf(a.get("y"), 0);
                    int aw = MapContract.intOf(a.get("w"), 0), ah = MapContract.intOf(a.get("h"), 0);
                    int bx = MapContract.intOf(b.get("x"), 0), by = MapContract.intOf(b.get("y"), 0);
                    int bw = MapContract.intOf(b.get("w"), 0), bh = MapContract.intOf(b.get("h"), 0);
                    if (ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah) {
                        warnings.add("rooms " + MapContract.str(a.get("id"), "") + " 与 " + MapContract.str(b.get("id"), "") + " 重叠");
                    }
                }
            }
        }

        // 5) 走廊
        Object corridors = map.get("corridors");
        if (corridors != null && !(corridors instanceof List<?>)) {
            errors.add("corridors 必须为数组");
        } else if (corridors instanceof List<?> corList) {
            for (int i = 0; i < corList.size(); i++) {
                Object o = corList.get(i);
                if (!(o instanceof Map<?, ?> c) || !(c.get("points") instanceof List<?> pts)) {
                    errors.add("corridors[" + i + "] 缺少 points 数组");
                    continue;
                }
                for (int k = 0; k < pts.size(); k++) {
                    Object p = pts.get(k);
                    if (!(p instanceof List<?> pp) || pp.size() != 2
                            || !(pp.get(0) instanceof Number) || !(pp.get(1) instanceof Number)) {
                        errors.add("corridors[" + i + "] 点 " + k + " 越界");
                        break;
                    }
                    int px = ((Number) pp.get(0)).intValue();
                    int py = ((Number) pp.get(1)).intValue();
                    if (inBounds && (px < 0 || py < 0 || px >= W || py >= H)) {
                        errors.add("corridors[" + i + "] 点 " + k + " 越界");
                        break;
                    }
                    if (k > 0 && pts.get(k - 1) instanceof List<?> q && q.size() == 2
                            && q.get(0) instanceof Number && q.get(1) instanceof Number) {
                        int d = Math.abs(px - ((Number) q.get(0)).intValue()) + Math.abs(py - ((Number) q.get(1)).intValue());
                        if (d != 1) warnings.add("corridors[" + i + "] 点 " + (k - 1) + "→" + k + " 非四邻接（d=" + d + "）");
                    }
                }
            }
        }

        // 6) 热点：必须落在可通行格（搜证点不能埋在墙里）
        Object zones = map.get("zones");
        if (zones != null && !(zones instanceof List<?>)) {
            errors.add("zones 必须为数组");
        } else if (zones instanceof List<?> zoneList) {
            for (int i = 0; i < zoneList.size(); i++) {
                Object o = zoneList.get(i);
                if (!(o instanceof Map<?, ?> z)) {
                    errors.add("zones[" + i + "] 不是对象");
                    continue;
                }
                int zx = MapContract.intOf(z.get("x"), Integer.MIN_VALUE);
                int zy = MapContract.intOf(z.get("y"), Integer.MIN_VALUE);
                if (zx == Integer.MIN_VALUE || zy == Integer.MIN_VALUE) {
                    errors.add("zones[" + i + "] 缺少 x/y");
                    continue;
                }
                if (inBounds && (zx < 0 || zy < 0 || zx >= W || zy >= H)) {
                    errors.add("zones[" + i + "] (" + MapContract.str(z.get("id"), "") + ") 越界");
                } else if (inBounds && !walkable(col, W, H, zx, zy)) {
                    errors.add("zones[" + i + "] (" + MapContract.str(z.get("id"), "") + ") 落在不可通行格（碰撞=1）");
                }
                Object radius = z.get("radius");
                if (radius == null || (radius instanceof Number n && n.intValue() < 0)) {
                    warnings.add("zones[" + i + "] (" + MapContract.str(z.get("id"), "") + ") radius 缺失按 1 处理");
                }
            }
        }

        // 7) 出生点：必须可通行
        Object spawns = map.get("spawn_points");
        if (spawns != null && !(spawns instanceof List<?>)) {
            errors.add("spawn_points 必须为数组");
        } else if (spawns instanceof List<?> spawnList) {
            for (int i = 0; i < spawnList.size(); i++) {
                Object o = spawnList.get(i);
                if (!(o instanceof Map<?, ?> s)) {
                    errors.add("spawn_points[" + i + "] 不是对象");
                    continue;
                }
                int sx = MapContract.intOf(s.get("x"), Integer.MIN_VALUE);
                int sy = MapContract.intOf(s.get("y"), Integer.MIN_VALUE);
                if (sx == Integer.MIN_VALUE || sy == Integer.MIN_VALUE) {
                    errors.add("spawn_points[" + i + "] 缺少 x/y");
                    continue;
                }
                if (inBounds && (sx < 0 || sy < 0 || sx >= W || sy >= H)) {
                    errors.add("spawn_points[" + i + "] (" + MapContract.str(s.get("id"), "") + ") 越界");
                } else if (inBounds && !walkable(col, W, H, sx, sy)) {
                    errors.add("spawn_points[" + i + "] (" + MapContract.str(s.get("id"), "") + ") 落在不可通行格");
                }
            }
        }

        return new Result(errors.isEmpty(), errors, warnings);
    }

    private static boolean walkable(int[][] col, int W, int H, int x, int y) {
        if (col == null || x < 0 || y < 0 || x >= W || y >= H) return false;
        if (y >= col.length || col[y] == null || x >= col[y].length) return false;
        return col[y][x] == 0;
    }
}
