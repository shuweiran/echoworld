package com.roleplay.engine.simulation.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间出口推导（P-0817-G，契约 v0.2 扩展键 {@code exits[]}）。
 *
 * <p>用途：从既有地图几何（rooms[] + layers.collision + corridors[]）程序化推导「哪个门洞通向哪个房间」，
 * 供前端「一屏一房间」房间模式做走门切换——确定性推导，不让 LLM/生成器手工出坐标。
 *
 * <p>算法：对每个房间取墙环（rect 外 1 圈格），环上可通行格 = 候选门洞；从门洞沿可通行区 BFS
 * （不重新进入本房间内部），到达的第一个其他房间即出口目标。BSP 开放房间（走廊贴边）与
 * LLM 声明式围合（墙上门洞）均适用；花园等开放房间同样走环扫描。
 *
 * <p>输出：{id, from, to, side, door:[x,y]}；每对 (from,to) 只保留一个出口（去重），
 * 双向由两侧房间各自推导天然互补。
 */
public final class MapExits {

    private MapExits() {
    }

    /**
     * 从契约地图推导 exits[]（缺失/空 rooms 或 layers → 空列表）。
     * 不改动原 map（返回新列表），调用方负责 put 回 map。
     */
    public static List<Map<String, Object>> deriveExits(Map<String, Object> map) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (map == null) return out;
        int W = MapContract.intOf(map.get("width"), 0);
        int H = MapContract.intOf(map.get("height"), 0);
        Object layers = map.get("layers");
        if (W <= 0 || H <= 0 || !(layers instanceof Map<?, ?> lm)) return out;
        int[][] col = MapContract.intGrid(lm.get("collision"));
        if (col == null || col.length != H) return out;

        Object roomsObj = map.get("rooms");
        if (!(roomsObj instanceof List<?> rooms) || rooms.isEmpty()) return out;

        // 房间 id → 序号 / 每个房间矩形
        List<Room> roomList = new ArrayList<>();
        int[][] owner = new int[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) owner[y][x] = -1;
        }
        for (int i = 0; i < rooms.size(); i++) {
            Object o = rooms.get(i);
            if (!(o instanceof Map<?, ?> rm)) continue;
            int rx = MapContract.intOf(rm.get("x"), -1), ry = MapContract.intOf(rm.get("y"), -1);
            int rw = MapContract.intOf(rm.get("w"), 0), rh = MapContract.intOf(rm.get("h"), 0);
            if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0) continue;
            Object idObj = rm.get("id");
            String rid = idObj == null ? "room_" + i : String.valueOf(idObj);
            Room r = new Room(rid, rx, ry, rw, rh);
            roomList.add(r);
            for (int y = ry; y < ry + rh && y < H; y++) {
                for (int x = rx; x < rx + rw && x < W; x++) {
                    if (x >= 0 && y >= 0) owner[y][x] = roomList.size() - 1;
                }
            }
        }
        if (roomList.isEmpty()) return out;

        java.util.Set<String> seen = new java.util.HashSet<>();
        int seq = 0;
        for (int i = 0; i < roomList.size(); i++) {
            Room r = roomList.get(i);
            List<int[]> ring = ringCells(r, W, H);
            for (int[] cell : ring) {
                int cx = cell[0], cy = cell[1];
                if (cy < 0 || cx < 0 || cy >= H || cx >= W) continue;
                if (col[cy][cx] != 0) continue;          // 门洞必须可通行
                if (owner[cy][cx] != -1 && owner[cy][cx] != i) continue; // 环格不应在别的房间内
                int target = bfsTarget(col, owner, W, H, cx, cy, i);
                if (target < 0 || target == i) continue;
                String fromId = roomList.get(i).id;
                String toId = roomList.get(target).id;
                String key = fromId + "->" + toId;
                if (!seen.add(key)) continue;            // 每对 (from,to) 只保留一个出口
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("id", "exit_" + (++seq));
                ex.put("from", fromId);
                ex.put("to", toId);
                ex.put("side", sideOf(r, cx, cy));
                ex.put("door", List.of(cx, cy));
                out.add(ex);
            }
        }
        return out;
    }

    /** 房间 rect 外 1 圈格（墙环候选）。 */
    private static List<int[]> ringCells(Room r, int W, int H) {
        List<int[]> cells = new ArrayList<>();
        for (int x = r.x - 1; x <= r.x + r.w; x++) {
            cells.add(new int[]{x, r.y - 1});
            cells.add(new int[]{x, r.y + r.h});
        }
        for (int y = r.y; y < r.y + r.h; y++) {
            cells.add(new int[]{r.x - 1, y});
            cells.add(new int[]{r.x + r.w, y});
        }
        return cells;
    }

    /**
     * 从门洞格 BFS 沿可通行区（不进入本房间内部 owner==me），
     * 到达的第一个其他房间序号；不可达返回 -1。
     */
    private static int bfsTarget(int[][] col, int[][] owner, int W, int H, int sx, int sy, int me) {
        boolean[][] visited = new boolean[H][W];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        visited[sy][sx] = true;
        q.add(new int[]{sx, sy});
        int[] dxs = {1, -1, 0, 0}, dys = {0, 0, 1, -1};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int i = 0; i < 4; i++) {
                int nx = p[0] + dxs[i], ny = p[1] + dys[i];
                if (nx < 0 || ny < 0 || nx >= W || ny >= H || visited[ny][nx]) continue;
                visited[ny][nx] = true;
                int o = owner[ny][nx];
                if (o != -1 && o != me) return o;        // 到达其他房间
                if (o == me) continue;                   // 不穿回本房间内部
                if (col[ny][nx] != 0) continue;          // 墙不可通行
                q.add(new int[]{nx, ny});
            }
        }
        return -1;
    }

    /** 门洞相对房间的方位（环格坐标判定）。 */
    private static String sideOf(Room r, int x, int y) {
        if (y == r.y - 1) return "top";
        if (y == r.y + r.h) return "bottom";
        if (x == r.x - 1) return "left";
        return "right";
    }

    private record Room(String id, int x, int y, int w, int h) {
    }
}
