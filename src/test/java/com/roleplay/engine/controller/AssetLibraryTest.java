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
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-0804-C 素材库后端验收测试（@SpringBootTest + H2 mem + mock LLM，D-008 基建；零 mock LLM 依赖）。
 *
 * <p>覆盖（8 用例）：
 * <ul>
 *   <li>① 导入登记 + GET 列表透传（字段回显）</li>
 *   <li>② 列表按类型过滤（CHARACTER_ANIMATION / SCENE_TILESET）</li>
 *   <li>③ 列表按角色 / 场景过滤</li>
 *   <li>④ 关联校验：未知角色 / 未知场景 → 400 拒绝</li>
 *   <li>⑤ 未关联素材（无角色无场景）→ 允许</li>
 *   <li>⑥ 删除 → 列表移除 + GET /{id} → 404</li>
 *   <li>⑦ 类型枚举校验：非法 asset_type / 缺 name → 400</li>
 *   <li>⑧ GET /{id} 响应含关联角色/场景名（linked_character_name / linked_scene_name）</li>
 * </ul>
 *
 * <p>各用例使用独立前缀命名，互不依赖、顺序无关；角色/场景经既有端点现建（端到端关联校验）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetLibraryTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("① 导入登记 + GET 列表透传（字段回显）")
    void importAsset_registers_andListRoundTrips() throws Exception {
        String name = "al_anim_walk";
        importAsset(Map.of(
                "name", name,
                "asset_type", "CHARACTER_ANIMATION",
                "file_path", "CHARACTER_ANIMATION/demo_player/player.png",
                "meta_json", "{\"frames\":[]}"
        ));

        JsonNode item = findAsset(assetList(), name);
        assertNotNull(item, "素材应出现在列表");
        assertEquals("CHARACTER_ANIMATION", item.get("asset_type").asText());
        assertEquals("CHARACTER_ANIMATION/demo_player/player.png", item.get("file_path").asText());
        assertEquals("{\"frames\":[]}", item.get("meta_json").asText());
        assertTrue(item.has("id"), "应含自增 id");
    }

    @Test
    @DisplayName("② 列表按类型过滤（CHARACTER_ANIMATION / SCENE_TILESET）")
    void list_filtersByType() throws Exception {
        importAsset(Map.of("name", "al_t_anim", "asset_type", "CHARACTER_ANIMATION", "file_path", "a/p.png"));
        importAsset(Map.of("name", "al_t_tiles", "asset_type", "SCENE_TILESET", "file_path", "s/t.png"));

        JsonNode anims = assetList(Map.of("type", "CHARACTER_ANIMATION"));
        assertNotNull(findIn(anims, "al_t_anim"), "类型过滤应含动画素材");
        assertNull(findIn(anims, "al_t_tiles"), "类型过滤不应含瓦片素材");

        JsonNode tiles = assetList(Map.of("type", "SCENE_TILESET"));
        assertNotNull(findIn(tiles, "al_t_tiles"));
        assertNull(findIn(tiles, "al_t_anim"));
    }

    @Test
    @DisplayName("③ 列表按角色 / 场景过滤")
    void list_filtersByCharacterAndScene() throws Exception {
        String charName = "al_char_三";
        createCharacter(charName);
        String sceneId = "al_scene_三";
        createScene(sceneId);

        importAsset(Map.of("name", "al_c_anim", "asset_type", "CHARACTER_ANIMATION",
                "character_name", charName, "file_path", "a/p.png"));
        importAsset(Map.of("name", "al_c_tiles", "asset_type", "SCENE_TILESET",
                "scene_id", sceneId, "file_path", "s/t.png"));
        importAsset(Map.of("name", "al_c_free", "asset_type", "SCENE_TILESET", "file_path", "s/f.png"));

        JsonNode byChar = assetList(Map.of("character", charName));
        assertNotNull(findIn(byChar, "al_c_anim"));
        assertNull(findIn(byChar, "al_c_tiles"), "角色过滤不应含场景关联素材");

        JsonNode byScene = assetList(Map.of("scene", sceneId));
        assertNotNull(findIn(byScene, "al_c_tiles"));
        assertNull(findIn(byScene, "al_c_free"), "场景过滤不应含未关联素材");
    }

    @Test
    @DisplayName("④ 关联校验：未知角色 / 未知场景 → 400 拒绝")
    void importAsset_rejectsUnknownAssociation() throws Exception {
        mockMvc.perform(post("/api/assets/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "al_bad_char",
                                "asset_type", "CHARACTER_ANIMATION",
                                "character_name", "不存在的角色_xyz",
                                "file_path", "a/p.png"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("角色不存在")));

        mockMvc.perform(post("/api/assets/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "al_bad_scene",
                                "asset_type", "SCENE_TILESET",
                                "scene_id", "不存在的场景_xyz",
                                "file_path", "s/t.png"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("场景不存在")));

        // 被拒后不落库
        assertNull(findAsset(assetList(), "al_bad_char"));
        assertNull(findAsset(assetList(), "al_bad_scene"));
    }

    @Test
    @DisplayName("⑤ 未关联素材（无角色无场景）→ 允许导入")
    void importAsset_allowsUnlinked() throws Exception {
        importAsset(Map.of("name", "al_free_standalone", "asset_type", "CHARACTER_ANIMATION", "file_path", "a/p.png"));
        JsonNode item = findAsset(assetList(), "al_free_standalone");
        assertNotNull(item, "未关联素材应允许");
        assertTrue(!item.has("character_name") || item.get("character_name").isNull());
        assertTrue(!item.has("scene_id") || item.get("scene_id").isNull());
    }

    @Test
    @DisplayName("⑥ 删除 → 列表移除 + GET /{id} → 404")
    void deleteAsset_removesRegistration() throws Exception {
        importAsset(Map.of("name", "al_del", "asset_type", "SCENE_TILESET", "file_path", "s/d.png"));
        JsonNode item = findAsset(assetList(), "al_del");
        assertNotNull(item);
        long id = item.get("id").asLong();

        mockMvc.perform(delete("/api/assets/" + id)).andExpect(status().isOk());
        assertNull(findAsset(assetList(), "al_del"), "删除后列表不应包含");
        mockMvc.perform(get("/api/assets/" + id)).andExpect(status().isNotFound());
        // 删除不存在的 id → 404
        mockMvc.perform(delete("/api/assets/99999999")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("⑦ 类型枚举校验：非法 asset_type / 缺 name → 400")
    void importAsset_rejectsInvalidTypeAndMissingName() throws Exception {
        mockMvc.perform(post("/api/assets/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "al_bad_type",
                                "asset_type", "AUDIO_CLIP",
                                "file_path", "a/p.png"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("asset_type")));

        mockMvc.perform(post("/api/assets/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "asset_type", "SCENE_TILESET",
                                "file_path", "s/t.png"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("name")));

        // 小写类型规范化（大写归一）
        importAsset(Map.of("name", "al_lower", "asset_type", "character_animation", "file_path", "a/p.png"));
        assertEquals("CHARACTER_ANIMATION", findAsset(assetList(), "al_lower").get("asset_type").asText());
    }

    @Test
    @DisplayName("⑧ GET /{id} 响应含关联角色/场景名（linked_character_name / linked_scene_name）")
    void getById_resolvesLinkedNames() throws Exception {
        String charName = "al_char_捌";
        createCharacter(charName);
        String sceneId = "al_scene_捌";
        createScene(sceneId);

        importAsset(Map.of("name", "al_linked_anim", "asset_type", "CHARACTER_ANIMATION",
                "character_name", charName, "file_path", "a/p.png"));
        importAsset(Map.of("name", "al_linked_tiles", "asset_type", "SCENE_TILESET",
                "scene_id", sceneId, "file_path", "s/t.png"));

        JsonNode anim = findAsset(assetList(), "al_linked_anim");
        long animId = anim.get("id").asLong();
        JsonNode got = getAsset(animId);
        assertEquals(charName, got.get("character_name").asText());
        assertEquals(charName, got.get("linked_character_name").asText());
        assertTrue(!got.has("linked_scene_name") || got.get("linked_scene_name").isNull());

        JsonNode tiles = findAsset(assetList(), "al_linked_tiles");
        long tilesId = tiles.get("id").asLong();
        JsonNode gotTiles = getAsset(tilesId);
        assertEquals(sceneId, gotTiles.get("scene_id").asText());
        assertEquals("测试场景捌", gotTiles.get("linked_scene_name").asText());
        assertTrue(!gotTiles.has("linked_character_name") || gotTiles.get("linked_character_name").isNull());
    }

    // ── helpers ──

    private void importAsset(Map<String, Object> body) throws Exception {
        mockMvc.perform(post("/api/assets/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void createCharacter(String name) throws Exception {
        mockMvc.perform(post("/api/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", name, "persona", "测试角色"))))
                .andExpect(status().isOk());
    }

    private void createScene(String sceneId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scene_id", sceneId);
        body.put("name", "测试场景捌");
        body.put("description", "测试");
        mockMvc.perform(post("/api/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private JsonNode assetList() throws Exception {
        return assetList(Map.of());
    }

    private JsonNode assetList(Map<String, String> params) throws Exception {
        String qs = params.isEmpty() ? "" : "?" + params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b).orElse("");
        MvcResult r = mockMvc.perform(get("/api/assets" + qs))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode getAsset(long id) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/assets/" + id))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private static JsonNode findAsset(JsonNode list, String name) {
        return findIn(list, name);
    }

    private static JsonNode findIn(JsonNode list, String name) {
        for (JsonNode n : list) {
            if (name.equals(n.get("name").asText())) return n;
        }
        return null;
    }
}
