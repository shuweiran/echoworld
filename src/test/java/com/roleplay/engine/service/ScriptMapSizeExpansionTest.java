package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.ScriptController;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.SimulationService;
import com.roleplay.engine.simulation.map.BspMapGenerator;
import com.roleplay.engine.simulation.map.MapContract;
import com.roleplay.engine.simulation.map.MapValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0803-J（剧本杀地图容量扩展）验收测试：
 * <ul>
 *   <li>S1：BSP 大图参数化生成 —— 64×64 / 48×48 / 100×60 / 128×64 显式尺寸生成成功 + 7 项校验通过
 *        + 热点数按面积缩放（≥默认 3、不超房间数、64×64+ 不空旷）</li>
 *   <li>S2：ScriptMapService 统一路径显式尺寸 —— LLM 空输出降级 BSP 精确按尺寸；大图（超 40×24）
 *        跳过 LLM 直接 BSP（fallback 原因含 token 预算说明，LLM 零调用 verify）</li>
 *   <li>S3：预算内显式尺寸仍走 LLM 路径；buildPrompt 含本次要求尺寸（含示例 JSON 尺寸同步）</li>
 *   <li>S4：ScriptGameService.generateMap 显式尺寸 → 落对局；regenerate 无显式尺寸保持原尺寸</li>
 *   <li>S5：controller POST /api/script/map 透传 width/height（非法尺寸不崩）</li>
 *   <li>旧断言锁定默认 24×16 不动（BspMapGeneratorTest.structureContract 等既有用例未改）</li>
 * </ul>
 */
class ScriptMapSizeExpansionTest {

    // ═══════════════════════════════════════════════════════════
    //  S1: BSP 大图参数化生成（尺寸精确 + 校验通过 + 热点按面积缩放）
    //  ═══════════════════════════════════════════════════════════

    static Stream<Arguments> largeSizes() {
        return Stream.of(
                Arguments.of(64, 64),   // 4K 格级大图（任务要求的 64×64 主场景）
                Arguments.of(48, 48),
                Arguments.of(100, 60),
                Arguments.of(128, 64)); // 更大尺寸冒烟
    }

    @ParameterizedTest(name = "S1: BSP 大图 {0}×{1} 生成 + 校验 + 热点缩放")
    @MethodSource("largeSizes")
    @DisplayName("S1: BSP 大图参数化 —— 尺寸精确 + 7 项校验通过 + 热点按面积缩放")
    void bspLargeMaps(int w, int h) {
        // zonesCount=-1 → 按面积自动缩放（P-0803-J 语义）
        Map<String, Object> m = BspMapGenerator.generate(BspMapGenerator.Options.of(20260801L, w, h, -1));
        assertEquals(w, m.get("width"), "width 必须精确等于请求尺寸");
        assertEquals(h, m.get("height"), "height 必须精确等于请求尺寸");
        assertEquals(1, m.get("map_version"));

        MapValidator.Result r = MapValidator.validateMap(m);
        assertTrue(r.ok(), "大图必须通过 7 项校验：errors=" + r.errors());

        // 热点数按面积缩放：不低于默认 3、不超过房间数（生成器 min 封顶）
        List<?> zones = (List<?>) m.get("zones");
        List<?> rooms = (List<?>) m.get("rooms");
        assertTrue(zones.size() >= BspMapGenerator.DEFAULT_ZONES_COUNT,
                "大图热点不应低于默认 3：zones=" + zones.size());
        assertTrue(zones.size() <= rooms.size(), "热点数不超过房间数：zones=" + zones.size() + " rooms=" + rooms.size());

        // 大图不空旷：64×64+（面积 ≥ 16× 默认）热点应显著多于默认 3
        if (w * h >= 64 * 64) {
            assertTrue(zones.size() >= 5, "64×64+ 大图热点应 ≥5（按面积缩放，不空旷）：zones=" + zones.size());
        }

        // 热点/出生点全部落在可通行格
        int[][] col = MapContract.intGrid(((Map<?, ?>) m.get("layers")).get("collision"));
        for (Object o : zones) {
            Map<?, ?> z = (Map<?, ?>) o;
            int x = MapContract.intOf(z.get("x"), -1), y = MapContract.intOf(z.get("y"), -1);
            assertEquals(0, col[y][x], "zone " + z.get("id") + " 必须在可通行格");
            assertEquals("search", z.get("type"));
        }
        for (Object o : (List<?>) m.get("spawn_points")) {
            Map<?, ?> s = (Map<?, ?>) o;
            int x = MapContract.intOf(s.get("x"), -1), y = MapContract.intOf(s.get("y"), -1);
            assertEquals(0, col[y][x], "spawn " + s.get("id") + " 必须在可通行格");
        }
    }

