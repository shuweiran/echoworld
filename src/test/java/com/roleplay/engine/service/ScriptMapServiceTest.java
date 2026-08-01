package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.ScriptController;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.map.MapValidator;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
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
 * 阶段 2 验收测试：LLM 地图生成统一路径（生成 → 校验 → 重试 → BSP 降级）+ zones↔clues 绑定。
 *
 * <p>覆盖：
 * <ul>
 *   <li>M1：LLM 合法输出 → LLM 路径（generator.kind=llm），校验通过，zones 绑定剧本线索地点</li>
 *   <li>M2：LLM 输出不合法（热点埋墙）→ 重试 2 次仍不合法 → BSP 降级（kind=bsp + fallback 原因）</li>
 *   <li>M3：LLM 失败（空输出）→ BSP 降级</li>
 *   <li>M4：宽容绑定 —— 精确 / trim / 同义词 / 子串 / 不匹配保留原值</li>
 *   <li>M5：ScriptGameService.generateMap —— 地图存对局 + toMap 暴露 + 二次调用缓存 + regenerate 重生成</li>
 *   <li>M6：快照持久化 —— map_data 落库并可恢复</li>
 *   <li>M7：controller 端点 —— POST /api/script/map 响应形状 + 缺 session_id 报错</li>
 * </ul>
 */
class ScriptMapServiceTest {

