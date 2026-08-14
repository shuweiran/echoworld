package com.roleplay.engine.simulation.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BSP 分区地图生成器 —— bsp.js generateBspMap 的 Java 等价移植（阶段 2 无 LLM 备选生成器/降级路径）。
 *
 * <p>确定性：同 seed 同输出（mulberry32 PRNG，与前端 bsp.js 同算法同常数）。
 * 输出契约 v1 结构（MapContract），校验器 {@link MapValidator} 直接可过（房间不重叠、走廊四邻接、
 * 热点/出生点均在可通行格——生成算法保证）。
 *
 * <p>流程（与 bsp.js 一致）：递归二分叶子 → 每叶子内生成房间（留 1 格墙边距）→ 兄弟子树房间间
 * 开 L 形走廊（连通 = 二叉树，全连通）→ 落格（外部=草地 3 不可通行、房间=地板 1/地毯 4/石板 5
 * 按房间序号取模交替、走廊=石板 5 可通行）→ zones（每房间一个搜证点，clue_location=房间名）
 * + spawn_points（玩家 1 + NPC 2）。
 *
 * <p>v0.2 扩展（P-0814-F，契约 docs/地图JSON契约-v1.md §v0.2）：
 * ① 房间地板多样性——floor(1)/carpet(4)/stone(5) 按房间序号取模交替铺装（确定性，不依赖 rng）；
 * ② spawnMarkers——grass 按 ~2% / debris 按 ~0.5% 密度随机撒点（种子确定性，避墙/走廊交叉口/门口）；
 * ③ decor——每房间 1-2 个静态装饰（type 从 pillar/flower_bed/bench/lamp 按房间序号确定性选取，
 *    落在房间内可通行非墙非 spawn 非热点非标记格，无 blocked 语义=渲染层使用）；
 * ④ warps——本批不产出（空数组），契约支持留给场景切换编排（既有 door zone 机制）。
 * 装饰密度硬编码常量（评估：生成器为确定性静态工具，配置键收益低，本批不加配置键，
 * 如需可调后续加 roleplay.game.map.decor-*-density 双份 yml）。
 */
public final class BspMapGenerator {

    /** 默认参数（与 bsp.js 一致）。 */
    public static final int DEFAULT_WIDTH = 24;
    public static final int DEFAULT_HEIGHT = 16;
    public static final int DEFAULT_SEED = 20260801;
    public static final int DEFAULT_MIN_LEAF = 7;
    public static final int DEFAULT_MIN_ROOM = 3;
    public static final int DEFAULT_ZONES_COUNT = 3;

    /** v0.2：spawnMarkers 撒点密度（按可撒格面积占比，确定性 rng）。 */
    public static final double GRASS_MARKER_DENSITY = 0.02;
    public static final double DEBRIS_MARKER_DENSITY = 0.005;

    /** v0.2：decor 静态装饰类型池（简单英文标识符，渲染层使用）。 */
    public static final List<String> DECOR_TYPES = List.of("pillar", "flower_bed", "bench", "lamp");

    /** 生成参数。 */
    public record Options(int width, int height, int tileSize, long seed, int minLeaf, int minRoom, int zonesCount) {
        public static Options defaults(long seed) {
            return new Options(DEFAULT_WIDTH, DEFAULT_HEIGHT, MapContract.DEFAULT_TILE_SIZE,
                    seed <= 0 ? DEFAULT_SEED : seed, DEFAULT_MIN_LEAF, DEFAULT_MIN_ROOM, DEFAULT_ZONES_COUNT);
        }