    @Test
    @DisplayName("S1b: scaledZonesCount 缩放曲线 —— 默认尺寸=3、面积×4 → 热点×2、下限恒 3")
    void scaledZonesCurve() {
        assertEquals(3, BspMapGenerator.scaledZonesCount(24, 16), "默认 24×16 必须仍是 3（旧行为不变）");
        assertEquals(3, BspMapGenerator.scaledZonesCount(12, 8), "小图下限恒 3");
        int big = BspMapGenerator.scaledZonesCount(64, 64);
        assertTrue(big >= 5 && big <= 15, "64×64 热点应约 10（√面积缩放）：" + big);
        assertTrue(BspMapGenerator.scaledZonesCount(128, 128) > big, "面积更大 → 热点更多");
    }

    // ═══════════════════════════════════════════════════════════
    //  S2: ScriptMapService 统一路径显式尺寸（BSP 降级精确尺寸 / 大图预算闸）
    //  ═══════════════════════════════════════════════════════════

    /** 契约合法地图（10×8 小图，LLM mock 用）。 */
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

    /** LLM 空输出的 mock（失败路径 → BSP 降级）。 */
    private LLMClient emptyLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(Map.of());
        return llm;
    }

    @Test
    @DisplayName("S2a: 显式 64×64（LLM 空输出）→ BSP 降级精确按尺寸 + 校验通过 + 热点缩放")
    void serviceLargeMapBspFallback() {
        ScriptMapService svc = new ScriptMapService(emptyLlm());
        ScriptMapService.MapResult r = svc.generateMap("庄园", List.of("客厅", "书房"),
                List.of("客厅", "书房"), 0L, 64, 64);
        assertTrue(r.usedBsp(), "LLM 空输出必须降级 BSP");
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")));
        assertEquals(64, r.map().get("width"));
        assertEquals(64, r.map().get("height"));
        assertTrue(r.validation().ok(), "降级大图必须通过校验 errors=" + r.validation().errors());
        assertTrue(((List<?>) r.map().get("zones")).size() >= 5, "大图热点按面积缩放 ≥5");
    }

    @Test
    @DisplayName("S2b: 大图超 LLM token 预算（40×24）→ 跳过 LLM 直接 BSP，fallback 原因含预算说明，LLM 零调用")
    void serviceLargeMapSkipsLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(validLlmMap());
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(validLlmMap());
        ScriptMapService svc = new ScriptMapService(llm);
        ScriptMapService.MapResult r = svc.generateMap("庄园", List.of("客厅", "书房"),
                List.of("客厅", "书房"), 42L, 64, 64);
        assertTrue(r.usedBsp());
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")));
        assertTrue(r.fallbackReasons().stream().anyMatch(s -> s.contains("LLM token 预算")),
                "fallback 原因必须含 token 预算说明：" + r.fallbackReasons());
        assertEquals(64, r.map().get("width"));
        assertEquals(64, r.map().get("height"));
        // 预算闸：LLM 一次都不应被调用（大图直接 BSP）
        verify(llm, never()).callJson(anyString(), anyInt());
        verify(llm, never()).callJson(anyString(), anyInt(), anyInt());
    }

    // ═══════════════════════════════════════════════════════════
    //  S3: 预算内显式尺寸仍走 LLM 路径 + prompt 尺寸嵌入
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S3a: 预算内显式尺寸（32×20）仍走 LLM 路径")
    void serviceInBudgetLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(validLlmMap());
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(validLlmMap());
        ScriptMapService svc = new ScriptMapService(llm);
        ScriptMapService.MapResult r = svc.generateMap("庄园", List.of("客厅", "书房"),
                List.of("客厅", "书房"), 42L, 32, 20);
        assertFalse(r.usedBsp(), "预算内不应降级");
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "llm".equals(g.get("kind")));
    }

    @Test
    @DisplayName("S3b: buildPrompt 显式尺寸 → prompt 含本次要求尺寸 + 示例 JSON 尺寸同步")
    void promptContainsSize() {
        String p = ScriptMapService.buildPrompt("庄园", List.of("客厅"), List.of("书房"), 32, 20);
        assertTrue(p.contains("32 × 20"), "prompt 应含本次要求尺寸");
        assertTrue(p.contains("\"width\": 32") && p.contains("\"height\": 20"), "示例 JSON 尺寸应同步为 32×20");
        // 旧四参签名：默认尺寸提示保留（20-32 × 14-20 建议）
        String old = ScriptMapService.buildPrompt("庄园", List.of("客厅"), List.of("书房"));
        assertTrue(old.contains("建议 20-32 × 14-20"), "旧签名保持默认尺寸建议");
        assertTrue(old.contains("\"width\": 24") && old.contains("\"height\": 16"), "旧签名示例 JSON 保持 24×16");
    }

    // ═══════════════════════════════════════════════════════════
    //  S4: ScriptGameService.generateMap 显式尺寸（落对局 + regenerate 保持）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S4: generateMap 显式 64×64 → 落对局；regenerate 无显式尺寸保持原尺寸；默认尺寸零破坏")
    void gameMapExplicitSize() {
        ScriptGameService svc = new ScriptGameService(emptyLlm(), new ApprovalService());
        String sid = "size-s4";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));

        // init 自动地图为默认尺寸（P-0804-H 续：默认 40×24 大地图；LLM 空输出 → BSP 精确尺寸）
        Map<String, Object> init = svc.getGame(sid).toMap("Alice");
        assertEquals(40, ((Map<?, ?>) init.get("map")).get("width"));
        assertEquals(24, ((Map<?, ?>) init.get("map")).get("height"));

        // 显式 64×64 重生成 → BSP 精确尺寸
        Map<String, Object> r = svc.generateMap(sid, "", 0, true, 64, 64);
        assertEquals("bsp", ((Map<?, ?>) r.get("generator")).get("kind"));
        assertEquals(64, ((Map<?, ?>) r.get("map")).get("width"));
        assertEquals(64, ((Map<?, ?>) r.get("map")).get("height"));

        // regenerate 无显式尺寸 → 保持对局已定 64×64（不回落默认）
        Map<String, Object> r2 = svc.generateMap(sid, "", 0, true);
        assertEquals(64, ((Map<?, ?>) r2.get("map")).get("width"));
        assertEquals(64, ((Map<?, ?>) r2.get("map")).get("height"));

        // 新对局不传尺寸 → 默认 40×24（P-0804-H 续；对局间尺寸独立，不串）
        String sid2 = "size-s4b";
        svc.initGame(sid2, "庄园", List.of("Alice", "Bob"));
        Map<String, Object> r3 = svc.generateMap(sid2, "", 0, true);
        assertEquals(40, ((Map<?, ?>) r3.get("map")).get("width"));
        assertEquals(24, ((Map<?, ?>) r3.get("map")).get("height"));
    }

    // ═══════════════════════════════════════════════════════════
    //  S5: controller 端点透传 width/height
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S5: POST /api/script/map 透传 width/height；非法尺寸不崩回落默认")
    void controllerPassesSize() throws Exception {
        ScriptGameService svc = new ScriptGameService(emptyLlm(), new ApprovalService());
        String sid = "size-s5";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob"));
        ScriptController controller = new ScriptController(svc, null, mock(SimulationService.class));
        setCurrentSession(controller, sid);

        // 显式 64×64 + regenerate → BSP 精确尺寸
        ResponseEntity<Map<String, Object>> resp = controller.map(Map.of(
                "session_id", sid, "width", "64", "height", "64", "regenerate", "true"));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(64, ((Map<?, ?>) resp.getBody().get("map")).get("width"));
        assertEquals(64, ((Map<?, ?>) resp.getBody().get("map")).get("height"));

        // 非法尺寸字符串不崩 → 回落对局已定尺寸（64×64）
        ResponseEntity<Map<String, Object>> respBad = controller.map(Map.of(
                "session_id", sid, "width", "abc", "height", "xyz", "regenerate", "true"));
        assertEquals(200, respBad.getStatusCode().value());
        assertEquals(64, ((Map<?, ?>) respBad.getBody().get("map")).get("width"));
    }

    // ═══════════════════════════════════════════════════════════

    /** 反射设置 controller.currentSessionId（private 字段，与 ScriptMapServiceTest 同款）。 */
    private static void setCurrentSession(ScriptController controller, String sid) throws Exception {
        Field f = ScriptController.class.getDeclaredField("currentSessionId");
        f.setAccessible(true);
        f.set(controller, sid);
    }
}
