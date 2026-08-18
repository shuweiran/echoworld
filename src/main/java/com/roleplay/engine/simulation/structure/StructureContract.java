package com.roleplay.engine.simulation.structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构树契约 v0.1（docs/结构树契约与生成API设计.md §3）——宽容解析归一 + 结构树工具。
 *
 * <p>定位：结构树 = 语义层（哪些部分组成、什么关系）；地图 = 几何层（rooms/exits/warps）；
 * {@code structure} 是地图 JSON 的可选键（缺失 = 普通地图，零破坏，P-0817-L 落地）。
 *
 * <p>宽容规则：version 缺失按 1、kind 缺失按 custom、seed 缺失按 0（响应回传实际种子）、
 * relations 缺失 = 空表（L1 布局按邻接推导）、节点 id 缺失按 "{parentId}_n"、
 * type 缺失按有无 children → building/room。
 */
public final class StructureContract {

    public static final int CURRENT_VERSION = 1;
    public static final List<String> KNOWN_KINDS =
            List.of("castle", "mansion", "city_block", "dungeon", "custom");
    public static final List<String> NODE_TYPES =
            List.of("structure", "zone", "building", "room");
    public static final List<String> RELATION_KINDS =
            List.of("contains", "adjacent", "connects");

    /** 模板键 → 默认占格 [w, h]（L1 布局参考；节点显式 size 优先）。 */
    public static final Map<String, int[]> DEFAULT_SIZES = Map.ofEntries(
            Map.entry("gatehouse", new int[]{10, 8}),
            Map.entry("great_hall", new int[]{16, 8}),
            Map.entry("banquet", new int[]{12, 8}),
            Map.entry("gu_parlor", new int[]{12, 8}),
            Map.entry("gu_bedroom", new int[]{8, 8}),
            Map.entry("gu_study", new int[]{8, 8}),
            Map.entry("kitchen", new int[]{8, 8}),
            Map.entry("armory", new int[]{10, 8}),
            Map.entry("garden", new int[]{26, 10}),
            Map.entry("courtyard", new int[]{26, 8}),
            Map.entry("foyer", new int[]{8, 8}),
            Map.entry("living", new int[]{12, 8}),
            Map.entry("study", new int[]{8, 8}),
            Map.entry("dining", new int[]{10, 8}),
            Map.entry("bedroom", new int[]{8, 8}),
            Map.entry("servant", new int[]{6, 6}),
            Map.entry("storage", new int[]{6, 6}),
            Map.entry("warehouse", new int[]{12, 8}),
            Map.entry("shop", new int[]{10, 8}),
            Map.entry("house", new int[]{10, 8}),
            Map.entry("street", new int[]{24, 6}),
            Map.entry("plaza", new int[]{16, 8}),
            Map.entry("entrance", new int[]{8, 8}),
            Map.entry("dungeon_hall", new int[]{14, 8}),
            Map.entry("dungeon_cell", new int[]{6, 6}),
            Map.entry("treasury", new int[]{8, 8}),
            Map.entry("boss_room", new int[]{12, 10}));
    public static final int[] DEFAULT_NODE_SIZE = {10, 8};

    private StructureContract() {
    }

    // ═══════════════════════════════════════════════════════════
    //  宽容解析 → 规范结构
    // ═══════════════════════════════════════════════════════════

    /** 结构树归一（缺失兜底 + 类型规整；业务校验走 {@link StructureValidator}）。 */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (raw != null) {
            s.put("version", intOf(raw.get("version"), CURRENT_VERSION));
            s.put("kind", str(raw.get("kind"), "custom"));
            s.put("name", str(raw.get("name"), ""));
            s.put("seed", longOf(raw.get("seed"), 0L));
            Object rootObj = raw.get("root");
            s.put("root", rootObj instanceof Map<?, ?> rm
                    ? normalizeNode(rm, "root")
                    : defaultRoot(str(raw.get("name"), "")));
            s.put("relations", normalizeRelations(raw.get("relations")));
        } else {
            s.put("version", CURRENT_VERSION);
            s.put("kind", "custom");
            s.put("name", "");
            s.put("seed", 0L);
            s.put("root", defaultRoot(""));
            s.put("relations", List.of());
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeNode(Object o, String fallbackId) {
        Map<String, Object> n = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            String id = str(m.get("id"), fallbackId);
            n.put("id", id);
            List<?> children = m.get("children") instanceof List<?> cl ? cl : List.of();
            String type = str(m.get("type"), children.isEmpty() ? "room" : "building");
            n.put("type", type);
            n.put("name", str(m.get("name"), id));
            n.put("template", str(m.get("template"), ""));
            if (Boolean.TRUE.equals(m.get("open"))) n.put("open", true);
            Object size = m.get("size");
            if (size instanceof List<?> sl && sl.size() == 2
                    && sl.get(0) instanceof Number && sl.get(1) instanceof Number) {
                n.put("size", List.of(((Number) sl.get(0)).intValue(), ((Number) sl.get(1)).intValue()));
            }
            if (m.get("theme") != null) n.put("theme", str(m.get("theme"), ""));
            if (m.get("style") != null) n.put("style", str(m.get("style"), ""));
            Object clues = m.get("clue_locations");
            if (clues instanceof List<?> cll) {
                List<String> locs = new ArrayList<>();
                for (Object c : cll) {
                    if (c != null) locs.add(String.valueOf(c));
                }
                if (!locs.isEmpty()) n.put("clue_locations", locs);
            }
            if (!children.isEmpty()) {
                List<Map<String, Object>> cs = new ArrayList<>();
                for (int i = 0; i < children.size(); i++) {
                    cs.add(normalizeNode(children.get(i), id + "_" + i));
                }
                n.put("children", cs);
            }
            return n;
        }
        // 非对象节点 → 兜底叶子
        n.put("id", fallbackId);
        n.put("type", "room");
        n.put("name", fallbackId);
        n.put("template", "");
        return n;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> normalizeRelations(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(o instanceof List<?> rels)) return out;
        for (Object r : rels) {
            if (!(r instanceof Map<?, ?> rm)) continue;
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("from", str(rm.get("from"), ""));
            rel.put("to", str(rm.get("to"), ""));
            rel.put("kind", str(rm.get("kind"), "adjacent"));
            out.add(rel);
        }
        return out;
    }