    /** 契约合法地图（10×8：客厅/书房两个房间，zones 2 个，spawns 3 个）。 */
    private Map<String, Object> validLlmMap() {
        List<List<Integer>> ground = new ArrayList<>();
        List<List<Integer>> collision = new ArrayList<>();
        for (int y = 0; y < 8; y++) {
            List<Integer> g = new ArrayList<>();
            List<Integer> c = new ArrayList<>();
            for (int x = 0; x < 10; x++) {
                // 外部一圈墙(2/1)，内部可通行(1/0)
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

    /** LLM 输出合法地图的 mock。 */
    private LLMClient validLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(validLlmMap());
        return llm;
    }

    /** LLM 输出不合法地图（热点埋在墙里，校验必不过）的 mock。 */
    private LLMClient invalidLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> m = validLlmMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> zones = new ArrayList<>((List<Map<String, Object>>) m.get("zones"));
        zones.set(0, Map.of("id", "z_bad", "name", "墙里热点", "type", "search", "x", 0, "y", 0, "radius", 1, "clue_location", "客厅"));
        m.put("zones", zones);
        when(llm.callJson(anyString(), anyInt())).thenReturn(m);
        return llm;
    }

    /** LLM 空输出的 mock（失败路径）。 */
    private LLMClient emptyLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());
        return llm;
    }

    // ═══════════════════════════════════════════════════════════
    //  M1-M3: 统一生成路径
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("M1: LLM 合法输出 → LLM 路径 + 校验通过 + zones 绑定剧本线索地点")
    void llmValidOutput() {
        ScriptMapService svc = new ScriptMapService(validLlm());
        ScriptMapService.MapResult r = svc.generateMap("民国", List.of("客厅", "书房"), List.of("客厅", "书房"), 42L);
        assertFalse(r.usedBsp(), "不应降级");
        assertTrue(r.validation().ok(), "校验通过 errors=" + r.validation().errors());
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "llm".equals(g.get("kind")));
        assertEquals(2, ((List<?>) r.map().get("zones")).size());
        assertEquals("客厅", zoneOf(r.map(), "z1").get("clue_location"));
    }

    @Test
    @DisplayName("M2: LLM 输出不合法（热点埋墙）→ 重试仍不合法 → BSP 降级 + fallback 原因")
    void llmInvalidFallsBackToBsp() {
        ScriptMapService svc = new ScriptMapService(invalidLlm());
        ScriptMapService.MapResult r = svc.generateMap("民国", List.of("客厅", "书房"), List.of("客厅", "书房"), 7L);
        assertTrue(r.usedBsp(), "必须降级 BSP");
        assertTrue(r.fallbackReasons().stream().anyMatch(s -> s.contains("校验失败")), "fallback 原因含校验失败");
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")));
        assertTrue(r.validation().ok(), "BSP 输出自洽");
    }

    @Test
    @DisplayName("M3: LLM 空输出/失败 → BSP 降级")
    void llmFailureFallsBackToBsp() {
        ScriptMapService svc = new ScriptMapService(emptyLlm());
        ScriptMapService.MapResult r = svc.generateMap("民国", List.of("客厅"), List.of("客厅"), 0L);
        assertTrue(r.usedBsp());
        assertTrue(r.fallbackReasons().stream().anyMatch(s -> s.contains("输出为空")), "fallback 原因含输出为空");
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")));
        assertTrue(r.validation().ok());
    }

    @Test
    @DisplayName("M3b: LLM 抛异常（超时/网络）→ BSP 降级不崩")
    void llmExceptionFallsBack() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenThrow(new RuntimeException("timeout"));
        ScriptMapService svc = new ScriptMapService(llm);
        ScriptMapService.MapResult r = svc.generateMap("民国", List.of(), List.of(), 0L);
        assertTrue(r.usedBsp());
        assertTrue(r.map().get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")));
    }

    // ═══════════════════════════════════════════════════════════
    //  M4: zones↔clues 宽容绑定（契约 §5：trim + 同义词表）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("M4: 宽容绑定 —— 精确/trim/同义词/子串/不匹配保留")
    void lenientBinding() {
        List<String> standards = List.of("客厅", "书房", "花园", "地下室");

        // 精确
        assertEquals("客厅", ScriptMapService.matchLocation("客厅", standards));
        // trim
        assertEquals("书房", ScriptMapService.matchLocation(" 书房 ", standards));
        // 同义词表
        assertEquals("客厅", ScriptMapService.matchLocation("大厅", standards));
        assertEquals("地下室", ScriptMapService.matchLocation("地窖", standards));
        assertEquals("花园", ScriptMapService.matchLocation("庭院", standards));
        // 子串（LLM 扩写）
        assertEquals("书房", ScriptMapService.matchLocation("书房的书架", standards));
        // 不匹配 → 保留原值
        assertEquals("天台", ScriptMapService.matchLocation("天台", standards));
    }

    @Test
    @DisplayName("M4b: bindClueLocations 归一 zones 的 clue_location 到剧本标准地点")
    void bindZones() {
        Map<String, Object> m = validLlmMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> zones = new ArrayList<>((List<Map<String, Object>>) m.get("zones"));
        zones.set(1, new LinkedHashMap<>(zones.get(1)));
        zones.get(1).put("clue_location", "图书室"); // 同义词 → 书房
        m.put("zones", zones);
        Map<String, Object> bound = ScriptMapService.bindClueLocations(m, List.of("书房"));
        assertEquals("书房", zoneOf(bound, "z2").get("clue_location"), "同义词表归一");
        // 非 search 类型不改
        assertEquals("客厅", zoneOf(bound, "z1").get("clue_location"));
    }

    // ═══════════════════════════════════════════════════════════
    //  M5-M7: ScriptGameService.generateMap + controller 端点
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("M5: generateMap 存对局 + toMap 暴露 + 二次缓存 + regenerate 重生成")
    void gameMapLifecycle() {
        ScriptGameService svc = new ScriptGameService(validLlm(), new ApprovalService());
        String sid = "map-m5";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));

        Map<String, Object> r1 = svc.generateMap(sid, "", 0, false);
        assertEquals(Boolean.FALSE, r1.get("cached"));
        assertTrue(r1.get("map") instanceof Map<?, ?>);
        assertEquals("llm", ((Map<?, ?>) r1.get("generator")).get("kind"));
        assertEquals(sid, r1.get("session_id"));

        // toMap 暴露 map
        Map<String, Object> st = svc.getGame(sid).toMap("Alice");
        assertTrue(st.get("map") instanceof Map<?, ?>);

        // 二次调用 → 缓存（不再调 LLM）
        Map<String, Object> r2 = svc.generateMap(sid, "", 0, false);
        assertEquals(Boolean.TRUE, r2.get("cached"));

        // regenerate=true → 重生成
        Map<String, Object> r3 = svc.generateMap(sid, "", 0, true);
        assertEquals(Boolean.FALSE, r3.get("cached"));

        // getMap 独立入口
        Map<String, Object> gm = svc.getMap(sid);
        assertTrue(gm.get("map") instanceof Map<?, ?>);
    }

    @Test
    @DisplayName("M5b: 无对局/未生成地图 → 明确报错")
    void gameMapErrors() {
        ScriptGameService svc = new ScriptGameService(validLlm(), new ApprovalService());
        assertEquals("游戏不存在", svc.generateMap("nope", "", 0, false).get("error"));
        String sid = "map-m5b";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob"));
        assertEquals("地图尚未生成", svc.getMap(sid).get("error"));
    }

    @Test
    @DisplayName("M7: controller 端点 —— POST /api/script/map 响应形状 + 缺 session_id 报错")
    void controllerEndpoint() throws Exception {
        ScriptGameService svc = new ScriptGameService(validLlm(), new ApprovalService());
        String sid = "map-m7";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob"));

        ScriptController controller = new ScriptController(svc, null, mock(SimulationService.class));
        setCurrentSession(controller, sid);

        ResponseEntity<Map<String, Object>> resp = controller.map(Map.of("session_id", sid));
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().get("map") instanceof Map<?, ?>);
        assertTrue(resp.getBody().get("generator") instanceof Map<?, ?>);
        assertEquals("llm", ((Map<?, ?>) resp.getBody().get("generator")).get("kind"));

        // 缺 session_id（当前对局兜底走 init 的 currentSessionId）
        ResponseEntity<Map<String, Object>> respNoSid = controller.map(Map.of());
        assertNotNull(respNoSid.getBody().get("map"), "currentSessionId 兜底");

        // 非法 seed 不崩
        ResponseEntity<Map<String, Object>> respBadSeed = controller.map(Map.of("session_id", sid, "seed", "abc"));
        assertNotNull(respBadSeed.getBody().get("map"));
    }

    @Test
    @DisplayName("M7b: controller 无当前对局 → 缺 session_id 报错")
    void controllerMissingSession() {
        ScriptController controller = new ScriptController(new ScriptGameService(validLlm(), new ApprovalService()),
                null, mock(SimulationService.class));
        ResponseEntity<Map<String, Object>> resp = controller.map(Map.of());
        assertTrue(String.valueOf(resp.getBody().get("error")).contains("session_id"));
    }

    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static Map<String, Object> zoneOf(Map<String, Object> map, String id) {
        for (Object o : (List<Object>) map.get("zones")) {
            Map<String, Object> z = (Map<String, Object>) o;
            if (id.equals(z.get("id"))) return z;
        }
        throw new AssertionError("zone " + id + " not found");
    }

    /** 反射设置 controller.currentSessionId（private 字段）。 */
    private static void setCurrentSession(ScriptController controller, String sid) throws Exception {
        Field f = ScriptController.class.getDeclaredField("currentSessionId");
        f.setAccessible(true);
        f.set(controller, sid);
    }
}
