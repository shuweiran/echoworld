package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.map.BspMapGenerator;
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.MapValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0803-D（地图增强批次）验收测试：
 * <ul>
 *   <li>C1：线索地点覆盖补齐 pass（ensureClueZoneCoverage）——BSP 兜底地图补齐 / LLM 部分覆盖补齐不重复 / 全覆盖零新增 / 空输入原样</li>
 *   <li>C2：generateMap 统一路径端到端——BSP 降级后地图 zones 覆盖全部线索地点且校验通过</li>
 *   <li>C3：initGame 自动生成地图（调研项 1 方案 A）——init 响应 toMap 即含 map，无需手动 generateMap</li>
 * </ul>
 */
class ScriptMapCoverageTest {

    /** 契约合法地图（10×8：客厅/书房两个房间，zones 2 个，spawns 2 个）。 */
    private Map<String, Object> validLlmMap() {
        List<List<Integer>> ground = new ArrayList<>();
        List<List<Integer>> collision = new ArrayList<>();
        for (int y = 0; y < 8; y++) {
            List<Integer> g = new ArrayList<>();
            List<Integer> c = new ArrayList<>();
            for (int x = 0; x < 10; x++) {
                boolean wall = x == 0 || y == 0 || x == 9 || y == 7;
                g.add(wall ? 2 : 1);
                c.add(wall ? 1 : 0);
            }
            ground.add(g);
            collision.add(c);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", 1);
        m.put("map_id", "llm_manor");
        m.put("name", "老宅");
        m.put("theme", "民国老宅");
        m.put("tile_size", 32);
        m.put("width", 10);
        m.put("height", 8);
        m.put("layers", Map.of("ground", ground, "collision", collision));
        m.put("rooms", List.of(
            Map.of("id", "living", "name", "客厅", "x", 1, "y", 1, "w", 4, "h", 2),
            Map.of("id", "study", "name", "书房", "x", 5, "y", 1, "w", 4, "h", 2)));
        m.put("zones", List.of(
            Map.of("id", "z1", "name", "客厅八仙桌", "type", "search", "x", 2, "y", 1, "radius", 1, "clue_location", "客厅")));
        m.put("spawn_points", List.of(
            Map.of("id", "sp1", "type", "player", "x", 2, "y", 2),
            Map.of("id", "sp2", "type", "npc", "x", 6, "y", 2)));
        m.put("generator", Map.of("kind", "llm", "model", "mock"));
        return m;
    }

    // ═══════════════════════════════════════════════════════════
    //  C1: ensureClueZoneCoverage 覆盖补齐 pass
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1a: LLM 地图部分覆盖 → 补齐缺失地点、不重复、校验仍通过")
    void partialCoverageFilled() {
        Map<String, Object> m = validLlmMap(); // 仅 z1 覆盖「客厅」
        List<String> clueLocations = List.of("客厅", "书房", "花园");
        Map<String, Object> out = ScriptMapService.ensureClueZoneCoverage(m, clueLocations);

        List<Map<String, Object>> zones = zonesOf(out);
        assertEquals(3, zones.size(), "客厅(已有) + 书房 + 花园 = 3 个 zone");
        assertEquals("客厅", zoneByClue(zones, "客厅").get("clue_location"));
        assertNotNull(zoneByClue(zones, "书房"), "书房被补齐");
        assertNotNull(zoneByClue(zones, "花园"), "花园被补齐");
        // 自动 zone 的 id 前缀 + 落点可通行
        for (Map<String, Object> z : zones) {
            if (String.valueOf(z.get("id")).startsWith("z_auto_")) {
                assertEquals("search", z.get("type"));
                assertEquals(1, z.get("radius"));
                int x = (int) z.get("x");
                int y = (int) z.get("y");
                int[][] col = MapContract.intGrid(((Map<?, ?>) m.get("layers")).get("collision"));
                assertEquals(0, col[y][x], "自动 zone 必须落在可通行格");
            }
        }
        // 整体仍通过校验器
        assertTrue(MapValidator.validateMap(out).ok(), "补齐后地图校验仍通过");
    }

    @Test
    @DisplayName("C1b: BSP 兜底地图（房间A/B/C 脱钩）→ 覆盖 pass 补齐全部线索地点")
    void bspMapCoverage() {
        Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.defaults(42L));
        List<String> clueLocations = List.of("客厅", "花园", "书房");
        // 先走统一绑定（模拟 generateMap 内部顺序：bind → ensureCoverage）
        Map<String, Object> bound = ScriptMapService.bindClueLocations(bsp, clueLocations);
        Map<String, Object> out = ScriptMapService.ensureClueZoneCoverage(bound, clueLocations);

        List<Map<String, Object>> zones = zonesOf(out);
        assertNotNull(zoneByClue(zones, "客厅"), "客厅 zone 补齐（G4-1 修复）");
        assertNotNull(zoneByClue(zones, "花园"), "花园 zone 补齐");
        assertNotNull(zoneByClue(zones, "书房"), "书房 zone 补齐");
        assertEquals(3 + 3, zones.size(), "BSP 原生 3 个（房间A/B/C）+ 自动 3 个");
        assertTrue(MapValidator.validateMap(out).ok(), "补齐后仍通过 7 项校验");
    }

