package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 C1 验收测试：剧本数据模型 Schema v1 版本化 + 双生成器统一。
 *
 * <p>覆盖：
 * <ul>
 *   <li>C1-1：旧格式（roles 字符串数组 / clues 带 public / 无 metadata / 无 killer_id）归一为 v1 规范结构，
 *       字段齐全、schema_version==1、secrets 键集合==roles</li>
 *   <li>C1-2：v1 格式输入透传（killer_id / is_hidden / intro / tags 保留）</li>
 *   <li>C1-3：LLM 空输出 → defaultScript 兜底仍符合 schema（A1-3 回归：secrets 键集合==roles）</li>
 *   <li>C1-4：双生成器输出一致性 —— ScriptService.generateScript 与 ScriptGameService.initGame 同输入同 schema</li>
 *   <li>C1-5：roles/clues 缺失兜底 + killer_id 解析</li>
 * </ul>
 *
 * <p>直接构造（mock LLMClient），与 ScriptGameServiceTest 风格一致。
 */
class ScriptSchemaV1Test {

    private static final String SESSION = "test-script-schema";

    /** 旧格式 LLM 输出（与既有 ScriptGameServiceTest/ScriptGameEndedTest mock 同形）。 */
    private LLMClient legacyLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false, "related_role", "管家"),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", true, "related_role", "")));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    /** v1 格式 LLM 输出。 */
    private LLMClient v1Llm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("metadata", Map.of("title", "雾都谜案", "player_min", 3, "player_max", 5, "tags", List.of("本格")));
        script.put("background", "雾都连续发生三起命案。");
        script.put("roles", List.of(
            Map.of("id", "role_1", "name", "侦探", "intro", "退休警探", "is_hidden", false, "secret", "你隐瞒了案发时在场的事实"),
            Map.of("id", "role_2", "name", "管家", "intro", "庄园管家", "is_hidden", true, "secret", "你偷走了遗嘱")));
        script.put("locations", List.of("书房", "地下室"));
        script.put("clues", List.of(
            Map.of("id", "clue_1", "title", "碎玻璃", "location", "书房", "content", "地上的碎玻璃", "transferable", true, "visible_to_owner_only", false),
            Map.of("id", "clue_2", "title", "密信", "location", "地下室", "content", "一封匿名信", "transferable", false, "visible_to_owner_only", true)));
        script.put("secrets", Map.of("侦探", "你隐瞒了案发时在场的事实", "管家", "你偷走了遗嘱"));
        script.put("killer_id", "role_2");
        script.put("truth", "凶手是管家，因为管家偷走了遗嘱。");
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    private LLMClient emptyLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());
        return llm;
    }

    // ═══════════════════════════════════════════════════════════
    //  C1-1: 旧格式归一为 v1
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1-1a: 旧格式（roles 字符串/clues 带 public/无 metadata）归一为 v1：schema_version==1、字段齐全、secrets 键集合==roles")
    void legacyFormatNormalizedToV1() {
        Map<String, Object> script = new ScriptService(legacyLlm())
            .generateScript("庄园", List.of("Alice", "Bob", "Carol"));

        assertEquals(1, script.get("schema_version"), "schema_version 必须为 1");
        assertTrue(script.containsKey("metadata"), "必须含 metadata");
        assertTrue(script.containsKey("roles"), "必须含 roles[]");
        assertTrue(script.containsKey("clues"), "必须含 clues[]");
        assertTrue(script.containsKey("secrets"), "必须含 secrets");
        assertTrue(script.containsKey("killer_id"), "必须含 killer_id（可为空串）");
        assertTrue(script.containsKey("truth"), "必须含 truth");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) script.get("metadata");
        assertEquals("庄园疑云", metadata.get("title"), "旧 name 应映射为 metadata.title");
        assertEquals(3, metadata.get("player_min"));
        assertEquals(3, metadata.get("player_max"), "角色数 3 → player_max=3");
        assertEquals(List.of(), metadata.get("tags"), "旧格式无 tags → 空列表");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) script.get("roles");
        assertEquals(List.of("管家", "女仆", "园丁"), ScriptSchemaV1.roleNames(script), "角色名顺序保持");
        assertEquals("role_1", roles.get(0).get("id"), "字符串角色应生成递增 id");
        assertEquals(Boolean.FALSE, roles.get(0).get("is_hidden"), "旧格式无 is_hidden → false");
        assertEquals("", roles.get(0).get("intro"), "旧格式无 intro → 空串");

        // C1-1 核心：secrets 键集合 == roles 名集合
        Map<String, String> secrets = ScriptSchemaV1.secretsByRole(script);
        assertEquals(Set.of("管家", "女仆", "园丁"), secrets.keySet(), "secrets 键集合必须等于 roles");
        assertEquals("你贪图遗产", secrets.get("管家"), "secrets 值透传");

        // clues 规范化 + 兼容键保留
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clues = (List<Map<String, Object>>) script.get("clues");
        assertEquals(2, clues.size());
        Map<String, Object> c1 = clues.get(0);
        assertEquals("c1", c1.get("id"));
        assertEquals("客厅", c1.get("location"));
        assertEquals("碎玻璃", c1.get("content"));
        assertEquals(Boolean.FALSE, c1.get("transferable"), "旧格式无 transferable → false");
        assertEquals(Boolean.TRUE, c1.get("visible_to_owner_only"), "旧格式 public=false → visible_to_owner_only=true");
        assertEquals(Boolean.FALSE, c1.get("public"), "兼容派生键 public 保留（search/toMap 消费）");
        assertEquals("管家", c1.get("related_role"), "兼容键 related_role 保留");
        Map<String, Object> c2 = clues.get(1);
        assertEquals(Boolean.FALSE, c2.get("visible_to_owner_only"), "旧格式 public=true → visible_to_owner_only=false");
        assertEquals(Boolean.TRUE, c2.get("public"));
    }

    // ═══════════════════════════════════════════════════════════
    //  C1-2: v1 格式透传
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1-2: v1 格式输入透传：killer_id/is_hidden/intro/tags/transferable/visible_to_owner_only 保留")
    void v1FormatPassThrough() {
        Map<String, Object> script = new ScriptService(v1Llm())
            .generateScript("雾都", List.of("Alice", "Bob"));

        assertEquals(1, script.get("schema_version"));
        assertEquals("雾都谜案", ScriptSchemaV1.title(script));
        assertEquals("role_2", ScriptSchemaV1.killerId(script), "killer_id 透传");
        assertEquals("凶手是管家，因为管家偷走了遗嘱。", ScriptSchemaV1.truth(script));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) script.get("metadata");
        assertEquals(3, metadata.get("player_min"), "LLM 提供的 player_min 透传");
        assertEquals(List.of("本格"), metadata.get("tags"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) script.get("roles");
        assertEquals("role_1", roles.get(0).get("id"));
        assertEquals("退休警探", roles.get(0).get("intro"), "intro 透传");
        assertEquals(Boolean.TRUE, roles.get(1).get("is_hidden"), "is_hidden 透传");
        assertEquals("你偷走了遗嘱", roles.get(1).get("secret"), "roles[].secret 透传");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clues = (List<Map<String, Object>>) script.get("clues");
        assertEquals("碎玻璃", clues.get(0).get("title"), "clue title 透传");
        assertEquals(Boolean.TRUE, clues.get(0).get("transferable"));
        assertEquals(Boolean.TRUE, clues.get(1).get("visible_to_owner_only"));
        assertEquals(Boolean.FALSE, clues.get(1).get("public"), "visible_to_owner_only=true → public=false 派生");
    }

    // ═══════════════════════════════════════════════════════════
    //  C1-3: 兜底仍符合 schema
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1-3: LLM 空输出 → defaultScript 兜底：schema_version==1 且 secrets 键集合==roles（A1-3 回归）")
    void defaultFallbackStillConformsToSchema() {
        Map<String, Object> script = new ScriptService(emptyLlm())
            .generateScript("山庄", List.of("Alice", "Bob"));

        assertEquals(1, script.get("schema_version"), "兜底也必须带 schema_version==1");
        assertEquals("山庄谋杀案", ScriptSchemaV1.title(script), "兜底标题 = 主题+谋杀案");
        List<String> roles = ScriptSchemaV1.roleNames(script);
        assertEquals(List.of("嫌疑人_Alice", "嫌疑人_Bob"), roles, "兜底角色按玩家生成");
        assertEquals(roles.size(), ScriptSchemaV1.secretsByRole(script).size(), "兜底 secrets 与 roles 同键");
        assertEquals(Set.copyOf(roles), ScriptSchemaV1.secretsByRole(script).keySet(),
            "A1-3: 兜底 secrets 键集合==roles");
        assertEquals(3, ScriptSchemaV1.clueList(script).size(), "兜底至少 3 条线索");
        assertEquals(5, ScriptSchemaV1.locations(script).size(), "兜底 5 个地点");
        assertTrue(ScriptSchemaV1.truth(script).contains("凶手是"), "兜底 truth 含凶手指向");
    }

    // ═══════════════════════════════════════════════════════════
    //  C1-4: 双生成器输出一致性
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1-4a: 双生成器一致性 —— generateScript 与 initGame 同输入，schema 输出完全一致")
    void dualGeneratorsProduceIdenticalSchema() {
        LLMClient llm = legacyLlm();
        List<String> players = List.of("Alice", "Bob", "Carol");

        // 路径一：ScriptService.generateScript
        Map<String, Object> direct = new ScriptService(llm).generateScript("庄园", players);

        // 路径二：ScriptGameService.initGame（内部委托 generateScript）
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", players);
        Map<String, Object> viaGame = svc.getScriptSchema(SESSION);

        assertFalse(viaGame.isEmpty(), "initGame 后应可读取 scriptSchema");
        assertEquals(direct.get("schema_version"), viaGame.get("schema_version"), "schema_version 一致");
        assertEquals(ScriptSchemaV1.title(direct), ScriptSchemaV1.title(viaGame), "title 一致");
        assertEquals(ScriptSchemaV1.roleNames(direct), ScriptSchemaV1.roleNames(viaGame), "roles 一致");
        assertEquals(ScriptSchemaV1.secretsByRole(direct), ScriptSchemaV1.secretsByRole(viaGame), "secrets 一致");
        assertEquals(ScriptSchemaV1.clueList(direct), ScriptSchemaV1.clueList(viaGame), "clues 一致");
        assertEquals(ScriptSchemaV1.locations(direct), ScriptSchemaV1.locations(viaGame), "locations 一致");
        assertEquals(ScriptSchemaV1.truth(direct), ScriptSchemaV1.truth(viaGame), "truth 一致");
    }

    @Test
    @DisplayName("C1-4b: initGame 输出的 game 状态与 schema 对齐：roles/secrets/clues 与 schema 同源")
    void initGameStateAlignedWithSchema() {
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        Map<String, Object> schema = svc.getScriptSchema(SESSION);

        assertEquals(game.roles, ScriptSchemaV1.roleNames(schema), "game.roles 与 schema roles 一致");
        assertEquals(game.secrets.keySet(), Set.copyOf(game.roles), "game.secrets 键集合==roles");
        assertEquals(2, game.clues.size(), "两条线索全部装载进 game.clues");
        assertEquals("你贪图遗产", game.secrets.get("管家"), "secrets 值正确透传");
        assertEquals("", game.killerId, "旧格式无 killer_id → 空串（不破坏 D6 truth 判定）");
        assertEquals(1, svc.getGame(SESSION).scriptSchema == null ? 0 : ScriptSchemaV1.CURRENT_VERSION,
            "toMap schema_version 来自 schema");
    }

    // ═══════════════════════════════════════════════════════════
    //  C1-5: 兜底与 killer_id 解析
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1-5a: roles/clues 缺失 → 按玩家兜底角色、默认地点线索")
    void missingRolesAndCluesFallback() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "空壳");
        script.put("background", "背景");
        script.put("truth", "凶手是Alice");
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);

        Map<String, Object> out = new ScriptService(llm).generateScript("空壳", List.of("Alice", "Bob"));
        assertEquals(List.of("嫌疑人_Alice", "嫌疑人_Bob"), ScriptSchemaV1.roleNames(out), "缺 roles → 按玩家兜底");
        assertEquals(3, ScriptSchemaV1.clueList(out).size(), "缺 clues → 默认 3 条");
        assertEquals(Set.copyOf(ScriptSchemaV1.roleNames(out)), ScriptSchemaV1.secretsByRole(out).keySet(),
            "兜底 secrets 与兜底 roles 同键");
    }

    @Test
    @DisplayName("C1-5b: v1 killer_id 经 initGame 落入 game.killerId；兼容旧 killer（角色名）反查 id")
    void killerIdResolvedIntoGame() {
        // v1 格式：killer_id=role_2
        ScriptGameService svc = new ScriptGameService(v1Llm(), new ApprovalService());
        svc.initGame(SESSION, "雾都", List.of("Alice", "Bob"));
        assertEquals("role_2", svc.getGame(SESSION).killerId, "schema killer_id 应落入 game.killerId");

        // 兼容：旧格式 killer（角色名）→ 反查角色 id
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "旧本");
        script.put("background", "背景");
        script.put("truth", "凶手是女仆");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("killer", "女仆");
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);

        ScriptGameService svc2 = new ScriptGameService(llm, new ApprovalService());
        svc2.initGame(SESSION + "-2", "旧本", List.of("Alice", "Bob", "Carol"));
        String kid = svc2.getGame(SESSION + "-2").killerId;
        String roleId = ScriptSchemaV1.roleNamesById(svc2.getScriptSchema(SESSION + "-2"))
            .entrySet().stream().filter(e -> "女仆".equals(e.getValue())).map(Map.Entry::getKey)
            .findFirst().orElse("");
        assertEquals(roleId, kid, "旧 killer 角色名应反查为对应 role id");
        assertFalse(roleId.isEmpty());
    }
}
