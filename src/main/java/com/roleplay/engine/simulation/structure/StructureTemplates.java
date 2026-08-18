package com.roleplay.engine.simulation.structure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构模板库（L0 默认来源，docs/结构树契约与生成API设计.md §6）——4 kinds：
 * castle / mansion / city_block / dungeon，每 kind 一套结构树（节点 + 关系），几何由 L1 生成。
 *
 * <p>加载顺序：classpath:/structure/&lt;kind&gt;.json（模板库文件，可独立编辑）→ 失败回退内置
 * 同构模板（保证任意打包/测试环境可用）。两种来源结构相同，响应 generator.l0=template。
 */
public final class StructureTemplates {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Map<String, Object>> BUILTIN = new LinkedHashMap<>();

    static {
        BUILTIN.put("castle", builtin("castle", "晨曦城堡", castleNodes(), castleRelations()));
        BUILTIN.put("mansion", builtin("mansion", "庄园宅邸", mansionNodes(), mansionRelations()));
        BUILTIN.put("city_block", builtin("city_block", "城市街区", cityNodes(), cityRelations()));
        BUILTIN.put("dungeon", builtin("dungeon", "地下城", dungeonNodes(), dungeonRelations()));
    }

    private StructureTemplates() {
    }

    /** 取模板结构树（未归一；未知 kind 返回 null）。 */
    public static Map<String, Object> template(String kind) {
        if (kind == null || kind.isBlank()) return null;
        String k = kind.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> fromFile = loadClasspath(k);
        if (fromFile != null) return fromFile;
        return BUILTIN.get(k);
    }

    private static Map<String, Object> loadClasspath(String kind) {
        try {
            Resource res = new ClassPathResource("structure/" + kind + ".json");
            if (!res.exists()) return null;
            try (InputStream in = res.getInputStream()) {
                Object o = MAPPER.readValue(in, Object.class);
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
                    return out;
                }
            }
        } catch (Exception e) {
            // 模板文件损坏/缺失 → 回退内置（L0 失败由服务层 BSP 兜底）
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    //  内置模板（与 resources/structure/*.json 同构；单文件可独立编辑）
    // ═══════════════════════════════════════════════════════════

    private static Map<String, Object> builtin(String kind, String name,
                                               List<Map<String, Object>> children,
                                               List<Map<String, Object>> relations) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", kind);
        root.put("type", "structure");
        root.put("name", name);
        root.put("template", "");
        root.put("children", children);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", 1);
        s.put("kind", kind);
        s.put("name", name);
        s.put("seed", 0L);
        s.put("root", root);
        s.put("relations", relations);
        return s;
    }

