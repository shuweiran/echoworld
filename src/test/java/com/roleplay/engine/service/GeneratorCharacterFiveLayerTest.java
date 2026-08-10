package com.roleplay.engine.service;

import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0811-B：角色生成五层 persona 拟合 + 场景上下文注入测试。
 *
 * <p>覆盖：① 五层输出解析 + personaCards 挂载（生成后 Persona.buildSystemPrompt 含五层模板）；
 * ② 场景上下文注入（mock LLM 捕获 prompt 断言包含场景名/描述 + 契合要求）；
 * ③ 对外不透出（GET /api/characters 无 layer0/layers/contrast 等键）；
 * ④ 向后兼容（无场景参数旧调用可用；LLM 输出缺层/完全失败 → 回退旧字段不崩）；
 * ⑤ maxTokens ≥2000 防回归（D-023 纪律）。
 */
class GeneratorCharacterFiveLayerTest {

    // ── helpers ──────────────────────────────────────────────────

    private static LLMClient mockLlm(Map<String, Object> jsonOut) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), any())).thenReturn(jsonOut);
        return llm;
    }

    /** 完整五层卡 JSON（契约 docs/persona-五层卡-格式.md 结构）。 */
    private static Map<String, Object> fullFiveLayerCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "小铃");
        card.put("appearance", "银发紫瞳，系着围裙");
        card.put("summary", "16岁咖啡店主");
        card.put("personaDesc", "温柔细心，胜负欲藏在心里");
        card.put("voice", "轻软，尾音上扬");
        card.put("background", "老街咖啡馆的店主，手艺是被爷爷手把手教的");
        card.put("contrast", Map.of(
                "surface", "表面：温柔体贴、细心周到",
                "actual", "实际：胜负欲强、对咖啡手艺有执念",
                "hint", "当话题涉及手艺被质疑时反差显现"));
        card.put("humanDetails", List.of(
                "小缺点：被夸会害羞到手足无措",
                "小习惯：紧张时绞围裙角",
                "口头禅：「欢迎回来」「嗯嗯」"));
        card.put("layer0", List.of(
                "当客人说咖啡不好喝时，你不辩解，先道歉再重做。",
                "当有人当面夸你时，你低头说「没有啦」然后转移话题。",
                "你永远不会承认自己认输。",
                "当店里忙不过来时，你也不抱怨客人，只加快手上的动作。",
                "你永远不会在客人面前说其他客人的闲话。"));
        card.put("layer1", Map.of(
                "gender", "女，她",
                "age", "16岁",
                "identity", "和风咖啡馆「铃屋」店主兼咖啡师",
                "world", "城市老街上一间木质装修的小咖啡馆",
                "relation", "玩家是经常光顾的熟客，坐靠窗固定位置"));
        card.put("layer2", Map.of(
                "catchphrases", List.of("「欢迎回来」——熟客进门时", "「嗯嗯」——倾听回应"),
                "sentenceStyle", "短句为主 5~12 字，多用「…」留白",
                "emojiHabits", "尾音带「〜」；书面爱用🌸☕",
                "sampleLines", List.of(
                        "日常：「欢迎回来〜今天还是老位置吗？☕」",
                        "被夸：「啊、那个…没有啦…🌸」",
                        "失落：「…今天的豆子，好像不太行」",
                        "开心：「嗯！下次给你试试新配方〜」")));
        card.put("layer3", Map.of(
                "care", "用行动不用语言：记住口味、主动续杯",
                "displeasure", "不直接说生气，突然安静、回答变短",
                "apology", "不找理由，低头认错 + 补偿行动",
                "affection", "几乎不主动说出口…「…明天也来哦」",
                "allowEmotionalWobble", true));
        card.put("layer4", Map.of(
                "triggers", List.of("被质疑咖啡品质", "被随意触碰"),
                "conflictSequence", "愣住笑容僵住 → 沉默超三秒 → 「…我去后厨看看」→ 端新饮品「…请用」→ 等对方先开口",
                "coldWar", "「在但不在」：问什么答什么但全是单字",
                "reconcileSignal", "放一块你喜欢的点心，什么都不说",
                "boundaries", List.of("不接「家里的事」", "被问「你是不是喜欢我」→ 僵住说「我去收银台理账」")));
        return card;
    }

    private static CharacterController newController(GeneratorService g) {
        return new CharacterController(g, mock(DatabaseService.class));
    }

    // ── ① 五层输出解析 + personaCards 挂载 ──────────────────────

    @Test
    @DisplayName("生成角色：输出含表层+五层键，生成后挂载 personaCards，Persona 系统提示含五层模板")
    void fiveLayerOutputParsedAndMounted() {
        GeneratorService g = new GeneratorService(mockLlm(fullFiveLayerCard()));
        CharacterController cc = newController(g);

        ResponseEntity<Map<String, Object>> resp = cc.generate(
                Map.of("keywords", "咖啡师", "scene_name", "老街咖啡馆", "scene_description", "木质装修的日式小店"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        // 表层响应：name + appearance/summary + layers 键名列表
        assertEquals("小铃", body.get("name"));
        assertEquals("银发紫瞳，系着围裙", body.get("appearance"));
        assertEquals("16岁咖啡店主", body.get("summary"));
        List<?> layerNames = (List<?>) body.get("layers");
        assertTrue(layerNames.contains("layer0"));
        assertTrue(layerNames.contains("layer4"));
        assertTrue(layerNames.contains("contrast"));
        assertTrue(layerNames.contains("humanDetails"));
        // 绝不回 layer 内容
        assertFalse(body.containsKey("layer0"));
        assertFalse(body.containsKey("layer4"));
        assertFalse(body.containsKey("contrast"));
        assertFalse(body.containsKey("humanDetails"));

        // generator 输出本身：表层 + 五层键齐全（供上层落库/挂载）
        Map<String, Object> out = g.generateCharacter("咖啡师", "老街咖啡馆", "木质装修");
        assertEquals("小铃", out.get("name"));
        assertEquals("温柔细心，胜负欲藏在心里", out.get("persona"));
        assertEquals("轻软，尾音上扬", out.get("voice"));
        assertEquals("老街咖啡馆的店主，手艺是被爷爷手把手教的", out.get("background"));
        for (String k : List.of("layer0", "layer1", "layer2", "layer3", "layer4", "contrast", "humanDetails")) {
            assertTrue(out.containsKey(k), "输出含五层键 " + k);
        }

        // personaCards 挂载：attachPersonaCard 后五层进 LLM 系统提示
        Persona p = new Persona("小铃");
        cc.attachPersonaCard(p);
        assertTrue(p.hasLayers(), "生成卡挂载后 Persona 有五层");
        String prompt = p.buildSystemPrompt();
        assertTrue(prompt.contains("【Layer 0 行为规则"), "系统提示含 Layer0");
        assertTrue(prompt.contains("先道歉再重做"), "Layer0 规则内容");
        assertTrue(prompt.contains("【Layer 1 身份】"), "系统提示含 Layer1");
        assertTrue(prompt.contains("和风咖啡馆「铃屋」"), "Layer1 身份内容");
        assertTrue(prompt.contains("【Layer 2 表达风格】"), "系统提示含 Layer2");
        assertTrue(prompt.contains("【Layer 3 情感模式】"), "系统提示含 Layer3");
        assertTrue(prompt.contains("【Layer 4 冲突链与雷区】"), "系统提示含 Layer4");
        assertTrue(prompt.contains("【反差设定】"), "系统提示含反差");
        assertTrue(prompt.contains("人味细节"), "系统提示含人味细节");
        assertTrue(prompt.contains("允许情绪波动"), "系统提示含允许情绪波动");
        // Persona 序列化不透出五层
        Map<String, Object> pm = p.toMap();
        assertFalse(pm.containsKey("layers"));
        assertFalse(pm.containsKey("layer0"));
        assertFalse(pm.containsKey("contrast"));
    }

    // ── ② 场景上下文注入 ────────────────────────────────────────

    @Test
    @DisplayName("场景上下文注入：prompt 包含场景名/描述 + 契合要求；无场景不注入")
    void sceneContextInjectedIntoPrompt() {
        LLMClient llm = mockLlm(fullFiveLayerCard());
        GeneratorService g = new GeneratorService(llm);

        // 有场景 + 无场景两次调用后统一捕获（避免 captor 二次 verify 重复捕获旧值）
        g.generateCharacter("咖啡师", "老街咖啡馆", "木质装修的日式小店，常有熟客");
        g.generateCharacter("咖啡师");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).callJson(promptCaptor.capture(), any());
        List<String> prompts = promptCaptor.getAllValues();

        String withScene = prompts.get(0);
        assertTrue(withScene.contains("当前场景：老街咖啡馆（木质装修的日式小店，常有熟客）"),
                "prompt 含「当前场景：{name}（{description}）」");
        assertTrue(withScene.contains("必须与场景背景契合、人物身份与场景自洽"), "prompt 含契合要求");
        assertTrue(withScene.contains("用户提示：咖啡师"), "prompt 含用户关键词");

        String noScene = prompts.get(1);
        assertFalse(noScene.contains("当前场景"), "无场景时 prompt 不含当前场景段");
        assertFalse(noScene.contains("必须与场景背景契合"), "无场景时 prompt 不含契合要求");
        assertTrue(noScene.contains("用户提示：咖啡师"), "无场景通用生成仍可用");

        // 只有场景名、描述为空 → 注入「（无描述）」占位不崩
        g.generateCharacter("咖啡师", "老街咖啡馆", "");
        ArgumentCaptor<String> cap2 = ArgumentCaptor.forClass(String.class);
        verify(llm, times(3)).callJson(cap2.capture(), any());
        assertTrue(cap2.getAllValues().get(2).contains("当前场景：老街咖啡馆（（无描述））"));
    }

    // ── ③ 对外不透出 ────────────────────────────────────────────

    @Test
    @DisplayName("对外不透出：GET /api/characters（getAll）只附加表层 appearance/summary，无五层键")
    void listDoesNotLeakLayers() {
        GeneratorService g = new GeneratorService(mockLlm(fullFiveLayerCard()));
        CharacterController cc = newController(g);

        cc.generate(Map.of("keywords", "咖啡师"));
        // P-0811-D：generate 已自动落库（无需再 create——同 name 再 create 会 409），列表应已含生成角色
        List<Map<String, Object>> list = cc.getAll();
        Map<String, Object> listed = list.stream()
                .filter(x -> "小铃".equals(x.get("name"))).findFirst().orElseThrow();
        assertEquals("银发紫瞳，系着围裙", listed.get("appearance"), "list 附加表层 appearance");
        assertEquals("16岁咖啡店主", listed.get("summary"), "list 附加表层 summary");
        assertFalse(listed.containsKey("layer0"), "list 不透出 layer0");
        assertFalse(listed.containsKey("layer4"), "list 不透出 layer4");
        assertFalse(listed.containsKey("contrast"), "list 不透出 contrast");
        assertFalse(listed.containsKey("humanDetails"), "list 不透出 humanDetails");
        assertFalse(listed.containsKey("layers"), "list 不透出 layers");
        assertEquals("温柔细心，胜负欲藏在心里", listed.get("persona"), "自动落库的 persona=生成结果（旧四字段保留）");
    }

    // ── ④ 向后兼容 ──────────────────────────────────────────────

    @Test
    @DisplayName("向后兼容：旧单参签名可用；LLM 输出缺层/完全失败 → 回退旧字段不崩")
    void backwardCompatNoSceneAndFallbacks() {
        // 旧单参签名（无场景）→ 委托新重载，表层 + 五层输出齐全
        GeneratorService g = new GeneratorService(mockLlm(fullFiveLayerCard()));
        Map<String, Object> out = g.generateCharacter("咖啡师");
        assertEquals("小铃", out.get("name"));
        assertEquals("银发紫瞳，系着围裙", out.get("appearance"));
        assertTrue(out.containsKey("layer0"));
        assertTrue(out.containsKey("contrast"));
        assertTrue(out.containsKey("humanDetails"));

        // LLM 输出缺层（只有 name/persona）→ 不崩，缺层键不出现，表层默认值兜底
        GeneratorService g2 = new GeneratorService(mockLlm(Map.of("name", "小明", "persona", "开朗")));
        Map<String, Object> out2 = g2.generateCharacter("小明");
        assertEquals("小明", out2.get("name"));
        assertEquals("开朗", out2.get("persona"));
        assertEquals("正常", out2.get("voice"), "缺 voice → 默认值");
        assertEquals("未知", out2.get("background"), "缺 background → 默认值");
        assertFalse(out2.containsKey("layer0"));
        assertFalse(out2.containsKey("contrast"));
        assertFalse(out2.containsKey("appearance"), "缺 appearance 不出现空键");

        // 部分层（只给 layer0 + 表层）→ 挂卡后五层模板宽容渲染不炸
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("name", "阿杰");
        partial.put("persona", "沉默寡言的维修工");
        partial.put("layer0", List.of("当被问私事时，你只回答「嗯」。", "你永远不会主动提起过去。"));
        CharacterController cc3 = newController(new GeneratorService(mockLlm(partial)));
        ResponseEntity<Map<String, Object>> resp3 = cc3.generate(Map.of("keywords", "维修工"));
        assertTrue(((List<?>) resp3.getBody().get("layers")).contains("layer0"));
        Persona p3 = new Persona("阿杰");
        cc3.attachPersonaCard(p3);
        assertTrue(p3.hasLayers());
        String prompt3 = p3.buildSystemPrompt();
        assertTrue(prompt3.contains("【Layer 0 行为规则"), "部分层渲染 Layer0");
        assertFalse(prompt3.contains("【Layer 4 冲突链与雷区"), "缺层不渲染（不炸）");

        // 完全失败（callJson 空 map）→ 回退旧 4 字段默认行为不崩，controller 不挂卡不炸
        GeneratorService g3 = new GeneratorService(mockLlm(Map.of()));
        Map<String, Object> out3 = g3.generateCharacter("x");
        assertEquals("路人", out3.get("name"));
        assertEquals("普通角色", out3.get("persona"));
        assertEquals("正常", out3.get("voice"));
        assertEquals("未知", out3.get("background"));
        CharacterController cc4 = newController(g3);
        ResponseEntity<Map<String, Object>> resp4 = cc4.generate(Map.of("keywords", "x"));
        assertEquals(HttpStatus.OK, resp4.getStatusCode());
        assertNull(resp4.getBody().get("layers"), "完全失败无 layers 键名列表");
        assertNull(cc4.personaCardFor("路人"), "完全失败不挂卡");
    }

    @Test
    @DisplayName("宽容解析：LLM 把 layer0 返回成字符串 → 归一为单元素列表；layer1 返回成字符串 → 不炸")
    void tolerantParsingOddTypes() {
        Map<String, Object> odd = new LinkedHashMap<>();
        odd.put("name", "怪客");
        odd.put("persona", "怪人");
        odd.put("layer0", "当有人问奇怪问题时，你笑而不答。");       // 字符串而非数组
        odd.put("layer1", "身份：神秘的占卜师");                     // 字符串而非 object
        odd.put("humanDetails", "小习惯：转硬币");                   // 字符串而非数组
        GeneratorService g = new GeneratorService(mockLlm(odd));
        Map<String, Object> out = g.generateCharacter("怪客");
        assertEquals(List.of("当有人问奇怪问题时，你笑而不答。"), out.get("layer0"), "layer0 字符串归一为列表");
        assertEquals(List.of("小习惯：转硬币"), out.get("humanDetails"), "humanDetails 字符串归一为列表");
        assertEquals("身份：神秘的占卜师", out.get("layer1"), "layer1 字符串原样保留（Persona 渲染端宽容跳过）");

        CharacterController cc = newController(g);
        ResponseEntity<Map<String, Object>> resp = cc.generate(Map.of("keywords", "怪客"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Persona p = new Persona("怪客");
        cc.attachPersonaCard(p);
        assertTrue(p.hasLayers());
        String prompt = p.buildSystemPrompt(); // 字符串 layer1 被跳过、layer0 正常渲染，不炸
        assertTrue(prompt.contains("【Layer 0 行为规则"));
        assertTrue(prompt.contains("笑而不答"));
    }

    // ── ⑤ maxTokens ≥2000 防回归（D-023 纪律） ──────────────────

    @Test
    @DisplayName("maxTokens ≥2000：结构化大 JSON 防截断（D-023 纪律）")
    void maxTokensAtLeast2000() {
        LLMClient llm = mockLlm(fullFiveLayerCard());
        GeneratorService g = new GeneratorService(llm);
        g.generateCharacter("咖啡师", "老街咖啡馆", "木质装修");
        ArgumentCaptor<Integer> tokenCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(llm).callJson(anyString(), tokenCaptor.capture());
        assertTrue(tokenCaptor.getValue() >= 2000,
                "D-023 纪律：五层 persona 大 JSON 的 maxTokens 必须 ≥2000（实际 " + tokenCaptor.getValue() + "）");
    }
}