    @Test
    @DisplayName("C1c: 已全覆盖 → 零新增（返回原引用）")
    void allCoveredNoChange() {
        Map<String, Object> m = validLlmMap();
        Map<String, Object> out = ScriptMapService.ensureClueZoneCoverage(m, List.of("客厅"));
        assertSame(m, out, "无新增时返回原 map 引用");
        assertEquals(1, zonesOf(out).size());
    }

    @Test
    @DisplayName("C1d: 空线索地点 / null → 原样返回")
    void emptyInputsNoChange() {
        Map<String, Object> m = validLlmMap();
        assertSame(m, ScriptMapService.ensureClueZoneCoverage(m, List.of()));
        assertSame(m, ScriptMapService.ensureClueZoneCoverage(m, null));
        assertNull(ScriptMapService.ensureClueZoneCoverage(null, List.of("客厅")));
    }

    // ═══════════════════════════════════════════════════════════
    //  C2: generateMap 端到端（LLM 路径自动补齐）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2: LLM 地图缺 zone → generateMap 输出已补齐（统一路径内建覆盖 pass）")
    void generateMapFillsCoverage() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(validLlmMap()); // 只有 1 个 zone（客厅）
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(validLlmMap());
        ScriptMapService svc = new ScriptMapService(llm);
        ScriptMapService.MapResult r = svc.generateMap("民国", List.of("客厅", "书房"),
                List.of("客厅", "书房", "花园"), 42L);
        assertFalse(r.usedBsp());
        List<Map<String, Object>> zones = zonesOf(r.map());
        assertEquals(3, zones.size(), "覆盖 pass 补齐书房/花园");
        assertNotNull(zoneByClue(zones, "花园"));
        assertTrue(r.validation().ok());
    }

    // ═══════════════════════════════════════════════════════════
    //  C3: initGame 自动生成地图（调研项 1 方案 A 自动串联）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C3a: initGame 后 toMap 即含 map（自动串联，无需手动 generateMap）")
    void initAutoGeneratesMap() {
        // mock 返回剧本 JSON（非地图）→ 自动地图走 BSP 降级，确定性且无 LLM 依赖
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜。");
        script.put("truth", "凶手是管家。");
        script.put("roles", List.of("管家", "女仆"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false)));
        script.put("secrets", Map.of("管家", "贪图遗产", "女仆", "知道密信"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(script);

        ScriptGameService svc = new ScriptGameService(llm, new ApprovalService());
        String sid = "coverage-c3a-" + System.nanoTime();
        Map<String, Object> init = svc.initGame(sid, "庄园", List.of("Alice", "Bob"));

        assertTrue(init.get("map") instanceof Map<?, ?>, "init 响应直接携带 map（自动串联）");
        Map<?, ?> map = (Map<?, ?>) init.get("map");
        assertEquals("bsp", ((Map<?, ?>) map.get("generator")).get("kind"), "剧本 mock 非地图 → BSP 降级");
        // 覆盖 pass 对 BSP 生效：线索地点「客厅」在 zones 中
        List<Map<String, Object>> zones = zonesOf((Map<String, Object>) map);
        assertNotNull(zoneByClue(zones, "客厅"), "BSP 地图经覆盖 pass 后含「客厅」线索 zone");
    }

    @Test
    @DisplayName("C3b: init 自动地图不阻塞——LLM 抛异常时 init 仍成功（防御性兜底）")
    void initAutoMapNeverBlocks() {
        LLMClient llm = mock(LLMClient.class);
        // 剧本调用返回合法剧本；地图调用抛异常（不可命中同一 stub，用 thenAnswer 区分）
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜。");
        script.put("truth", "凶手是管家。");
        script.put("roles", List.of("管家", "女仆"));
        script.put("locations", List.of("客厅"));
        script.put("clues", List.of(Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false)));
        script.put("secrets", Map.of("管家", "贪图遗产", "女仆", "知道密信"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script)
                .thenThrow(new RuntimeException("map llm boom"));
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(script)
                .thenThrow(new RuntimeException("map llm boom"));

        ScriptGameService svc = new ScriptGameService(llm, new ApprovalService());
        String sid = "coverage-c3b-" + System.nanoTime();
        Map<String, Object> init = svc.initGame(sid, "庄园", List.of("Alice", "Bob"));

        assertTrue(init.get("map") instanceof Map<?, ?>, "地图 LLM 失败 → BSP 兜底，init 不阻塞");
        assertEquals("bsp", ((Map<?, ?>) ((Map<?, ?>) init.get("map")).get("generator")).get("kind"));
        assertNotNull(init.get("players"), "对局正常建立");
    }

    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> zonesOf(Map<String, Object> map) {
        return new ArrayList<>((List<Map<String, Object>>) map.get("zones"));
    }

    private static Map<String, Object> zoneByClue(List<Map<String, Object>> zones, String clueLocation) {
        for (Map<String, Object> z : zones) {
            if (clueLocation.equals(String.valueOf(z.get("clue_location")))) return z;
        }
        return null;
    }
}
