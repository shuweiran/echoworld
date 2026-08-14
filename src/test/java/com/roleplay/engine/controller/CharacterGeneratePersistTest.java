package com.roleplay.engine.controller;

import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.PersonaCardLoader;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.GeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0811-D：生成角色自动落库 + 五层卡外部目录持久化测试。
 *
 * <p>覆盖：① generate 自动落库（mock LLM：GET /api/characters 可见新角色 + personaCards 挂载 +
 * 卡落盘文件存在 + DB saveCharacter 调用）；② 撞名 409（同 name 二次生成，不落库不挂卡）；
 * ③ 五层卡持久化（写盘 → PersonaCardLoader 从外部目录加载合并 → attach 生效；外部优先于 classpath；
 * 目录缺失零破坏 + classpath 默认卡向后兼容）；④ 配置键存在（test yml 绑定 + 主 yml 文件含键）。
 */
class CharacterGeneratePersistTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetLoader() {
        // 静态加载器隔离：清缓存 + 清外部目录（防跨用例/跨类污染）
        PersonaCardLoader.resetForTests();
    }

    // ── helpers ──────────────────────────────────────────────────

    private static LLMClient mockLlm(Map<String, Object> jsonOut) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), any())).thenReturn(jsonOut);
        return llm;
    }

    /** LLM 原始输出（五层 persona JSON，契约 docs/persona-五层卡-格式.md 结构）。 */
    private static Map<String, Object> fiveLayerRaw(String name) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", name);
        raw.put("appearance", "银发紫瞳，系着围裙");
        raw.put("summary", "16岁咖啡店主");
        raw.put("personaDesc", "温柔细心，胜负欲藏在心里");
        raw.put("voice", "轻软，尾音上扬");
        raw.put("background", "老街咖啡馆的店主，手艺是被爷爷手把手教的");
        raw.put("contrast", Map.of("surface", "表面：温柔体贴", "actual", "实际：胜负欲强"));
        raw.put("humanDetails", List.of("小缺点：被夸会手足无措", "口头禅：「欢迎回来」"));
        raw.put("layer0", List.of("当客人说咖啡不好喝时，你不辩解，先道歉再重做。",
                "当有人当面夸你时，你低头说「没有啦」然后转移话题。"));
        raw.put("layer1", Map.of("gender", "女，她", "age", "16岁", "identity", "咖啡馆店主"));
        raw.put("layer2", Map.of("sentenceStyle", "短句为主 5~12 字", "sampleLines", List.of("「欢迎回来〜」")));
        raw.put("layer3", Map.of("care", "用行动不用语言"));
        raw.put("layer4", Map.of("triggers", List.of("被质疑咖啡品质")));
        return raw;
    }

    private static GeneratorService generatorWith(LLMClient llm) {
        return new GeneratorService(llm);
    }

    // ── ① generate 自动落库 ──────────────────────────────────────

    @Test
    @DisplayName("① generate 自动落库：列表可见 + personaCards 挂载 + 卡落盘 + DB 四字段保存")
    void generateAutoPersistsToLibraryAndDisk() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = new CharacterController(generatorWith(mockLlm(fiveLayerRaw("小铃"))), db);
        cc.setPersonaCardsDir(tempDir.toString());

        ResponseEntity<Map<String, Object>> resp = cc.generate(Map.of("keywords", "咖啡师"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("saved"), "响应补 saved=true");
        assertEquals("小铃", body.get("name"));
        assertEquals("银发紫瞳，系着围裙", body.get("appearance"), "表层 appearance 回");
        assertEquals("16岁咖啡店主", body.get("summary"), "表层 summary 回");

        // 自动落库：GET /api/characters（getAll）可见新角色（含生成 persona）
        Map<String, Object> listed = cc.getAll().stream()
                .filter(x -> "小铃".equals(x.get("name"))).findFirst().orElseThrow();
        assertEquals("温柔细心，胜负欲藏在心里", listed.get("persona"), "自动落库 persona=生成结果");
        assertEquals("轻软，尾音上扬", listed.get("voice"));
        assertEquals("老街咖啡馆的店主，手艺是被爷爷手把手教的", listed.get("background"));
        assertFalse(listed.containsKey("layer0"), "列表不透出五层");

        // DB 落库（对齐 create 端点逻辑：四字段）
        verify(db).saveCharacter("小铃", "温柔细心，胜负欲藏在心里", "轻软，尾音上扬",
                "老街咖啡馆的店主，手艺是被爷爷手把手教的");

        // personaCards 挂载（attach 后五层进 LLM 系统提示）
        assertNotNull(cc.personaCardFor("小铃"), "生成后 personaCards 挂载");
        Persona p = new Persona("小铃");
        cc.attachPersonaCard(p);
        assertTrue(p.hasLayers());
        assertTrue(p.buildSystemPrompt().contains("【Layer 0 核心行为准则"), "五层卡进系统提示");

        // 卡落盘文件存在（data/persona/{角色名}.json，UTF-8 无 BOM）
        Path file = tempDir.resolve("小铃.json");
        assertTrue(Files.exists(file), "卡落盘文件存在: " + file);
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"layer0\""), "文件含 layer0");
        assertTrue(json.contains("先道歉再重做"), "文件含层内容");
        assertFalse(json.startsWith("\uFEFF"), "UTF-8 无 BOM");
    }

    @Test
    @DisplayName("①b 未配置目录（null）→ 生成不写盘不炸，纯内存行为不变")
    void generateWithoutDirSkipsDiskSilently() {
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = new CharacterController(generatorWith(mockLlm(fiveLayerRaw("小铃"))), db);
        // 不 setPersonaCardsDir → 落盘关闭

        ResponseEntity<Map<String, Object>> resp = cc.generate(Map.of("keywords", "咖啡师"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("小铃", resp.getBody().get("name"));
        assertNotNull(cc.personaCardFor("小铃"), "内存卡仍挂载");
        verify(db).saveCharacter(anyString(), anyString(), anyString(), anyString());
    }

    // ── ② 撞名 409 ──────────────────────────────────────────────

    @Test
    @DisplayName("② 撞名 409：同 name 二次生成 → 409 不落库不挂卡；首次落库仅一次")
    void generateNameCollisionReturns409() {
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = new CharacterController(generatorWith(mockLlm(fiveLayerRaw("小铃"))), db);
        cc.setPersonaCardsDir(tempDir.toString());

        ResponseEntity<Map<String, Object>> first = cc.generate(Map.of("keywords", "咖啡师"));
        assertEquals(HttpStatus.OK, first.getStatusCode());

        // 同 name 二次生成 → 409（LLM 又返回同名角色）
        ResponseEntity<Map<String, Object>> second = cc.generate(Map.of("keywords", "再来一次"));
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("角色名已存在: 小铃", second.getBody().get("error"));

        // 不落库不挂卡：列表/卡/DB 都只有首次的一份
        assertEquals(1, cc.getAll().size(), "列表只有首次生成的一个角色");
        verify(db, times(1)).saveCharacter(anyString(), anyString(), anyString(), anyString());
        assertNotNull(cc.personaCardFor("小铃"), "首次生成的卡仍在");
        // 卡文件只有一份
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count(), "磁盘只有一张卡文件");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── ③ 五层卡持久化（写盘 → 加载合并 → attach 生效） ─────────

    @Test
    @DisplayName("③ 持久化加载：写盘卡经 PersonaCardLoader 外部目录扫描合并，attach 生效（模拟重启）")
    void cardPersistedThenLoadedFromExternalDir() throws Exception {
        // 写盘（generate 路径）
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = new CharacterController(generatorWith(mockLlm(fiveLayerRaw("小铃"))), db);
        cc.setPersonaCardsDir(tempDir.toString());
        cc.generate(Map.of("keywords", "咖啡师"));
        assertTrue(Files.exists(tempDir.resolve("小铃.json")));

        // 模拟重启：加载器指向外部目录（生产由 CharacterController @PostConstruct 注册）
        PersonaCardLoader.setExternalCardsDir(tempDir);
        try {
            // 外部目录卡被扫描合并（新角色卡不依赖 classpath）
            Map<String, Object> card = PersonaCardLoader.cardFor("小铃");
            assertNotNull(card, "外部目录卡被加载");
            assertTrue(card.containsKey("layer0"));
            assertEquals("小铃", card.get("name"));

            // attach 生效：无导入卡时回退外部卡
            Persona p = new Persona("小铃");
            PersonaCardLoader.attach(p, null);
            assertTrue(p.hasLayers(), "attach 生效");
            assertTrue(p.buildSystemPrompt().contains("【Layer 0 核心行为准则"));
            assertTrue(p.buildSystemPrompt().contains("先道歉再重做"), "层内容来自外部卡");
            assertEquals("银发紫瞳，系着围裙", p.getAppearance(), "表层外观回填");

            // classpath 默认卡仍可用（向后兼容）
            assertNotNull(PersonaCardLoader.cardFor("凯尔"), "classpath 默认卡不受影响");
            Persona kyle = new Persona("凯尔");
            PersonaCardLoader.attach(kyle, null);
            assertTrue(kyle.hasLayers(), "凯尔默认卡仍可挂");
        } finally {
            PersonaCardLoader.resetForTests();
        }
    }

    @Test
    @DisplayName("③b 外部目录优先于 classpath 同名卡（覆盖加载）")
    void externalDirOverridesClasspath() throws Exception {
        // 外部卡覆盖 classpath 同名「小铃」
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("name", "小铃");
        ext.put("layer0", List.of("外部铁律：这是外部目录的规则"));
        Files.writeString(tempDir.resolve("小铃.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ext),
                StandardCharsets.UTF_8);

        PersonaCardLoader.setExternalCardsDir(tempDir);
        try {
            Map<String, Object> card = PersonaCardLoader.cardFor("小铃");
            assertNotNull(card);
            assertEquals(List.of("外部铁律：这是外部目录的规则"), card.get("layer0"), "外部目录优先");
        } finally {
            PersonaCardLoader.resetForTests();
        }
    }

    @Test
    @DisplayName("③c 目录缺失零破坏：不可读/不存在目录静默跳过，classpath 默认卡不受影响")
    void missingDirZeroBreakage() {
        PersonaCardLoader.setExternalCardsDir(tempDir.resolve("不存在的子目录"));
        try {
            assertNull(PersonaCardLoader.cardFor("幽灵角色"), "无卡角色查卡为 null 不炸");
            Persona p = new Persona("幽灵角色");
            PersonaCardLoader.attachDefault(p);
            assertFalse(p.hasLayers(), "attachDefault 对无卡角色 no-op 不炸");
            // classpath 默认卡不受影响
            assertNotNull(PersonaCardLoader.cardFor("小铃"), "classpath 默认卡仍可加载");
            Persona kyle = new Persona("凯尔");
            PersonaCardLoader.attachDefault(kyle);
            assertTrue(kyle.hasLayers(), "凯尔默认卡仍可挂");
        } finally {
            PersonaCardLoader.resetForTests();
        }
    }

    // ── ④ 配置键存在 ────────────────────────────────────────────

    @Test
    @DisplayName("④ 配置键存在：主 application.yml 含 persona-cards-dir 键（路径可配 D-004 纪律）")
    void mainYmlContainsPersonaCardsDirKey() throws Exception {
        String main = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertTrue(main.contains("persona-cards-dir"), "主 yml 必须含 roleplay.game.persona-cards-dir 键");
    }
}
