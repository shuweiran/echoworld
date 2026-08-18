package com.roleplay.engine.simulation.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0817-L（结构树契约 + 生成 API）：StructureContract 宽容解析 + StructureValidator 校验项。
 * 覆盖：缺失/非法零破坏、归一默认、id 唯一/父子无环、叶子 template、relations 目标、地图级校验。
 */
class StructureContractTest {

    @Test
    @DisplayName("宽容解析：缺失 → 默认 version/kind/root/relations，零破坏")
    void normalizeDefaults() {
        Map<String, Object> s = StructureContract.normalize(null);
        assertEquals(1, s.get("version"));
        assertEquals("custom", s.get("kind"));
        assertEquals("root", ((Map<?, ?>) s.get("root")).get("id"));
        assertTrue(s.get("relations") instanceof List<?>);
        assertEquals(0L, s.get("seed"));
    }

    @Test
    @DisplayName("归一：完整结构树 → 节点/关系规整")
    void normalizeFull() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("version", "1");
        raw.put("kind", "castle");
        raw.put("name", "测试城堡");
        raw.put("seed", 20260817L);
        raw.put("root", Map.of(
                "id", "castle",
                "type", "structure",
                "name", "测试城堡",
                "children", List.of(
                        Map.of("id", "gate", "type", "building", "name", "城门",
                                "template", "gatehouse", "size", List.of(10, 8)),
                        Map.of("id", "yard", "type", "zone", "name", "外庭", "open", true))));
        raw.put("relations", List.of(Map.of("from", "gate", "to", "yard")));
        Map<String, Object> s = StructureContract.normalize(raw);
        assertEquals(1, s.get("version"));
        assertEquals("castle", s.get("kind"));
        assertEquals(20260817L, s.get("seed"));
        List<Map<String, Object>> leaves = StructureContract.leafNodes(s);
        assertEquals(2, leaves.size());
        assertTrue(StructureContract.leafIds(s).containsAll(List.of("gate", "yard")));
        assertEquals("adjacent", ((Map<?, ?>) ((List<?>) s.get("relations")).get(0)).get("kind"));
    }

    @Test
    @DisplayName("校验：节点 id 重复 → 错误")
    void duplicateId() {
        Map<String, Object> s = StructureContract.normalize(structureWithChildren(
                node("a", "building", "gatehouse", 8, 8, false),
                node("a", "building", "great_hall", 8, 8, false)));
        StructureValidator.Result r = StructureValidator.validate(s);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("id 重复")));
    }

    @Test
    @DisplayName("校验：父子嵌套重复访问 → 环错误")
    void cycle() {
        // 手搭自引用结构（不经 normalize，防递归）：root 同时作为自己的子节点
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "root");
        root.put("type", "structure");
        root.put("name", "环");
        root.put("template", "");
        List<Object> children = new ArrayList<>();
        children.add(root); // 自引用
        root.put("children", children);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", 1);
        s.put("kind", "custom");
        s.put("seed", 1L);
        s.put("root", root);
        s.put("relations", List.of());
        StructureValidator.Result r = StructureValidator.validate(s);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("成环") || e.contains("重复")));
    }

    @Test
    @DisplayName("校验：叶子 building 缺 template / type 未知 → 错误")
    void leafTemplateRequired() {
        Map<String, Object> a = node("a", "building", "", 8, 8, false);
        a.remove("template"); // building 叶子缺 template → 校验错误
        Map<String, Object> s = StructureContract.normalize(structureWithChildren(
                a,
                node("b", "unknown_type", "great_hall", 8, 8, false)));
        StructureValidator.Result r = StructureValidator.validate(s);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("缺少 template")));
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("type 未知")));
    }

    @Test
    @DisplayName("校验：relations from/to 不存在、kind 未知 → 错误")
    void relationTargets() {
        Map<String, Object> s = StructureContract.normalize(structureWithChildren(
                node("a", "room", "客厅", 8, 8, false)));
        List<Map<String, Object>> rels = new ArrayList<>();
        rels.add(Map.of("from", "a", "to", "ghost", "kind", "adjacent"));
        rels.add(Map.of("from", "a", "to", "a", "kind", "teleport"));
        s.put("relations", rels);
        StructureValidator.Result r = StructureValidator.validate(s);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("to 不存在")));
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("kind 未知")));
    }

    @Test
    @DisplayName("地图级校验：无 structure 通过；叶子未映射警告；exits 不在叶子报错")
    void validateMapLevel() {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("width", 10);
        assertTrue(StructureValidator.validateMap(plain).ok());

        Map<String, Object> s = StructureContract.normalize(structureWithChildren(
                node("a", "room", "客厅", 8, 8, false),
                node("b", "room", "厨房", 8, 8, false)));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("width", 32);
        map.put("height", 32);
        map.put("structure", s);
        map.put("rooms", List.of(Map.of("id", "a", "x", 2, "y", 2, "w", 8, "h", 8)));
        map.put("exits", List.of(Map.of("from", "a", "to", "ghost", "door", List.of(5, 5))));
        StructureValidator.Result r = StructureValidator.validateMap(map);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("不在结构叶子集合")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("未映射到 rooms[]")));
    }

    // ── 构造辅助 ──

    private static Map<String, Object> node(String id, String type, String name, int w, int h, boolean open) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("name", name);
        n.put("template", type.equals("room") ? "" : "gatehouse");
        if (open) n.put("open", true);
        n.put("size", List.of(w, h));
        return n;
    }

    private static Map<String, Object> structureWithChildren(Map<String, Object>... children) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "root");
        root.put("type", "structure");
        root.put("name", "测试结构");
        root.put("template", "");
        root.put("children", List.of(children));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", 1);
        s.put("kind", "custom");
        s.put("seed", 42L);
        s.put("root", root);
        s.put("relations", List.of());
        return s;
    }
}
