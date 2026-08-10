package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P-0810-21（地图方案统一版 P0 三项）验收测试：
 * <ul>
 *   <li>P0-1 剧本上下文注入地图 prompt —— buildPrompt 新增 background 段（剧本 background/storyline + truth），
 *       仅氛围参考、地点仍以「剧本地点」为准；旧签名委托零破坏</li>
 *   <li>P0-2 rooms 覆盖 locations 校验 —— 生成后处理（复用 matchLocation 同义词宽容匹配），
 *       缺失记 MapResult.warnings（不降级 BSP、不误杀）；rooms 全覆盖时零警告</li>
 *   <li>P0-3 尺寸下限 clamp（B 方案）—— 显式小尺寸提升到 min 32×20；超 LLM 预算仍走 BSP 既有行为不变</li>
 * </ul>
 */
class ScriptMapP0QualityTest {

    /** 契约合法地图（10×8：客厅/书房两个房间，zones 2 个，spawns 2 个；与 ScriptMapServiceTest.validLlmMap 同构）。 */
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
            Map.of("id", "z1", "name", "客厅八仙桌", "type", "search", "x", 2, "y", 1, "radius", 1, "clue_location", "客厅"),
            Map.of("id", "z2", "name", "书房书架", "type", "search", "x", 6, "y", 1, "radius", 1, "clue_location", "书房")));
        m.put("spawn_points", List.of(
            Map.of("id", "sp1", "type", "player", "x", 2, "y", 2),
            Map.of("id", "sp2", "type", "npc", "x", 6, "y", 2)));
        m.put("generator", Map.of("kind", "llm", "model", "mock"));
        return m;
    }

    /** LLM 返回指定地图的 mock。 */
    private LLMClient llmReturning(Map<String, Object> m) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(m);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(m);
        return llm;
    }

    /** LLM 空输出（BSP 降级路径）。 */
    private LLMClient emptyLlm() {
        return llmReturning(Map.of());
    }

    // ═══════════════════════════════════════════════════════════
    //  P0-1 剧本上下文注入地图 prompt
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("P0-1a: buildPrompt 新签名含背景段与真相；旧签名委托不注入（零破坏）")
    void buildPromptContainsBackground() {
        String bg = "风雨夜，庄园主人被杀。";
        String truth = "案件真相：凶手是管家";
        String withBg = ScriptMapService.buildPrompt("民国凶案", bg + "。" + truth,
                List.of("客厅", "书房"), List.of("客厅", "书房"), 24, 16);
        assertTrue(withBg.contains(bg), "新签名 prompt 应包含剧本背景");
        assertTrue(withBg.contains(truth), "新签名 prompt 应包含案件真相");
        assertTrue(withBg.contains("剧本背景（仅用于氛围与场景布置参考"), "应有背景段说明（仅氛围参考）");
        assertTrue(withBg.contains("必须严格覆盖这些地点"), "应强化 rooms 覆盖剧本地点软约束");

        String old = ScriptMapService.buildPrompt("民国凶案", List.of("客厅", "书房"), List.of("客厅", "书房"));
        assertFalse(old.contains(bg), "旧签名不应注入背景（委托 null 背景）");
        assertTrue(old.contains("（无背景信息"), "旧签名背景占位文案");
    }

    @Test
    @DisplayName("P0-1b: generateMap 注入 background 进 LLM prompt，且不影响校验与降级判定")
    void generateMapInjectsBackgroundIntoPrompt() {
        LLMClient llm = llmReturning(validLlmMap());
        ScriptMapService svc = new ScriptMapService(llm);
        List<String> locations = List.of("客厅", "书房");
        List<String> clues = List.of("客厅", "书房");
        String backgroundText = "风雨夜，庄园主人被杀。案件真相：凶手是管家";

        ScriptMapService.MapResult r = svc.generateMap("民国", backgroundText, locations, clues, 42L, 24, 16);

        assertFalse(r.usedBsp(), "合法 LLM 输出不应降级（注入背景不影响既有 7 项校验）");
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "llm".equals(g.get("kind")));
        assertTrue(r.validation().ok(), "校验通过 errors=" + r.validation().errors());
        assertTrue(r.warnings().isEmpty(), "rooms 全覆盖 → 无质量警告");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llm, times(1)).callJson(captor.capture(), eq(8000), anyInt());
        String prompt = captor.getValue();
        assertTrue(prompt.contains("风雨夜，庄园主人被杀"), "LLM 收到的 prompt 应含剧本背景");
        assertTrue(prompt.contains("案件真相：凶手是管家"), "LLM 收到的 prompt 应含真相");
        assertTrue(prompt.contains("客厅"), "prompt 仍含剧本地点");
    }

    // ═══════════════════════════════════════════════════════════
    //  P0-2 rooms 覆盖 locations 校验（生成后处理，warning 不降级）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("P0-2a: LLM 地图 rooms 漏地点 → warning 记录缺失地点，不降级 BSP")
    void roomCoverageWarnsMissingLocation() {
        LLMClient llm = llmReturning(validLlmMap()); // rooms: 客厅/书房；zones clue: 客厅/书房
        ScriptMapService svc = new ScriptMapService(llm);
        // 地点含 花园（由 ensureClueZoneCoverage 补 zone 覆盖）与 地下室（无 zone 无 room → 缺失）
        List<String> locations = List.of("客厅", "书房", "花园", "地下室");
        List<String> clues = List.of("客厅", "书房", "花园");

        ScriptMapService.MapResult r = svc.generateMap("民国", null, locations, clues, 42L, 24, 16);

        assertFalse(r.usedBsp(), "房间数 ≠ 地点数不是校验错误，不应降级 BSP");
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "llm".equals(g.get("kind")));
        assertTrue(!r.warnings().isEmpty(), "缺失地点应产生质量警告");
        String joined = String.join("", r.warnings());
        assertTrue(joined.contains("地下室"), "警告应点名缺失地点 地下室：warnings=" + r.warnings());
        assertFalse(joined.contains("花园"), "花园已被 zone 覆盖补齐，不应误报：warnings=" + r.warnings());
        // zones 侧覆盖兜底仍生效（花园自动补 zone，搜证可用）
        List<?> zones = (List<?>) r.map().get("zones");
        assertTrue(zones.stream().anyMatch(z -> z instanceof Map<?, ?> zm
                && "花园".equals(String.valueOf(zm.get("clue_location")))), "花园应有自动补齐 zone");
    }

    @Test
    @DisplayName("P0-2b: rooms 全覆盖 → 零警告；同义词宽容匹配不误杀")
    void noWarningWhenAllRoomsCovered() {
        LLMClient llm = llmReturning(validLlmMap());
        ScriptMapService svc = new ScriptMapService(llm);
        ScriptMapService.MapResult r = svc.generateMap("民国", null,
                List.of("客厅", "书房"), List.of("客厅", "书房"), 42L, 24, 16);
        assertTrue(r.warnings().isEmpty(), "rooms 覆盖全部地点 → 无警告：warnings=" + r.warnings());
    }

    @Test
    @DisplayName("P0-2c: BSP 兜底路径同样执行覆盖校验（占位房间名不匹配 → warning，不阻塞）")
    void bspPathWarnsMissingRooms() {
        ScriptMapService svc = new ScriptMapService(emptyLlm());
        // 客厅=有线索地点（zone 覆盖兜底不误报）；地下室=无线索地点（BSP 无对应房间/zone → 缺失）
        ScriptMapService.MapResult r = svc.generateMap("民国", null,
                List.of("客厅", "地下室"), List.of("客厅"), 0L, 24, 16);
        assertTrue(r.usedBsp(), "LLM 空输出 → BSP 降级（既有行为不变）");
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")));
        assertTrue(!r.warnings().isEmpty(), "BSP 占位房间名不覆盖无线索地点 → 有质量警告（不阻塞、不额外降级）");
        assertTrue(String.join("", r.warnings()).contains("地下室"),
                "警告应点名缺失地点 地下室：warnings=" + r.warnings());
        // zones 侧兜底仍可用（BSP 地图搜证不空手）
        assertTrue(((List<?>) r.map().get("zones")).size() >= 1, "线索地点 zone 覆盖补齐仍生效");
    }

    // ═══════════════════════════════════════════════════════════
    //  P0-3 尺寸下限 clamp（B 方案：min 32×20；大图 BSP 行为不变）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("P0-3a: 显式小尺寸（8×8 / 10×10）→ clamp 到 32×20")
    void explicitSmallSizeClampedToMin() {
        ScriptGameService svc = new ScriptGameService(emptyLlm(), new ApprovalService());
        String sid = "p0-3-clamp";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob"));

        Map<String, Object> r = svc.generateMap(sid, "", 0, true, 8, 8);
        assertEquals("bsp", ((Map<?, ?>) r.get("generator")).get("kind"));
        assertEquals(32, ((Map<?, ?>) r.get("map")).get("width"), "8×8 应被提升到下限 32");
        assertEquals(20, ((Map<?, ?>) r.get("map")).get("height"), "8×8 应被提升到下限 20");
        assertEquals(32, svc.getGame(sid).mapWidth, "对局记录尺寸应为 clamp 后尺寸");
        assertEquals(20, svc.getGame(sid).mapHeight);

        // 10×10（预算内但低于下限）→ 同样 clamp
        Map<String, Object> r2 = svc.generateMap(sid, "", 0, true, 10, 10);
        assertEquals(32, ((Map<?, ?>) r2.get("map")).get("width"));
        assertEquals(20, ((Map<?, ?>) r2.get("map")).get("height"));
    }

    @Test
    @DisplayName("P0-3b: 显式尺寸超 LLM 上限仍走 BSP 精确尺寸（既有行为保持不变）")
    void explicitOverBudgetStillBsp() {
        ScriptGameService svc = new ScriptGameService(emptyLlm(), new ApprovalService());
        String sid = "p0-3-big";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob"));

        Map<String, Object> r = svc.generateMap(sid, "", 0, true, 64, 64);
        assertEquals("bsp", ((Map<?, ?>) r.get("generator")).get("kind"));
        assertEquals(64, ((Map<?, ?>) r.get("map")).get("width"), "大图 BSP 精确尺寸不变");
        assertEquals(64, ((Map<?, ?>) r.get("map")).get("height"));
    }

    @Test
    @DisplayName("P0-3c: 默认尺寸 ≥ 下限时不 clamp（手动构造默认 40×24 保持既有行为）")
    void defaultSizeAboveMinUnchanged() {
        ScriptGameService svc = new ScriptGameService(emptyLlm(), new ApprovalService());
        String sid = "p0-3-default";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob"));

        Map<String, Object> r = svc.generateMap(sid, "", 0, true);
        assertEquals("bsp", ((Map<?, ?>) r.get("generator")).get("kind"));
        assertEquals(40, ((Map<?, ?>) r.get("map")).get("width"), "默认 40×24 ≥ 下限 → 不 clamp");
        assertEquals(24, ((Map<?, ?>) r.get("map")).get("height"));
    }
}