        /**
         * 显式参数构造（P-0803-J 地图容量扩展）：width/height ≤0 → 默认 24×16；seed ≤0 → 默认种子；
         * zonesCount &lt; 0 → 按面积自动缩放 {@link #scaledZonesCount}（默认 24×16 下等于 DEFAULT_ZONES_COUNT=3，
         * 旧调用方传 -1 行为不变；大图传 -1 自动获得与面积匹配的热点数）。
         */
        public static Options of(long seed, int width, int height, int zonesCount) {
            int w = width <= 0 ? DEFAULT_WIDTH : width;
            int h = height <= 0 ? DEFAULT_HEIGHT : height;
            return new Options(w, h, MapContract.DEFAULT_TILE_SIZE, seed <= 0 ? DEFAULT_SEED : seed,
                    DEFAULT_MIN_LEAF, DEFAULT_MIN_ROOM, zonesCount < 0 ? scaledZonesCount(w, h) : zonesCount);
        }
    }

    /**
     * 热点数按面积缩放（P-0803-J 地图容量扩展）：基准 24×16=384 格 ↔ DEFAULT_ZONES_COUNT=3，
     * 平方根缩放（面积×4 → 热点×2）——大图不空旷（64×64≈4096 格 → ≈10 个热点）、小图不过密，
     * 下限恒为 DEFAULT_ZONES_COUNT。实际 zone 数最终还受房间数封顶（生成时 min(zonesCount, rooms)）。
     */
    public static int scaledZonesCount(int width, int height) {
        int area = Math.max(1, width) * Math.max(1, height);
        double factor = Math.sqrt(area / (double) (DEFAULT_WIDTH * DEFAULT_HEIGHT));
        return Math.max(DEFAULT_ZONES_COUNT, (int) Math.round(DEFAULT_ZONES_COUNT * factor));
    }

    private BspMapGenerator() {
    }

    /** mulberry32（与 bsp.js makeRng 同算法同常数，保证跨端确定性）。 */
    public static final class Rng {
        private int a;

        public Rng(long seed) {
            this.a = (int) (seed & 0xFFFFFFFFL);
        }

        /** Math.imul 等价（JS 的 32 位带符号乘法，Java 需手写）。 */
        private static int imul(int x, int y) {
            return (int) ((long) x * y);
        }

        public double next() {
            a |= 0;
            a = (a + 0x6D2B79F5) | 0;
            int t = imul(a ^ (a >>> 15), 1 | a);
            t = (t + imul(t ^ (t >>> 7), 61 | t)) ^ t;
            // JS 的 (t ^ (t >>> 14)) >>> 0 是转无符号 32 位；Java int 为有符号，用掩码等价
            return (double) ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
    }

    private static final class Node {
        int x, y, w, h;
        Node a, b;
        Room room;
    }

    private record Room(String id, String name, int x, int y, int w, int h) {
    }

