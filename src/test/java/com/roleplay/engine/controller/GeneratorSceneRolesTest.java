package com.roleplay.engine.controller;

import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.controller.SceneController;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.RouterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
 * P-0811-E（追加）：场景 + 配套角色一次生成测试。
 *
 * <p>覆盖：① generateScene 返回 {name, description, roles[]}（roles 结构与单角色生成一致：表层+五层）；
 * ② roles 缺失/非数组 → 空数组（旧调用方零破坏）；③ 角色缺层 → 宽容不崩（layer0 字符串归一/默认值兜底）；
 * ④ POST /api/scenes/generate 配套角色自动落库（CharacterController.getAll 可见 + 表层响应不泄五层）；
 * ⑤ 撞名自动加序号后缀（「名字_2」）；⑥ 落库失败（DB unique 异常）跳过但保留在响应（saved=false）；
 * ⑦ maxTokens ≥2000 防回归（D-023 纪律）。
 */
class GeneratorSceneRolesTest {

    // ── helpers ──────────────────────────────────────────────────

    private static LLMClient mockLlm(Map<String, Object> jsonOut) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), any())).thenReturn(jsonOut);
        return llm;
    }

    /** 单个角色（与单角色生成契约一致：表层 + 五层，含 layer0/humanDetails）。 */
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

    private static CharacterController newCharacterController(GeneratorService g, DatabaseService db) {
        return new CharacterController(g, db);
    }

    private static SceneController newSceneController(GeneratorService g, CharacterController cc, DatabaseService db) {
        return new SceneController(g, mock(RouterService.class), cc, db);
    }

    // ── ① 结构 + ⑦ maxTokens ────────────────────────────────────

    @Test
    @DisplayName("generateScene：返回 {name, description, roles[]}，roles 结构与单角色一致（表层+五层），maxTokens≥2000")
    void sceneWithRolesStructureAndMaxTokens() {
        LLMClient llm = mockLlm(sceneJson("雨夜的旧书店", "灯光昏黄，书架间传来窸窣声",
                List.of(role("店长", "守店三十年的老人"), role("常客", "每晚来读书的作家"), role("夜猫", "书店里的流浪猫灵"))));
        GeneratorService g = new GeneratorService(llm);

        Map<String, Object> out = g.generateScene("旧书店", "");
        assertEquals("雨夜的旧书店", out.get("name"));
        assertEquals("灯光昏黄，书架间传来窸窣声", out.get("description"));
        List<?> roles = (List<?>) out.get("roles");
        assertEquals(3, roles.size());
        Map<?, ?> r0 = (Map<?, ?>) roles.get(0);
        assertEquals("店长", r0.get("name"));
        assertEquals("店长的人格设定", r0.get("persona"));
        assertEquals("店长的说话风格", r0.get("voice"));
        assertEquals("店长的背景故事", r0.get("background"));
        assertTrue(r0.containsKey("appearance"));
        assertTrue(r0.containsKey("summary"));
        assertTrue(r0.containsKey("layer0"));
        assertTrue(r0.containsKey("layer1"));
        assertTrue(r0.containsKey("layer4"));
        assertTrue(r0.containsKey("contrast"));
        assertTrue(r0.containsKey("humanDetails"));

        ArgumentCaptor<Integer> tokenCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(llm).callJson(anyString(), tokenCaptor.capture());
        assertTrue(tokenCaptor.getValue() >= 2000,
                "D-023 纪律：场景+配套角色大 JSON 的 maxTokens 必须 ≥2000（实际 " + tokenCaptor.getValue() + "）");
    }

    // ── ② roles 缺失/非数组 ─────────────────────────────────────

    @Test
    @DisplayName("宽容解析：roles 缺失/非数组 → 空数组（旧调用方零破坏，name/description 正常）")
    void rolesMissingTolerated() {
        // roles 缺失
        GeneratorService g1 = new GeneratorService(mockLlm(Map.of("name", "新场景", "description", "描述")));
        Map<String, Object> out1 = g1.generateScene("x", "");
        assertEquals("新场景", out1.get("name"));
        assertEquals("描述", out1.get("description"));
        assertTrue(((List<?>) out1.get("roles")).isEmpty(), "roles 缺失 → 空数组");

        // roles 非数组（字符串）
        GeneratorService g2 = new GeneratorService(mockLlm(
                new LinkedHashMap<>(Map.of("name", "场景B", "description", "描述B", "roles", "不是数组"))));
        Map<String, Object> out2 = g2.generateScene("x", "");
        assertEquals("场景B", out2.get("name"));
        assertTrue(((List<?>) out2.get("roles")).isEmpty(), "roles 非数组 → 空数组");

        // 完全失败（空 map）→ 默认值 + 空数组不崩
        GeneratorService g3 = new GeneratorService(mockLlm(Map.of()));
        Map<String, Object> out3 = g3.generateScene("x", "");
        assertEquals("新场景", out3.get("name"));
        assertEquals("一个普通的场景。", out3.get("description"));
        assertTrue(((List<?>) out3.get("roles")).isEmpty());
    }

    // ── ③ 角色缺层宽容 ──────────────────────────────────────────

    @Test
    @DisplayName("宽容解析：角色缺层/类型不符不崩——layer0 字符串归一、缺 voice/background 默认值、角色名缺省补「场景角色N」")
    void roleFieldsTolerant() {
        Map<String, Object> oddRole = new LinkedHashMap<>();
        oddRole.put("name", "怪客");
        oddRole.put("persona", "怪人");
        oddRole.put("layer0", "当有人问奇怪问题时，你笑而不答。");   // 字符串而非数组
        oddRole.put("humanDetails", "小习惯：转硬币");               // 字符串而非数组
        GeneratorService g = new GeneratorService(mockLlm(
                sceneJson("怪场景", "描述", List.of(oddRole))));
        Map<String, Object> out = g.generateScene("怪", "");
        List<?> roles = (List<?>) out.get("roles");
        assertEquals(1, roles.size());
        Map<?, ?> r = (Map<?, ?>) roles.get(0);
        assertEquals("怪客", r.get("name"));
        assertEquals("怪人", r.get("persona"));
        assertEquals("正常", r.get("voice"), "缺 voice → 默认值");
        assertEquals("未知", r.get("background"), "缺 background → 默认值");
        assertEquals(List.of("当有人问奇怪问题时，你笑而不答。"), r.get("layer0"), "layer0 字符串归一为列表");
        assertEquals(List.of("小习惯：转硬币"), r.get("humanDetails"), "humanDetails 字符串归一为列表");

        // 角色名缺省 → 补「场景角色N」
        Map<String, Object> noNameRole = new LinkedHashMap<>(role("", "无名"));
        noNameRole.remove("name");
        GeneratorService g2 = new GeneratorService(mockLlm(
                sceneJson("场景C", "描述", List.of(noNameRole))));
        Map<?, ?> r2 = (Map<?, ?>) ((List<?>) g2.generateScene("c", "").get("roles")).get(0);
        assertEquals("场景角色1", r2.get("name"));
    }

    // ── ④ 自动落库 + 不泄五层 ───────────────────────────────────

    @Test
    @DisplayName("POST /api/scenes/generate：配套角色自动落库（CharacterController.getAll 可见）+ 响应表层不泄五层")
    void controllerPersistsRoles() {
        DatabaseService db = mock(DatabaseService.class);
        GeneratorService g = new GeneratorService(mockLlm(sceneJson("雨夜书店", "描述",
                List.of(role("店长", "守店老人"), role("常客", "夜读作家")))));
        CharacterController cc = newCharacterController(g, db);
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
        }
        Map<?, ?> r0 = (Map<?, ?>) roles.get(0);
        assertTrue(r0.get("layers") instanceof List<?>, "响应附 layers 键名列表");
        assertTrue(((List<?>) r0.get("layers")).contains("layer0"));

        // 角色已落库：CharacterController.getAll 可见（表层增强 appearance/summary）
        List<Map<String, Object>> all = cc.getAll();
        Map<String, Object> listed = all.stream().filter(x -> "店长".equals(x.get("name"))).findFirst().orElseThrow();
        assertEquals("外观：店长", listed.get("appearance"));
        assertEquals("守店老人", listed.get("summary"));
        assertFalse(listed.containsKey("layer0"), "角色列表不透出五层");
    }

    // ── ⑤ 撞名序号后缀 ──────────────────────────────────────────

    @Test
    @DisplayName("撞名自动加序号后缀：库内已有「店长」→ 场景角色落库为「店长_2」，不 409 中断整批")
    void duplicateNameGetsSuffix() {
        DatabaseService db = mock(DatabaseService.class);
        GeneratorService g = new GeneratorService(mockLlm(sceneJson("场景", "描述",
                List.of(role("店长", "A"), role("常客", "B")))));
        CharacterController cc = newCharacterController(g, db);
        // 先落库一个「店长」
        cc.persistGeneratedRole(role("店长", "先来的店长"));
        SceneController sc = newSceneController(g, cc, db);

        ResponseEntity<Map<String, Object>> resp = sc.generate(Map.of("keywords", "书店"));
        List<?> roles = (List<?>) resp.getBody().get("roles");
        // 响应 roles 顺序与生成一致：第一个「店长」→ 撞名 → 「店长_2」；第二个「常客」正常
        Map<?, ?> r0 = (Map<?, ?>) roles.get(0);
        assertEquals("店长_2", r0.get("name"));
        assertEquals(true, r0.get("saved"));
        Map<?, ?> r1 = (Map<?, ?>) roles.get(1);
        assertEquals("常客", r1.get("name"));

        // 两个角色都已落库（「店长」×1 + 「店长_2」+「常客」）
        List<Map<String, Object>> all = cc.getAll();
        assertEquals(1, all.stream().filter(x -> "店长".equals(x.get("name"))).count());
        assertTrue(all.stream().anyMatch(x -> "店长_2".equals(x.get("name"))));
        assertTrue(all.stream().anyMatch(x -> "常客".equals(x.get("name"))));
    }

    // ── ⑥ 落库失败跳过但保留 ────────────────────────────────────

    @Test
    @DisplayName("落库失败（DB unique 异常）：跳过落库但保留在响应（saved=false），其余角色正常落库")
    void persistFailureKeptInResponse() {
        DatabaseService db = mock(DatabaseService.class);
        // 「常客」落库抛 unique 异常（模拟并发撞名）→ 序号后缀尝试（常客_2…）也失败 → persistGeneratedRole 返回 null
        when(db.saveCharacter(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    if (name != null && name.startsWith("常客")) throw new DataIntegrityViolationException("dup: " + name);
                    return null;
                });
        GeneratorService g = new GeneratorService(mockLlm(sceneJson("场景", "描述",
                List.of(role("店长", "A"), role("常客", "B")))));
        CharacterController cc = newCharacterController(g, db);
        SceneController sc = newSceneController(g, cc, db);

        ResponseEntity<Map<String, Object>> resp = sc.generate(Map.of("keywords", "书店"));
        List<?> roles = (List<?>) resp.getBody().get("roles");
        Map<?, ?> r0 = (Map<?, ?>) roles.get(0);
        assertEquals("店长", r0.get("name"));
        assertEquals(true, r0.get("saved"));
        Map<?, ?> r1 = (Map<?, ?>) roles.get(1);
        assertEquals("常客", r1.get("name"), "落库失败的角色保留原名在响应里");
        assertEquals(false, r1.get("saved"), "落库失败标记 saved=false");
        assertEquals("B", r1.get("summary"), "落库失败仍保留表层 summary");

        // 只有店长落库
        List<Map<String, Object>> all = cc.getAll();
        assertTrue(all.stream().anyMatch(x -> "店长".equals(x.get("name"))));
        assertFalse(all.stream().anyMatch(x -> "常客".equals(x.get("name"))), "落库失败的角色不入角色库");
    }
}
