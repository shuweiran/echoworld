package com.roleplay.engine.controller;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptMapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0803-O 两条地图链路 LLM 全量生成 —— 剧本卡默认地图端点（POST /api/scenes/map）双模式验收测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>O1：body 带非空 theme → LLM 全量生成（generator.kind=llm / mode=llm / 契约 v1 校验通过）</li>
 *   <li>O2：body 无 theme（空/缺省）→ BSP 确定性零回归（generator.kind=bsp，P-0803-H 既有行为不变）</li>
 *   <li>O3：LLM 输出为空（失败路径）→ 自动 BSP 降级兜底（mode=bsp-fallback + fallback 原因 + 契约 v1 仍合法）</li>
 *   <li>O4：4 参旧构造（mapService=null）带 theme 请求 → 防御回落 BSP 确定性（不崩）</li>
 * </ul>
 *
 * <p>单元测试直接构造 controller（5 参/4 参构造），ScriptMapService 用真实实例 + Mockito mock LLMClient
 * （对齐 ScriptMapServiceTest 模式），零 Spring 上下文、零网络。
 */
class SceneMapLlmModeTest {

    /** 契约合法地图（10×8：客厅/书房两个房间，zones 2 个，spawns 2 个）—— 与 ScriptMapServiceTest 同款夹具。 */
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
        return m;
    }

    /** LLM 输出合法地图的 mock（3 参 callJson = prompt + maxTokens + timeout 的现行签名）。 */
    private LLMClient validLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(validLlmMap());
        return llm;
    }

    /** LLM 空输出（失败路径）的 mock。 */
    private LLMClient emptyLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt(), anyInt())).thenReturn(Map.of());
        return llm;
    }

    private SceneController controller(LLMClient llm) {
        return new SceneController(mock(GeneratorService.class), mock(RouterService.class),
                mock(CharacterController.class), mock(DatabaseService.class),
                new ScriptMapService(llm));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(ResponseEntity<Map<String, Object>> resp) {
        return resp.getBody();
    }

    @Test
    @DisplayName("O1: 带 theme → LLM 全量生成（kind=llm / mode=llm / 契约 v1 校验通过）")
    void themeRequestUsesLlmFullGeneration() {
        SceneController controller = controller(validLlm());
        ResponseEntity<Map<String, Object>> resp =
                controller.generateDefaultMap(Map.of("theme", "民国宅邸凶案"));
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = mapOf(resp);
        assertNotNull(body.get("map"));
        Map<String, Object> map = (Map<String, Object>) body.get("map");
        assertEquals("llm", body.get("mode"));
        assertTrue(map.get("generator") instanceof Map<?, ?> g && "llm".equals(g.get("kind")),
                "LLM 模式生成器 kind 应为 llm");
        // 契约 v1 全量元素（ground+collision 双层数组 + rooms/zones/spawns）
        assertTrue(map.get("map_version") instanceof Number n && n.intValue() >= 1);
        assertTrue(map.get("layers") instanceof Map<?, ?> layers
                && layers.get("ground") instanceof List<?>
                && layers.get("collision") instanceof List<?>, "应含 ground+collision 双层数组");
        assertTrue(map.get("rooms") instanceof List<?> rooms && !rooms.isEmpty(), "应含 rooms");
        assertTrue(map.get("zones") instanceof List<?> zones && !zones.isEmpty(), "应含 zones");
        assertTrue(map.get("spawn_points") instanceof List<?> spawns && !spawns.isEmpty(), "应含 spawn_points");
        // 溯源键
        assertTrue(body.get("validation") instanceof Map<?, ?> v && Boolean.TRUE.equals(v.get("ok")), "校验应通过");
        assertTrue(body.get("fallback") instanceof List<?> && ((List<?>) body.get("fallback")).isEmpty(), "LLM 成功路径无兜底");
        assertTrue(body.get("generator") instanceof Map<?, ?>);
    }

    @Test
    @DisplayName("O2: 无 theme（空/缺省）→ BSP 确定性零回归（kind=bsp，P-0803-H 既有行为）")
    void noThemeKeepsBspDeterministic() {
        SceneController controller = controller(emptyLlm()); // 即便 LLM 可用，无 theme 也不应触发 LLM
        // 缺省 body
        ResponseEntity<Map<String, Object>> r1 = controller.generateDefaultMap(null);
        assertEquals(200, r1.getStatusCode().value());
        Map<String, Object> bsp1 = (Map<String, Object>) mapOf(r1).get("map");
        assertTrue(bsp1.get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")),
                "无 theme 必须走 BSP（零 LLM）");
        // 显式空串 theme + seed
        ResponseEntity<Map<String, Object>> r2 = controller.generateDefaultMap(Map.of("theme", "", "seed", 42L));
        Map<String, Object> bsp2 = (Map<String, Object>) mapOf(r2).get("map");
        assertTrue(bsp2.get("generator") instanceof Map<?, ?> g2 && "bsp".equals(g2.get("kind")));
        // 同 seed 同输出（确定性，P-0803-H ⑤ 语义在 controller 层复验）
        ResponseEntity<Map<String, Object>> r3 = controller.generateDefaultMap(Map.of("theme", "  ", "seed", 42L));
        assertEquals(bsp2.get("map_id"), ((Map<String, Object>) mapOf(r3).get("map")).get("map_id"));
    }

    @Test
    @DisplayName("O3: LLM 输出为空（失败路径）→ 自动 BSP 降级兜底（mode=bsp-fallback + fallback 原因）")
    void llmFailureFallsBackToBsp() {
        SceneController controller = controller(emptyLlm());
        ResponseEntity<Map<String, Object>> resp =
                controller.generateDefaultMap(Map.of("theme", "民国宅邸凶案"));
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = mapOf(resp);
        assertEquals("bsp-fallback", body.get("mode"), "LLM 失败应标记 bsp-fallback");
        Map<String, Object> map = (Map<String, Object>) body.get("map");
        assertTrue(map.get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")), "兜底地图 generator=bsp");
        assertTrue(body.get("fallback") instanceof List<?> fb && !fb.isEmpty(), "fallback 应含原因");
        assertTrue(String.valueOf(fbOf(body)).contains("输出为空"), "fallback 原因含 LLM 输出为空");
        // 兜底地图仍契约 v1 合法（BspMapGenerator 输出自洽）
        assertTrue(map.get("map_version") instanceof Number n && n.intValue() >= 1);
        assertTrue(map.get("zones") instanceof List<?> zones && !zones.isEmpty());
        assertTrue(map.get("spawn_points") instanceof List<?> spawns && !spawns.isEmpty());
        assertTrue(body.get("validation") instanceof Map<?, ?> v && Boolean.TRUE.equals(v.get("ok")), "BSP 兜底自洽校验通过");
    }

    @Test
    @DisplayName("O4: 4 参旧构造（mapService=null）+ 带 theme 请求 → 防御回落 BSP 确定性（不崩）")
    void fourArgConstructorFallsBackDefensively() {
        SceneController controller = new SceneController(mock(GeneratorService.class), mock(RouterService.class),
                mock(CharacterController.class), mock(DatabaseService.class));
        ResponseEntity<Map<String, Object>> resp =
                controller.generateDefaultMap(Map.of("theme", "民国宅邸凶案", "seed", 7L));
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> map = (Map<String, Object>) mapOf(resp).get("map");
        assertTrue(map.get("generator") instanceof Map<?, ?> g && "bsp".equals(g.get("kind")),
                "无 ScriptMapService 时 theme 请求防御回落 BSP");
    }

    @SuppressWarnings("unchecked")
    private static List<String> fbOf(Map<String, Object> body) {
        return (List<String>) body.get("fallback");
    }
}
