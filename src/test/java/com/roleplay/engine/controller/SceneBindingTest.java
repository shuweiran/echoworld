package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-0803-H 剧本选择与角色卡功能改造 —— 剧本卡绑定后端验收测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>① 创建剧本带 category/default_roles/default_map → 响应回显 + GET 列表透传</li>
 *   <li>② 旧式创建（不带新字段）→ 默认 category=general / 空默认角色组 / 无地图（向后兼容）</li>
 *   <li>③ PUT 更新分类/默认角色组 + 空串清除地图 → 生效且可回读</li>
 *   <li>④ POST /api/scenes/map → BSP 确定性生成契约 v1 地图（map_version/width/height/zones/spawn_points）</li>
 *   <li>⑤ 同 seed 同输出（确定性）</li>
 * </ul>
 *
 * <p>测试走 application-test.yml（H2 mem create-drop + mock LLM + RANDOM_PORT，D-008 基建）。
 * 各用例使用独立 scene_id 前缀，互不依赖、顺序无关。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SceneBindingTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("① 创建剧本带分类/默认角色组/默认地图 → 回显 + 列表透传")
    void create_withBindings_roundTrips() throws Exception {
        String sid = "sb1";
        Map<String, Object> defaultMap = Map.of("map_version", 1, "name", "测试地图");
        MvcResult res = postScene(sid, Map.of(
                "name", "剧本一",
                "description", "测试描述",
                "category", "werewolf",
                "default_roles", List.of("苏哲", "林诗"),
                "default_map", defaultMap
        ));
        JsonNode created = res.json();
        assertEquals("werewolf", created.get("category").asText());
        assertEquals(2, created.get("default_roles").size());
        assertEquals("苏哲", created.get("default_roles").get(0).asText());
        assertEquals("测试地图", created.get("default_map").get("name").asText());

        // GET 列表透传
        JsonNode list = getScenes();
        JsonNode sc = findScene(list, sid);
        assertNotNull(sc, "scene " + sid + " 应在列表中");
        assertEquals("werewolf", sc.get("category").asText());
        assertEquals(2, sc.get("default_roles").size());
        assertEquals("测试地图", sc.get("default_map").get("name").asText());
    }

    @Test
    @DisplayName("② 旧式创建（不带新字段）→ 默认 general / 空角色组 / 无地图（向后兼容）")
    void create_legacy_usesDefaults() throws Exception {
        String sid = "sb2";
        MvcResult res = postScene(sid, Map.of("name", "旧式剧本", "description", "d"));
        JsonNode created = res.json();
        assertEquals("general", created.get("category").asText());
        assertTrue(created.get("default_roles").isArray() && created.get("default_roles").isEmpty());
        // default_map 缺省（non_null 序列化省略）或显式 null 均视为无地图
        assertTrue(!created.has("default_map") || created.get("default_map").isNull(), "旧式创建 default_map 应为空");

        JsonNode sc = findScene(getScenes(), sid);
        assertNotNull(sc);
        assertEquals("general", sc.get("category").asText());
        assertTrue(sc.get("default_roles").isEmpty());
    }

    @Test
    @DisplayName("③ PUT 更新分类/默认角色组 + 空串清除地图 → 生效且可回读")
    void update_changesBindings_andEmptyMapClears() throws Exception {
        String sid = "sb3";
        postScene(sid, Map.of(
                "name", "剧本三",
                "description", "d",
                "category", "general",
                "default_roles", List.of("阿强"),
                "default_map", Map.of("map_version", 1, "name", "旧地图")
        ));

        // 更新：改分类 + 角色组 + 空串清除地图
        mockMvc.perform(put("/api/scenes/" + sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "category", "werewolf",
                                "default_roles", List.of("老王", "小美"),
                                "default_map", ""
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("werewolf"))
                .andExpect(jsonPath("$.default_roles.length()").value(2))
                .andExpect(jsonPath("$.default_map").doesNotExist());

        JsonNode sc = findScene(getScenes(), sid);
        assertNotNull(sc);
        assertEquals("werewolf", sc.get("category").asText());
        assertEquals(2, sc.get("default_roles").size());
        assertEquals("老王", sc.get("default_roles").get(0).asText());
        assertTrue(!sc.has("default_map") || sc.get("default_map").isNull(), "清除地图后 default_map 应为空");
    }

    @Test
    @DisplayName("④ POST /api/scenes/map → BSP 契约 v1 地图（确定性生成，零 LLM）")
    void mapEndpoint_returnsBspContractV1() throws Exception {
        MvcResult res = callMap(12345L);
        JsonNode map = res.json().get("map");
        assertNotNull(map, "响应应含 map 键");
        assertTrue(map.get("map_version").asInt() >= 1);
        assertTrue(map.get("width").asInt() > 0);
        assertTrue(map.get("height").asInt() > 0);
        assertTrue(map.get("zones").isArray() && map.get("zones").size() > 0, "BSP 地图应含搜证热点");
        assertTrue(map.get("spawn_points").isArray() && map.get("spawn_points").size() > 0, "BSP 地图应含出生点");
        assertEquals("bsp", map.get("generator").get("kind").asText());
    }

    @Test
    @DisplayName("⑤ 同 seed 同输出（BSP 确定性）")
    void mapEndpoint_deterministicPerSeed() throws Exception {
        MvcResult a = callMap(999L);
        MvcResult b = callMap(999L);
        JsonNode mapA = a.json().get("map");
        JsonNode mapB = b.json().get("map");
        assertEquals(mapA.get("map_id").asText(), mapB.get("map_id").asText());
        assertEquals(mapA.get("width").asInt(), mapB.get("width").asInt());
        assertEquals(mapA.get("zones").size(), mapB.get("zones").size());
        assertFalse(mapA.get("generator").get("seed").asLong() == 0L, "显式 seed 应生效");
    }

    // ── helpers ──

    private MvcResult postScene(String sid, Map<String, Object> body) throws Exception {
        Map<String, Object> full = new java.util.LinkedHashMap<>();
        full.put("scene_id", sid);
        full.putAll(body);
        MvcResult r = new MvcResult();
        r.body = mockMvc.perform(post("/api/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(full)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        r.json = mapper.readTree(r.body);
        return r;
    }

    private MvcResult callMap(Long seed) throws Exception {
        MvcResult r = new MvcResult();
        r.body = mockMvc.perform(post("/api/scenes/map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(seed == null ? Map.of() : Map.of("seed", seed))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        r.json = mapper.readTree(r.body);
        return r;
    }

    private JsonNode getScenes() throws Exception {
        String body = mockMvc.perform(get("/api/scenes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private static JsonNode findScene(JsonNode list, String sid) {
        for (JsonNode n : list) {
            if (sid.equals(n.get("scene_id").asText())) return n;
        }
        return null;
    }

    /** 轻量响应包装（避免重复 try/parse 样板） */
    private static final class MvcResult {
        String body;
        JsonNode json;

        JsonNode json() { return json; }
    }
}
