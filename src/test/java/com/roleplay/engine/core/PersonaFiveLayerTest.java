package com.roleplay.engine.core;

import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.GeneratorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
import static org.mockito.Mockito.when;

/**
 * P-0810-10：五层 persona 模板接入后端测试。
 *
 * <p>覆盖：① buildSystemPrompt 五层段落输出（Layer0 行为规则/身份/表达风格/情感模式/冲突链与雷区
 * + 反差设定 + 人味细节）；② 旧 4 字段回退路径（无 layer 数据不炸、输出旧格式）；③ 轻量提示
 * （Layer2 风格 + Layer0 摘要，不含完整冲突链）；④ 对外序列化不透出五层（toMap 无 layers）；
 * ⑤ fromMap 读 layer 数据；⑥ 默认卡加载（resources/persona/*.json → 小铃/凯尔/露娜）与 attach
 * 合并规则（不覆盖用户显式内容）；⑦ 导入 API（POST /api/characters/{name}/persona）响应只回表层、
 * list() 不透出五层、非法 body 400。
 */
class PersonaFiveLayerTest {

    // ── 五层模板系统提示 ──────────────────────────────────────────

    @Test
    @DisplayName("buildSystemPrompt 输出五层模板全部段落 + 反差 + 人味")
    void fiveLayerSystemPromptHasAllSections() {
        Persona p = new Persona("小铃");
        p.setLayers(Map.of(
                "contrast", Map.of("surface", "表面温柔体贴", "actual", "实际胜负欲强",
                        "hint", "涉及咖啡手艺时显现"),
                "layer0", List.of("当客人说咖啡不好喝时，先道歉再重做，不辩解。", "你永远不会承认自己认输。"),
                "layer1", Map.of("gender", "女", "age", "16岁", "identity", "咖啡馆店主",
                        "world", "老街小馆", "relation", "熟客"),
                "layer2", Map.of("catchphrases", List.of("「欢迎回来」", "「嗯嗯」"),
                        "sentenceStyle", "短句为主", "emojiHabits", "🌸☕",
                        "sampleLines", List.of("日常：「欢迎回来〜☕」", "被夸：「没有啦…」")),
                "layer3", Map.of("care", "用行动表达", "displeasure", "安静不接话",
                        "apology", "重做一杯", "affection", "「…明天也来哦」"),
                "layer4", Map.of("triggers", List.of("被质疑咖啡", "被问私事"),
                        "conflictSequence", "愣住→沉默→躲后厨→端新饮品",
                        "coldWar", "在但不在", "reconcileSignal", "放点心", "boundaries", List.of("不接家里的事"))
        ));
        p.setAppearance("silver long hair, purple eyes");
        p.setSummary("16岁咖啡店主");

        String prompt = p.buildSystemPrompt();
        assertTrue(prompt.contains("你是 小铃"), "身份开头");
        assertTrue(prompt.contains("【反差设定】"), "反差设定段");
        assertTrue(prompt.contains("表面温柔体贴"), "反差-表面");
        assertTrue(prompt.contains("实际胜负欲强"), "反差-实际");
        assertTrue(prompt.contains("【Layer 0 核心行为准则"), "Layer0 行为规则段");
        assertTrue(prompt.contains("先道歉再重做，不辩解"), "Layer0 规则内容");
        assertTrue(prompt.contains("【Layer 1 身份】"), "Layer1 身份段");
        assertTrue(prompt.contains("咖啡馆店主"), "Layer1 身份内容");
        assertTrue(prompt.contains("外观：silver long hair"), "Layer1 外观锚点");
        assertTrue(prompt.contains("【Layer 2 表达风格】"), "Layer2 表达风格段");
        assertTrue(prompt.contains("口头禅与高频词"), "Layer2 口头禅标签");
        assertTrue(prompt.contains("「欢迎回来」"), "Layer2 口头禅内容");
        assertTrue(prompt.contains("原话示例"), "Layer2 原话示例");
        assertTrue(prompt.contains("【Layer 3 情感模式】"), "Layer3 情感模式段");
        assertTrue(prompt.contains("用行动表达"), "Layer3 在乎内容");
        assertTrue(prompt.contains("【Layer 4 冲突链与雷区】"), "Layer4 冲突链段");
        assertTrue(prompt.contains("触发点"), "Layer4 触发点标签");
        assertTrue(prompt.contains("愣住→沉默→躲后厨"), "Layer4 冲突序列内容");
        assertTrue(prompt.contains("【行为总原则】"), "行为总原则段");
        assertTrue(prompt.contains("【身份锁定】"), "身份锁定保留");
        assertTrue(prompt.contains("【表演规则】"), "表演规则保留");
    }