    private static Map<String, Object> defaultRoot(String name) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "root");
        root.put("type", "structure");
        root.put("name", name.isBlank() ? "未命名结构" : name);
        root.put("template", "");
        root.put("children", List.of());
        return root;
    }

    // ═══════════════════════════════════════════════════════════
    //  结构树工具
    // ═══════════════════════════════════════════════════════════

    /** 全部节点（前序扁平化，含 root）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> allNodes(Map<String, Object> structure) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (structure == null) return out;
        Object root = structure.get("root");
        if (!(root instanceof Map<?, ?> rm)) return out;
        collect((Map<String, Object>) rm, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> node, List<Map<String, Object>> out) {
        collect(node, out, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    /** 遍历防环：按对象身份去重（自引用结构不栈溢出，id 重复仍全部收集供校验器报重）。 */
    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> node, List<Map<String, Object>> out,
                                java.util.Set<Map<String, Object>> seen) {
        if (!seen.add(node)) return;
        out.add(node);
        if (node.get("children") instanceof List<?> cs) {
            for (Object c : cs) {
                if (c instanceof Map<?, ?> cm) collect((Map<String, Object>) cm, out, seen);
            }
        }
    }

    /** 叶子节点（无 children）。 */
    public static List<Map<String, Object>> leafNodes(Map<String, Object> structure) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> n : allNodes(structure)) {
            if (!(n.get("children") instanceof List<?> cs) || cs.isEmpty()) out.add(n);
        }
        return out;
    }

    public static List<String> leafIds(Map<String, Object> structure) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> n : leafNodes(structure)) ids.add(str(n.get("id"), ""));
        return ids;
    }

    /** id → 节点（重复 id 后者覆盖；校验器另行报重）。 */
    public static Map<String, Map<String, Object>> nodeIndex(Map<String, Object> structure) {
        Map<String, Map<String, Object>> idx = new LinkedHashMap<>();
        for (Map<String, Object> n : allNodes(structure)) {
            String id = str(n.get("id"), "");
            if (!id.isBlank()) idx.put(id, n);
        }
        return idx;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> relations(Map<String, Object> structure) {
        Object r = structure == null ? null : structure.get("relations");
        if (r instanceof List<?> rels) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : rels) {
                if (o instanceof Map<?, ?> m) out.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
            return out;
        }
        return List.of();
    }

    /** 节点尺寸：[w, h]（节点 size → 模板默认 → 全局默认）。 */
    public static int[] nodeSize(Map<String, Object> node) {
        Object size = node.get("size");
        if (size instanceof List<?> sl && sl.size() == 2
                && sl.get(0) instanceof Number && sl.get(1) instanceof Number) {
            return new int[]{Math.max(4, ((Number) sl.get(0)).intValue()),
                    Math.max(4, ((Number) sl.get(1)).intValue())};
        }
        int[] def = DEFAULT_SIZES.get(str(node.get("template"), ""));
        return def == null ? DEFAULT_NODE_SIZE : def;
    }

    /** 确定性子种子：hash64(父 seed + "/" + 路径)——同根 seed 同结构同图。 */
    public static long childSeed(long parent, String path) {
        long h = 0x9E3779B97F4A7C15L ^ parent;
        String p = path == null ? "" : path;
        for (int i = 0; i < p.length(); i++) {
            h = (h * 31 + p.charAt(i)) ^ (h >>> 33);
        }
        return h == 0 ? 1L : h;
    }

    // ── 类型规整辅助 ──

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

    public static long longOf(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
