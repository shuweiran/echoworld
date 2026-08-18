package com.roleplay.engine.simulation.structure;

import com.roleplay.engine.simulation.map.MapContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构树校验器（docs/结构树契约与生成API设计.md §3.4）——宽容解析后的结构树防线。
 *
 * <p>校验项：
 * <ol>
 *   <li>version 数字（缺失警告按 1）、kind 已知、seed 数字、root 存在</li>
 *   <li>节点 id 全局唯一、父子无环（同节点重复访问）、叶子必须 type=room/zone 或含 template</li>
 *   <li>relations from/to 必须存在、kind 已知</li>
 *   <li>叶子节点映射到地图 rooms[]（缺失 → 警告）</li>
 *   <li>exits 的 from/to 必须落在结构叶子集合（与 MapValidator 检查项 12 叠加）</li>
 *   <li>多图结构：跨图 warps 目标地图存在（警告缺反向）—— {@link #validateWarps}</li>
 * </ol>
 *
 * <p>宽容：structure 缺失 = 普通地图，直接通过（零破坏）；校验失败由 StructureMapService
 * 走 BSP 兜底，不 500（对齐 ScriptMapService 降级语义）。
 */
public final class StructureValidator {

    /** 校验结果。 */
    public record Result(boolean ok, List<String> errors, List<String> warnings) {
    }

    private StructureValidator() {
    }

    /** 结构树独立校验（structure 非 null）。 */
    public static Result validate(Map<String, Object> structure) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (structure == null) {
            return new Result(false, List.of("structure 缺失"), List.of());
        }

        // 1) 顶层字段
        Object ver = structure.get("version");
        if (ver == null) warnings.add("structure.version 缺失，宽容解析按 1 处理");
        else if (!(ver instanceof Number)) errors.add("structure.version 必须是数字");

        String kind = MapContract.str(structure.get("kind"), "");
        if (kind.isBlank()) errors.add("structure.kind 缺失");
        else if (!StructureContract.KNOWN_KINDS.contains(kind)) errors.add("structure.kind 未知：" + kind);

        Object seed = structure.get("seed");
        if (seed == null) warnings.add("structure.seed 缺失，宽容解析按 0（生成时取当前毫秒）");
        else if (!(seed instanceof Number)) errors.add("structure.seed 必须是数字");

        Object root = structure.get("root");
        if (!(root instanceof Map<?, ?>)) {
            errors.add("structure.root 缺失");
            return new Result(errors.isEmpty(), errors, warnings);
        }

        // 2) 节点
        List<Map<String, Object>> nodes = StructureContract.allNodes(structure);
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> n : nodes) {
            String id = StructureContract.str(n.get("id"), "");
            if (id.isBlank()) errors.add("结构节点缺少 id");
            else if (!ids.add(id)) errors.add("结构节点 id 重复：" + id);
            String type = StructureContract.str(n.get("type"), "");
            if (!StructureContract.NODE_TYPES.contains(type)) {
                errors.add("节点 " + id + " type 未知：" + type);
            }
            List<?> children = n.get("children") instanceof List<?> cs ? cs : List.of();
            if (children.isEmpty()) {
                String template = StructureContract.str(n.get("template"), "");
                if ("building".equals(type) && template.isBlank()) {
                    errors.add("叶子节点 " + id + "（type=building）缺少 template");
                }
                if ("zone".equals(type) && !Boolean.TRUE.equals(n.get("open"))) {
                    warnings.add("zone 节点 " + id + " 未标 open=true（布局按开放区域处理）");
                }
            }
        }
        // 环检测：结构树是嵌套树，环只能由 id 重复造成（已报）；这里显式 DFS 防父子嵌套引用
        if (root instanceof Map<?, ?> rm && rm.get("id") != null) {
            checkCycle((Map<String, Object>) rm, new LinkedHashSet<>(), new HashSet<>(), errors);
        }

        // 3) relations
        for (Map<String, Object> rel : StructureContract.relations(structure)) {
            String from = StructureContract.str(rel.get("from"), "");
            String to = StructureContract.str(rel.get("to"), "");
            String relKind = StructureContract.str(rel.get("kind"), "");
            if (!ids.contains(from)) errors.add("relations from 不存在：" + from);
            if (!ids.contains(to)) errors.add("relations to 不存在：" + to);
            if (relKind.isBlank()) warnings.add("relations (" + from + "→" + to + ") kind 缺失，按 adjacent");
            else if (!StructureContract.RELATION_KINDS.contains(relKind)) {
                errors.add("relations kind 未知：" + relKind);
            }
        }
        return new Result(errors.isEmpty(), errors, warnings);
    }

    @SuppressWarnings("unchecked")
    private static void checkCycle(Map<String, Object> node, Set<String> visiting, Set<String> visited,
                                   List<String> errors) {
        String id = StructureContract.str(node.get("id"), "");
        if (id.isBlank()) return;
        if (!visiting.add(id)) {
            errors.add("结构树成环（节点重复访问）：" + id);
            return;
        }
        if (node.get("children") instanceof List<?> cs) {
            for (Object c : cs) {
                if (c instanceof Map<?, ?> cm) checkCycle((Map<String, Object>) cm, visiting, visited, errors);
            }
        }
        visiting.remove(id);
        visited.add(id);
    }

    /**
     * 地图级校验：structure（可选）+ 叶子 → rooms[] 映射 + exits 落在叶子集合。
     * structure 缺失 = 普通地图，直接通过。
     */
    @SuppressWarnings("unchecked")
    public static Result validateMap(Map<String, Object> map) {
        if (map == null) return new Result(false, List.of("地图 JSON 不是对象"), List.of());
        if (!(map.get("structure") instanceof Map<?, ?>)) {
            return new Result(true, List.of(), List.of());
        }
        Map<String, Object> structure = (Map<String, Object>) map.get("structure");
        Result base = validate(structure);
        List<String> errors = new ArrayList<>(base.errors());
        List<String> warnings = new ArrayList<>(base.warnings());

        Set<String> leaves = new LinkedHashSet<>(StructureContract.leafIds(structure));
        Set<String> roomIds = new LinkedHashSet<>();
        if (map.get("rooms") instanceof List<?> rooms) {
            for (Object o : rooms) {
                if (o instanceof Map<?, ?> r) {
                    String rid = MapContract.str(r.get("id"), "");
                    if (!rid.isBlank()) roomIds.add(rid);
                }
            }
        }
        // 4) 叶子 → rooms[]（缺失警告；映射由 L1 布局器保证）
        for (String leaf : leaves) {
            if (!roomIds.contains(leaf)) warnings.add("结构叶子 " + leaf + " 未映射到 rooms[]");
        }
        // 5) exits 落在结构叶子集合
        if (map.get("exits") instanceof List<?> exits) {
            for (int i = 0; i < exits.size(); i++) {
                Object o = exits.get(i);
                if (!(o instanceof Map<?, ?> ex)) continue;
                String from = MapContract.str(ex.get("from"), "");
                String to = MapContract.str(ex.get("to"), "");
                if (!leaves.contains(from)) errors.add("exits[" + i + "] from \"" + from + "\" 不在结构叶子集合");
                if (!leaves.contains(to)) errors.add("exits[" + i + "] to \"" + to + "\" 不在结构叶子集合");
            }
        }
        return new Result(errors.isEmpty(), errors, warnings);
    }

    /**
     * 6) 多图 warps：每张图的 warps[].to[0]（目标 mapId）必须存在于 maps；缺反向 → 警告。
     * 单图（maps.size() ≤ 1）直接通过。
     */
    @SuppressWarnings("unchecked")
    public static Result validateWarps(Map<String, Map<String, Object>> maps) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (maps == null || maps.size() <= 1) return new Result(true, List.of(), List.of());
        Set<String> ids = new LinkedHashSet<>(maps.keySet());
        for (Map.Entry<String, Map<String, Object>> e : maps.entrySet()) {
            String fromMap = e.getKey();
            Map<String, Object> map = e.getValue();
            if (!(map.get("warps") instanceof List<?> warps)) continue;
            for (Object o : warps) {
                if (!(o instanceof Map<?, ?> w)) continue;
                Object to = w.get("to");
                if (!(to instanceof List<?> tl) || tl.size() != 3) continue;
                String target = String.valueOf(tl.get(0));
                if (!ids.contains(target)) {
                    errors.add("map " + fromMap + " warp 目标地图不存在：" + target);
                }
                Map<String, Object> targetMap = maps.get(target);
                boolean hasReverse = false;
                if (targetMap != null && targetMap.get("warps") instanceof List<?> tw) {
                    for (Object to2 : tw) {
                        if (to2 instanceof Map<?, ?> w2 && w2.get("to") instanceof List<?> tl2
                                && tl2.size() == 3 && String.valueOf(tl2.get(0)).equals(fromMap)) {
                            hasReverse = true;
                            break;
                        }
                    }
                }
                if (!hasReverse) warnings.add("map " + fromMap + " → " + target + " warp 缺少反向");
            }
        }
        return new Result(errors.isEmpty(), errors, warnings);
    }
}
