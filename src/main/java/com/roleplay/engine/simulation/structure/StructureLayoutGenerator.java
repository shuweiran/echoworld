package com.roleplay.engine.simulation.structure;

import com.roleplay.engine.simulation.map.BspMapGenerator;
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.MapExits;
import com.roleplay.engine.service.ScriptMapService;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构树 → 契约 v1 地图（L1 布局器，docs/结构树契约与生成API设计.md §5）。
 *
 * <p>确定性：同 seed 同输出——装饰由节点种子驱动（childSeed(父seed, 路径)），打包/走廊/门洞
 * 由节点顺序 + 尺寸确定性得出（无随机全局状态）。复用范式：模板原型 make_layout 的
 * 「走廊先落 → 房间地板 → 非开放房间围墙（墙环遇可通行自动开门）」、MapExits 出口推导、
 * ScriptMapService 线索绑定/覆盖补齐。
 *
 * <p>单图优先：结构总面积 ≤ 预算 → 一张图，房间间 exits[]（MapExits 推导）；
 * 超限 / map_mode=multi → 按 zone 节点切图（每 zone 一张图），跨图 warps 双向连接
 * （契约 v0.2 warps[]：{from:[x,y], to:[mapId,x,y]}）。
 */
public final class StructureLayoutGenerator {

    /** 外边界墙内侧留白（格）。 */
    public static final int MARGIN = 2;
    /** 房间间隔（格）：两房间墙环相邻（走廊把门洞连起来）。 */
    public static final int GUTTER = 2;
    /** 房间尺寸下限（防退化矩形）。 */
    public static final int MIN_ROOM = 4;
    /** 房间尺寸上限（防单房间吃掉整图预算）。 */
    public static final int MAX_ROOM = 40;

    /** 布局结果：maps（mapId → 契约 v1）+ 连接表（exit/warp）+ 结构树 + 是否多图 + exteriors 元数据。 */
    public record Result(Map<String, Map<String, Object>> maps,
                         List<Map<String, Object>> connections,
                         Map<String, Object> structure,
                         boolean multi,
                         List<Map<String, Object>> exteriors) {
    }

    private record Room(String id, String name, String template, boolean open,
                        int x, int y, int w, int h) {
    }

    private StructureLayoutGenerator() {
    }

    /**
     * 结构树 → 一张或多张契约 v1 地图 + 连接表。
     *
     * @param structure 已归一的结构树（StructureContract.normalize）
     * @param rootSeed  根种子（同 seed 同输出）
     * @param maxW      单图预算宽（>0）
     * @param maxH      单图预算高（>0）
     * @param forceMulti 强制拆多图（map_mode=multi）
     * @param exterior  外部/内部分离（map_mode=exterior：建筑外观块 + 每栋内部地图 + 门格 warp）
     * @param locations 剧本地点（可选，zones 绑定时序）
     * @param clueLocations 线索地点（可选，zones[].clue_location 对齐）
     */
    @SuppressWarnings("unchecked")
    public static Result layout(Map<String, Object> structure, long rootSeed,
                                int maxW, int maxH, boolean forceMulti, boolean exterior,
                                List<String> locations, List<String> clueLocations) {
        return layout(structure, rootSeed, maxW, maxH, forceMulti, exterior, GUTTER,
                locations, clueLocations);
    }

