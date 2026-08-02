package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.db.entity.CharacterEntity;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.service.PlayerIdentityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 改造方案《玩家角色改名与 AI 识别》Phase 1 验收测试（P-0802-P1-demo）。
 *
 * <p>覆盖（方案 §8 用例 5）：
 * <ul>
 *   <li>① PUT 改名撞名 → 409 且原同名角色 persona 未被覆盖（update 排除自身）</li>
 *   <li>② create / batch 撞名（含批内重复）→ 409，整批不落库</li>
 *   <li>③ playerId 绑定落库（create/update/batch 透传）+ findByPlayerId 反查 + 解析器双向 resolve</li>
 *   <li>④ 撞名 409 后内存列表与 DB 一致（GET 列表 size == repository count，原数据完好）</li>
 *   <li>⑤ 无 player_id 请求行为与现状一致（create 正常 200、无 player_id 键、反查为空）</li>
 * </ul>
 *
 * <p>测试走 application-test.yml（H2 mem create-drop + mock LLM + RANDOM_PORT，D-008 基建）。
 * 各用例使用独立角色名前缀，互不依赖、顺序无关。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CharacterRenameValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CharacterRepository characterRepo;

    @Autowired
    private PlayerIdentityService identityService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── ① PUT 改名撞名 → 409 且原数据未覆盖 ──

    @Test
    @DisplayName("①a PUT 改名撞名 → 409，原同名角色 persona 未被覆盖，被改名角色仍在")
    void update_renameCollision_returns409_andOriginalPersonaIntact() throws Exception {
        createCharacter("c1a", "P1A");
        createCharacter("c1b", "P1B");

        // 把 c1a 改名为已存在的 c1b → 409
        mockMvc.perform(put("/api/characters/c1a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c1b", "persona", "HACKED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("角色名已存在: c1b"));

        // 原 c1b 的 persona 未被覆盖
        JsonNode c1b = findCharacter("c1b");
        assertEquals("P1B", c1b.get("persona").asText());
        // c1a 仍存在且 persona 未被改
        JsonNode c1a = findCharacter("c1a");
        assertEquals("P1A", c1a.get("persona").asText());
    }

    @Test
    @DisplayName("①b PUT 同名自更新（排除自身）→ 200 正常更新")
    void update_sameNameSelfUpdate_isAllowed() throws Exception {
        createCharacter("c2a", "P2A");
        mockMvc.perform(put("/api/characters/c2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c2a", "persona", "P2A-NEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persona").value("P2A-NEW"));
        assertEquals("P2A-NEW", findCharacter("c2a").get("persona").asText());
    }

    // ── ② create / batch 撞名 → 409 ──

    @Test
    @DisplayName("②a create 撞名 → 409，原角色 persona 未被覆盖")
    void create_duplicateName_returns409() throws Exception {
        createCharacter("c3a", "P3A");
        mockMvc.perform(post("/api/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c3a", "persona", "HACKED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("角色名已存在: c3a"));
        assertEquals("P3A", findCharacter("c3a").get("persona").asText());
    }

    @Test
    @DisplayName("②b batch 撞名（批内一项撞库内已有名）→ 409，整批不落库")
    void batch_collision_returns409_nothingSaved() throws Exception {
        createCharacter("c4b", "P4B");
        mockMvc.perform(post("/api/characters/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(List.of(
                                Map.of("name", "c4a", "persona", "P4A"),
                                Map.of("name", "c4b", "persona", "HACKED")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("角色名已存在: c4b"));
        // 新名 c4a 不应被创建（整批拒绝）
        assertFalse(characterRepo.findByName("c4a").isPresent(), "撞名 409 后批内其他项不应落库");
        assertEquals("P4B", findCharacter("c4b").get("persona").asText());
    }

    @Test
    @DisplayName("②c batch 批内重复名 → 409，批内任何一项都不落库")
    void batch_duplicateWithinBatch_returns409() throws Exception {
        mockMvc.perform(post("/api/characters/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(List.of(
                                Map.of("name", "c5a", "persona", "P5A"),
                                Map.of("name", "c5a", "persona", "P5A-DUP")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("角色名已存在: c5a"));
        assertFalse(characterRepo.findByName("c5a").isPresent(), "批内重复名 409 后不应有任何落库");
    }

    // ── ③ playerId 绑定落库 + findByPlayerId 反查 + 解析器双向 resolve ──

    @Test
    @DisplayName("③a create 带 player_id → 落库绑定，findByPlayerId 反查命中，解析器双向 resolve")
    void create_withPlayerId_bindsAndResolves() throws Exception {
        String pid = "pid-6a";
        mockMvc.perform(post("/api/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c6a", "persona", "P6A", "player_id", pid))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player_id").value(pid))
                .andExpect(jsonPath("$.name").value("c6a"));

        // DB 反查
        CharacterEntity entity = characterRepo.findByPlayerId(pid).orElseThrow();
        assertEquals("c6a", entity.getName());
        // 解析器双向
        assertEquals("c6a", identityService.resolveCharacterName(pid).orElseThrow());
        assertEquals(pid, identityService.resolvePlayerId("c6a").orElseThrow());
        // GET 列表透传 player_id
        assertEquals(pid, findCharacter("c6a").get("player_id").asText());
    }

    @Test
    @DisplayName("③b update 改名 → 绑定随角色迁移（playerId 不变，findByPlayerId 反查到新名）")
    void update_rename_preservesPlayerIdBinding() throws Exception {
        String pid = "pid-7a";
        createCharacterWithPid("c7a", "P7A", pid);

        mockMvc.perform(put("/api/characters/c7a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c7b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("c7b"))
                .andExpect(jsonPath("$.player_id").value(pid));

        // 绑定随新名走：反查 c7b 命中、c7a 已不存在
        assertEquals("c7b", characterRepo.findByPlayerId(pid).orElseThrow().getName());
        assertFalse(characterRepo.findByName("c7a").isPresent(), "改名后旧名行应已删除");
        assertEquals("c7b", identityService.resolveCharacterName(pid).orElseThrow());
    }

    @Test
    @DisplayName("③c batch 带 player_id → 透传落库，findByPlayerId 反查命中")
    void batch_withPlayerId_binds() throws Exception {
        String pid = "pid-8a";
        mockMvc.perform(post("/api/characters/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(List.of(
                                Map.of("name", "c8a", "persona", "P8A", "player_id", pid)))))
                .andExpect(status().isOk());
        assertEquals("c8a", characterRepo.findByPlayerId(pid).orElseThrow().getName());
        assertEquals(pid, findCharacter("c8a").get("player_id").asText());
    }

    // ── ⑤ 无 player_id 请求行为与现状一致（零变化回归） ──

    @Test
    @DisplayName("⑤ 无 player_id 创建 → 200，响应不含 player_id 键，反查为空，绑定不产生")
    void create_withoutPlayerId_behaviorUnchanged() throws Exception {
        mockMvc.perform(post("/api/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c9a", "persona", "P9A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("c9a"))
                .andExpect(jsonPath("$.player_id").doesNotExist());

        assertTrue(identityService.resolvePlayerId("c9a").isEmpty(), "未绑定角色的反查应为空");
        assertTrue(identityService.resolveCharacterName("pid-9x").isEmpty(), "未占用 player_id 的解析应为空");
        assertEquals("P9A", findCharacter("c9a").get("persona").asText());
    }

    // ── ④ 撞名 409 后内存列表与 DB 一致 ──

    @Test
    @DisplayName("④ 连续撞名 409（PUT/POST/batch）后内存列表与 DB 一致，原数据完好")
    void collision409_memoryAndDbConsistent() throws Exception {
        createCharacter("c10a", "P10A");
        createCharacter("c10b", "P10B");
        long before = characterRepo.count();

        // PUT 撞名
        mockMvc.perform(put("/api/characters/c10a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c10b"))))
                .andExpect(status().isConflict());
        // POST 撞名
        mockMvc.perform(post("/api/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "c10b"))))
                .andExpect(status().isConflict());
        // batch 撞名
        mockMvc.perform(post("/api/characters/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(List.of(Map.of("name", "c10c"), Map.of("name", "c10b")))))
                .andExpect(status().isConflict());

        // 内存列表（GET）与 DB 行数一致
        MvcResult res = mockMvc.perform(get("/api/characters")).andExpect(status().isOk()).andReturn();
        JsonNode list = mapper.readTree(res.getResponse().getContentAsString());
        assertEquals(before, list.size(), "撞名 409 后内存列表条数应不变");
        assertEquals(before, characterRepo.count(), "撞名 409 后 DB 行数应不变");
        // 原数据完好
        assertEquals("P10A", findCharacter("c10a").get("persona").asText());
        assertEquals("P10B", findCharacter("c10b").get("persona").asText());
        assertFalse(characterRepo.findByName("c10c").isPresent(), "被拒批次的项不应落库");
    }

    // ── helpers ──

    private void createCharacter(String name, String persona) throws Exception {
        mockMvc.perform(post("/api/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", name, "persona", persona))))
                .andExpect(status().isOk());
    }

    private void createCharacterWithPid(String name, String persona, String playerId) throws Exception {
        mockMvc.perform(post("/api/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", name, "persona", persona, "player_id", playerId))))
                .andExpect(status().isOk());
    }

    private JsonNode findCharacter(String name) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/characters")).andExpect(status().isOk()).andReturn();
        JsonNode list = mapper.readTree(res.getResponse().getContentAsString());
        for (JsonNode node : list) {
            if (name.equals(node.get("name").asText())) return node;
        }
        throw new AssertionError("character not found: " + name);
    }
}
