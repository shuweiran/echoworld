package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.map.BspMapGenerator;
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.MapExits;
import com.roleplay.engine.simulation.map.MapValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** 地图生成使用主控 LLM；视觉审核才使用可选的多模态 MapLlmClient。 */
    private final LLMClient llmClient;

    /** 配置化的 BSP 降级种子（roleplay.game.map.bsp-seed，默认 0=未配置回退 {@link #DEFAULT_BSP_SEED}）。 */
    @Value("${roleplay.game.map.bsp-seed:0}")
    private long configuredBspSeed;

    /** P-0817-D（大图支持）：地图最大宽度（roleplay.game.map.max-width，默认 256；scene 预览路径也生效）。 */
    @Value("${roleplay.game.map.max-width:256}")
    private int mapMaxWidth = 256;

    /** P-0817-D（大图支持）：地图最大高度（roleplay.game.map.max-height，默认 256）。 */
    @Value("${roleplay.game.map.max-height:256}")
    private int mapMaxHeight = 256;

    public ScriptMapService(@Qualifier("arbiterLlmClient") LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /** LLM 失败/超时/输出不合法时的 BSP 降级种子兜底（未配置 roleplay.game.map.bsp-seed 时使用）。 */
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

    /** 生成结果：map + generator 溯源 + 校验信息。
     * P-0810-21（P0-2）：warnings = 生成后质量提示（如「rooms 未覆盖剧本地点」），不影响 usedBsp 判定。 */
    public record MapResult(Map<String, Object> map, MapValidator.Result validation, List<String> fallbackReasons,
                            List<String> warnings) {
        public boolean usedBsp() {
            return fallbackReasons != null && !fallbackReasons.isEmpty();
        }
    }

    /**
     * 生成地图（统一路径）——旧四参签名委托五参（width/height=0 → 默认 24×16，行为不变）。
     */
    public MapResult generateMap(String theme, List<String> locations, List<String> clueLocations, long seed) {
        return generateMap(theme, null, locations, clueLocations, seed, 0, 0);
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
        return generateMap(theme, null, locations, clueLocations, seed, width, height);
    }

    /**
     * 生成地图（统一路径，P-0810-21 P0-1：剧本上下文 background 注入——背景/真相仅用于氛围与场景布置参考）。
     *
     * @param theme         主题（LLM prompt 输入）
     * @param background    剧本背景/真相文本（background + truth 拼接；可空——旧调用方/预览路径零影响）
     * @param locations     剧本 locations[]（房间语义参考 + zones 绑定时序）
     * @param clueLocations 剧本 clues[].location 去重列表（zones[].clue_location 对齐目标）
     * @param seed          BSP 降级种子（LLM 路径忽略）
     * @param width         显式地图宽度（≤0 → 默认 24；超 {@link #LLM_MAX_WIDTH} 时跳过 LLM 直接 BSP）
     * @param height        显式地图高度（≤0 → 默认 16；超 {@link #LLM_MAX_HEIGHT} 时跳过 LLM 直接 BSP）
     */
    public MapResult generateMap(String theme, String background, List<String> locations, List<String> clueLocations,
                                 long seed, int width, int height) {
        List<String> fallbackReasons = new ArrayList<>();
        // 剧本杀地图维持既有 24×16 契约；64×40 仅是一般 2D 世界的默认边界，不能让
        // 未显式尺寸的剧本 LLM 输出突然与校验尺寸不一致。
        int effW = width > 0 ? width : 24;
        int effH = height > 0 ? height : 16;
        // P-0817-D（大图支持）：显式尺寸超上限 clamp（scene 预览 / script 双路径统一；默认尺寸不受影响）
        if (effW > mapMaxWidth || effH > mapMaxHeight) {
            int cw = Math.min(effW, mapMaxWidth);
            int ch = Math.min(effH, mapMaxHeight);
            fallbackReasons.add("请求尺寸 " + effW + "×" + effH + " 超出上限 "
                    + mapMaxWidth + "×" + mapMaxHeight + " → clamp 到 " + cw + "×" + ch);
            effW = cw;
            effH = ch;
        }

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
                // P-0818-B/E 修正：生成回 DeepSeek（快）→ 超时恢复 45s 快速降级
                raw = llmClient.callJson(buildPrompt(theme, background, locations, clueLocations, effW, effH), 8000, 45);
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
            // P-0804-H 续：声明式 LLM 输出可无 layers 网格（程序确定性生成墙体/地面）——
            // 校验前补空地网格（外边界 1 层墙 + 内部空地），ensureRoomWalls 再画房间墙
            Object nl0 = normalized.get("layers");
            boolean needBlank = true;
            if (nl0 instanceof Map<?, ?> l0) {
                int[][] c0 = MapContract.intGrid(l0.get("collision"));
                int[][] g0 = MapContract.intGrid(l0.get("ground"));
                int w0 = MapContract.intOf(normalized.get("width"), 0);
                int h0 = MapContract.intOf(normalized.get("height"), 0);
                needBlank = c0 == null || g0 == null || c0.length != h0 || g0.length != h0;
            }
            if (needBlank) {
                int w0 = MapContract.intOf(normalized.get("width"), effW);
                int h0 = MapContract.intOf(normalized.get("height"), effH);
                int[][] c0 = new int[h0][w0];
                int[][] g0 = new int[h0][w0];
                for (int y = 0; y < h0; y++) {
                    for (int x = 0; x < w0; x++) {
                        boolean edge = y == 0 || y == h0 - 1 || x == 0 || x == w0 - 1;
                        c0[y][x] = edge ? 1 : 0;
                        g0[y][x] = edge ? 2 : 1;
                    }
                }
                Map<String, Object> nl0b = new LinkedHashMap<>();
                nl0b.put("ground", MapContract.toIntList(g0));
                nl0b.put("collision", MapContract.toIntList(c0));
                normalized.put("layers", nl0b);
                log.info("ScriptMapService: LLM 声明式无网格 → 补空白地网格（{}×{}）", w0, h0);
            }
            MapValidator.Result v = MapValidator.validateMap(normalized);
            if (v.ok()) {
                // P-0804-H：房间围合墙（LLM 第二轮补墙）——v4-flash 从零生成不画墙，改为基于已有布局
                // 让 LLM 在网格上补墙（修改任务远比从零生成简单，原型实测四边 100% 围合）；失败则用第一轮结果
                Map<String, Object> walled = ensureRoomWalls(normalized, attempt);
                Map<String, Object> base = walled != null ? walled : normalized;
                base.put("generator", generatorInfo("llm", attempt, seed));
                if (walled != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> gen = (Map<String, Object>) base.get("generator");
                    gen.put("note", "LLM 布局 + 程序确定性围合（门位/墙样式由 LLM 声明，P-0804-H）");
                }
                // P-0803-D（调研项 4 方案 A）：线索地点覆盖补齐——LLM 缺 zone 时自动补全
                Map<String, Object> covered = ensureClueZoneCoverage(bindClueLocations(base, clueLocations), clueLocations);
                // P-0817-G（房间模式）：出口表确定性推导（LLM 声明式围合的门洞 → 邻房 BFS）
                covered = ensureExits(covered);
                List<String> warnings = missingRoomWarnings(covered, locations);
                if (!warnings.isEmpty()) {
                    log.warn("ScriptMapService: LLM map OK but rooms 未完全覆盖剧本地点: {}", warnings);
                }
                log.info("ScriptMapService: LLM map OK (attempt {}), {} zones, {} rooms, {} spawns (coverage +{})",
                        attempt + 1, zoneCount(covered), roomCount(covered), spawnCount(covered),
                        zoneCount(covered) - zoneCount(normalized));
                return new MapResult(covered, v, fallbackReasons, warnings);
            }
            // P-0804-G：坐标宽容修正——LLM 输出校验失败且错误仅为「热点/出生点落在不可通行格」时，
            // 自动将 zones/spawn_points 微调至最近可通行格（collision=0），修复后通过校验则不再降级 BSP。
            if (isOnlyOffGridAnchorErrors(v.errors())) {
                Map<String, Object> repaired = repairOffGridAnchors(normalized);
                if (repaired != null) {
                    MapValidator.Result v2 = MapValidator.validateMap(repaired);
                    if (v2.ok()) {
                        Map<String, Object> gen = generatorInfo("llm", attempt, seed);
                        gen.put("note", "坐标宽容修正：热点/出生点自动微调至最近可通行格（P-0804-G）");
                        repaired.put("generator", gen);
                        Map<String, Object> covered2 = ensureClueZoneCoverage(bindClueLocations(repaired, clueLocations), clueLocations);
                        // P-0817-G（房间模式）：出口表确定性推导（anchor 修复路径同补）
                        covered2 = ensureExits(covered2);
                        log.info("ScriptMapService: LLM map OK after anchor repair (attempt {}), {} zones, {} rooms", attempt + 1, zoneCount(covered2), roomCount(covered2));
                        return new MapResult(covered2, v2, fallbackReasons, missingRoomWarnings(covered2, locations));
                    }
                }
            }
            fallbackReasons.add("LLM 输出校验失败（attempt " + (attempt + 1) + "）：" + String.join("；", v.errors()));
            log.warn("ScriptMapService: LLM map invalid (attempt {}): {}", attempt + 1, v.errors());
        }

        // ── 路径 2：BSP 降级（确定性兜底，校验器保证可通过；显式尺寸 + 热点数按面积自动缩放） ──
        long effectiveSeed = seed <= 0 ? (configuredBspSeed > 0 ? configuredBspSeed : DEFAULT_BSP_SEED) : seed;
        Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.of(effectiveSeed, effW, effH, -1));
        fallbackReasons.add("降级：BSP 生成器兜底（seed=" + effectiveSeed + "，尺寸 " + effW + "×" + effH + "）");
        MapValidator.Result v = MapValidator.validateMap(bsp);
        log.warn("ScriptMapService: fell back to BSP map (seed={}), validation ok={}", effectiveSeed, v.ok());
        Map<String, Object> bound = bindClueLocations(bsp, clueLocations);
        // P-0803-D（调研项 4 方案 A）：BSP 兜底地图 zone（房间 A/B/C）与线索脱钩 → 覆盖补齐
        Map<String, Object> covered = ensureClueZoneCoverage(bound, clueLocations);
        // P-0817-G（房间模式）：出口表确定性推导（BSP 走廊贴边门洞，双保险）
        covered = ensureExits(covered);
        log.info("ScriptMapService: BSP map coverage pass: {} → {} zones", zoneCount(bound), zoneCount(covered));
        List<String> warnings = missingRoomWarnings(covered, locations);
        if (!warnings.isEmpty()) {
            log.warn("ScriptMapService: BSP map rooms 未覆盖剧本地点（BSP 兜底房间为占位名，符合既有语义）: {}", warnings);
        }
        return new MapResult(covered, v, fallbackReasons, warnings);
    }

    /**
     * P-0817-G（房间模式）：出口表确定性推导——exits 缺失或为空时用 {@link MapExits#deriveExits}
     * 从 rooms/collision 几何推导（LLM 声明式围合门洞 / BSP 走廊贴边均适用），并写回 map。
     * 已存在 exits（如 BSP 生成器自带）则原样保留。
     */
    private static Map<String, Object> ensureExits(Map<String, Object> map) {
        if (map == null) return map;
        Object ex = map.get("exits");
        if (ex instanceof List<?> l && !l.isEmpty()) return map;
        Map<String, Object> out = new LinkedHashMap<>(map);
        out.put("exits", MapExits.deriveExits(map));
        return out;
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
    //  P0-2（P-0810-21）：rooms 覆盖 locations 校验（贴合度护栏）
    // ═══════════════════════════════════════════════════════════

    /**
     * P0-2：计算剧本地点中「未被 rooms[].name ∪ zones[].clue_location 覆盖」的地点列表。
     * 复用 {@link #matchLocation} 同义词宽容匹配（与 zones 侧 bindClueLocations 同一容错风格），
     * 避免「餐厅 vs 厨房」类命名差异误报；zones 侧已由 ensureClueZoneCoverage 自动补齐（不误杀），
     * 此处仅 rooms 侧的质量提示——缺失不降级 BSP（房间数 ≠ 地点数不是校验错误）。
     */
    public static List<String> missingRoomLocations(Map<String, Object> map, List<String> locations) {
        if (map == null || locations == null || locations.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String loc : locations) {
            if (loc == null || loc.isBlank()) continue;
            String standard = loc.trim();
            if (coveredBy(map, standard)) continue;
            missing.add(standard);
        }
        return missing;
    }

    /** 单个地点是否被 rooms[].name（宽容匹配）或 zones[].clue_location（已归一精确匹配）覆盖。 */
    private static boolean coveredBy(Map<String, Object> map, String standard) {
        if (map.get("rooms") instanceof List<?> rooms) {
            for (Object o : rooms) {
                if (o instanceof Map<?, ?> r) {
                    String name = MapContract.str(r.get("name"), "");
                    if (!name.isBlank() && matchLocation(name, List.of(standard)).equals(standard)) return true;
                }
            }
        }
        if (map.get("zones") instanceof List<?> zones) {
            for (Object o : zones) {
                if (o instanceof Map<?, ?> z) {
                    String cl = MapContract.str(z.get("clue_location"), "");
                    if (cl.trim().equals(standard)) return true;
                }
            }
        }
        return false;
    }

    /** 生成后质量提示（缺失地点 → warning 文案；无缺失 → 空列表）。 */
    private static List<String> missingRoomWarnings(Map<String, Object> map, List<String> locations) {
        List<String> missing = missingRoomLocations(map, locations);
        if (missing.isEmpty()) return List.of();
        return List.of("rooms 未覆盖剧本地点（" + String.join("、", missing)
                + "）——地图房间与剧本地点存在偏差；搜证可用性已由 zones 覆盖兜底（ensureClueZoneCoverage）");
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
        return buildPrompt(theme, null, locations, clueLocations, 0, 0);
    }

    /** 地图生成 prompt（P-0803-J：显式尺寸嵌入——预算内 LLM 路径按本次要求尺寸输出）。 */
    public static String buildPrompt(String theme, List<String> locations, List<String> clueLocations,
                                     int width, int height) {
        return buildPrompt(theme, null, locations, clueLocations, width, height);
    }

    /**
     * 地图生成 prompt（P-0810-21 P0-1：剧本上下文注入——background 段仅用于氛围与场景布置参考，
     * rooms/zones 地点仍以「剧本地点」为准，防止 LLM 把背景中的场景名词当作地点来源）。
     */
    public static String buildPrompt(String theme, String background, List<String> locations, List<String> clueLocations,
                                     int width, int height) {
        String locs = locations == null || locations.isEmpty() ? "（自由发挥 4-6 个地点）" : String.join("、", locations);
        String clues = clueLocations == null || clueLocations.isEmpty() ? "（自由发挥）" : String.join("、", clueLocations);
        String bg = (background == null || background.isBlank())
                ? "（无背景信息——仅按剧本地点生成）" : background.trim();
        String sizeHint = (width > 0 && height > 0)
                ? "width/height 为格数（本次要求 " + width + " × " + height
                + "，layers.ground/collision 二维数组必须严格为 height 行 × width 列）"
                : "width/height 为格数（建议 20-32 × 14-20）";
        return """
            你是一个剧本杀地图设计师。请为以下剧本生成一张 2D 地图（地图 JSON 契约 v1）。

            剧本主题：%s
            剧本背景（仅用于氛围与场景布置参考——rooms/zones 的地点仍以「剧本地点」为准，
            不得把背景中的场景名词当作地点来源）：%s
            剧本地点（rooms[].name 必须严格覆盖这些地点，不得遗漏、不得凭空增减；zones 也应覆盖这些地点）：%s
            线索所在地点（zones[].clue_location 必须逐一取自这里）：%s

            地图要求：
            - map_version: 1；%s；tile_size: 32
            - layers.ground：瓦片 id 二维数组（height 行 × width 列），1=木地板 2=墙 3=草地 4=地毯 5=石板
            - layers.collision：与 ground 同尺寸，1=不可通行（墙/外部边界）、0=可通行（房间/走廊内部）
              （房间内部必须是 0，热点与出生点不能埋在墙里）
            - 开阔布局：地图可通行格（collision=0）必须占全图一半以上；墙体只用于外边界与房间分隔，
              禁止用墙大面积填满地图；房间/花园/走廊之间留出开阔空地
            - 热点（zones）均匀分布在各房间/花园/空地，热点之间间隔至少 4 格，全部落在可通行格上
            - rooms[]：每个房间 {id, name, x, y, w, h, tags}，x/y 为左上角格坐标，房间之间用走廊连通；
              房间名单必须与「剧本地点」一一对应（不得遗漏任何地点，也不得凭空增加地点）
            - 形状自由（P-0804-H）：房间形状由你自由设计——可为矩形，也可为 L 形/T 形/凹形等不规则形状
              （不规则房间用 2-3 个同 id 同 name 的矩形组合表示，彼此相邻拼成整体）；不要所有房间都千篇一律的规整矩形
            - 墙体类别最多两层（P-0804-H）：任何墙体厚度最多 2 层瓦片，禁止 3 格以上厚墙浪费空间
            - 房间必须由墙围合（P-0804-H）：每个房间区域四周必须有 1 层墙（ground=2 且 collision=1），
              形成明确的独立空间；每面墙留 1-2 个门洞（1 格宽，ground=1 且 collision=0）连通走廊/相邻房间；
              墙体编码示例（一个 4×4 房间，四周 1 层墙 + 下墙中间 1 个门洞）：
                collision: [[1,1,1,1,1,1],[1,0,0,0,0,1],[1,0,0,0,0,1],[1,0,0,0,0,1],[1,0,0,0,0,1],[1,1,0,1,1,1]]
                ground:    [[2,2,2,2,2,2],[2,1,1,1,1,2],[2,1,1,1,1,2],[2,1,1,1,1,2],[2,1,1,1,1,2],[2,2,1,2,2,2]]
              花园/庭院等开阔区域不需要围合墙，但建筑物内每个房间必须围合
            - 房间可声明门位与墙样式（P-0804-H，程序将按声明确定性围合）：
              doors: [{\"side\": \"top|bottom|left|right\", \"offset\": 0~1}]（offset 为该边相对位置，0.5=正中；每边最多 1 个门，
              不声明的边程序自动在正中开门洞）；wallStyle: \"brick|wood|stone\"（可选，墙瓦片样式）；
              每个房间建议声明 1-3 个门（面向走廊/相邻房间/花园的方向），大门房间（如别墅大门）声明 offset 0.5
            - corridors[]：可选，{id, from, to, points}，points 为四邻接连通路径 [[x,y],...]
            - 不要输出 layers（ground/collision 网格）——墙体、地面、外边界由程序确定性生成，
              你只需声明房间几何与门位；程序会为每个房间四边画 1 层墙并按 doors 开门洞
            - zones[]：搜证热点（type 固定 \"search\"），每个热点 {id, name, type, x, y, radius, clue_location, prompt}，
              clue_location 必须与「线索所在地点」一致，x/y 必须在可通行格上；
              可选 door 型热点（type=\"door\"，通往其他地图的门）：{id, name, type, x, y, radius, target}，
              target 为目标地图 id（如 \"map_2\"），同样必须落在可通行格上
            - spawn_points[]：{id, type(player/npc), x, y}，1 个玩家出生点 + 2-4 个 npc 出生点，必须在可通行格上

            可选增强键（v0.2 扩展，P-0814-F；全部可省略——不输出任何增强键也完全合法；输出则必须满足约束）：
            - layers.objects：Front 层静态装饰类型名二维数组（与 ground 同尺寸；元素为字符串或 null，
              如 [["tree_oak", null, "fence"], ...]；类型用简单英文标识符：tree_oak/fence/flower_bed/pillar/bench/lamp）
            - layers.overlay：AlwaysFront 前景遮罩二维数组（与 ground 同尺寸；元素为字符串或 null，如 "canopy"）
            - tileProps：每格属性字典（稀疏，只写非默认格）：{"x,y": {"blocked": true, "water": true,
              "action": "examine", "args": "wall_painting"}}——键必须为 "x,y" 坐标字符串，值必须为对象字典
            - decor：显式装饰/交互物列表：[{"id": "chest_1", "type": "chest", "tile": [6,10],
              "state": {"locked": true}, "onInteract": {"dialog": "..."}, "once": false, "radius": 1}]
              ——id 全局唯一且非空、type 为简单英文标识符、tile 为 [x,y] 且不能落在墙格（ground=2）上
            - spawnMarkers：生成器指示：{"grass": [[2,2],[3,2]], "debris": [[30,40]]}（类别名为键，值为坐标数组）
            - warps：传送点（可省略）：[{"from": [63,20], "to": ["town", 10, 30]}]（to 为 [地图id字符串, x, y]）
            约束：所有坐标必须 0≤x<width、0≤y<height；瓦片 id 只允许 1-5（layers.ground 内）；
            objects/overlay/decor.type 是字符串类型名不是瓦片 id

            返回JSON格式（不要任何markdown标记，纯JSON；不输出 layers 网格）：
            {\"map_version\": 1, \"map_id\": \"脚本地图\", \"name\": \"地图名\", \"theme\": \"主题描述\",
             \"tile_size\": 32, \"width\": %d, \"height\": %d,
             \"rooms\": [{\"id\": \"room_1\", \"name\": \"客厅\", \"x\": 1, \"y\": 1, \"w\": 6, \"h\": 5,
                        \"doors\": [{\"side\": \"bottom\", \"offset\": 0.5}], \"tags\": [\"searchable\"]}],
             \"corridors\": [],
             \"zones\": [{\"id\": \"z_1\", \"name\": \"客厅八仙桌\", \"type\": \"search\", \"x\": 3, \"y\": 3, \"radius\": 1, \"clue_location\": \"客厅\", \"prompt\": \"...\"}],
             \"spawn_points\": [{\"id\": \"sp_player\", \"type\": \"player\", \"x\": 2, \"y\": 2}],
             \"decor\": [{\"id\": \"decor_1\", \"type\": \"bench\", \"tile\": [2, 2]}],
             \"spawnMarkers\": {\"grass\": [[3, 3]]}}
            """.formatted(theme, bg, locs, clues, sizeHint, width > 0 ? width : 24, height > 0 ? height : 16);
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

    /** P-0804-G：只回答——「热点/出生点落在不可通行格」一类错误时才尝试修复（其他错误类型不掩盖，防放松校验）。 */
    private static boolean isOnlyOffGridAnchorErrors(java.util.List<String> errors) {
        if (errors == null || errors.isEmpty()) return false;
        for (String e : errors) {
            if (!e.contains("落在不可通行格")) return false;
        }
        return true;
    }

    /** P-0804-G：坐标宽容修正——将落在不可通行格或越界的 zones/spawn_points 微调至最近可通行格。无需修复返回 null。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> repairOffGridAnchors(Map<String, Object> m) {
        Object layersObj = m.get("layers");
        if (!(layersObj instanceof Map<?, ?> layers)) return null;
        int[][] collision = MapContract.intGrid(layers.get("collision"));
        int width = MapContract.intOf(m.get("width"), 0);
        int height = MapContract.intOf(m.get("height"), 0);
        if (collision == null || width <= 0 || height <= 0) return null;
        boolean any = false;
        Object zonesObj = m.get("zones");
        if (zonesObj instanceof List<?> zones) {
            // 深拷贝重建（LLM mock/不可变 List 场景兼容）：只替换需修复的元素，其余原样
            java.util.List<Map<String, Object>> rebuilt = new java.util.ArrayList<>();
            for (Object zo : zones) {
                if (!(zo instanceof Map<?, ?> zm)) continue;
                Map<String, Object> zc = new java.util.LinkedHashMap<>((Map<String, Object>) zm);
                int x = MapContract.intOf(zc.get("x"), -1);
                int y = MapContract.intOf(zc.get("y"), -1);
                if (x < 0 || y < 0 || x >= width || y >= height || collision[y][x] != 0) {
                    int[] near = nearestWalkable(collision, width, height, x, y);
                    if (near != null) {
                        zc.put("x", near[0]);
                        zc.put("y", near[1]);
                        any = true;
                    }
                }
                rebuilt.add(zc);
            }
            m.put("zones", rebuilt);
        }
        Object spObj = m.get("spawn_points");
        if (spObj instanceof List<?> spawns) {
            java.util.List<Map<String, Object>> rebuilt = new java.util.ArrayList<>();
            for (Object so : spawns) {
                if (!(so instanceof Map<?, ?> sm)) continue;
                Map<String, Object> sc = new java.util.LinkedHashMap<>((Map<String, Object>) sm);
                int x = MapContract.intOf(sc.get("x"), -1);
                int y = MapContract.intOf(sc.get("y"), -1);
                if (x < 0 || y < 0 || x >= width || y >= height || collision[y][x] != 0) {
                    int[] near = nearestWalkable(collision, width, height, x, y);
                    if (near != null) {
                        sc.put("x", near[0]);
                        sc.put("y", near[1]);
                        any = true;
                    }
                }
                rebuilt.add(sc);
            }
            m.put("spawn_points", rebuilt);
        }
        return any ? m : null;
    }

    /** 近似查找最近可通行格（collision=0）：从原点开始按曼哈顿半径逐层扩散扫描。 */
    private static int[] nearestWalkable(int[][] collision, int width, int height, int ox, int oy) {
        for (int r = 0; r <= Math.max(width, height); r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) + Math.abs(dy) != r) continue;
                    int nx = ox + dx, ny = oy + dy;
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    if (collision[ny][nx] == 0) return new int[] { nx, ny };
                }
            }
        }
        return null;
    }


    /**
     * P-0804-H：房间围合墙（LLM 第二轮补墙）。
     * 围合度检查：每个房间四边中「墙覆盖边比例」< 0.6 即视为未围合 → 触发第二轮 LLM 调用，
     * 基于第一轮布局在网格上补墙（修改任务，v4-flash 可稳定完成）。返回补墙后的地图；无需补/失败 → null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureRoomWalls(Map<String, Object> map, int attempt) {
        Object roomsObj = map.get("rooms");
        if (!(roomsObj instanceof List<?> rooms) || rooms.isEmpty()) return null;
        Object layersObj = map.get("layers");
        int width = MapContract.intOf(map.get("width"), 0);
        int height = MapContract.intOf(map.get("height"), 0);
        if (width <= 0 || height <= 0) return null;
        int[][] col, ground;
        Map<String, Object> nl;
        // P-0804-H 续：LLM 声明式可不出 layers 网格（程序确定性生成）——缺失/无效时从零建空地网格：
        // 外边界 1 层墙 + 内部全空地（ground=1 可通行），再由 deterministicEnclose 画房间墙
        if (layersObj instanceof Map<?, ?> layers && MapContract.intGrid(layers.get("collision")) != null
                && MapContract.intGrid(layers.get("ground")) != null) {
            col = MapContract.intGrid(layers.get("collision"));
            ground = MapContract.intGrid(layers.get("ground"));
            nl = new LinkedHashMap<>((Map<String, Object>) layers);
        } else {
            col = new int[height][width];
            ground = new int[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean edge = y == 0 || y == height - 1 || x == 0 || x == width - 1;
                    col[y][x] = edge ? 1 : 0;
                    ground[y][x] = edge ? 2 : 1;
                }
            }
            nl = new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>(map);
        // P-0804-H：确定性围合（LLM 声明门位/墙样式 + 程序保证几何）——替代 LLM 第二轮补墙（质量不稳定：
        // 常只画零散墙块/误埋热点/墙厚失控）。房间四边强制 1 层墙，每边按 rooms 声明开 1 门洞（默认正中），
        // 房间内部草稿墙清空（保证开阔）；连通性由「每边门洞」天然保证。
        // P-0804-H 续：thinWallsToMaxTwo 已整体废弃（不再调用）——声明式时代墙全部由 deterministicEnclose
        // 程序绘制（每边 1 层，相邻房间 2 层），天然满足「≤2 层」；任何 run 长度/厚度削薄都会误伤
        // 房间整面长墙（原实现把下墙整行 32 格削掉 2/3 → 主人反馈「墙体感官/边界墙」问题根因）
        // 厚度控制在源头：deterministicEnclose 只画 1 层 + 房间内部草稿墙清空
        deterministicEnclose(ground, col, width, height, rooms);
        nl.put("ground", MapContract.toIntList(ground));
        nl.put("collision", MapContract.toIntList(col));
        out.put("layers", nl);
        MapValidator.Result v2 = MapValidator.validateMap(out);
        if (!v2.ok()) {
            // 围合可能误埋 zones/spawns（如门洞位置与热点冲突）→ 坐标宽容修正（挪至最近可通行格）后重验
            Map<String, Object> repaired = repairOffGridAnchors(out);
            if (repaired != null) {
                MapValidator.Result v3 = MapValidator.validateMap(repaired);
                if (v3.ok()) {
                    log.info("ScriptMapService: enclosure OK after anchor repair (attempt {}), {} rooms enclosed", attempt + 1, rooms.size());
                    return repaired;
                }
            }
            log.warn("ScriptMapService: enclosure invalid: {}", v2.errors());
            return null;
        }
        log.info("ScriptMapService: enclosure OK (attempt {}), {} rooms enclosed", attempt + 1, rooms.size());
        return out;
    }

    /**
     * P-0804-H：确定性围合——LLM 参与墙体设计的方式是声明门位与墙样式（rooms[].doors/wallStyle），
     * 程序保证几何：每个非花园房间四边强制画 1 层墙（覆盖 LLM 草稿墙，几何统一），每边按声明开 1 个门洞
     * （默认正中，offset 0~1 可调）；房间内部草稿墙清空（保证室内开阔，家具/热点是 zones 不受影响）。
     * 花园/庭院/后院不围合（保持开阔）；「每边门洞」保证房间间连通性。
     */
    private static void deterministicEnclose(int[][] ground, int[][] col, int width, int height, List<?> rooms) {
        // P-0804-H 续：三阶段重构——Phase1 所有房间画墙（无门洞）→ Phase2 统一开所有门洞 →
        // Phase3 BFS 保底连通（封闭房间补洞）。原逐房间「画墙+开门洞」会导致相邻房间共用墙的
        // 门洞互相覆盖（后画的把先画的洞填回墙）→ 房间完全封闭（主人反馈「路太少/完全封闭」）
        java.util.List<int[]> rects = new java.util.ArrayList<>();
        java.util.List<int[]> doors = new java.util.ArrayList<>();
        for (Object ro : rooms) {
            if (!(ro instanceof Map<?, ?> r)) continue;
            int rx = MapContract.intOf(r.get("x"), -1), ry = MapContract.intOf(r.get("y"), -1);
            int rw = MapContract.intOf(r.get("w"), -1), rh = MapContract.intOf(r.get("h"), -1);
            if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0) continue;
            String name = String.valueOf(r.get("name"));
            if (name.contains("花园") || name.contains("庭院") || name.contains("后院")) continue;
            // 房间离地图边界至少 1 格（clamp）——贴边房间四边墙无法完整生成
            rx = Math.max(1, Math.min(rx, width - 2));
            ry = Math.max(1, Math.min(ry, height - 2));
            rw = Math.min(rw, width - 1 - rx);
            rh = Math.min(rh, height - 1 - ry);
            if (rw <= 0 || rh <= 0) continue;
            // P-0804-H 续：clamp 后的坐标写回 rooms 元数据——前端渲染/校验用坐标与实际墙对齐
            // （否则贴边房间声明 x=0 但实际墙在 x=1 → 元数据错位）；rooms 为 LLM 解析的 LinkedHashMap 可变
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> rm = (Map<String, Object>) r;
                rm.put("x", rx); rm.put("y", ry); rm.put("w", rw); rm.put("h", rh);
            } catch (UnsupportedOperationException e) {
                // rooms 为不可变 Map（测试 mock 用 Map.of）→ 跳过写回，元数据保持声明值（仅元数据偏差，墙几何不受影响）
            }
            // 重叠防御：与已围合房间交叠 ≥60% 的重复房间跳过
            boolean dup = false;
            for (int[] e : rects) {
                int ox = Math.max(rx, e[0]), oy = Math.max(ry, e[1]);
                int ow = Math.min(rx + rw, e[0] + e[2]) - ox, oh = Math.min(ry + rh, e[1] + e[3]) - oy;
                if (ow > 0 && oh > 0) {
                    double overlap = (double) (ow * oh) / (rw * rh);
                    if (overlap >= 0.6) { dup = true; log.info("ScriptMapService: 跳过重叠房间 {}（与已围合房间交叠 {:.0%}）", name, overlap); break; }
                }
            }
            if (dup) continue;
            rects.add(new int[] { rx, ry, rw, rh });
            // 清空房间内部草稿墙（保证室内开阔）
            for (int y = ry; y < ry + rh && y < height; y++) {
                for (int x = rx; x < rx + rw && x < width; x++) {
                    if (col[y][x] == 1) { col[y][x] = 0; ground[y][x] = 1; }
                }
            }
            // 门位声明：doors: [{"side": "top|bottom|left|right", "offset": 0~1}]，默认每边正中
            int topDoor = rx + rw / 2, botDoor = rx + rw / 2, lefDoor = ry + rh / 2, rigDoor = ry + rh / 2;
            Object doorsObj = r.get("doors");
            if (doorsObj instanceof List<?> doorsDecl) {
                for (Object d : doorsDecl) {
                    if (!(d instanceof Map<?, ?> dm)) continue;
                    String side = String.valueOf(dm.get("side"));
                    double off = 0.5;
                    Object offObj = dm.get("offset");
                    if (offObj instanceof Number n) off = Math.max(0.0, Math.min(1.0, n.doubleValue()));
                    if ("top".equals(side)) topDoor = clamp(rx, rx + rw - 1, rx + (int) (off * rw));
                    else if ("bottom".equals(side)) botDoor = clamp(rx, rx + rw - 1, rx + (int) (off * rw));
                    else if ("left".equals(side)) lefDoor = clamp(ry, ry + rh - 1, ry + (int) (off * rh));
                    else if ("right".equals(side)) rigDoor = clamp(ry, ry + rh - 1, ry + (int) (off * rh));
                }
            }
            // Phase1：画四边墙（door=-1 无门洞）
            if (ry - 1 >= 0) fillWallRow(ground, col, ry - 1, rx, rw, -1);
            else fillWallRow(ground, col, ry, rx, rw, -1);
            if (ry + rh < height) fillWallRow(ground, col, ry + rh, rx, rw, -1);
            else fillWallRow(ground, col, ry + rh - 1, rx, rw, -1);
            if (rx - 1 >= 0) fillWallCol(ground, col, rx - 1, ry, rh, -1);
            else fillWallCol(ground, col, rx, ry, rh, -1);
            if (rx + rw < width) fillWallCol(ground, col, rx + rw, ry, rh, -1);
            else fillWallCol(ground, col, rx + rw - 1, ry, rh, -1);
            // Phase2：收集门洞（统一开，避免共用墙覆盖）
            doors.add(new int[] { topDoor, ry - 1 >= 0 ? ry - 1 : ry });
            doors.add(new int[] { botDoor, ry + rh < height ? ry + rh : ry + rh - 1 });
            doors.add(new int[] { rx - 1 >= 0 ? rx - 1 : rx, lefDoor });
            doors.add(new int[] { rx + rw < width ? rx + rw : rx + rw - 1, rigDoor });
        }
        // Phase2：统一开门洞
        for (int[] d : doors) {
            int x = d[0], y = d[1];
            if (x >= 0 && x < width && y >= 0 && y < height && col[y][x] == 1) {
                col[y][x] = 0; ground[y][x] = 1;
            }
        }
        // Phase3：BFS 保底连通（封闭房间在最近墙格补洞）
        ensureConnectivity(ground, col, width, height, rects);
    }

    /**
     * P-0804-H 续：BFS 连通保底——从首个房间中心洪泛标记可达格；每个房间中心不可达时，
     * 在房间四边墙上找「离可达区域最近」的墙格打通（可穿透相邻房间墙直至邻接可达格），
     * 重复直到全部连通或无法改善（最多 8 轮）。
     */
    private static void ensureConnectivity(int[][] ground, int[][] col, int width, int height, java.util.List<int[]> rects) {
        if (rects.isEmpty()) return;
        for (int iter = 0; iter < 10; iter++) {
            boolean[][] reach = floodReach(col, width, height, rects.get(0));
            boolean fixed = false;
            for (int[] r : rects) {
                int cx = r[0] + r[2] / 2, cy = r[1] + r[3] / 2;
                if (reach[cy][cx]) continue;
                if (punchDoorTowardReach(ground, col, width, height, r, reach)) fixed = true;
            }
            if (!fixed) break;
        }
    }

    /** 从 rect 中心 BFS（collision=0 可通行），返回可达标记。 */
    private static boolean[][] floodReach(int[][] col, int width, int height, int[] rect) {
        boolean[][] reach = new boolean[height][width];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        int sx = Math.min(width - 1, rect[0] + rect[2] / 2), sy = Math.min(height - 1, rect[1] + rect[3] / 2);
        if (col[sy][sx] == 1) { // 中心是墙（异常）→ 找房间内最近空地
            outer:
            for (int rad = 0; rad < Math.max(width, height); rad++) {
                for (int dy = -rad; dy <= rad; dy++) {
                    for (int dx = -rad; dx <= rad; dx++) {
                        int xx = sx + dx, yy = sy + dy;
                        if (xx >= 0 && xx < width && yy >= 0 && yy < height && col[yy][xx] == 0) { sx = xx; sy = yy; break outer; }
                    }
                }
            }
        }
        reach[sy][sx] = true;
        q.add(new int[] { sx, sy });
        int[] dxs = { 1, -1, 0, 0 }, dys = { 0, 0, 1, -1 };
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int i = 0; i < 4; i++) {
                int nx = p[0] + dxs[i], ny = p[1] + dys[i];
                if (nx >= 0 && nx < width && ny >= 0 && ny < height && !reach[ny][nx] && col[ny][nx] == 0) {
                    reach[ny][nx] = true;
                    q.add(new int[] { nx, ny });
                }
            }
        }
        return reach;
    }

    /** 房间不可达时：四边墙上找离可达区域最近的墙格，向可达方向打通（穿透墙直到邻接可达格）。 */
    private static boolean punchDoorTowardReach(int[][] ground, int[][] col, int width, int height, int[] r, boolean[][] reach) {
        int bestX = -1, bestY = -1, bestD = Integer.MAX_VALUE;
        // 房间四边墙上的墙格
        java.util.List<int[]> wallCells = new java.util.ArrayList<>();
        int rx = r[0], ry = r[1], rw = r[2], rh = r[3];
        for (int x = rx; x < rx + rw; x++) {
            if (ry - 1 >= 0) wallCells.add(new int[] { x, ry - 1 });
            if (ry + rh < height) wallCells.add(new int[] { x, ry + rh });
        }
        for (int y = ry; y < ry + rh; y++) {
            if (rx - 1 >= 0) wallCells.add(new int[] { rx - 1, y });
            if (rx + rw < width) wallCells.add(new int[] { rx + rw, y });
        }
        // 内部墙格（草稿残留，房间内可能仍有墙）
        for (int y = ry; y < ry + rh && y < height; y++) {
            for (int x = rx; x < rx + rw && x < width; x++) {
                if (col[y][x] == 1) wallCells.add(new int[] { x, y });
            }
        }
        for (int[] w : wallCells) {
            int wx = w[0], wy = w[1];
            if (wx < 0 || wx >= width || wy < 0 || wy >= height || col[wy][wx] != 1) continue;
            // 该墙格四邻是否有可达格
            boolean adjReach = false;
            int[] dxs = { 1, -1, 0, 0 }, dys = { 0, 0, 1, -1 };
            for (int i = 0; i < 4; i++) {
                int nx = wx + dxs[i], ny = wy + dys[i];
                if (nx >= 0 && nx < width && ny >= 0 && ny < height && reach[ny][nx]) adjReach = true;
            }
            if (adjReach) { bestX = wx; bestY = wy; bestD = 0; break; } // 邻接可达 → 直接打通（同轮其他墙格不再比较）
            // 否则按曼哈顿距离最近可达格排序
            for (int yy = 0; yy < height; yy++) {
                for (int xx = 0; xx < width; xx++) {
                    if (reach[yy][xx]) {
                        int d = Math.abs(xx - wx) + Math.abs(yy - wy);
                        if (d < bestD) { bestD = d; bestX = wx; bestY = wy; }
                    }
                }
            }
        }
        if (bestX < 0) return false; // 找不到墙格 → 本轮跳过（理论上不发生：房间必有四边墙）
        // 打通该格 + 向最近可达方向直线打通（最多 24 格，可穿透相邻房间多重墙；
        // 距离更远时本轮打洞使可达区向房间扩张，下轮 floodReach 更新后再继续逼近）
        int tx = -1, ty = -1, tD = Integer.MAX_VALUE;
        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                if (reach[yy][xx]) {
                    int d = Math.abs(xx - bestX) + Math.abs(yy - bestY);
                    if (d < tD) { tD = d; tx = xx; ty = yy; }
                }
            }
        }
        if (tx < 0) return false;
        int sx = bestX, sy = bestY;
        for (int k = 0; k < 24; k++) {
            if (sx == tx && sy == ty) break;
            int dx = Integer.compare(tx, sx), dy = Integer.compare(ty, sy);
            if (dx != 0) sx += dx; else if (dy != 0) sy += dy;
            if (sx >= 0 && sx < width && sy >= 0 && sy < height && col[sy][sx] == 1) {
                col[sy][sx] = 0; ground[sy][sx] = 1;
            }
        }
        return true;
    }

    private static int clamp(int lo, int hi, int v) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 画一行墙（固定行 y，列 [x0, x0+len)），door 列留空为门洞；door=-1 不开洞。 */
    private static void fillWallRow(int[][] ground, int[][] col, int y, int x0, int len, int door) {
        for (int x = x0; x < x0 + len; x++) {
            if (door >= 0 && x == door) { col[y][x] = 0; ground[y][x] = 1; continue; }
            col[y][x] = 1; ground[y][x] = 2;
        }
    }

    /** 画一列墙（固定列 x，行 [y0, y0+len)），door 行留空为门洞；door=-1 不开洞。 */
    private static void fillWallCol(int[][] ground, int[][] col, int x, int y0, int len, int door) {
        for (int y = y0; y < y0 + len; y++) {
            if (door >= 0 && y == door) { col[y][x] = 0; ground[y][x] = 1; continue; }
            col[y][x] = 1; ground[y][x] = 2;
        }
    }

    /**
     * P-0804-H：墙体厚度规整——外边界只留 1 层（清掉内侧紧贴外边界且外侧为墙的格），
     * 内部连续墙段（collision=1 的 run）超过 2 格时削薄到 2 格（保留 run 前 2 格，其余改可通行）。
     * 门洞（0）会打断 run，天然保留。修改原地（ground/collision）。
     */
    private static void thinWallsToMaxTwo(int[][] ground, int[][] col, int width, int height) {
        // P-0804-H 续：外边界内侧一圈清理已删除——声明式时代 x=1/y=1 的墙是房间顶墙/左墙
        // （房间 clamp 后 ry/rx≥1，顶墙/左墙恰好画在外边界内侧行），原逻辑无条件清掉 → 主人反馈
        // 「房间在地图边界那一边没有有效生成墙」根因之二（上墙 0/8、左墙 0/6 全部来自此）
        // 厚度削薄——只削「墙厚度 >2」的中间层（三格以上并排的墙削到 2 层），
        // 不再按沿墙方向 run 长度削（原实现把房间整面长墙 run>2 削成 2 格 → 长墙残缺，
        // 主人反馈「房间在地图边界那一边没有有效生成墙」根因之一；2 层墙=相邻房间各 1 层，合法不削）
        boolean[][] removed = new boolean[height][width];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                if (col[y][x] != 1) continue;
                // 水平厚度：同排 x, x+1, x+2 三格都是墙 → 削中间 x+1（厚度 3 → 2）
                if (x + 2 < width - 1 && col[y][x + 1] == 1 && col[y][x + 2] == 1 && !removed[y][x + 1]) {
                    removed[y][x + 1] = true;
                }
                // 垂直厚度：同列 y, y+1, y+2 三格都是墙 → 削中间 y+1
                if (y + 2 < height - 1 && col[y + 1][x] == 1 && col[y + 2][x] == 1 && !removed[y + 1][x]) {
                    removed[y + 1][x] = true;
                }
            }
        }
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                if (removed[y][x]) { col[y][x] = 0; ground[y][x] = 1; }
            }
        }
    }

    private static String gridToString(int[][] g) {
        if (g == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int[] row : g) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(row[i]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

}
