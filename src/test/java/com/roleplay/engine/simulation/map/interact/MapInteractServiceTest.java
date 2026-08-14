package com.roleplay.engine.simulation.map.interact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0814-H 热点/搜证点交互分发器纯逻辑单测（假 GameContext 直测全链，无 Spring 无游戏状态）：
 * <ul>
 *   <li>R1：半径判定边界 —— Chebyshev 默认 r=1（斜角 1 格可交互 / 轴向 2 格拒绝）、decor.radius 覆盖、
 *       缺玩家坐标跳过校验（尽力而为）</li>
 *   <li>R2：优先级分流 —— decor 实体 &gt; tileProps.action &gt; 环境占位；decor_id 显式解析 / 不存在报错 /
 *       缺目标报错</li>
 *   <li>R3：once 幂等 —— 首次交互标记 processed、重复交互返回「已处理」不重复执行</li>
 *   <li>R4：conditions 门 —— requireFlag 不满足 → failDialog 返回且零动作执行；flag 写入后放行</li>
 *   <li>R5：动作叠加 —— dialog/addItem/flag/sound/anim/menu/state 一次交互全执行 + 未知动作 warning 忽略</li>
 *   <li>R6：tileProps 分发 —— action 名查表 + args 透传（dialog 文本 / addItem 对象）</li>
 * </ul>
 */
class MapInteractServiceTest {

    /** 假上下文：记录授予/flag/状态/processed 调用。 */
    static class FakeCtx implements MapInteractService.GameContext {
        final Set<String> held = new LinkedHashSet<>();
        final Map<String, String> clueTitles = new LinkedHashMap<>();
        final Set<String> flags = new LinkedHashSet<>();
        final Set<String> processed = new LinkedHashSet<>();
        final Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        int grantCalls = 0;
        int writeFlagCalls = 0;

        @Override
        public boolean grantClue(String player, String clueId, Map<String, Object> clueData) {
            grantCalls++;
            if (held.contains(clueId)) return false;
            // 模拟真实上下文：未知线索 id 且无 title/content 数据 → 无法授予
            if (!clueTitles.containsKey(clueId) && (clueData == null || clueData.isEmpty())) return false;
            held.add(clueId);
            return true;
        }

        @Override
        public String clueTitle(String clueId) {
            return clueTitles.get(clueId);
        }

        @Override
        public boolean hasFlag(String flag) {
            return flags.contains(flag);
        }

        @Override
        public void writeFlag(String flag) {
            writeFlagCalls++;
            flags.add(flag);
        }

        @Override
        public boolean isProcessed(String mapId, String decorId) {
            return processed.contains(mapId + "|" + decorId);
        }

        @Override
        public void setProcessed(String mapId, String decorId) {
            processed.add(mapId + "|" + decorId);
        }

        @Override
        public Map<String, Object> runtimeState(String mapId, String decorId) {
            Map<String, Object> st = states.get(mapId + "|" + decorId);
            return st == null ? Map.of() : new LinkedHashMap<>(st);
        }

        @Override
        public void setRuntimeState(String mapId, String decorId, Map<String, Object> merged) {
            states.put(mapId + "|" + decorId, new LinkedHashMap<>(merged));
        }
    }

    /** 构造契约地图（decor 列表 + tileProps 字典）。 */
    private static Map<String, Object> mapWith(List<Map<String, Object>> decor, Map<String, Object> tileProps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_id", "map_1");
        m.put("decor", decor);
        m.put("tileProps", tileProps);
        return m;
    }

