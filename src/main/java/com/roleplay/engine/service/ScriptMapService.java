package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.map.BspMapGenerator;
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.MapValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 地图生成统一路径（阶段 2，对齐 D-014 双生成器统一模式 + 契约 §4 校验器防线）。
 *
 * <p>生成链：LLM 生成（prompt 内嵌主题 + 剧本地点/线索地点，zones[].clue_location 对齐）→
 * 宽容归一 {@link MapContract#normalize} → 校验 {@link MapValidator#validateMap} →
 * 不合法（errors 非空）重试 1 次 → 仍不合法或 LLM 失败/超时/空输出 → BSP 降级
 * {@link BspMapGenerator#generate}（确定性，seed 可配）。
 *
 * <p>搜证线索绑定热点（契约 §5）：生成后对 zones[] 做宽容映射——clue_location 与剧本
 * clues[].location 不一致时依次尝试 trim / 同义词表；仍不匹配则保留原值（前端搜索该地点
 * 返回空线索，不崩）。
 */
@Service
public class ScriptMapService {

    private static final Logger log = LoggerFactory.getLogger(ScriptMapService.class);

    private final LLMClient llmClient;

    public ScriptMapService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /** LLM 失败/超时/输出不合法时的 BSP 降级种子（可配置，勿 hardcode 纪律——见 yml roleplay.game.map.bsp-seed）。 */
    public static final long DEFAULT_BSP_SEED = 20260801L;

    /**
     * LLM 路径 token 预算上限（P-0803-J 地图容量扩展的尺寸分界线）：40×24 格 = 960 格，
     * ground+collision 双层数组 ≈ 1920 个数字 ≈ 2500-3000 tokens，已逼近 callJson 4000 上限。
     * 显式请求尺寸超此上限（如 64×64）→ 跳过 LLM 直接 BSP 确定性生成（精确尺寸、零截断风险）。
     */
    public static final int LLM_MAX_WIDTH = 40;
    public static final int LLM_MAX_HEIGHT = 24;

    /** 同义词表（契约 §5 宽容映射预案）：键为 LLM 可能输出的变体，值为剧本标准地点。 */
    private static final Map<String, String> LOCATION_SYNONYMS = Map.ofEntries(
            Map.entry("客厅", "客厅"), Map.entry("大堂", "客厅"), Map.entry("大厅", "客厅"), Map.entry("会客厅", "客厅"),
            Map.entry("书房", "书房"), Map.entry("图书室", "书房"), Map.entry("图书馆", "书房"),
            Map.entry("卧室", "卧室"), Map.entry("主卧", "卧室"), Map.entry("客房", "卧室"),
            Map.entry("花园", "花园"), Map.entry("院子", "花园"), Map.entry("庭院", "花园"),
            Map.entry("厨房", "厨房"), Map.entry("灶房", "厨房"), Map.entry("餐厅", "厨房"),
            Map.entry("地下室", "地下室"), Map.entry("地窖", "地下室"),
            Map.entry("走廊", "走廊"), Map.entry("过道", "走廊"),
            Map.entry("浴室", "浴室"), Map.entry("卫生间", "浴室"), Map.entry("洗手间", "浴室"),
            Map.entry("车库", "车库"), Map.entry("停车库", "车库"),
            Map.entry("阳台", "阳台"), Map.entry("露台", "阳台"),
            Map.entry("阁楼", "阁楼"), Map.entry("顶楼", "阁楼"),
            Map.entry("储藏室", "储藏室"), Map.entry("储物间", "储藏室"),
            Map.entry("书房密室", "密室"), Map.entry("暗室", "密室"));

    /** 生成结果：map + generator 溯源 + 校验信息。 */
    public record MapResult(Map<String, Object> map, MapValidator.Result validation, List<String> fallbackReasons) {
        public boolean usedBsp() {
            return fallbackReasons != null && !fallbackReasons.isEmpty();
        }
    }

    /**
     * 生成地图（统一路径）——旧四参签名委托五参（width/height=0 → 默认 24×16，行为不变）。
     */
    public MapResult generateMap(String theme, List<String> locations, List<String> clueLocations, long seed) {
        return generateMap(theme, locations, clueLocations, seed, 0, 0);
    }

    /**
     * 生成地图（统一路径，P-0803-J 地图容量扩展：显式尺寸贯穿）。
     *
     * @param theme         主题（LLM prompt 输入）
     * @param locations     剧本 locations[]（房间语义参考 + zones 绑定时序）
     * @param clueLocations 剧本 clues[].location 去重列表（zones[].clue_location 对齐目标）
     * @param seed          BSP 降级种子（LLM 路径忽略）
     * @param width         显式地图宽度（≤0 → 默认 24；超 {@link #LLM_MAX_WIDTH} 时跳过 LLM 直接 BSP）
     * @param height        显式地图高度（≤0 → 默认 16；超 {@link #LLM_MAX_HEIGHT} 时跳过 LLM 直接 BSP）
     */
    public MapResult generateMap(String theme, List<String> locations, List<String> clueLocations, long seed,
                                 int width, int height) {
        List<String> fallbackReasons = new ArrayList<>();
        int effW = width > 0 ? width : BspMapGenerator.DEFAULT_WIDTH;
        int effH = height > 0 ? height : BspMapGenerator.DEFAULT_HEIGHT;

        // ── 大图预算闸：显式尺寸超 LLM token 预算 → 跳过 LLM 直接 BSP（确定性、精确尺寸） ──
        boolean llmBudgetOk = effW <= LLM_MAX_WIDTH && effH <= LLM_MAX_HEIGHT;
        if (!llmBudgetOk) {
            fallbackReasons.add("请求尺寸 " + effW + "×" + effH + " 超出 LLM token 预算上限 "
                    + LLM_MAX_WIDTH + "×" + LLM_MAX_HEIGHT + " → 直接 BSP 生成（大图确定性路径）");
        }

        // ── 路径 1：LLM 生成 → 归一 → 校验（不合法重试 1 次；仅预算内尺寸尝试） ──
        for (int attempt = 0; llmBudgetOk && attempt < 2; attempt++) {
            Map<String, Object> raw;
            try {
                // G1-3（P-0803-D）：token 预算 800 → 4000。对照 D-023 同款缺陷（剧本 JSON 600 被硬截断）；
                // 地图 JSON 含 ground+collision 双层数组（20-32×14-20 格 ≈ 560-1280 个数字）+ rooms/zones/spawns，
                // 估算输出 1300-2000+ tokens，800 必截断 → 高频 BSP 降级。4000 与 D-023 剧本档位对齐。
                // P-0803-F（超时修复）：单次调用 45s 上限（比剧本 60s 更激进）——地图输出小于剧本，
                // 45s 未完成说明模型慢/卡 → 快速走 BSP 降级，防止 init 自动串联（剧本+地图两次 LLM）被拖死超前端超时。
                // P-0803-J：prompt 内嵌本次要求尺寸（预算内）。
                raw = llmClient.callJson(buildPrompt(theme, locations, clueLocations, effW, effH), 4000, 45);
            } catch (Exception e) {
                log.warn("ScriptMapService: LLM call failed (attempt {}/2): {}", attempt + 1, e.getMessage());
                raw = Map.of();
            }
            if (raw == null || raw.isEmpty()) {
                fallbackReasons.add("LLM 输出为空（attempt " + (attempt + 1) + "）");
                continue;
            }
            Map<String, Object> normalized = MapContract.normalize(raw);
            // LLM 输出强制 map_version=1（宽容解析已兜底，这里再显式对齐）
            normalized.put("map_version", MapContract.CURRENT_VERSION);
            MapValidator.Result v = MapValidator.validateMap(normalized);
            if (v.ok()) {
                normalized.put("generator", generatorInfo("llm", attempt, seed));
                // P-0803-D（调研项 4 方案 A）：线索地点覆盖补齐——LLM 缺 zone 时自动补全
                Map<String, Object> covered = ensureClueZoneCoverage(bindClueLocations(normalized, clueLocations), clueLocations);
                log.info("ScriptMapService: LLM map OK (attempt {}), {} zones, {} rooms, {} spawns (coverage +{})",
                        attempt + 1, zoneCount(covered), roomCount(covered), spawnCount(covered),
                        zoneCount(covered) - zoneCount(normalized));
                return new MapResult(covered, v, fallbackReasons);
            }
            fallbackReasons.add("LLM 输出校验失败（attempt " + (attempt + 1) + "）：" + String.join("；", v.errors()));
            log.warn("ScriptMapService: LLM map invalid (attempt {}): {}", attempt + 1, v.errors());
        }

        // ── 路径 2：BSP 降级（确定性兜底，校验器保证可通过；显式尺寸 + 热点数按面积自动缩放） ──
        long effectiveSeed = seed <= 0 ? DEFAULT_BSP_SEED : seed;
        Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.of(effectiveSeed, effW, effH, -1));
        fallbackReasons.add("降级：BSP 生成器兜底（seed=" + effectiveSeed + "，尺寸 " + effW + "×" + effH + "）");
        MapValidator.Result v = MapValidator.validateMap(bsp);
        log.warn("ScriptMapService: fell back to BSP map (seed={}), validation ok={}", effectiveSeed, v.ok());
        Map<String, Object> bound = bindClueLocations(bsp, clueLocations);
        // P-0803-D（调研项 4 方案 A）：BSP 兜底地图 zone（房间 A/B/C）与线索脱钩 → 覆盖补齐
        Map<String, Object> covered = ensureClueZoneCoverage(bound, clueLocations);
        log.info("ScriptMapService: BSP map coverage pass: {} → {} zones", zoneCount(bound), zoneCount(covered));
        return new MapResult(covered, v, fallbackReasons);
    }

    // ═══════════════════════════════════════════════════════════
    //  zones[].clue_location ↔ clues[].location 宽容绑定（契约 §5）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对地图 zones[] 做线索地点绑定：search 型热点的 clue_location 归一为剧本标准地点。
     * 依次尝试：精确匹配 → trim 匹配 → 同义词表 → 保留原值。
     * 返回新 map（zones 重建，其余引用不变）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> bindClueLocations(Map<String, Object> map, List<String> clueLocations) {
        if (map == null) return map;
        Object zonesObj = map.get("zones");
        if (!(zonesObj instanceof List<?> zones) || clueLocations == null || clueLocations.isEmpty()) return map;

        List<String> standards = new ArrayList<>();
        for (String loc : clueLocations) {
            if (loc != null && !loc.isBlank()) standards.add(loc.trim());
        }

        List<Map<String, Object>> bound = new ArrayList<>();
        for (Object o : zones) {
            if (!(o instanceof Map<?, ?> zm)) {
                bound.add((Map<String, Object>) o);
                continue;
            }
            Map<String, Object> zone = new LinkedHashMap<>((Map<String, Object>) zm);
            String type = String.valueOf(zone.getOrDefault("type", "search"));
            if ("search".equals(type) && zone.get("clue_location") != null) {
                String clueLoc = String.valueOf(zone.get("clue_location")).trim();
                zone.put("clue_location", matchLocation(clueLoc, standards));
            }
            bound.add(zone);
        }

        Map<String, Object> out = new LinkedHashMap<>(map);
        out.put("zones", bound);
        return out;
    }

    /** 宽容匹配：精确 → trim → 同义词表 → 原值。 */
    public static String matchLocation(String raw, List<String> standards) {
        if (raw == null || raw.isBlank()) return raw == null ? "" : raw;
        String trimmed = raw.trim();
        for (String s : standards) {
            if (s.equals(trimmed)) return s;
        }
        for (String s : standards) {
            if (s.equals(trimmed)) return s;
            // 子串双向匹配（如「书房的书架」→「书房」）
            if (trimmed.contains(s) || s.contains(trimmed)) return s;
        }
        String synonym = LOCATION_SYNONYMS.getOrDefault(trimmed, LOCATION_SYNONYMS.getOrDefault(trimmed.toLowerCase(Locale.ROOT), ""));
        if (!synonym.isBlank()) {
            for (String s : standards) {
                if (s.equals(synonym)) return s;
            }
        }
        return trimmed;
    }

    // ═══════════════════════════════════════════════════════════
    //  线索地点覆盖补齐 pass（调研项 4 方案 A：G4-1/G4-2）
    // ═══════════════════════════════════════════════════════════

    /**
     * 线索地点覆盖补齐：对每个 distinct 线索地点，若 zones 中无 search 型热点的 clue_location（已归一）
     * 等于它，自动补一个 search zone（落在可通行格，优先同名房间中心）。一个 pass 同时解决
     * BSP 兜底地图与线索脱钩（G4-1）与 LLM 地图缺 zone（G4-2）——地图搜证不再必然空手。
     * 返回新 map；无新增时返回原 map 引用。放置算法保证新 zone 可通行 → 不引入校验错误。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> ensureClueZoneCoverage(Map<String, Object> map, List<String> clueLocations) {
        if (map == null || clueLocations == null || clueLocations.isEmpty()) return map;
        List<Map<String, Object>> zones = new ArrayList<>();
        if (map.get("zones") instanceof List<?> zl) {
            for (Object o : zl) {
                if (o instanceof Map<?, ?> z) zones.add(new LinkedHashMap<>((Map<String, Object>) z));
            }
        }
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (Map<String, Object> z : zones) {
            if ("search".equals(String.valueOf(z.getOrDefault("type", "search")))) {
                String cl = String.valueOf(z.getOrDefault("clue_location", "")).trim();
                if (!cl.isBlank()) covered.add(cl);
            }
        }
        int W = MapContract.intOf(map.get("width"), 0);
        int H = MapContract.intOf(map.get("height"), 0);
        int[][] collision = null;
        if (map.get("layers") instanceof Map<?, ?> lm) {
            collision = MapContract.intGrid(lm.get("collision"));
        }
        int nextId = zones.size() + 1;
        List<Map<String, Object>> added = new ArrayList<>();
        for (String loc : clueLocations) {
            if (loc == null || loc.isBlank()) continue;
            String standard = loc.trim();
            if (covered.contains(standard)) continue;
            int[] pos = findZoneSpot(map, collision, W, H, standard);
            if (pos == null) continue; // 畸形地图找不到可通行格 → 放弃该项，不阻塞
            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("id", "z_auto_" + (nextId++));
            zone.put("name", standard + " 线索点");
            zone.put("type", "search");
            zone.put("x", pos[0]);
            zone.put("y", pos[1]);
            zone.put("radius", 1);
            zone.put("clue_location", standard);
            zone.put("prompt", "（自动覆盖补齐）这里似乎藏着什么线索……");
            added.add(zone);
        }
        if (added.isEmpty()) return map;
        List<Map<String, Object>> outZones = new ArrayList<>(zones);
        outZones.addAll(added);
        Map<String, Object> out = new LinkedHashMap<>(map);
        out.put("zones", outZones);
        return out;
    }

    /** 找一个可通行格放置自动 zone：优先同名房间中心 → 任意房间中心 → 全图扫描；失败返回 null。 */
    private static int[] findZoneSpot(Map<String, Object> map, int[][] collision, int W, int H, String location) {
        if (map.get("rooms") instanceof List<?> rooms) {
            // 1) 同名房间（clue_location 与房间名一致 → 落在该房间中心附近）
            for (Object o : rooms) {
                if (!(o instanceof Map<?, ?> r)) continue;
                if (!location.equals(MapContract.str(r.get("name"), ""))) continue;
                int rx = MapContract.intOf(r.get("x"), 0), ry = MapContract.intOf(r.get("y"), 0);
                int rw = MapContract.intOf(r.get("w"), 0), rh = MapContract.intOf(r.get("h"), 0);
                int[] best = nearestWalkable(collision, W, H, rx + rw / 2, ry + rh / 2, rx, ry, rw, rh);
                if (best != null) return best;
            }
            // 2) 任意房间中心
            for (Object o : rooms) {
                if (!(o instanceof Map<?, ?> r)) continue;
                int rx = MapContract.intOf(r.get("x"), 0), ry = MapContract.intOf(r.get("y"), 0);
                int rw = MapContract.intOf(r.get("w"), 0), rh = MapContract.intOf(r.get("h"), 0);
                int[] best = nearestWalkable(collision, W, H, rx + rw / 2, ry + rh / 2, rx, ry, rw, rh);
                if (best != null) return best;
            }
        }
        // 3) 全图扫描（左上角起第一个可通行格）
        if (collision != null) {
            for (int y = 0; y < collision.length && y < H; y++) {
                if (collision[y] == null) continue;
                for (int x = 0; x < collision[y].length && x < W; x++) {
                    if (collision[y][x] == 0) return new int[]{x, y};
                }
            }
        }
        return null;
    }

    /** 矩形区域内距 (cx,cy) 曼哈顿距离最近的可通行格；区域内无可通行格返回 null。 */
    private static int[] nearestWalkable(int[][] collision, int W, int H, int cx, int cy, int rx, int ry, int rw, int rh) {
        if (collision == null) return null;
        int bestD = Integer.MAX_VALUE;
        int[] best = null;
        int x0 = Math.max(0, rx), y0 = Math.max(0, ry);
        int x1 = Math.min(W, rx + rw), y1 = Math.min(H, ry + rh);
        for (int y = y0; y < y1; y++) {
            if (y >= collision.length || collision[y] == null) continue;
            int[] row = collision[y];
            for (int x = x0; x < x1; x++) {
                if (x >= row.length) break;
                if (row[x] == 0) {
                    int d = Math.abs(x - cx) + Math.abs(y - cy);
                    if (d < bestD) {
                        bestD = d;
                        best = new int[]{x, y};
                    }
                }
            }
        }
        return best;
    }

    // ═══════════════════════════════════════════════════════════

    /** 地图生成 prompt（主题 + 地点/线索地点 → 契约 v1 JSON，zones.clue_location 与线索地点对齐）。旧签名委托新签名（尺寸不指定）。 */
    public static String buildPrompt(String theme, List<String> locations, List<String> clueLocations) {
        return buildPrompt(theme, locations, clueLocations, 0, 0);
    }

    /** 地图生成 prompt（P-0803-J：显式尺寸嵌入——预算内 LLM 路径按本次要求尺寸输出）。 */
    public static String buildPrompt(String theme, List<String> locations, List<String> clueLocations,
                                     int width, int height) {
        String locs = locations == null || locations.isEmpty() ? "（自由发挥 4-6 个地点）" : String.join("、", locations);
        String clues = clueLocations == null || clueLocations.isEmpty() ? "（自由发挥）" : String.join("、", clueLocations);
        String sizeHint = (width > 0 && height > 0)
                ? "width/height 为格数（本次要求 " + width + " × " + height
                + "，layers.ground/collision 二维数组必须严格为 height 行 × width 列）"
                : "width/height 为格数（建议 20-32 × 14-20）";
        return """
            你是一个剧本杀地图设计师。请为以下剧本生成一张 2D 地图（地图 JSON 契约 v1）。

            剧本主题：%s
            剧本地点（rooms[].name / zones 应覆盖这些地点）：%s
            线索所在地点（zones[].clue_location 必须逐一取自这里）：%s

            地图要求：
            - map_version: 1；%s；tile_size: 32
            - layers.ground：瓦片 id 二维数组（height 行 × width 列），1=木地板 2=墙 3=草地 4=地毯 5=石板
            - layers.collision：与 ground 同尺寸，1=不可通行（墙/外部边界）、0=可通行（房间/走廊内部）
              （房间内部必须是 0，热点与出生点不能埋在墙里）
            - rooms[]：每个房间 {id, name, x, y, w, h, tags}，x/y 为左上角格坐标，房间之间用走廊连通
            - corridors[]：可选，{id, from, to, points}，points 为四邻接连通路径 [[x,y],...]
            - zones[]：搜证热点（type 固定 "search"），每个热点 {id, name, type, x, y, radius, clue_location, prompt}，
              clue_location 必须与「线索所在地点」一致，x/y 必须在可通行格上；
              可选 door 型热点（type="door"，通往其他地图的门）：{id, name, type, x, y, radius, target}，
              target 为目标地图 id（如 "map_2"），同样必须落在可通行格上
            - spawn_points[]：{id, type(player/npc), x, y}，1 个玩家出生点 + 2-4 个 npc 出生点，必须在可通行格上

            返回JSON格式（不要任何markdown标记，纯JSON）：
            {"map_version": 1, "map_id": "脚本地图", "name": "地图名", "theme": "主题描述",
             "tile_size": 32, "width": %d, "height": %d,
             "tileset": {"src": "assets/tiles.png", "first_gid": 1, "tile_count": 5},
             "layers": {"ground": [[...]], "collision": [[...]]},
             "rooms": [{"id": "room_1", "name": "客厅", "x": 1, "y": 1, "w": 6, "h": 5, "tags": ["searchable"]}],
             "corridors": [],
             "zones": [{"id": "z_1", "name": "客厅八仙桌", "type": "search", "x": 3, "y": 3, "radius": 1, "clue_location": "客厅", "prompt": "..."}],
             "spawn_points": [{"id": "sp_player", "type": "player", "x": 2, "y": 2}]}
            """.formatted(theme, locs, clues, sizeHint, width > 0 ? width : 24, height > 0 ? height : 16);
    }

    // ── 溯源信息 ──

    private static Map<String, Object> generatorInfo(String kind, int attempt, long seed) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("kind", kind);
        g.put("model", "deepseek（LLM 管线）");
        g.put("attempt", attempt + 1);
        g.put("prompt_version", 1);
        g.put("bsp_seed", seed);
        g.put("note", "LLM 生成路径，输出经 MapValidator 校验通过");
        return g;
    }

    // ── 计数辅助（日志） ──

    private static int zoneCount(Map<String, Object> map) {
        Object z = map.get("zones");
        return z instanceof List<?> l ? l.size() : 0;
    }

    private static int roomCount(Map<String, Object> map) {
        Object r = map.get("rooms");
        return r instanceof List<?> l ? l.size() : 0;
    }

    private static int spawnCount(Map<String, Object> map) {
        Object s = map.get("spawn_points");
        return s instanceof List<?> l ? l.size() : 0;
    }
}
