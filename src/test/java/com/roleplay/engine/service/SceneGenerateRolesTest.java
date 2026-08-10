package com.roleplay.engine.service;

import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.controller.SceneController;
import com.roleplay.engine.core.PersonaCardLoader;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0811-F：生成场景 → 同步生成配套角色测试（service 包视角）。
 *
 * <p>覆盖：① generateScene 返回 {name, description, roles[]}（roles 结构与单角色生成一致：表层+五层，
 * mock LLM 输出含 roles → 解析齐全）；② roles 缺失/空数组/非数组 → 空列表不崩（旧调用方零破坏）；
 * ③ POST /api/scenes/generate 配套角色自动落库（GET /api/characters 可见）+ 五层卡落盘
 * （personaCardsDir 指向 @TempDir，断言 {角色名}.json 写盘且含层内容，UTF-8 无 BOM）；
 * ④ 撞名序号后缀（预置同名角色 → 生成「名_2」）；⑤ maxTokens 4000 断言防回归（D-023 纪律）。
 */
class SceneGenerateRolesTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetLoader() {
        PersonaCardLoader.resetForTests();
    }

    // ── helpers ──────────────────────────────────────────────────

    private static LLMClient mockLlm(Map<String, Object> jsonOut) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), any())).thenReturn(jsonOut);
        return llm;
    }

    /** 单个角色（表层 + 五层，契约 docs/persona-五层卡-格式.md 结构）。 */
    private static Map<String, Object> role(String name, String summary) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("appearance", "外观：" + name);
        r.put("summary", summary);
        r.put("personaDesc", name + "的人格设定");
        r.put("voice", name + "的说话风格");
        r.put("background", name + "的背景故事");
        r.put("contrast", Map.of("surface", "表面", "actual", "实际", "hint", "提示"));
        r.put("humanDetails", List.of("小习惯：转笔"));
        r.put("layer0", List.of("当被问到时，你如实回答。", "你永远不会说谎。"));
        r.put("layer1", Map.of("gender", "未说明", "identity", "场景居民"));
        r.put("layer2", Map.of("sentenceStyle", "短句"));
        r.put("layer3", Map.of("care", "用行动表达"));
        r.put("layer4", Map.of("triggers", List.of("被质疑")));
        return r;
    }

    private static Map<String, Object> sceneJson(String name, String desc, List<Map<String, Object>> roles) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("description", desc);
        s.put("roles", roles);
        return s;
    }

    private static CharacterController newCharacterController(GeneratorService g, DatabaseService db, String dir) {
        CharacterController cc = new CharacterController(g, db);
        if (dir != null) cc.setPersonaCardsDir(dir);
        return cc;
    }

    private static SceneController newSceneController(GeneratorService g, CharacterController cc, DatabaseService db) {
        return new SceneController(g, mock(RouterService.class), cc, db);
    }

    // ── ① roles 结构 + ⑤ maxTokens 4000 ─────────────────────────

    @Test
    @DisplayName("① generateScene：mock LLM 含 roles → 解析齐全（表层+五层），maxTokens 恒 4000")
    void sceneWithRolesParsedAndMaxTokens4000() {
        LLMClient llm = mockLlm(sceneJson("雨夜的旧书店", "灯光昏黄，书架间传来窸窣声",
                List.of(role("店长", "守店三十年的老人"), role("常客", "每晚来读书的作家"),
                        role("夜猫", "书店里的流浪猫灵"), role("学徒", "初来乍到的少年"))));
        GeneratorService g = new GeneratorService(llm);

        Map<String, Object> out = g.generateScene("旧书店", "");
        assertEquals("雨夜的旧书店", out.get("name"));
        assertEquals("灯光昏黄，书架间传来窸窣声", out.get("description"));
        List<?> roles = (List<?>) out.get("roles");
        assertEquals(4, roles.size());
        Map<?, ?> r0 = (Map<?, ?>) roles.get(0);
        assertEquals("店长", r0.get("name"));
        assertEquals("店长的人格设定", r0.get("persona"));
        assertEquals("店长的说话风格", r0.get("voice"));
        assertEquals("店长的背景故事", r0.get("background"));
        assertEquals("外观：店长", r0.get("appearance"));
        assertEquals("守店三十年的老人", r0.get("summary"));
        for (String k : List.of("layer0", "layer1", "layer2", "layer3", "layer4", "contrast", "humanDetails")) {
            assertTrue(r0.containsKey(k), "角色含五层键 " + k);
        }

        // ⑤ maxTokens 恒 4000（D-023 纪律：场景+4~6 角色大 JSON 用 300/400 必截断）
        ArgumentCaptor<Integer> tokenCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(llm).callJson(anyString(), tokenCaptor.capture());
        assertEquals(4000, tokenCaptor.getValue(),
                "D-023 纪律：generateScene 的 maxTokens 必须恒 4000（实际 " + tokenCaptor.getValue() + "）");
    }

    // ── ② roles 缺失/空 → 空列表 ────────────────────────────────

    @Test
    @DisplayName("② 宽容解析：roles 缺失/空数组/非数组 → 空列表不崩（旧调用方只取 name/description 零破坏）")
    void rolesMissingOrEmptyTolerated() {
        // 完全缺失
        GeneratorService g1 = new GeneratorService(mockLlm(Map.of("name", "新场景", "description", "描述")));
        Map<String, Object> out1 = g1.generateScene("x", "");
        assertEquals("新场景", out1.get("name"));
        assertEquals("描述", out1.get("description"));
        assertTrue(((List<?>) out1.get("roles")).isEmpty(), "roles 缺失 → 空数组");

        // 空数组
        GeneratorService g2 = new GeneratorService(mockLlm(sceneJson("场景B", "描述B", List.of())));
        Map<String, Object> out2 = g2.generateScene("x", "");
        assertEquals("场景B", out2.get("name"));
        assertTrue(((List<?>) out2.get("roles")).isEmpty(), "roles 空数组 → 空数组");

        // 非数组（字符串）
        GeneratorService g3 = new GeneratorService(mockLlm(
                new LinkedHashMap<>(Map.of("name", "场景C", "description", "描述C", "roles", "不是数组"))));
        Map<String, Object> out3 = g3.generateScene("x", "");
        assertEquals("场景C", out3.get("name"));
        assertTrue(((List<?>) out3.get("roles")).isEmpty(), "roles 非数组 → 空数组");

        // 完全失败（空 map）→ 默认值 + 空数组不崩
        GeneratorService g4 = new GeneratorService(mockLlm(Map.of()));
        Map<String, Object> out4 = g4.generateScene("x", "");
        assertEquals("新场景", out4.get("name"));
        assertEquals("一个普通的场景。", out4.get("description"));
        assertTrue(((List<?>) out4.get("roles")).isEmpty());
    }

    // ── ③ 自动落库 + 五层卡落盘 ─────────────────────────────────

    @Test
    @DisplayName("③ POST /api/scenes/generate：角色自动落库（GET /api/characters 可见）+ 五层卡落盘（{名}.json）")
    void controllerPersistsRolesAndCardToDisk() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        GeneratorService g = new GeneratorService(mockLlm(sceneJson("雨夜书店", "描述",
                List.of(role("店长", "守店老人"), role("常客", "夜读作家")))));
        CharacterController cc = newCharacterController(g, db, tempDir.toString());
        SceneController sc = newSceneController(g, cc, db);

        ResponseEntity<Map<String, Object>> resp = sc.generate(Map.of("keywords", "书店"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("雨夜书店", body.get("name"));
        List<?> roles = (List<?>) body.get("roles");
        assertEquals(2, roles.size());
        for (Object o : roles) {
            Map<?, ?> r = (Map<?, ?>) o;
            assertEquals(true, r.get("saved"));
            assertTrue(r.containsKey("name"));
            // 响应绝不回五层内容（P-0810-10 硬性）
            assertFalse(r.containsKey("layer0"));
            assertFalse(r.containsKey("layer4"));
            assertFalse(r.containsKey("contrast"));
            assertFalse(r.containsKey("humanDetails"));
            assertFalse(r.containsKey("personaDesc"));
            assertFalse(r.containsKey("background"));
            assertFalse(r.containsKey("voice"));
            assertTrue(r.get("layers") instanceof List<?>, "响应附 layers 键名列表");
            assertTrue(((List<?>) r.get("layers")).contains("layer0"));
        }

        // ① 角色已落库：GET /api/characters（cc.getAll）可见，且表层增强 appearance/summary
        List<Map<String, Object>> all = cc.getAll();
        Map<String, Object> listed = all.stream().filter(x -> "店长".equals(x.get("name"))).findFirst().orElseThrow();
        assertEquals("外观：店长", listed.get("appearance"));
        assertEquals("守店老人", listed.get("summary"));
        assertFalse(listed.containsKey("layer0"), "角色列表不透出五层");

        // ② 五层卡落盘：{角色名}.json 写盘（UTF-8 无 BOM 校验「独」= E7 8B AC），内容含层
        Path cardFile = tempDir.resolve("店长.json");
        assertTrue(Files.exists(cardFile), "五层卡落盘: " + cardFile);
        byte[] bytes = Files.readAllBytes(cardFile);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("layer0"), "卡文件含 layer0 键");
        assertTrue(content.contains("当被问到时，你如实回答。"), "卡文件含层内容");
        // 无 BOM：前 3 字节不为 EF BB BF；中文「独」UTF-8 编码应为 E7 8B AC（抽查字节正确性）
        assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF,
                "卡文件无 BOM");
        byte[] du = "独".getBytes(StandardCharsets.UTF_8);
        assertEquals((byte) 0xE7, du[0], "「独」首字节 E7");
        assertEquals((byte) 0x8B, du[1], "「独」次字节 8B");
        assertEquals((byte) 0xAC, du[2], "「独」尾字节 AC");
    }

    // ── ④ 撞名序号后缀 ──────────────────────────────────────────

    @Test
    @DisplayName("④ 撞名自动加序号后缀：预置同名角色 → 场景角色落库为「名_2」，不 409 中断整批")
    void duplicateNameGetsSuffix() {
        DatabaseService db = mock(DatabaseService.class);
        GeneratorService g = new GeneratorService(mockLlm(sceneJson("场景", "描述",
                List.of(role("店长", "A"), role("常客", "B")))));
        CharacterController cc = newCharacterController(g, db, null);
        // 预置同名角色「店长」（走 create 端点，模拟角色库已有）
        ResponseEntity<?> seed = cc.create(Map.of("name", "店长", "persona", "先来的店长", "voice", "V", "background", "B"));
        assertEquals(HttpStatus.OK, seed.getStatusCode());
        SceneController sc = newSceneController(g, cc, db);

        ResponseEntity<Map<String, Object>> resp = sc.generate(Map.of("keywords", "书店"));
        List<?> roles = (List<?>) resp.getBody().get("roles");
        Map<?, ?> r0 = (Map<?, ?>) roles.get(0);
        assertEquals("店长_2", r0.get("name"), "撞名 → 序号后缀");
        assertEquals(true, r0.get("saved"));
        Map<?, ?> r1 = (Map<?, ?>) roles.get(1);
        assertEquals("常客", r1.get("name"), "未撞名角色正常");

        // 落库结果：原「店长」保留 + 「店长_2」新增 + 「常客」新增（整批无 409）
        List<Map<String, Object>> all = cc.getAll();
        assertTrue(all.stream().anyMatch(x -> "店长".equals(x.get("name"))));
        assertTrue(all.stream().anyMatch(x -> "店长_2".equals(x.get("name"))));
        assertTrue(all.stream().anyMatch(x -> "常客".equals(x.get("name"))));
    }
}