    /** 生成一张契约 v1 地图（同 seed 同输出）。 */
    public static Map<String, Object> generate(Options opts) {
        int W = opts.width();
        int H = opts.height();
        int tileSize = opts.tileSize() > 0 ? opts.tileSize() : MapContract.DEFAULT_TILE_SIZE;
        int minLeaf = opts.minLeaf() <= 0 ? DEFAULT_MIN_LEAF : opts.minLeaf();
        int minRoom = opts.minRoom() <= 0 ? DEFAULT_MIN_ROOM : opts.minRoom();
        int zonesCount = opts.zonesCount();
        Rng rng = new Rng(opts.seed());

        // 1) 递归二分叶子
        Node root = new Node();
        root.x = 0;
        root.y = 0;
        root.w = W;
        root.h = H;
        List<Node> leaves = new ArrayList<>();
        split(root, minLeaf, rng, leaves);

        // 2) 每叶子内生成房间（留 1 格墙边距）
        for (int i = 0; i < leaves.size(); i++) {
            Node leaf = leaves.get(i);
            int rw = minRoom + (int) Math.floor(rng.next() * Math.max(1, leaf.w - minRoom - 2));
            int rh = minRoom + (int) Math.floor(rng.next() * Math.max(1, leaf.h - minRoom - 2));
            rw = Math.min(rw, leaf.w - 2);
            rh = Math.min(rh, leaf.h - 2);
            int rx = leaf.x + 1 + (int) Math.floor(rng.next() * Math.max(1, leaf.w - rw - 1));
            int ry = leaf.y + 1 + (int) Math.floor(rng.next() * Math.max(1, leaf.h - rh - 1));
            char roomLetter = (char) ('A' + (i % 26));
            leaf.room = new Room("room_" + i, "房间 " + roomLetter, rx, ry, rw, rh);
        }

        // 3) 兄弟子树房间间开 L 形走廊（连通 = 二叉树，全连通）
        List<Map<String, Object>> corridors = new ArrayList<>();
        connect(root, rng, corridors);

        // 4) 落格：外部=草地(3)，房间=地板(1)/地毯(4)/石板(5) 按房间序号取模交替（v0.2 地板多样性），走廊=石板(5)
        int[][] ground = new int[H][W];
        for (int gy = 0; gy < H; gy++) {
            for (int gx = 0; gx < W; gx++) ground[gy][gx] = MapContract.TILE_GRASS;
        }
        int roomIdx = 0;
        for (Node leaf : leaves) {
            Room r = leaf.room;
            // P-0814-F：房间地板多样性——按房间序号取模确定性交替（不再依赖 rng 概率），
            // 0=木地板 1=地毯 2=石板；走廊仍为石板(5)，围合外恒为草地(3)（BSP 无门/围合墙，门口点缀不适用）
            int kind = roomIdx % 3;
            int floorTile = kind == 0 ? MapContract.TILE_FLOOR
                    : kind == 1 ? MapContract.TILE_CARPET : MapContract.TILE_STONE;
            roomIdx++;
            for (int y = r.y(); y < r.y() + r.h(); y++) {
                for (int x = r.x(); x < r.x() + r.w(); x++) {
                    ground[y][x] = floorTile;
                }
            }
        }
        for (Map<String, Object> cor : corridors) {
            @SuppressWarnings("unchecked")
            List<List<Integer>> pts = (List<List<Integer>>) cor.get("points");
            for (List<Integer> p : pts) ground[p.get(1)][p.get(0)] = MapContract.TILE_STONE;
        }

        // 碰撞层：草地(外部)不可通行，其余可通行
        int[][] collision = new int[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                collision[y][x] = ground[y][x] == MapContract.TILE_GRASS ? 1 : 0;
            }
        }