    /** P-0818-E（视觉审核修正）：gutter 可调（默认 2；审核发现过挤 → +1，过空 → -1）。 */
    public static Result layout(Map<String, Object> structure, long rootSeed,
                                int maxW, int maxH, boolean forceMulti, boolean exterior, int gutter,
                                List<String> locations, List<String> clueLocations) {
        List<Map<String, Object>> children = new ArrayList<>();
        if (structure.get("root") instanceof Map<?, ?> root
                && root.get("children") instanceof List<?> cs) {
            for (Object c : cs) {
                if (c instanceof Map<?, ?> cm) children.add((Map<String, Object>) cm);
            }
        }
        List<Map<String, Object>> relations = StructureContract.relations(structure);
        String kind = StructureContract.str(structure.get("kind"), "custom");
        String name = StructureContract.str(structure.get("name"), "");

        // 单图视角全量打包
        List<Room> packed = pack(children, rootSeed, gutter);
        int totalW = 0;
        int totalH = 0;
        for (Room r : packed) {
            totalW = Math.max(totalW, r.x() + r.w());
            totalH = Math.max(totalH, r.y() + r.h());
        }
        totalW += MARGIN;
        totalH += MARGIN;

        boolean multi = forceMulti || totalW > maxW || totalH > maxH;
        Map<String, Map<String, Object>> maps = new LinkedHashMap<>();
        List<Map<String, Object>> connections = new ArrayList<>();

        if (exterior) {
            return layoutExterior(structure, children, relations, rootSeed, kind, name,
                    gutter, locations, clueLocations);
        }

        if (!multi) {
            String mapId = "map_1";
            Map<String, Object> map = buildMap(mapId, kind, name, rootSeed, packed, relations,
                    structure, locations, clueLocations);
            maps.put(mapId, map);
            addExitConnections(connections, mapId, map);
        } else {
            List<List<Map<String, Object>>> groups = splitGroups(children, relations, rootSeed, maxW, maxH, forceMulti, gutter);
            List<String> mapIds = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                String mapId = "map_" + (i + 1);
                mapIds.add(mapId);
                List<Room> groupRooms = pack(groups.get(i), StructureContract.childSeed(rootSeed, mapId), gutter);
                Map<String, Object> map = buildMap(mapId, kind, name, rootSeed, groupRooms, relations,
                        structure, locations, clueLocations);
                maps.put(mapId, map);
            }
            // warps：相邻图链式双向连接（每图 warp 落第一房间中心，契约 v0.2 warps[]）
            for (int i = 0; i < mapIds.size() - 1; i++) {
                addWarp(maps, connections, mapIds.get(i), mapIds.get(i + 1));
            }
        }
        return new Result(maps, connections, structure, multi, List.of());
    }

    /**
     * P-0817-Q（外部/内部分离）：外部地图（建筑外观块 + 门格，城镇开放地面）+ 每栋建筑一张内部地图。
     * 传统 RPG「进屋切换室内地图」：外部门格 warp → 内部入口；内部出口 warp → 外部门格。
     */
    private static Result layoutExterior(Map<String, Object> structure, List<Map<String, Object>> children,
                                         List<Map<String, Object>> relations, long rootSeed,
                                         String kind, String name, int gutter,
                                         List<String> locations, List<String> clueLocations) {
        Map<String, Map<String, Object>> maps = new LinkedHashMap<>();
        List<Map<String, Object>> connections = new ArrayList<>();
        List<Map<String, Object>> exteriors = new ArrayList<>();
        List<Room> blocks = pack(children, rootSeed, gutter);

        Map<String, Object> exterior = buildExteriorMap("map_1", kind, name, rootSeed, blocks,
                structure, locations, clueLocations);
        maps.put("map_1", exterior);

        for (Room b : blocks) {
            if (b.open()) continue; // zone（街道/广场）无内部
            String mapId = "map_" + b.id() + "_in";
            List<Map<String, Object>> one = new ArrayList<>();
            for (Map<String, Object> n : children) {
                if (b.id().equals(StructureContract.str(n.get("id"), ""))) {
                    one.add(n);
                    break;
                }
            }
            List<Room> oneRoom = pack(one, StructureContract.childSeed(rootSeed, mapId), gutter);
            Map<String, Object> interior = buildMap(mapId, kind, name, rootSeed, oneRoom, relations,
                    structure, locations, clueLocations);
            int[] entry = forceInteriorEntry(interior);
            maps.put(mapId, interior);
            // 外部门格（块底边中点）↔ 内部入口格 双向 warp
            int[] door = new int[]{b.x() + b.w() / 2, b.y() + b.h() - 1};
            addExteriorWarp(maps, connections, "map_1", mapId, door, entry);
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("building_id", b.id());
            ext.put("name", b.name());
            ext.put("door", List.of(door[0], door[1]));
            ext.put("interior_map_id", mapId);
            ext.put("interior_door", List.of(entry[0], entry[1]));
            exteriors.add(ext);
        }
        return new Result(maps, connections, structure, true, exteriors);
    }

    /**
     * 外部地图：全图开放地面（城镇街巷可通行），建筑画成外观块（石板底 + 屋顶 decor 铺满 +
     * 碰撞 + 底边门格）。zones=开放区（街道/广场）搜证点 + 出生点；rooms 空（无房间模式）。
     */
    private static Map<String, Object> buildExteriorMap(String mapId, String kind, String name, long rootSeed,
                                                        List<Room> blocks, Map<String, Object> structure,
                                                        List<String> locations, List<String> clueLocations) {
        int W = 0;
        int H = 0;
        for (Room r : blocks) {
            W = Math.max(W, r.x() + r.w());
            H = Math.max(H, r.y() + r.h());
        }
        W += MARGIN;
        H += MARGIN;
        int[][] ground = new int[H][W];
        int[][] collision = new int[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                ground[y][x] = MapContract.TILE_GRASS;
                collision[y][x] = 0; // 开放城镇：除建筑块/边界墙外均可通行
            }
        }
        List<Map<String, Object>> decor = new ArrayList<>();
        List<Map<String, Object>> zones = new ArrayList<>();
        List<Map<String, Object>> spawns = new ArrayList<>();

        for (Room r : blocks) {
            if (r.open()) {
                // 开放区（街道/广场/庭院）：可通行 + 搜证点 + 出生点（首个 zone）
                Map<String, Object> zone = new LinkedHashMap<>();
                zone.put("id", "z_" + r.id());
                zone.put("name", r.name());
                zone.put("type", "search");
                zone.put("x", r.x() + r.w() / 2);
                zone.put("y", r.y() + r.h() / 2);
                zone.put("radius", 1);
                zone.put("clue_location", r.name());
                zone.put("prompt", "（外部区域）这里似乎藏着什么线索……");
                zones.add(zone);
                if (spawns.isEmpty()) {
                    spawns.add(spawn("sp_player", "player", r.x() + r.w() / 2, r.y() + r.h() / 2));
                }
                continue;
            }
            // 建筑外观块：石板底 + 屋顶 decor 铺满（除门格）+ 碰撞
            int doorX = r.x() + r.w() / 2;
            int doorY = r.y() + r.h() - 1;
            for (int y = r.y(); y < r.y() + r.h(); y++) {
                for (int x = r.x(); x < r.x() + r.w(); x++) {
                    ground[y][x] = MapContract.TILE_STONE;
                    collision[y][x] = 1;
                    if (x == doorX && y == doorY) continue; // 门格留空
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", "roof_" + r.id() + "_" + x + "_" + y);
                    d.put("type", "roof");
                    d.put("tile", List.of(x, y));
                    decor.add(d);
                }
            }
            // 门格：可通行 + 门装饰（warp 由 addExteriorWarp 写入）
            ground[doorY][doorX] = MapContract.TILE_STONE;
            collision[doorY][doorX] = 0;
            Map<String, Object> doorD = new LinkedHashMap<>();
            doorD.put("id", "door_" + r.id());
            doorD.put("type", "door");
            doorD.put("tile", List.of(doorX, doorY));
            decor.add(doorD);
        }
        // 外边界墙
        for (int x = 0; x < W; x++) {
            ground[0][x] = MapContract.TILE_WALL;
            ground[H - 1][x] = MapContract.TILE_WALL;
            collision[0][x] = 1;
            collision[H - 1][x] = 1;
        }
        for (int y = 0; y < H; y++) {
            ground[y][0] = MapContract.TILE_WALL;
            ground[y][W - 1] = MapContract.TILE_WALL;
            collision[y][0] = 1;
            collision[y][W - 1] = 1;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("map_version", MapContract.CURRENT_VERSION);
        map.put("map_id", mapId);
        map.put("name", name.isBlank() ? "外部地图" : name + " · 城镇外观");
        map.put("theme", kind + " · 外部/内部分离 · 建筑外观");
        map.put("tile_size", MapContract.DEFAULT_TILE_SIZE);
        map.put("width", W);
        map.put("height", H);
        Map<String, Object> tileset = new LinkedHashMap<>();
        tileset.put("src", "assets/tiles.png");
        tileset.put("first_gid", 1);
        tileset.put("tile_count", MapContract.DEFAULT_TILE_COUNT);
        map.put("tileset", tileset);
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("ground", MapContract.toIntList(ground));
        layers.put("collision", MapContract.toIntList(collision));
        map.put("layers", layers);
        map.put("rooms", List.of());
        map.put("corridors", List.of());
        map.put("zones", zones);
        map.put("spawn_points", spawns);
        map.put("exits", List.of());
        map.put("decor", decor);
        map.put("tileProps", Map.of());
        map.put("spawnMarkers", Map.of("grass", List.of(), "debris", List.of()));
        map.put("warps", new ArrayList<Map<String, Object>>());
        if (clueLocations != null && !clueLocations.isEmpty()) {
            map = ScriptMapService.bindClueLocations(map, clueLocations);
            map = ScriptMapService.ensureClueZoneCoverage(map, clueLocations);
        }
        map.put("structure", subStructure(structure, blocks, mapId));
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("kind", "structure");
        gen.put("seed", rootSeed);
        gen.put("map_id", mapId);
        gen.put("l0", "template");
        gen.put("note", "外部地图：建筑外观块 + 门格 warp（进入内部地图）");
        map.put("generator", gen);
        return map;
    }

    /** 内部地图入口：把房间南墙环中点开洞（可通行），返回房间内入口格（warp 落点）。 */
    private static int[] forceInteriorEntry(Map<String, Object> map) {
        if (!(map.get("rooms") instanceof List<?> rooms) || rooms.isEmpty()) return new int[]{2, 2};
        Map<?, ?> r = (Map<?, ?>) rooms.get(0);
        int rx = MapContract.intOf(r.get("x"), 0);
        int ry = MapContract.intOf(r.get("y"), 0);
        int rw = MapContract.intOf(r.get("w"), 0);
        int rh = MapContract.intOf(r.get("h"), 0);
        int doorX = rx + rw / 2;
        int doorY = ry + rh; // 南墙环行
        int[][] col = MapContract.intGrid(((Map<?, ?>) map.get("layers")).get("collision"));
        int[][] ground = MapContract.intGrid(((Map<?, ?>) map.get("layers")).get("ground"));
        if (col != null && ground != null && doorY >= 0 && doorY < col.length && doorX >= 0 && doorX < col[doorY].length) {
            col[doorY][doorX] = 0;
            ground[doorY][doorX] = MapContract.TILE_STONE;
            ((Map<String, Object>) map.get("layers")).put("collision", MapContract.toIntList(col));
            ((Map<String, Object>) map.get("layers")).put("ground", MapContract.toIntList(ground));
        }
        return new int[]{doorX, doorY - 1};
    }

    /** 外部门格 ↔ 内部入口 双向 warp + connections。 */
    @SuppressWarnings("unchecked")
    private static void addExteriorWarp(Map<String, Map<String, Object>> maps,
                                        List<Map<String, Object>> connections,
                                        String exteriorId, String interiorId,
                                        int[] door, int[] entry) {
        Map<String, Object> exterior = maps.get(exteriorId);
        Map<String, Object> interior = maps.get(interiorId);
        List<Map<String, Object>> ew = (List<Map<String, Object>>) exterior.get("warps");
        List<Map<String, Object>> iw = (List<Map<String, Object>>) interior.get("warps");
        if (ew == null || iw == null) return;
        Map<String, Object> w1 = new LinkedHashMap<>();
        w1.put("id", "warp_" + exteriorId + "_" + interiorId);
        w1.put("from", List.of(door[0], door[1]));
        w1.put("to", List.of(interiorId, entry[0], entry[1]));
        ew.add(w1);
        Map<String, Object> w2 = new LinkedHashMap<>();
        w2.put("id", "warp_" + interiorId + "_" + exteriorId);
        w2.put("from", List.of(entry[0], entry[1]));
        w2.put("to", List.of(exteriorId, door[0], door[1]));
        iw.add(w2);
        connections.add(warpConnection(exteriorId, interiorId, List.of(door[0], door[1]),
                List.of(interiorId, entry[0], entry[1])));
        connections.add(warpConnection(interiorId, exteriorId, List.of(entry[0], entry[1]),
                List.of(exteriorId, door[0], door[1])));
    }

    // ═══════════════════════════════════════════════════════════
    //  打包（确定性：按模板节点顺序 + 尺寸，货架式排布）
    // ═══════════════════════════════════════════════════════════

    private static List<Room> pack(List<Map<String, Object>> nodes, long seed) {
        return pack(nodes, seed, GUTTER);
    }

    private static List<Room> pack(List<Map<String, Object>> nodes, long seed, int gutter) {
        int maxNodeW = 0;
        for (Map<String, Object> n : nodes) {
            int[] s = clampSize(StructureContract.nodeSize(n));
            maxNodeW = Math.max(maxNodeW, s[0]);
        }
        int shelfW = Math.max(48, maxNodeW * 2 + 8);
        List<Room> out = new ArrayList<>();
        int curX = MARGIN;
        int curY = MARGIN;
        int rowH = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> n = nodes.get(i);
            int[] s = clampSize(StructureContract.nodeSize(n));
            int w = s[0];
            int h = s[1];
            if (curX + w + gutter > shelfW + MARGIN) {
                curX = MARGIN;
                curY += rowH + gutter;
                rowH = 0;
            }
            String id = StructureContract.str(n.get("id"), "node_" + i);
            String type = StructureContract.str(n.get("type"), "room");
            boolean open = "zone".equals(type) || Boolean.TRUE.equals(n.get("open"));
            out.add(new Room(id, StructureContract.str(n.get("name"), id),
                    StructureContract.str(n.get("template"), ""), open, curX, curY, w, h));
            rowH = Math.max(rowH, h);
            curX += w + gutter;
        }
        return out;
    }

    private static int[] clampSize(int[] s) {
        return new int[]{Math.max(MIN_ROOM, Math.min(MAX_ROOM, s[0])),
                Math.max(MIN_ROOM, Math.min(MAX_ROOM, s[1]))};
    }

    // ═══════════════════════════════════════════════════════════
    //  拆图分组（超单图预算 / map_mode=multi）
    // ═══════════════════════════════════════════════════════════

    /**
     * 按 zone 节点切图（每 zone 一组）：建筑按 relations 归属第一个关联 zone；
     * 无关联建筑按种子确定偏移轮转补齐；无 zone 的结构（如地牢）→ 按预算行带贪心切图。
     */
    private static List<List<Map<String, Object>>> splitGroups(List<Map<String, Object>> children,
                                                               List<Map<String, Object>> relations,
                                                               long seed, int maxW, int maxH,
                                                               boolean forceMulti, int gutter) {
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Map<String, Object> n : children) {
            if ("zone".equals(StructureContract.str(n.get("type"), ""))) zones.add(n);
        }
        if (!zones.isEmpty()) {
            Map<String, String> zoneOfBuilding = new LinkedHashMap<>();
            for (Map<String, Object> rel : relations) {
                String from = StructureContract.str(rel.get("from"), "");
                String to = StructureContract.str(rel.get("to"), "");
                String zoneId = null;
                for (Map<String, Object> z : zones) {
                    String zid = StructureContract.str(z.get("id"), "");
                    if (zid.equals(from) || zid.equals(to)) {
                        zoneId = zid;
                        break;
                    }
                }
                if (zoneId == null) continue;
                String buildingId = from.equals(zoneId) ? to : from;
                zoneOfBuilding.putIfAbsent(buildingId, zoneId);
            }
            List<List<Map<String, Object>>> groups = new ArrayList<>();
            for (Map<String, Object> z : zones) {
                List<Map<String, Object>> g = new ArrayList<>();
                g.add(z);
                groups.add(g);
            }
            List<Map<String, Object>> unassigned = new ArrayList<>();
            for (Map<String, Object> n : children) {
                String id = StructureContract.str(n.get("id"), "");
                String zoneId = zoneOfBuilding.get(id);
                if (zoneId != null) {
                    int idx = indexOfZone(groups, zoneId);
                    if (idx >= 0) groups.get(idx).add(n);
                    else unassigned.add(n);
                } else if (!"zone".equals(StructureContract.str(n.get("type"), ""))) {
                    unassigned.add(n);
                }
            }
            int off = (int) (seed & 0xFFFF);
            for (int i = 0; i < unassigned.size(); i++) {
                groups.get((i + off) % groups.size()).add(unassigned.get(i));
            }
            return groups;
        }

        // 无 zone：map_mode=multi 强制 ≥2 组（按模板顺序交替，确定性）；
        // 否则按预算贪心行带切图（单房间超预算 → 强制放入防死循环）
        if (forceMulti && children.size() >= 2) {
            List<List<Map<String, Object>>> forced = new ArrayList<>();
            forced.add(new ArrayList<>());
            forced.add(new ArrayList<>());
            for (int i = 0; i < children.size(); i++) {
                forced.get(i % 2).add(children.get(i));
            }
            return forced;
        }
        List<Map<String, Object>> remaining = new ArrayList<>(children);
        List<List<Map<String, Object>>> out = new ArrayList<>();
        int guard = 0;
        while (!remaining.isEmpty() && guard++ < 64) {
            List<Map<String, Object>> group = new ArrayList<>();
            int w = 0;
            int h = 0;
            int rowH = 0;
            for (Iterator<Map<String, Object>> it = remaining.iterator(); it.hasNext(); ) {
                Map<String, Object> n = it.next();
                int[] s = clampSize(StructureContract.nodeSize(n));
                    if (w + s[0] + gutter <= maxW && h + Math.max(rowH, s[1]) <= maxH) {
                        group.add(n);
                        w += s[0] + gutter;
                    h += Math.max(0, s[1] - rowH);
                    rowH = Math.max(rowH, s[1]);
                    it.remove();
                }
            }
            if (group.isEmpty()) {
                group.add(remaining.remove(0));
            }
            out.add(group);
        }
        return out;
    }

    private static int indexOfZone(List<List<Map<String, Object>>> groups, String zoneId) {
        for (int i = 0; i < groups.size(); i++) {
            if (!groups.get(i).isEmpty()
                    && zoneId.equals(StructureContract.str(groups.get(i).get(0).get("id"), ""))) {
                return i;
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════
    //  单图构建（走廊先落 → 房间地板 → 围墙开门 → 热点/出生点/装饰/出口）
    // ═══════════════════════════════════════════════════════════

    private static Map<String, Object> buildMap(String mapId, String kind, String name, long rootSeed,
                                                List<Room> rooms, List<Map<String, Object>> relations,
                                                Map<String, Object> structure,
                                                List<String> locations, List<String> clueLocations) {
        int W = 0;
        int H = 0;
        for (Room r : rooms) {
            W = Math.max(W, r.x() + r.w());
            H = Math.max(H, r.y() + r.h());
        }
        W += MARGIN;
        H += MARGIN;

        int[][] ground = new int[H][W];
        int[][] collision = new int[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                ground[y][x] = MapContract.TILE_GRASS;
                collision[y][x] = 1;
            }
        }

        // 1) 走廊（relations 两端点均在本图）：L 形，先落（stone / 可通行）
        Set<String> ids = new LinkedHashSet<>();
        for (Room r : rooms) ids.add(r.id());
        List<Map<String, Object>> corridors = new ArrayList<>();
        int corSeq = 0;
        for (Map<String, Object> rel : relations) {
            String from = StructureContract.str(rel.get("from"), "");
            String to = StructureContract.str(rel.get("to"), "");
            if (!ids.contains(from) || !ids.contains(to)) continue;
            Room a = roomById(rooms, from);
            Room b = roomById(rooms, to);
            if (a == null || b == null) continue;
            List<List<Integer>> pts = corridor(a, b);
            if (pts.isEmpty()) continue;
            for (List<Integer> p : pts) {
                int x = p.get(0);
                int y = p.get(1);
                if (x >= 0 && y >= 0 && x < W && y < H) {
                    ground[y][x] = MapContract.TILE_STONE;
                    collision[y][x] = 0;
                }
            }
            Map<String, Object> cor = new LinkedHashMap<>();
            cor.put("id", "cor_" + (corSeq++));
            cor.put("from", from);
            cor.put("to", to);
            cor.put("points", dedup(pts));
            corridors.add(cor);
        }

        // 2) 房间地板（L2 配方：模板键 → base_floor + 地面图案；开放 zone=草地）
        for (Room r : rooms) {
            StructureRoomTemplates.Recipe recipe = StructureRoomTemplates.recipe(r.template());
            int floorTile = r.open() ? MapContract.TILE_GRASS : recipe.baseFloor();
            for (int y = r.y(); y < r.y() + r.h(); y++) {
                for (int x = r.x(); x < r.x() + r.w(); x++) {
                    ground[y][x] = floorTile;
                    collision[y][x] = 0;
                }
            }
            applyPattern(ground, r, recipe.patterns());
        }

        // 3) 非开放房间围 1 层墙（门位留空：墙环遇可通行自动开门）
        for (Room r : rooms) {
            if (r.open()) continue;
            List<int[]> ring = new ArrayList<>();
            for (int x = r.x() - 1; x <= r.x() + r.w(); x++) {
                ring.add(new int[]{x, r.y() - 1});
                ring.add(new int[]{x, r.y() + r.h()});
            }
            for (int y = r.y(); y < r.y() + r.h(); y++) {
                ring.add(new int[]{r.x() - 1, y});
                ring.add(new int[]{r.x() + r.w(), y});
            }
            for (int[] c : ring) {
                int x = c[0];
                int y = c[1];
                if (x < 0 || y < 0 || x >= W || y >= H) continue;
                if (collision[y][x] == 0) continue; // 邻接走廊/开放房间 → 自动开门
                ground[y][x] = MapContract.TILE_WALL;
                collision[y][x] = 1;
            }
        }

        // 4) 外边界墙
        for (int x = 0; x < W; x++) {
            ground[0][x] = MapContract.TILE_WALL;
            ground[H - 1][x] = MapContract.TILE_WALL;
            collision[0][x] = 1;
            collision[H - 1][x] = 1;
        }
        for (int y = 0; y < H; y++) {
            ground[y][0] = MapContract.TILE_WALL;
            ground[y][W - 1] = MapContract.TILE_WALL;
            collision[y][0] = 1;
            collision[y][W - 1] = 1;
        }

        // 5) rooms[] + 房间内容（L2 家具配方：模板键 → 家具/搜证锚点，种子确定性放置）
        List<Map<String, Object>> roomsOut = new ArrayList<>();
        List<Map<String, Object>> decor = new ArrayList<>();
        Map<String, Object> tileProps = new LinkedHashMap<>();
        Map<String, int[]> noteCells = new LinkedHashMap<>();
        Set<String> occupiedCells = new LinkedHashSet<>();
        Set<String> corridorCells = corridorCellKeys(corridors);
        for (Room r : rooms) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id());
            rm.put("name", r.name());
            rm.put("x", r.x());
            rm.put("y", r.y());
            rm.put("w", r.w());
            rm.put("h", r.h());
            rm.put("tags", List.of("searchable"));
            roomsOut.add(rm);

            StructureRoomTemplates.Recipe recipe = StructureRoomTemplates.recipe(r.template());
            long rSeed = StructureContract.childSeed(rootSeed, "room/" + r.id());
            int[] note = placeRoomFurniture(r, recipe, rSeed, ground, collision, W, H,
                    decor, tileProps, occupiedCells, corridorCells);
            noteCells.put(r.id(), note);
        }

        // 6) zones / spawn_points（zone 优先 note 搜证锚点，否则房间内自由格；出生点取自由格）
        List<Map<String, Object>> zones = new ArrayList<>();
        List<Map<String, Object>> spawns = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            int[] anchor = noteCells.get(r.id());
            int[] z = anchor != null ? anchor
                    : firstFreeCell(r, r.x() + r.w() / 2, r.y() + r.h() / 2,
                    ground, collision, W, H, occupiedCells, corridorCells);
            if (z == null) z = new int[]{r.x() + r.w() / 2, r.y() + r.h() / 2};
            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("id", "z_" + r.id());
            zone.put("name", r.name() + " 线索点");
            zone.put("type", "search");
            zone.put("x", z[0]);
            zone.put("y", z[1]);
            zone.put("radius", 1);
            zone.put("clue_location", r.name());
            zone.put("prompt", "（结构树生成）这里似乎藏着什么线索……");
            zones.add(zone);

            // 出生点：玩家首房，NPC 后续 3 房
            int[] s = firstFreeCell(r, r.x() + 2, r.y() + 2,
                    ground, collision, W, H, occupiedCells, corridorCells);
            if (s == null) s = new int[]{r.x() + 1, r.y() + 1};
            if (i == 0) {
                spawns.add(spawn("sp_player", "player", s[0], s[1]));
            } else if (i <= 3) {
                spawns.add(spawn("sp_npc_" + i, "npc", s[0], s[1]));
            }
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("map_version", MapContract.CURRENT_VERSION);
        map.put("map_id", mapId);
        map.put("name", name.isBlank() ? "结构地图" : name);
        map.put("theme", kind + " · 大型结构 · 结构树布局");
        map.put("tile_size", MapContract.DEFAULT_TILE_SIZE);
        map.put("width", W);
        map.put("height", H);
        Map<String, Object> tileset = new LinkedHashMap<>();
        tileset.put("src", "assets/tiles.png");
        tileset.put("first_gid", 1);
        tileset.put("tile_count", MapContract.DEFAULT_TILE_COUNT);
        map.put("tileset", tileset);
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("ground", MapContract.toIntList(ground));
        layers.put("collision", MapContract.toIntList(collision));
        map.put("layers", layers);
        map.put("rooms", roomsOut);
        map.put("corridors", corridors);
        map.put("zones", zones);
        map.put("spawn_points", spawns);
        map.put("exits", List.of());
        map.put("decor", decor);
        map.put("tileProps", tileProps);
        map.put("spawnMarkers", Map.of("grass", List.of(), "debris", List.of()));
        map.put("warps", new ArrayList<Map<String, Object>>());

        // 6) 线索绑定 / 覆盖补齐（复用既有公开静态方法，zones 与剧本线索对齐）
        if (clueLocations != null && !clueLocations.isEmpty()) {
            map = ScriptMapService.bindClueLocations(map, clueLocations);
            map = ScriptMapService.ensureClueZoneCoverage(map, clueLocations);
        }
        // 7) 出口表（确定性推导）+ 结构树归属 + 生成器溯源
        map.put("exits", MapExits.deriveExits(map));
        map.put("structure", subStructure(structure, rooms, mapId));
        map.put("generator", generatorInfo(mapId, rootSeed, rooms.size(), "结构树布局生成（L0 模板 + L1 程序化打包/走廊/门洞）"));
        return map;
    }

    private static Map<String, Object> generatorInfo(String mapId, long seed, int nodeCount, String note) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("kind", "structure");
        g.put("seed", seed);
        g.put("map_id", mapId);
        g.put("node_count", nodeCount);
        g.put("l0", "template");
        g.put("note", note);
        return g;
    }

    private static Map<String, Object> spawn(String id, String type, int x, int y) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("type", type);
        s.put("x", x);
        s.put("y", y);
        return s;
    }

    /** 走廊格集合（家具避让；"x,y"）。 */
    private static Set<String> corridorCellKeys(List<Map<String, Object>> corridors) {
        Set<String> cells = new LinkedHashSet<>();
        for (Map<String, Object> cor : corridors) {
            if (!(cor.get("points") instanceof List<?> pts)) continue;
            for (Object p : pts) {
                if (p instanceof List<?> pp && pp.size() == 2
                        && pp.get(0) instanceof Number && pp.get(1) instanceof Number) {
                    cells.add(((Number) pp.get(0)).intValue() + "," + ((Number) pp.get(1)).intValue());
                }
            }
        }
        return cells;
    }

    /** 已放置家具（note 锚点定位用）。 */
    private record Placed(String type, int x, int y) {
    }

    /**
     * L2 家具配方放置（P-0817-N/O）：锚定/固定/随机 → occupy；
     * 挡路家具（P-0817-O）占用格写 collision=1 + tileProps{blocked:true}，
     * 非挡路（note/flower_bed/rug/window/incense/scroll）保持可通行；
     * 挡路家具避开房间中心安全区（出生点/搜证点留可通行兜底）。
     * 最后按配方放 note 搜证锚点（返回 note 格；无则 null）。全部确定性（节点种子驱动）。
     */
    private static int[] placeRoomFurniture(Room r, StructureRoomTemplates.Recipe recipe, long seed,
                                            int[][] ground, int[][] collision, int W, int H,
                                            List<Map<String, Object>> decor, Map<String, Object> tileProps,
                                            Set<String> occupied, Set<String> corridorCells) {
        List<Placed> placed = new ArrayList<>();
        BspMapGenerator.Rng rng = new BspMapGenerator.Rng(seed);
        for (StructureRoomTemplates.Furniture f : recipe.furniture()) {
            if (f.isNote()) continue;
            int[] sz = StructureRoomTemplates.FURNITURE_SIZES.getOrDefault(f.type(), new int[]{1, 1});
            int fw = Math.min(sz[0], r.w());
            int fh = Math.min(sz[1], r.h());
            boolean blocked = StructureRoomTemplates.isBlocked(f.type());
            if (f.count() > 0) {
                for (int n = 0; n < f.count(); n++) {
                    int[] p = randomFreeCell(r, ground, collision, W, H, occupied, corridorCells, blocked, rng);
                    if (p == null) break;
                    occupy(p[0], p[1], fw, fh, f.type(), r, ground, collision, W, H,
                            decor, tileProps, occupied, corridorCells, placed);
                }
            } else {
                int[] p = anchorPos(f, r, fw, fh);
                if (!occupy(p[0], p[1], fw, fh, f.type(), r, ground, collision, W, H,
                        decor, tileProps, occupied, corridorCells, placed)) {
                    int[] near = nearestFree(r, fw, fh, p[0], p[1], blocked,
                            ground, collision, W, H, occupied, corridorCells);
                    if (near != null) {
                        occupy(near[0], near[1], fw, fh, f.type(), r, ground, collision, W, H,
                                decor, tileProps, occupied, corridorCells, placed);
                    }
                }
            }
        }
        // 搜证锚点：note 放指定家具旁（槽位取第 slot 个同型家具；邻格自由才落）
        String noteOn = recipe.noteTarget();
        int slot = 0;
        for (StructureRoomTemplates.Furniture f : recipe.furniture()) {
            if (f.isNote()) {
                slot = f.noteSlot();
                break;
            }
        }
        if (noteOn != null) {
            List<Placed> cands = new ArrayList<>();
            for (Placed p : placed) {
                if (p.type().equals(noteOn)) cands.add(p);
            }
            if (!cands.isEmpty()) {
                Placed t = cands.get(Math.min(slot, cands.size() - 1));
                for (int[] d : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
                    int nx = t.x() + d[0];
                    int ny = t.y() + d[1];
                    if (freeCell(nx, ny, r, ground, collision, W, H, occupied, corridorCells, false)) {
                        occupy(nx, ny, 1, 1, "note", r, ground, collision, W, H,
                                decor, tileProps, occupied, corridorCells, placed);
                        return new int[]{nx, ny};
                    }
                }
            }
        }
        return null;
    }

    /** 锚定位置（anchor top/bottom/left/right/center + offset；固定 at 直接偏移）。 */
    private static int[] anchorPos(StructureRoomTemplates.Furniture f, Room r, int fw, int fh) {
        int rx = r.x();
        int ry = r.y();
        int rw = r.w();
        int rh = r.h();
        if (f.atX() >= 0) return new int[]{rx + f.atX(), ry + f.atY()};
        int ox = f.offX();
        int oy = f.offY();
        return switch (f.anchor()) {
            case "top" -> new int[]{rx + ox, ry + oy};
            case "bottom" -> new int[]{rx + ox, ry + rh - fh + oy};
            case "left" -> new int[]{rx + ox, ry + oy};
            case "right" -> new int[]{rx + rw - fw + ox, ry + oy};
            default -> new int[]{rx + rw / 2 + ox, ry + rh / 2 + oy};
        };
    }

    /**
     * 占用 fw×fh：全部格 free 才落（记录 occupied + decor；挡路家具写 collision=1 +
     * tileProps{blocked:true}），否则 false。
     */
    private static boolean occupy(int x, int y, int fw, int fh, String type, Room r,
                                  int[][] ground, int[][] collision, int W, int H,
                                  List<Map<String, Object>> decor, Map<String, Object> tileProps,
                                  Set<String> occupied, Set<String> corridorCells,
                                  List<Placed> placed) {
        boolean blocked = StructureRoomTemplates.isBlocked(type);
        for (int dy = 0; dy < fh; dy++) {
            for (int dx = 0; dx < fw; dx++) {
                if (!freeCell(x + dx, y + dy, r, ground, collision, W, H, occupied, corridorCells, blocked)) {
                    return false;
                }
            }
        }
        for (int dy = 0; dy < fh; dy++) {
            for (int dx = 0; dx < fw; dx++) {
                int cx = x + dx;
                int cy = y + dy;
                occupied.add(cx + "," + cy);
                if (blocked) {
                    collision[cy][cx] = 1; // 挡路家具：真实碰撞（玩家/AI 移动 + 寻路自动绕开）
                    Map<String, Object> prop = new LinkedHashMap<>();
                    prop.put("blocked", true);
                    tileProps.put(cx + "," + cy, prop);
                }
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", "decor_" + r.id() + "_" + decor.size());
        d.put("type", type);
        d.put("tile", List.of(x, y));
        decor.add(d);
        placed.add(new Placed(type, x, y));
        return true;
    }

    /**
     * 房间内自由格：在房间 rect 内、未占用、非走廊格、可通行、非墙；
     * forBlocked=true（挡路家具）时额外避开中心安全区（出生点/搜证点可通行兜底）。
     */
    private static boolean freeCell(int x, int y, Room r, int[][] ground, int[][] collision,
                                    int W, int H, Set<String> occupied, Set<String> corridorCells,
                                    boolean forBlocked) {
        if (x < 0 || y < 0 || x >= W || y >= H) return false;
        if (x < r.x() || y < r.y() || x >= r.x() + r.w() || y >= r.y() + r.h()) return false;
        if (forBlocked && inSafeZone(x, y, r)) return false;
        if (occupied.contains(x + "," + y) || corridorCells.contains(x + "," + y)) return false;
        return collision[y][x] == 0 && ground[y][x] != MapContract.TILE_WALL;
    }

    /** 房间中心安全区（2×2，clamp 到房间内）：挡路家具禁区，出生点/搜证点优先。 */
    private static boolean inSafeZone(int x, int y, Room r) {
        int cx0 = Math.max(r.x(), r.x() + r.w() / 2 - 1);
        int cy0 = Math.max(r.y(), r.y() + r.h() / 2 - 1);
        int cx1 = Math.min(r.x() + r.w(), cx0 + 2);
        int cy1 = Math.min(r.y() + r.h(), cy0 + 2);
        return x >= cx0 && x < cx1 && y >= cy0 && y < cy1;
    }

    /** 螺旋搜索最近自由位（锚点失败兜底，对齐原型沿边寻找）。 */
    private static int[] nearestFree(Room r, int fw, int fh, int x, int y, boolean forBlocked,
                                     int[][] ground, int[][] collision, int W, int H,
                                     Set<String> occupied, Set<String> corridorCells) {
        int maxD = Math.max(r.w(), r.h()) + 2;
        for (int d = 1; d <= maxD; d++) {
            for (int dy = -d; dy <= d; dy++) {
                for (int dx = -d; dx <= d; dx++) {
                    if (Math.abs(dx) + Math.abs(dy) != d) continue;
                    int cx = x + dx;
                    int cy = y + dy;
                    if (rectFree(cx, cy, fw, fh, r, forBlocked, ground, collision, W, H,
                            occupied, corridorCells)) {
                        return new int[]{cx, cy};
                    }
                }
            }
        }
        return null;
    }

    private static boolean rectFree(int x, int y, int fw, int fh, Room r, boolean forBlocked,
                                    int[][] ground, int[][] collision, int W, int H,
                                    Set<String> occupied, Set<String> corridorCells) {
        for (int dy = 0; dy < fh; dy++) {
            for (int dx = 0; dx < fw; dx++) {
                if (!freeCell(x + dx, y + dy, r, ground, collision, W, H, occupied, corridorCells, forBlocked)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 房间内随机自由格（rng 确定性；随机家具如 rock 挡路，避安全区）。 */
    private static int[] randomFreeCell(Room r, int[][] ground, int[][] collision, int W, int H,
                                        Set<String> occupied, Set<String> corridorCells, boolean forBlocked,
                                        BspMapGenerator.Rng rng) {
        List<int[]> cells = new ArrayList<>();
        for (int y = r.y(); y < r.y() + r.h(); y++) {
            for (int x = r.x(); x < r.x() + r.w(); x++) {
                if (freeCell(x, y, r, ground, collision, W, H, occupied, corridorCells, forBlocked)) {
                    cells.add(new int[]{x, y});
                }
            }
        }
        if (cells.isEmpty()) return null;
        return cells.get((int) Math.floor(rng.next() * cells.size()));
    }

    /** 自由格（zone/spawn 兜底）：安全区优先，再全房间扫描。 */
    private static int[] firstFreeCell(Room r, int sx, int sy,
                                       int[][] ground, int[][] collision, int W, int H,
                                       Set<String> occupied, Set<String> corridorCells) {
        int cx0 = Math.max(r.x(), r.x() + r.w() / 2 - 1);
        int cy0 = Math.max(r.y(), r.y() + r.h() / 2 - 1);
        int cx1 = Math.min(r.x() + r.w(), cx0 + 2);
        int cy1 = Math.min(r.y() + r.h(), cy0 + 2);
        for (int y = cy0; y < cy1; y++) {
            for (int x = cx0; x < cx1; x++) {
                if (freeCell(x, y, r, ground, collision, W, H, occupied, corridorCells, false)) {
                    return new int[]{x, y};
                }
            }
        }
        for (int y = r.y(); y < r.y() + r.h(); y++) {
            for (int x = r.x(); x < r.x() + r.w(); x++) {
                if (freeCell(x, y, r, ground, collision, W, H, occupied, corridorCells, false)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    /** 地面图案（top:n / bottom:n / border:1 / center），原型 apply_pattern 移植。 */
    private static void applyPattern(int[][] ground, Room r, List<StructureRoomTemplates.Pattern> patterns) {
        for (StructureRoomTemplates.Pattern p : patterns) {
            int w = r.w();
            int h = r.h();
            String rows = p.rows();
            int tile = p.tile();
            if (rows.startsWith("top:")) {
                int n = Math.min(Integer.parseInt(rows.substring(4)), h);
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < w; x++) ground[r.y() + y][r.x() + x] = tile;
                }
            } else if (rows.startsWith("bottom:")) {
                int n = Math.min(Integer.parseInt(rows.substring(7)), h);
                for (int y = Math.max(0, h - n); y < h; y++) {
                    for (int x = 0; x < w; x++) ground[r.y() + y][r.x() + x] = tile;
                }
            } else if ("border:1".equals(rows)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) ground[r.y() + y][r.x() + x] = tile;
                    }
                }
            } else if ("center".equals(rows)) {
                int cx0 = w / 2 - 1;
                int cy0 = h / 2 - 1;
                for (int y = Math.max(0, cy0); y < Math.min(h, cy0 + 2); y++) {
                    for (int x = Math.max(0, cx0); x < Math.min(w, cx0 + 2); x++) {
                        ground[r.y() + y][r.x() + x] = tile;
                    }
                }
            }
        }
    }

    /** L 形走廊：A 右侧墙环中点 → 水平 → B 左侧墙环中点（含两端门洞格）。 */
    private static List<List<Integer>> corridor(Room a, Room b) {
        List<List<Integer>> pts = new ArrayList<>();
        int ax = a.x() + a.w();
        int ay = a.y() + a.h() / 2;
        int bx = b.x() - 1;
        int by = b.y() + b.h() / 2;
        pts.add(List.of(ax, ay));
        int dx = Integer.compare(bx, ax);
        for (int x = ax; x != bx; x += dx) {
            pts.add(List.of(x + dx, ay));
        }
        int dy = Integer.compare(by, ay);
        for (int y = ay; y != by; y += dy) {
            pts.add(List.of(bx, y + dy));
        }
        return dedup(pts);
    }

    private static List<List<Integer>> dedup(List<List<Integer>> pts) {
        List<List<Integer>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (List<Integer> p : pts) {
            String k = p.get(0) + "," + p.get(1);
            if (seen.add(k)) out.add(p);
        }
        return out;
    }

    private static Room roomById(List<Room> rooms, String id) {
        for (Room r : rooms) {
            if (r.id().equals(id)) return r;
        }
        return null;
    }

    /** 每图结构树归属：子根只含本图节点 + 本图 relations 子集（校验零警告）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> subStructure(Map<String, Object> structure, List<Room> rooms,
                                                    String mapId) {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("version", MapContract.intOf(structure.get("version"), 1));
        st.put("kind", structure.get("kind"));
        st.put("name", structure.get("name"));
        st.put("seed", structure.get("seed"));
        st.put("map_id", mapId);
        Set<String> ids = new LinkedHashSet<>();
        for (Room r : rooms) ids.add(r.id());
        Map<String, Object> root = new LinkedHashMap<>();
        if (structure.get("root") instanceof Map<?, ?> r) {
            root.put("id", MapContract.str(r.get("id"), "root"));
            root.put("type", "structure");
            root.put("name", MapContract.str(r.get("name"), ""));
            root.put("template", "");
            List<Map<String, Object>> children = new ArrayList<>();
            if (r.get("children") instanceof List<?> cs) {
                for (Object c : cs) {
                    if (c instanceof Map<?, ?> cm && ids.contains(MapContract.str(cm.get("id"), ""))) {
                        children.add((Map<String, Object>) cm);
                    }
                }
            }
            root.put("children", children);
        }
        st.put("root", root);
        List<Map<String, Object>> subRels = new ArrayList<>();
        for (Map<String, Object> rel : StructureContract.relations(structure)) {
            String from = StructureContract.str(rel.get("from"), "");
            String to = StructureContract.str(rel.get("to"), "");
            if (ids.contains(from) && ids.contains(to)) subRels.add(rel);
        }
        st.put("relations", subRels);
        return st;
    }

    // ═══════════════════════════════════════════════════════════
    //  连接表（exit 图内 / warp 跨图）
    // ═══════════════════════════════════════════════════════════

    private static void addExitConnections(List<Map<String, Object>> connections, String mapId,
                                           Map<String, Object> map) {
        if (!(map.get("exits") instanceof List<?> exits)) return;
        for (Object o : exits) {
            if (!(o instanceof Map<?, ?> ex)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "exit");
            c.put("map_id", mapId);
            Map<String, Object> exit = new LinkedHashMap<>();
            exit.put("from", ex.get("from"));
            exit.put("to", ex.get("to"));
            exit.put("door", ex.get("door"));
            c.put("exit", exit);
            connections.add(c);
        }
    }

    /** 两图之间双向 warp + connections 条目（warp 点 = 各自第一房间中心，均可通行）。 */
    @SuppressWarnings("unchecked")
    private static void addWarp(Map<String, Map<String, Object>> maps,
                                List<Map<String, Object>> connections,
                                String aId, String bId) {
        Map<String, Object> a = maps.get(aId);
        Map<String, Object> b = maps.get(bId);
        List<Integer> pa = warpPoint(a);
        List<Integer> pb = warpPoint(b);
        if (pa == null || pb == null) return;
        List<Map<String, Object>> aw = (List<Map<String, Object>>) a.get("warps");
        List<Map<String, Object>> bw = (List<Map<String, Object>>) b.get("warps");
        if (aw == null || bw == null) return;

        Map<String, Object> wa = new LinkedHashMap<>();
        wa.put("id", "warp_" + aId + "_" + bId);
        wa.put("from", pa);
        wa.put("to", List.of(bId, pb.get(0), pb.get(1)));
        aw.add(wa);
        Map<String, Object> wb = new LinkedHashMap<>();
        wb.put("id", "warp_" + bId + "_" + aId);
        wb.put("from", pb);
        wb.put("to", List.of(aId, pa.get(0), pa.get(1)));
        bw.add(wb);

        connections.add(warpConnection(aId, bId, pa, List.of(bId, pb.get(0), pb.get(1))));
        connections.add(warpConnection(bId, aId, pb, List.of(aId, pa.get(0), pa.get(1))));
    }

    private static Map<String, Object> warpConnection(String fromMap, String toMap, List<Integer> from,
                                                      List<Object> to) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "warp");
        c.put("from_map", fromMap);
        c.put("to_map", toMap);
        Map<String, Object> warp = new LinkedHashMap<>();
        warp.put("from", from);
        warp.put("to", to);
        c.put("warp", warp);
        return c;
    }

    /** 第一房间中心（可通行内部；warp 落点）。 */
    private static List<Integer> warpPoint(Map<String, Object> map) {
        if (map.get("rooms") instanceof List<?> rooms && !rooms.isEmpty()
                && rooms.get(0) instanceof Map<?, ?> r) {
            int x = MapContract.intOf(r.get("x"), 0) + MapContract.intOf(r.get("w"), 0) / 2;
            int y = MapContract.intOf(r.get("y"), 0) + MapContract.intOf(r.get("h"), 0) / 2;
            return List.of(x, y);
        }
        return null;
    }
}