    private static Map<String, Object> node(String id, String type, String name, String template,
                                            int w, int h, boolean open) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("name", name);
        if (template != null && !template.isBlank()) n.put("template", template);
        if (open) n.put("open", true);
        n.put("size", List.of(w, h));
        return n;
    }

    private static Map<String, Object> rel(String from, String to, String kind) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("from", from);
        r.put("to", to);
        r.put("kind", kind == null || kind.isBlank() ? "adjacent" : kind);
        return r;
    }

    private static List<Map<String, Object>> castleNodes() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(node("gate", "building", "城门楼", "gatehouse", 10, 8, false));
        l.add(node("courtyard", "zone", "外庭", "courtyard", 26, 8, true));
        l.add(node("great_hall", "building", "大厅", "great_hall", 16, 8, false));
        l.add(node("west_wing", "building", "会客厅", "gu_parlor", 12, 8, false));
        l.add(node("tower_bed", "building", "塔楼卧室", "gu_bedroom", 8, 8, false));
        l.add(node("tower_study", "building", "塔楼书房", "gu_study", 8, 8, false));
        l.add(node("kitchen", "building", "厨房", "kitchen", 8, 8, false));
        l.add(node("east_wing", "building", "宴会厅", "banquet", 12, 8, false));
        l.add(node("armory", "building", "兵械库", "armory", 10, 8, false));
        l.add(node("inner_garden", "zone", "内庭花园", "garden", 26, 10, true));
        l.add(node("back_garden", "zone", "后花园", "garden", 28, 10, true));
        return l;
    }

    private static List<Map<String, Object>> castleRelations() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(rel("gate", "courtyard", "adjacent"));
        l.add(rel("courtyard", "great_hall", "adjacent"));
        l.add(rel("great_hall", "west_wing", "adjacent"));
        l.add(rel("great_hall", "tower_bed", "adjacent"));
        l.add(rel("great_hall", "tower_study", "adjacent"));
        l.add(rel("great_hall", "kitchen", "adjacent"));
        l.add(rel("great_hall", "east_wing", "adjacent"));
        l.add(rel("courtyard", "armory", "adjacent"));
        l.add(rel("courtyard", "inner_garden", "adjacent"));
        l.add(rel("great_hall", "back_garden", "connects"));
        return l;
    }

    private static List<Map<String, Object>> mansionNodes() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(node("foyer", "building", "门厅", "foyer", 8, 8, false));
        l.add(node("living", "building", "客厅", "living", 12, 8, false));
        l.add(node("study", "building", "书房", "study", 8, 8, false));
        l.add(node("dining", "building", "餐厅", "dining", 10, 8, false));
        l.add(node("kitchen", "building", "厨房", "kitchen", 8, 8, false));
        l.add(node("bedroom1", "building", "主卧", "gu_bedroom", 8, 8, false));
        l.add(node("bedroom2", "building", "客房", "gu_bedroom", 8, 8, false));
        l.add(node("bedroom3", "building", "次卧", "gu_bedroom", 8, 8, false));
        l.add(node("backyard", "zone", "后院", "garden", 16, 10, true));
        l.add(node("servant", "building", "佣人房", "servant", 6, 6, false));
        l.add(node("storage", "building", "储藏室", "storage", 6, 6, false));
        return l;
    }

    private static List<Map<String, Object>> mansionRelations() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(rel("foyer", "living", "adjacent"));
        l.add(rel("foyer", "study", "adjacent"));
        l.add(rel("foyer", "dining", "adjacent"));
        l.add(rel("dining", "kitchen", "adjacent"));
        l.add(rel("living", "bedroom1", "adjacent"));
        l.add(rel("living", "bedroom2", "adjacent"));
        l.add(rel("living", "bedroom3", "adjacent"));
        l.add(rel("dining", "servant", "adjacent"));
        l.add(rel("kitchen", "storage", "adjacent"));
        l.add(rel("kitchen", "backyard", "connects"));
        return l;
    }

    private static List<Map<String, Object>> cityNodes() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(node("street", "zone", "主街", "street", 24, 6, true));
        l.add(node("plaza", "zone", "广场", "plaza", 16, 8, true));
        for (int i = 1; i <= 4; i++) l.add(node("shop" + i, "building", "店铺" + i, "shop", 10, 8, false));
        for (int i = 1; i <= 4; i++) l.add(node("house" + i, "building", "民居" + i, "house", 10, 8, false));
        l.add(node("warehouse", "building", "仓库", "warehouse", 12, 8, false));
        return l;
    }

    private static List<Map<String, Object>> cityRelations() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (int i = 1; i <= 4; i++) l.add(rel("street", "shop" + i, "adjacent"));
        for (int i = 1; i <= 4; i++) l.add(rel("street", "house" + i, "adjacent"));
        l.add(rel("street", "plaza", "adjacent"));
        l.add(rel("plaza", "warehouse", "adjacent"));
        l.add(rel("street", "warehouse", "connects"));
        return l;
    }

    private static List<Map<String, Object>> dungeonNodes() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(node("entrance", "building", "入口", "entrance", 8, 8, false));
        l.add(node("hall", "building", "地牢大厅", "dungeon_hall", 14, 8, false));
        for (int i = 1; i <= 4; i++) l.add(node("cell" + i, "building", "监牢" + i, "dungeon_cell", 6, 6, false));
        l.add(node("storage", "building", "储藏室", "storage", 8, 8, false));
        l.add(node("treasury", "building", "宝库", "treasury", 8, 8, false));
        l.add(node("boss", "building", "首领间", "boss_room", 12, 10, false));
        return l;
    }

    private static List<Map<String, Object>> dungeonRelations() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(rel("entrance", "hall", "adjacent"));
        for (int i = 1; i <= 4; i++) l.add(rel("hall", "cell" + i, "adjacent"));
        l.add(rel("hall", "storage", "adjacent"));
        l.add(rel("storage", "treasury", "adjacent"));
        l.add(rel("treasury", "boss", "connects"));
        return l;
    }
}