        // 5) zones / spawn_points
        List<Map<String, Object>> roomsOut = new ArrayList<>();
        List<Map<String, Object>> zones = new ArrayList<>();
        List<Room> roomList = new ArrayList<>();
        for (Node leaf : leaves) roomList.add(leaf.room);
        for (Room r : roomList) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id());
            rm.put("name", r.name());
            rm.put("x", r.x());
            rm.put("y", r.y());
            rm.put("w", r.w());
            rm.put("h", r.h());
            rm.put("tags", List.of("searchable"));
            roomsOut.add(rm);
        }
        int count = Math.min(zonesCount, roomList.size());
        for (int z = 0; z < count; z++) {
            Room room = roomList.get(z);
            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("id", "z_" + room.id());
            zone.put("name", room.name() + " 线索点");
            zone.put("type", "search");
            zone.put("x", room.x() + (int) Math.floor(room.w() / 2.0));
            zone.put("y", room.y() + (int) Math.floor(room.h() / 2.0));
            zone.put("radius", 1);
            zone.put("clue_location", room.name());
            zone.put("prompt", "（BSP 自动生成）这里似乎藏着什么线索……阶段 2 绑定剧本杀 clues[].location。");
            zones.add(zone);
        }
        List<Map<String, Object>> spawns = new ArrayList<>();
        Map<String, Object> sp0 = new LinkedHashMap<>();
        sp0.put("id", "sp_player");
        sp0.put("type", "player");
        sp0.put("x", roomList.get(0).x() + 1);
        sp0.put("y", roomList.get(0).y() + 1);
        spawns.add(sp0);
        for (int n = 1; n < Math.min(3, roomList.size()); n++) {
            Map<String, Object> sp = new LinkedHashMap<>();
            sp.put("id", "sp_npc_" + n);
            sp.put("type", "npc");
            sp.put("x", roomList.get(n).x() + 1);
            sp.put("y", roomList.get(n).y() + 1);
            spawns.add(sp);
        }

        // 6) v0.2：spawnMarkers（草 ~2% / 杂物 ~0.5% 密度，种子确定性撒点，避墙/走廊交叉口/门口）
        //    注：BSP 房间开放连通无门（围合墙/门洞为 LLM 声明式路径专属），门口语义不适用；
        //    候选格=可通行（collision=0）且非走廊（ground≠石板）的格——撒在房间地板/地毯上，走廊交叉口天然避开。
        Map<String, Object> spawnMarkers = buildSpawnMarkers(ground, collision, W, H, rng);

        // 7) v0.2：decor 静态装饰（每房间 1-2 个，种子确定性；不嵌墙、不占 spawn/热点/标记格；无 blocked 语义=渲染层使用）
        List<Map<String, Object>> decor = buildDecor(roomList, ground, collision, W, H, rng,
                spawnMarkers, zones, spawns);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("map_version", MapContract.CURRENT_VERSION);
        map.put("map_id", "bsp_seed_" + opts.seed());
        map.put("name", "BSP 生成地图");
        map.put("theme", "BSP 分区生成（无 LLM 备选生成器，契约与 LLM 输出同构）");
        map.put("tile_size", tileSize);
        map.put("width", W);
        map.put("height", H);
        Map<String, Object> tileset = new LinkedHashMap<>();
        tileset.put("src", "assets/tiles.png");
        tileset.put("first_gid", 1);
        tileset.put("tile_count", MapContract.DEFAULT_TILE_COUNT);
        map.put("tileset", tileset);
        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("kind", "bsp");
        generator.put("seed", opts.seed());
        generator.put("leaf_count", leaves.size());
        generator.put("note", "降级路径：LLM 失败/超时/输出不合法时的兜底生成器");
        map.put("generator", generator);
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("ground", MapContract.toIntList(ground));
        layers.put("collision", MapContract.toIntList(collision));
        map.put("layers", layers);
        map.put("rooms", roomsOut);
        map.put("corridors", corridors);
        map.put("zones", zones);
        map.put("spawn_points", spawns);
        // v0.2 扩展键（P-0814-F）：生成器指示 + 显式装饰；warps 本批不产出（空数组，场景切换走既有 door zone 编排）
        map.put("spawnMarkers", spawnMarkers);
        map.put("decor", decor);
        map.put("warps", List.of());
        return map;
    }

    // ═══════════════════════════════════════════════════════════
    //  v0.2 装饰生成（P-0814-F）
    // ═══════════════════════════════════════════════════════════

    /**
     * spawnMarkers：候选格（可通行且非走廊）按密度取目标数量后确定性无放回抽样（rng 驱动，种子确定性）。
     * target = max(minCount, round(size×density)) 封顶 size——grass 保底 1 个（视觉丰富度），
     * debris 诚实密度（小图可为 0，合法）。返回 {"grass": [[x,y],...], "debris": [[x,y],...]}。
     */
    private static Map<String, Object> buildSpawnMarkers(int[][] ground, int[][] collision, int W, int H, Rng rng) {
        List<int[]> candidates = new ArrayList<>();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (collision[y][x] == 0 && ground[y][x] != MapContract.TILE_STONE) {
                    candidates.add(new int[]{x, y});
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grass", pickMarkers(candidates, GRASS_MARKER_DENSITY, 1, rng));
        out.put("debris", pickMarkers(candidates, DEBRIS_MARKER_DENSITY, 0, rng));
        return out;
    }

    /** 确定性无放回抽样（部分 Fisher-Yates）：取 target = max(minCount, round(size×density)) 个，封顶 size。 */
    private static List<List<Integer>> pickMarkers(List<int[]> candidates, double density, int minCount, Rng rng) {
        List<List<Integer>> out = new ArrayList<>();
        if (candidates.isEmpty()) return out;
        int target = Math.max(minCount, (int) Math.round(candidates.size() * density));
        target = Math.min(target, candidates.size());
        List<int[]> pool = new ArrayList<>(candidates);
        for (int i = 0; i < target; i++) {
            int j = i + (int) Math.floor(rng.next() * (pool.size() - i));
            int[] pick = pool.get(j);
            pool.set(j, pool.get(i));
            out.add(List.of(pick[0], pick[1]));
        }
        return out;
    }

    /**
     * decor：每房间 1-2 个静态装饰（0-2 上限，实现为至少 1 个保证装饰密度；房间无可放置格则跳过）。
     * 类型按房间序号确定性选取（DECOR_TYPES）；位置=房间矩形内可通行非墙格，
     * 避开热点/出生点/spawnMarkers/已占用格（随机尝试 20 次 → 顺序扫描兜底）。
     */
    private static List<Map<String, Object>> buildDecor(List<Room> roomList, int[][] ground, int[][] collision,
            int W, int H, Rng rng, Map<String, Object> spawnMarkers, List<Map<String, Object>> zones,
            List<Map<String, Object>> spawns) {
        java.util.Set<String> occupied = new java.util.HashSet<>();
        for (Map<String, Object> z : zones) occupied.add(anchorKey(z));
        for (Map<String, Object> s : spawns) occupied.add(anchorKey(s));
        for (Object v : spawnMarkers.values()) {
            if (v instanceof List<?> pts) {
                for (Object p : pts) {
                    if (p instanceof List<?> pp && pp.size() == 2
                            && pp.get(0) instanceof Number && pp.get(1) instanceof Number) {
                        occupied.add(((Number) pp.get(0)).intValue() + "," + ((Number) pp.get(1)).intValue());
                    }
                }
            }
        }
        List<Map<String, Object>> decor = new ArrayList<>();
        int roomIdx = 0;
        for (Room r : roomList) {
            int count = 1 + (rng.next() < 0.5 ? 1 : 0); // 每房间 1-2 个（rng 确定性）
            for (int n = 0; n < count; n++) {
                int[] spot = pickRoomSpot(r, ground, collision, W, H, occupied, rng);
                if (spot == null) break;
                occupied.add(spot[0] + "," + spot[1]);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", "decor_" + roomIdx + "_" + n);
                d.put("type", DECOR_TYPES.get((roomIdx + n) % DECOR_TYPES.size()));
                d.put("tile", List.of(spot[0], spot[1]));
                decor.add(d);
            }
            roomIdx++;
        }
        return decor;
    }

    /** 房间矩形内找可放置格：随机尝试 20 次 → 顺序扫描兜底；占用格经 occupied.add 判定并占用。 */
    private static int[] pickRoomSpot(Room r, int[][] ground, int[][] collision, int W, int H,
            java.util.Set<String> occupied, Rng rng) {
        int x0 = r.x(), y0 = r.y(), x1 = r.x() + r.w(), y1 = r.y() + r.h();
        for (int attempt = 0; attempt < 20; attempt++) {
            int x = x0 + (int) Math.floor(rng.next() * r.w());
            int y = y0 + (int) Math.floor(rng.next() * r.h());
            if (x < 0 || y < 0 || x >= W || y >= H) continue;
            if (collision[y][x] != 0 || ground[y][x] == MapContract.TILE_WALL) continue;
            if (!occupied.add(x + "," + y)) continue;
            return new int[]{x, y};
        }
        for (int y = Math.max(0, y0); y < Math.min(H, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(W, x1); x++) {
                if (collision[y][x] != 0 || ground[y][x] == MapContract.TILE_WALL) continue;
                if (!occupied.add(x + "," + y)) continue;
                return new int[]{x, y};
            }
        }
        return null;
    }

    /** 热点/出生点地图项的 "x,y" 键（缺 x/y 时用不可占用键防误占）。 */
    private static String anchorKey(Map<String, Object> m) {
        int x = MapContract.intOf(m.get("x"), Integer.MIN_VALUE);
        int y = MapContract.intOf(m.get("y"), Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) return "__none__";
        return x + "," + y;
    }

    private static void split(Node node, int minLeaf, Rng rng, List<Node> leaves) {
        boolean canH = node.h >= minLeaf * 2;
        boolean canV = node.w >= minLeaf * 2;
        if (!canH && !canV) {
            leaves.add(node);
            return;
        }
        boolean horizontal;
        if (canH && canV) {
            // 偏好切长边，加随机扰动（70% 切长边 / 30% 随机）
            horizontal = (node.h >= node.w) ? (rng.next() < 0.7) : (rng.next() < 0.3);
        } else {
            horizontal = canH;
        }
        Node a = new Node();
        Node b = new Node();
        if (horizontal) {
            int splitY = minLeaf + (int) Math.floor(rng.next() * (node.h - minLeaf * 2 + 1));
            a.x = node.x;
            a.y = node.y;
            a.w = node.w;
            a.h = splitY;
            b.x = node.x;
            b.y = node.y + splitY;
            b.w = node.w;
            b.h = node.h - splitY;
        } else {
            int splitX = minLeaf + (int) Math.floor(rng.next() * (node.w - minLeaf * 2 + 1));
            a.x = node.x;
            a.y = node.y;
            a.w = splitX;
            a.h = node.h;
            b.x = node.x + splitX;
            b.y = node.y;
            b.w = node.w - splitX;
            b.h = node.h;
        }
        node.a = a;
        node.b = b;
        split(a, minLeaf, rng, leaves);
        split(b, minLeaf, rng, leaves);
    }

    /** 递归连通兄弟子树（二叉树全连通）；返回本子树代表房间。 */
    private static Room connect(Node node, Rng rng, List<Map<String, Object>> corridors) {
        if (node.a != null) {
            Room ra = connect(node.a, rng, corridors);
            Room rb = connect(node.b, rng, corridors);
            int c1x = ra.x() + (int) Math.floor(ra.w() / 2.0);
            int c1y = ra.y() + (int) Math.floor(ra.h() / 2.0);
            int c2x = rb.x() + (int) Math.floor(rb.w() / 2.0);
            int c2y = rb.y() + (int) Math.floor(rb.h() / 2.0);
            List<List<Integer>> pts = new ArrayList<>();
            boolean first = rng.next() < 0.5;
            if (first) {
                for (int x = c1x; x != c2x; x += (c2x > c1x ? 1 : -1)) pts.add(List.of(x, c1y));
                pts.add(List.of(c2x, c1y));
                for (int y = c1y; y != c2y; y += (c2y > c1y ? 1 : -1)) pts.add(List.of(c2x, y));
                pts.add(List.of(c2x, c2y));
            } else {
                for (int y = c1y; y != c2y; y += (c2y > c1y ? 1 : -1)) pts.add(List.of(c1x, y));
                pts.add(List.of(c1x, c2y));
                for (int x = c1x; x != c2x; x += (c2x > c1x ? 1 : -1)) pts.add(List.of(x, c2y));
                pts.add(List.of(c2x, c2y));
            }
            // 去重保序
            List<List<Integer>> uniq = new ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (List<Integer> p : pts) {
                String k = p.get(0) + "," + p.get(1);
                if (seen.add(k)) uniq.add(p);
            }
            Map<String, Object> cor = new LinkedHashMap<>();
            cor.put("id", "cor_" + corridors.size());
            cor.put("from", ra.id());
            cor.put("to", rb.id());
            cor.put("points", uniq);
            corridors.add(cor);
            return rng.next() < 0.5 ? ra : rb;
        }
        return node.room;
    }
}