    @Test
    @DisplayName("五层提示含人味细节与允许情绪波动（P-0810-10 反差+人味需求）")
    void fiveLayerPromptHasHumanDetailsAndEmotionWobble() {
        Persona p = new Persona("凯尔");
        p.setLayers(Map.of(
                "layer0", List.of("当被质疑能力时，你硬撑证明自己。"),
                "layer3", Map.of("care", "行动派"),
                "humanDetails", List.of("小缺点：打肿脸充胖子", "口头禅：包在我身上",
                        "情绪化：被戳痛处笑着说「哈哈」")
        ));

        String prompt = p.buildSystemPrompt();
        assertTrue(prompt.contains("人味细节"), "人味细节段");
        assertTrue(prompt.contains("打肿脸充胖子"), "人味细节-小缺点");
        assertTrue(prompt.contains("允许情绪波动"), "允许情绪波动明确写进 Layer3");
        assertTrue(prompt.contains("不完美的回应"), "不完美回应引导");
    }

    @Test
    @DisplayName("旧 4 字段回退：无 layer 数据 → 旧格式输出，不炸")
    void backwardCompatOldFields() {
        Persona p = new Persona("小明", "开朗外向的年轻人");
        p.setVoice("说话轻快");
        p.setBackground("程序员");

        String prompt = p.buildSystemPrompt();
        assertTrue(prompt.contains("【人格设定】"), "旧格式人格设定");
        assertTrue(prompt.contains("开朗外向的年轻人"), "旧 personaDesc 内容");
        assertTrue(prompt.contains("【说话风格】"), "旧格式说话风格");
        assertTrue(prompt.contains("【背景故事】"), "旧格式背景故事");
        assertTrue(prompt.contains("【身份锁定】"), "身份锁定");
        assertFalse(prompt.contains("【Layer 0 核心行为准则"), "无五层段");

        // 轻量提示同样回退旧格式
        String light = p.buildLightweightPrompt();
        assertTrue(light.contains("【人格设定】"), "旧格式轻量人格设定");
        assertTrue(light.contains("120-220 字"), "旧格式轻量长度规则");
    }

    @Test
    @DisplayName("轻量提示：Layer2 风格 + Layer0 摘要，不含完整冲突链")
    void lightweightPromptIsCompact() {
        Persona p = new Persona("露娜");
        p.setLayers(Map.of(
                "contrast", Map.of("surface", "神秘优雅", "actual", "心软怕寂寞"),
                "layer0", List.of("当被问占卜细节时，只说三分。", "你永远不会把话说满。", "第三条规则", "第四条规则"),
                "layer2", Map.of("catchphrases", List.of("「我看到了…」"),
                        "sentenceStyle", "句长偏长玄乎", "emojiHabits", "🌙✨",
                        "sampleLines", List.of("日常：「我早算到你会来✨」", "被夸：「命运如此」", "第三条示例")),
                "layer3", Map.of("care", "做护身符"),
                "layer4", Map.of("triggers", List.of("被嘲笑占卜"), "conflictSequence", "冷笑→离开")
        ));

        String light = p.buildLightweightPrompt();
        assertTrue(light.contains("【说话风格】"), "Layer2 风格在轻量提示中");
        assertTrue(light.contains("「我看到了…」"), "口头禅内容");
        assertTrue(light.contains("【行为要点】"), "Layer0 摘要段");
        assertTrue(light.contains("只说三分"), "Layer0 首条规则");
        assertFalse(light.contains("【Layer 4 冲突链与雷区】"), "轻量不含完整冲突链");
        assertFalse(light.contains("【Layer 3 情感模式】"), "轻量不含完整情感模式");
        assertFalse(light.contains("冷笑→离开"), "轻量不含雷区细节");
        assertTrue(light.contains("【身份锁定】"), "身份锁定保留");
    }