    private static Map<String, Object> decor(String id, String type, int x, int y) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("type", type);
        d.put("tile", List.of(x, y));
        return d;
    }

    private static Map<String, Object> onInteract(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object... o) {
        return new ArrayList<>(List.of(o));
    }

    // ═══════════════════════════════════════════════════════════
    //  R1: 半径判定边界（Chebyshev |dx|≤r 且 |dy|≤r）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("R1a: 默认半径 1 —— 轴向/斜角 1 格可交互，2 格拒绝（够不着文案）")
    void radiusDefaultOne() {
        Map<String, Object> map = mapWith(List.of(
                withOnInteract(decor("chest_1", "chest", 4, 4), onInteract("dialog", "箱子打开了"))), Map.of());
        FakeCtx ctx = new FakeCtx();
        // 轴向 1 格（左）→ 可交互
        Map<String, Object> ok = MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 3, 4, ctx);
        assertEquals(Boolean.TRUE, ok.get("handled"));
        assertEquals(List.of("箱子打开了"), ok.get("dialog"));
        // 斜角 1 格（右下）→ 可交互（Chebyshev 方形半径）
        Map<String, Object> diag = MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 5, 5, ctx);
        assertEquals(Boolean.TRUE, diag.get("handled"));
        // 轴向 2 格 → 拒绝（|dx|=2 > 1）
        Map<String, Object> far = MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 6, 4, ctx);
        assertEquals(Boolean.FALSE, far.get("handled"));
        assertTrue(String.valueOf(far.get("error")).contains(MapInteractService.OUT_OF_RANGE_TEXT), String.valueOf(far.get("error")));
        // 斜角 2,2 → 拒绝（Chebyshev 距离 2 > 1）
        Map<String, Object> farDiag = MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 6, 6, ctx);
        assertEquals(Boolean.FALSE, farDiag.get("handled"));
        assertTrue(String.valueOf(farDiag.get("error")).contains("够不着"), String.valueOf(farDiag.get("error")));
    }

    @Test
    @DisplayName("R1b: decor.radius 覆盖默认 —— radius=2 时 2 格可交互、3 格拒绝")
    void radiusOverride() {
        Map<String, Object> d = withOnInteract(decor("chest_1", "chest", 4, 4), onInteract("dialog", "箱子打开了"));
        d.put("radius", 2);
        Map<String, Object> map = mapWith(List.of(d), Map.of());
        FakeCtx ctx = new FakeCtx();
        assertEquals(Boolean.TRUE, MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 6, 4, ctx).get("handled"), "2 格在 radius=2 内");
        assertEquals(Boolean.TRUE, MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 5, 6, ctx).get("handled"), "斜角 2 格在 radius=2 内");
        Map<String, Object> far = MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 7, 4, ctx);
        assertEquals(Boolean.FALSE, far.get("handled"), "3 格超半径");
        assertTrue(String.valueOf(far.get("error")).contains("够不着"), String.valueOf(far.get("error")));
    }

    @Test
    @DisplayName("R1c: 缺玩家坐标跳过靠近校验（尽力而为，客户端上报）")
    void missingPlayerCoordsSkipsRadius() {
        Map<String, Object> map = mapWith(List.of(
                withOnInteract(decor("chest_1", "chest", 4, 4), onInteract("dialog", "箱子打开了"))), Map.of());
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", "chest_1", null, null, null, ctx);
        assertEquals(Boolean.TRUE, r.get("handled"), "无坐标不校验半径");
    }

    // ═══════════════════════════════════════════════════════════
    //  R2: 优先级分流（decor 实体 > tileProps.action > 环境占位）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("R2a: 同格 decor 实体优先于 tileProps.action")
    void priorityDecorOverTileProps() {
        Map<String, Object> map = mapWith(List.of(
                withOnInteract(decor("chest_1", "chest", 4, 4), onInteract("dialog", "箱子打开了"))),
                Map.of("4,4", Map.of("action", "dialog", "args", "墙上的画")));
        FakeCtx ctx = new FakeCtx();
        // tile 坐标命中同格 → decor 实体胜出
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", null, "4,4", 4, 4, ctx);
        assertEquals("chest_1", r.get("decor_id"));
        assertEquals(List.of("箱子打开了"), r.get("dialog"));
    }

    @Test
    @DisplayName("R2b: 无 decor 时 tileProps.action 分发（args 透传）")
    void tilePropsWhenNoDecor() {
        Map<String, Object> map = mapWith(List.of(), Map.of(
                "8,2", Map.of("action", "dialog", "args", "墙上的画框很沉。"),
                "8,3", Map.of("action", "addItem", "args", Map.of("id", "c2", "title", "密信"))));
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> d1 = MapInteractService.interact("map_1", map, "Alice", null, "8,2", 8, 3, ctx);
        assertEquals(Boolean.TRUE, d1.get("handled"));
        assertEquals(List.of("墙上的画框很沉。"), d1.get("dialog"));
        Map<String, Object> d2 = MapInteractService.interact("map_1", map, "Alice", null, "8,3", 8, 3, ctx);
        assertEquals(Boolean.TRUE, d2.get("handled"));
        assertEquals(1, ((List<?>) d2.get("items")).size());
        assertEquals("c2", ((Map<?, ?>) ((List<?>) d2.get("items")).get(0)).get("id"));
        assertTrue(ctx.held.contains("c2"));
    }

    @Test
    @DisplayName("R2c: 无 decor 无 tileProps → 环境占位文案")
    void placeholderFallback() {
        Map<String, Object> map = mapWith(List.of(), Map.of());
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", null, "9,9", 9, 9, ctx);
        assertEquals(Boolean.FALSE, r.get("handled"));
        assertEquals(MapInteractService.PLACEHOLDER_TEXT, r.get("result"));
    }

    @Test
    @DisplayName("R2d: decor_id 显式解析 / 不存在报错 / 缺目标报错 / tile 非法报错")
    void targetResolutionErrors() {
        Map<String, Object> map = mapWith(List.of(
                withOnInteract(decor("chest_1", "chest", 4, 4), onInteract("dialog", "箱子打开了"))), Map.of());
        FakeCtx ctx = new FakeCtx();
        // decor_id 显式命中
        Map<String, Object> hit = MapInteractService.interact("map_1", map, "Alice", "chest_1", null, 4, 4, ctx);
        assertEquals("chest_1", hit.get("decor_id"));
        // decor_id 不存在
        Map<String, Object> missing = MapInteractService.interact("map_1", map, "Alice", "nope", null, 4, 4, ctx);
        assertEquals(Boolean.FALSE, missing.get("ok"));
        assertTrue(String.valueOf(missing.get("error")).contains("decor 不存在"));
        // 双缺
        Map<String, Object> noTarget = MapInteractService.interact("map_1", map, "Alice", null, null, 4, 4, ctx);
        assertEquals(Boolean.FALSE, noTarget.get("ok"));
        assertTrue(String.valueOf(noTarget.get("error")).contains("缺少交互目标"));
        // tile 非法
        Map<String, Object> badTile = MapInteractService.interact("map_1", map, "Alice", null, "abc", 4, 4, ctx);
        assertEquals(Boolean.FALSE, badTile.get("ok"));
        assertTrue(String.valueOf(badTile.get("error")).contains("tile 坐标格式非法"));
    }

    // ═══════════════════════════════════════════════════════════
    //  R3: once 幂等
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("R3: once 首次交互执行并标记 processed，重复交互返回「已处理」不重复执行")
    void onceIdempotency() {
        Map<String, Object> d = decor("note_1", "note", 2, 2);
        d.put("once", true);
        d.put("onInteract", onInteract("dialog", "便条：凶手是管家。", "addItem", "c1"));
        Map<String, Object> map = mapWith(List.of(d), Map.of());
        FakeCtx ctx = new FakeCtx();
        ctx.clueTitles.put("c1", "便条");
        Map<String, Object> first = MapInteractService.interact("map_1", map, "Alice", "note_1", null, 2, 3, ctx);
        assertEquals(Boolean.TRUE, first.get("handled"));
        assertEquals(Boolean.TRUE, first.get("processed"));
        assertEquals(List.of("便条：凶手是管家。"), first.get("dialog"));
        assertTrue(ctx.held.contains("c1"));
        assertTrue(ctx.processed.contains("map_1|note_1"));
        int grantsAfterFirst = ctx.grantCalls;
        // 重复交互 → 已处理，不再执行任何动作
        Map<String, Object> again = MapInteractService.interact("map_1", map, "Alice", "note_1", null, 2, 3, ctx);
        assertEquals(Boolean.FALSE, again.get("handled"));
        assertEquals(Boolean.TRUE, again.get("processed"));
        assertTrue(String.valueOf(again.get("result")).contains(MapInteractService.ALREADY_PROCESSED_TEXT));
        assertNull(again.get("dialog"), "重复交互不返回动作结果");
        assertEquals(grantsAfterFirst, ctx.grantCalls, "不再重复授予");
    }

    @Test
    @DisplayName("R3b: 非 once decor 可重复交互")
    void nonOnceRepeatable() {
        Map<String, Object> map = mapWith(List.of(
                withOnInteract(decor("sign_1", "sign", 1, 1), onInteract("dialog", "告示牌：注意安全"))), Map.of());
        FakeCtx ctx = new FakeCtx();
        assertEquals(Boolean.TRUE, MapInteractService.interact("map_1", map, "Alice", "sign_1", null, 1, 2, ctx).get("handled"));
        Map<String, Object> second = MapInteractService.interact("map_1", map, "Alice", "sign_1", null, 1, 2, ctx);
        assertEquals(Boolean.TRUE, second.get("handled"), "非 once 可重复");
        assertFalse(second.containsKey("processed"));
    }

    // ═══════════════════════════════════════════════════════════
    //  R4: conditions 门（requireFlag / failDialog）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("R4: requireFlag 不满足 → failDialog 返回且零动作执行；flag 写入后放行")
    void conditionsGate() {
        Map<String, Object> d = decor("door_1", "door", 5, 5);
        d.put("conditions", Map.of("requireFlag", "key_room", "failDialog", "门锁着…"));
        d.put("onInteract", onInteract("dialog", "门开了", "addItem", "c9"));
        Map<String, Object> map = mapWith(List.of(d), Map.of());
        FakeCtx ctx = new FakeCtx();
        // addItem 授予已知线索（未知线索无数据 → 不授予，见 R5d）
        ctx.clueTitles.put("c9", "神秘钥匙");
        // 门未满足：failDialog 返回，不执行动作
        Map<String, Object> blocked = MapInteractService.interact("map_1", map, "Alice", "door_1", null, 5, 6, ctx);
        assertEquals(Boolean.FALSE, blocked.get("handled"));
        assertEquals(Boolean.TRUE, blocked.get("blocked"));
        assertEquals(List.of("门锁着…"), blocked.get("dialog"));
        assertNull(blocked.get("items"), "条件不满足不执行 addItem");
        assertEquals(0, ctx.grantCalls);
        assertEquals(0, ctx.writeFlagCalls);
        assertFalse(ctx.processed.contains("map_1|door_1"), "条件拦截不标记 processed");
        // 满足后放行
        ctx.flags.add("key_room");
        Map<String, Object> open = MapInteractService.interact("map_1", map, "Alice", "door_1", null, 5, 6, ctx);
        assertEquals(Boolean.TRUE, open.get("handled"));
        assertEquals(List.of("门开了"), open.get("dialog"));
        assertTrue(ctx.held.contains("c9"));
    }

    // ═══════════════════════════════════════════════════════════
    //  R5: 动作叠加 + 未知动作
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("R5a: 一次交互叠加 dialog/addItem/flag/sound/anim/menu/state 全执行")
    void actionStacking() {
        Map<String, Object> d = decor("machine_1", "machine", 3, 3);
        d.put("state", Map.of("powered", false));
        d.put("onInteract", Map.of(
                "dialog", "机器启动了！",
                "addItem", Map.of("id", "c3", "title", "零件"),
                "flag", "machine_powered",
                "sound", "power_on",
                "anim", "machine_pulse",
                "menu", Map.of("type", "machine", "hint", "放入材料"),
                "state", Map.of("powered", true)));
        Map<String, Object> map = mapWith(List.of(d), Map.of());
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", "machine_1", null, 3, 3, ctx);
        assertEquals(Boolean.TRUE, r.get("handled"));
        assertEquals(List.of("机器启动了！"), r.get("dialog"));
        assertEquals(1, ((List<?>) r.get("items")).size());
        assertEquals("c3", ((Map<?, ?>) ((List<?>) r.get("items")).get(0)).get("id"));
        assertEquals("零件", ((Map<?, ?>) ((List<?>) r.get("items")).get(0)).get("title"));
        assertEquals(List.of("machine_powered"), r.get("flags"));
        assertEquals(List.of("power_on"), r.get("sounds"));
        assertEquals(List.of("machine_pulse"), r.get("anims"));
        assertEquals("machine", ((Map<?, ?>) r.get("menu")).get("type"));
        // state 合并：初始 {powered:false} + 运行时 {powered:true}
        assertEquals(Boolean.TRUE, ((Map<?, ?>) r.get("state")).get("powered"));
        assertEquals(Boolean.TRUE, ctx.states.get("map_1|machine_1").get("powered"));
        assertTrue(ctx.flags.contains("machine_powered"));
    }

    @Test
    @DisplayName("R5b: 未知动作类型 → warning 忽略不崩；dialog 正常执行")
    void unknownActionIgnored() {
        Map<String, Object> d = decor("odd_1", "odd", 1, 1);
        d.put("onInteract", Map.of("frobnicate", "x", "dialog", "正常文本"));
        Map<String, Object> map = mapWith(List.of(d), Map.of());
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", "odd_1", null, 1, 2, ctx);
        assertEquals(Boolean.TRUE, r.get("handled"));
        assertEquals(List.of("正常文本"), r.get("dialog"));
        assertTrue(((List<?>) r.get("warnings")).stream().anyMatch(w -> String.valueOf(w).contains("frobnicate")),
                String.valueOf(r.get("warnings")));
    }

    @Test
    @DisplayName("R5c: onInteract 列表形态按序执行（多步 dialog 顺序保留）")
    void listFormOrder() {
        Map<String, Object> d = decor("book_1", "book", 2, 2);
        d.put("onInteract", list(onInteract("dialog", "第一页"), onInteract("dialog", "第二页")));
        Map<String, Object> map = mapWith(List.of(d), Map.of());
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", "book_1", null, 2, 3, ctx);
        assertEquals(List.of("第一页", "第二页"), r.get("dialog"), "列表形态顺序执行");
    }

    @Test
    @DisplayName("R5d: addItem 未知线索 id 且无数据 → 不授予不报错；缺动作全空 → 交互未产生任何效果")
    void addItemUnknownClue() {
        Map<String, Object> d = withOnInteract(decor("ghost_1", "ghost", 1, 1), onInteract("addItem", "ghost_clue"));
        Map<String, Object> map = mapWith(List.of(d), Map.of());
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", "ghost_1", null, 1, 2, ctx);
        assertEquals(Boolean.FALSE, r.get("handled"), "无法授予的 addItem 不产生效果");
        assertFalse(r.containsKey("items"), "未知线索不授予");
        assertTrue(String.valueOf(r.get("result")).contains("交互未产生任何效果"), String.valueOf(r.get("result")));
        // 全空动作 → 未产生效果
        Map<String, Object> d2 = decor("empty_1", "empty", 2, 2);
        d2.put("onInteract", Map.of("unknown_action", 1));
        Map<String, Object> map2 = mapWith(List.of(d2), Map.of());
        Map<String, Object> r2 = MapInteractService.interact("map_1", map2, "Alice", "empty_1", null, 2, 3, ctx);
        assertEquals(Boolean.FALSE, r2.get("handled"));
        assertTrue(String.valueOf(r2.get("result")).contains("交互未产生任何效果"));
        assertTrue(((List<?>) r2.get("warnings")).stream().anyMatch(w -> String.valueOf(w).contains("unknown_action")));
    }

    // ═══════════════════════════════════════════════════════════
    //  R6: tileProps 分发（action 名 → 分发表查表 + args 透传）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("R6a: tileProps action=flag / action=dialog（字符串 args）分发")
    void tilePropsFlagAndStringArgs() {
        Map<String, Object> map = mapWith(List.of(), Map.of(
                "7,1", Map.of("action", "flag", "args", "examined_painting"),
                "7,2", Map.of("action", "dialog", "args", "墙上有道裂缝")));
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> f = MapInteractService.interact("map_1", map, "Alice", null, "7,1", 7, 1, ctx);
        assertEquals(Boolean.TRUE, f.get("handled"));
        assertEquals(List.of("examined_painting"), f.get("flags"));
        assertTrue(ctx.flags.contains("examined_painting"));
        Map<String, Object> d = MapInteractService.interact("map_1", map, "Alice", null, "7,2", 7, 2, ctx);
        assertEquals(List.of("墙上有道裂缝"), d.get("dialog"));
    }

    @Test
    @DisplayName("R6b: tileProps 无 action（如 water）→ 环境占位不崩")
    void tilePropsNoActionFallsToPlaceholder() {
        Map<String, Object> map = mapWith(List.of(), Map.of("5,9", Map.of("water", true)));
        FakeCtx ctx = new FakeCtx();
        Map<String, Object> r = MapInteractService.interact("map_1", map, "Alice", null, "5,9", 5, 9, ctx);
        assertEquals(Boolean.FALSE, r.get("handled"));
        assertEquals(MapInteractService.PLACEHOLDER_TEXT, r.get("result"));
    }

    /** 测试小工具：给 decor 附加 onInteract。 */
    private static Map<String, Object> withOnInteract(Map<String, Object> d, Map<String, Object> onInteract) {
        d.put("onInteract", onInteract);
        return d;
    }
}