    @Test
    @DisplayName("对外序列化不透出五层（toMap 无 layers/contrast/layerN）")
    void toMapDoesNotLeakLayers() {
        Persona p = new Persona("小铃");
        p.setLayers(Map.of(
                "layer0", List.of("行为铁律…"),
                "layer3", Map.of("care", "用行动表达"),
                "layer4", Map.of("triggers", List.of("雷区…")),
                "contrast", Map.of("surface", "X", "actual", "Y")
        ));
        p.setAppearance("银发紫瞳");
        p.setSummary("一句话摘要");

        Map<String, Object> m = p.toMap();
        assertEquals("小铃", m.get("name"));
        assertEquals("银发紫瞳", m.get("appearance"));
        assertEquals("一句话摘要", m.get("summary"));
        assertFalse(m.containsKey("layers"), "toMap 不含 layers");
        assertFalse(m.containsKey("layer0"), "toMap 不含 layer0");
        assertFalse(m.containsKey("layer3"), "toMap 不含 layer3");
        assertFalse(m.containsKey("layer4"), "toMap 不含 layer4");
        assertFalse(m.containsKey("contrast"), "toMap 不含 contrast");
        assertFalse(m.containsKey("humanDetails"), "toMap 不含 humanDetails");
    }

    @Test
    @DisplayName("fromMap 读入 layer 数据（layers 键）且旧字段兼容")
    void fromMapReadsLayers() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "小铃");
        data.put("persona", "旧人格描述");
        data.put("voice", "旧声音");
        data.put("background", "旧背景");
        data.put("appearance", "银发紫瞳");
        data.put("summary", "一句话");
        data.put("layers", Map.of("layer0", List.of("规则1"), "contrast", Map.of("surface", "S", "actual", "A")));

        Persona p = Persona.fromMap(data);
        assertEquals("小铃", p.getName());
        assertEquals("旧人格描述", p.getPersonaDesc());
        assertEquals("银发紫瞳", p.getAppearance());
        assertTrue(p.hasLayers(), "fromMap 读到 layers");
        assertEquals(List.of("规则1"), p.getLayers().get("layer0"));

        // 无 layers 键 → 不炸、回退旧字段
        Map<String, Object> old = Map.of("name", "小明", "persona", "描述");
        Persona p2 = Persona.fromMap(old);
        assertFalse(p2.hasLayers());
        assertEquals("描述", p2.getPersonaDesc());
    }

    // ── 默认卡加载（resources/persona/*.json） ─────────────────────

    @Test
    @DisplayName("默认卡加载：小铃/凯尔/露娜三张卡可挂载，含反差+人味")
    void defaultCardsLoadAndAttach() {
        PersonaCardLoader.resetForTests();
        try {
            for (String name : List.of("小铃", "凯尔", "露娜")) {
                Map<String, Object> card = PersonaCardLoader.cardFor(name);
                assertNotNull(card, name + " 默认卡存在");
                assertTrue(card.containsKey("layer0"), name + " 卡含 layer0");
                assertTrue(card.containsKey("contrast"), name + " 卡含反差设定（P-0810-10 硬性）");
                assertTrue(card.containsKey("humanDetails"), name + " 卡含人味细节（P-0810-10 硬性）");
                assertTrue(card.containsKey("appearance"), name + " 卡含外观锚点");

                Persona p = new Persona(name);
                PersonaCardLoader.attachDefault(p);
                assertTrue(p.hasLayers(), name + " attachDefault 后挂上五层");
                assertFalse(p.getAppearance().isEmpty(), name + " 外观回填");
                assertFalse(p.getSummary().isEmpty(), name + " 摘要回填");
                assertFalse(p.getPersonaDesc().isEmpty(), name + " personaDesc 回填");
                // 五层数据在系统提示中完整可见
                String prompt = p.buildSystemPrompt();
                assertTrue(prompt.contains("【Layer 0 核心行为准则"), name + " 提示含 Layer0");
                assertTrue(prompt.contains("【反差设定】"), name + " 提示含反差");
                assertTrue(prompt.contains("人味细节"), name + " 提示含人味");
                assertTrue(prompt.contains("允许情绪波动"), name + " 提示含允许情绪波动");
            }
        } finally {
            PersonaCardLoader.resetForTests();
        }
    }

    @Test
    @DisplayName("attach 规则：不覆盖用户显式内容；未知角色 no-op")
    void attachRulesRespectUserContent() {
        PersonaCardLoader.resetForTests();
        try {
            // 用户显式 personaDesc 不被默认卡覆盖
            Persona p = new Persona("小铃");
            p.setPersonaDesc("用户自定义描述");
            PersonaCardLoader.attachDefault(p);
            assertEquals("用户自定义描述", p.getPersonaDesc(), "用户显式 personaDesc 保留");
            assertTrue(p.hasLayers(), "layer 数据仍挂上");

            // 未知角色：no-op 不炸
            Persona unknown = new Persona("不存在的角色");
            PersonaCardLoader.attachDefault(unknown);
            assertFalse(unknown.hasLayers());
            assertEquals("", unknown.getPersonaDesc());

            // 已挂 layer 的 Persona：attachDefault 不覆盖
            Persona already = new Persona("小铃");
            already.setLayers(Map.of("layer0", List.of("自定义铁律")));
            PersonaCardLoader.attachDefault(already);
            assertEquals(List.of("自定义铁律"), already.getLayers().get("layer0"), "已有 layer 不被覆盖");

            // id 别名可查（heroine → 小铃卡）
            assertNotNull(PersonaCardLoader.cardFor("heroine"), "id 别名 heroine 可查");
            assertEquals("小铃", PersonaCardLoader.cardFor("heroine").get("name"));
        } finally {
            PersonaCardLoader.resetForTests();
        }
    }

    // ── 导入 API（POST /api/characters/{name}/persona） ────────────

    private CharacterController newController() {
        DatabaseService db = mock(DatabaseService.class);
        when(db.saveCharacter(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new LinkedHashMap<>());
        return new CharacterController(mock(GeneratorService.class), db);
    }

    @Test
    @DisplayName("导入 persona 卡：响应只回表层，list() 不透出五层")
    void importCardSurfaceOnly() {
        CharacterController cc = newController();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("layer0", List.of("当被质疑时，不辩解。"));
        card.put("layer3", Map.of("care", "行动派"));
        card.put("contrast", Map.of("surface", "S", "actual", "A"));
        card.put("appearance", "金发蓝眼");
        card.put("summary", "骑士一句话");

        ResponseEntity<?> resp = cc.importPersonaCard("凯尔", card);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertEquals("ok", body.get("status"));
        assertEquals("凯尔", body.get("name"));
        assertEquals("金发蓝眼", body.get("appearance"));
        assertEquals("骑士一句话", body.get("summary"));
        assertTrue(((List<?>) body.get("layers")).contains("layer0"), "响应列出层键名");
        assertFalse(body.containsKey("layer0"), "响应不包含 layer0 内容");
        assertFalse(body.containsKey("contrast"), "响应不包含 contrast 内容");
        assertFalse(body.containsKey("layer3"), "响应不包含 layer3 内容");

        // personaCardFor 可取回整卡（供 init 挂载）
        Map<String, Object> stored = cc.personaCardFor("凯尔");
        assertNotNull(stored);
        assertEquals("凯尔", stored.get("name"));

        // list()（对外 API）只附加表层 appearance/summary，不透出五层
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", "凯尔");
        ch.put("persona", "描述");
        ch.put("voice", "");
        ch.put("background", "");
        cc.create(ch);
        List<Map<String, Object>> list = cc.getAll();
        Map<String, Object> listed = list.stream()
                .filter(x -> "凯尔".equals(x.get("name"))).findFirst().orElseThrow();
        assertEquals("金发蓝眼", listed.get("appearance"), "list 表层含 appearance");
        assertEquals("骑士一句话", listed.get("summary"), "list 表层含 summary");
        assertFalse(listed.containsKey("layer0"), "list 不透出 layer0");
        assertFalse(listed.containsKey("layer3"), "list 不透出 layer3");
        assertFalse(listed.containsKey("contrast"), "list 不透出 contrast");
        assertFalse(listed.containsKey("humanDetails"), "list 不透出 humanDetails");

        // attachPersonaCard：导入卡优先于默认资源卡
        Persona p = new Persona("凯尔");
        cc.attachPersonaCard(p);
        assertTrue(p.hasLayers());
        assertEquals(List.of("当被质疑时，不辩解。"), p.getLayers().get("layer0"), "导入卡 layer0 生效");
    }

    @Test
    @DisplayName("导入 API 校验：空 body / 缺层数据 → 400")
    void importCardValidation() {
        CharacterController cc = newController();

        ResponseEntity<?> empty = cc.importPersonaCard("凯尔", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());

        ResponseEntity<?> noLayer = cc.importPersonaCard("凯尔", Map.of("name", "凯尔", "appearance", "x"));
        assertEquals(HttpStatus.BAD_REQUEST, noLayer.getStatusCode());

        // 合法卡正常入库
        ResponseEntity<?> ok = cc.importPersonaCard("凯尔", Map.of("layer2", Map.of("sentenceStyle", "爽快")));
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertNotNull(cc.personaCardFor("凯尔"));
        // 未导入的角色 → null
        assertNull(cc.personaCardFor("露娜"));
    }
}
