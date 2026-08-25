package com.roleplay.engine.service;

import com.roleplay.engine.core.PersonaCardLoader;
import com.roleplay.engine.llm.LLMClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI-powered generation for characters and scenes.
 * Maps from Python Arbiter.generate_scene() / generate_character() methods.
 */
@Service
public class GeneratorService {

    private final LLMClient llmClient;

    public GeneratorService(@Qualifier("arbiterLlmClient") LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * P-0811-E（追加）：场景 + 配套角色一次生成（LLM 输出 {name, description, roles[]}，
     * 每个角色结构与 {@link #generateCharacter(String, String, String)} 一致：表层 name/appearance/
     * summary/personaDesc/voice/background + 五层 layer0-4/contrast/humanDetails）。
     *
     * <p>返回类型 Map&lt;String,String&gt; → Map&lt;String,Object&gt;（表层键名不变 name/description，
     * 新增 roles 数组）；roles 缺失/非数组 → 空数组（旧调用方零破坏，仍只取 name/description）。
     * maxTokens 300→4000（D-023 教训：场景+4~6 角色大 JSON 用 300 必截断）。
     */
    public Map<String, Object> generateScene(String keywords, String currentScene) {
        String context = "";
        if (currentScene != null && !currentScene.isEmpty()) {
            context = "当前场景：" + currentScene + "\n\n请基于此生成一个相关但不同的场景。";
        }
        String kw = (keywords != null && !keywords.isEmpty())
            ? (keywords.length() > 100 ? keywords.substring(0, 100) : keywords)
            : "生成一个适合多角色互动的场景";

        String prompt = String.format("""
            你是一个角色扮演游戏的主控（DM）。请生成一个场景设定，并同步生成 4~6 个与该场景契合的配套角色。

            %s
            用户提示：%s

            请返回 JSON，包含以下键：
            - name：场景名称（string）
            - description：场景描述（string，80-120字）
            - roles：配套角色数组（array of object，4~6 个），每个角色必须与场景背景契合、人物关系自洽，
              每个角色结构与单角色生成一致：
              - name：角色名（string）
              - appearance：外观描述（string，30-80字）
              - summary：角色一句话背景摘要（string，15-40字）
              - personaDesc：人格设定摘要（string，60-100字）
              - voice：说话风格（string，30-60字）
              - background：背景故事（string，50-80字）
              - contrast：反差设定（object：surface 表面形象 / actual 真实内在 / hint 反差触发提示）
              - humanDetails：人味细节（array of string，3-6条：小缺点/小习惯/口头禅/情绪化表达。注意：肢体动作/小习惯最多 1 条，且必须绑定特定情境才触发，禁止写成每轮必现的高频固定开场动作）
              - layer0：行为规则（array of string，5-8条）
              - layer1：身份（object）
              - layer2：表达风格（object）
              - layer3：情感模式（object）
              - layer4：冲突链与雷区（object）

            只返回 JSON，不要其他文字。

            补充要求（人味细节设计，重要，适用于每个角色）：
            - 肢体动作/小习惯（如推眼镜、摸耳钉、转笔）最多 1 条，且必须设计为「特定情境才触发」
              （如「紧张时会不自觉推眼镜」「尴尬时摸耳钉」），禁止写成无情境限制、每轮必现的高频固定开场动作。
            - 口头禅/固定句式/动作若每轮必现会破坏自然感：同类表达应提供多个可变说法，
              让角色在不同情境下轮换使用，不要写死为单一固定模板。
            """, context, kw);

        // D-023 纪律：场景+4~6 角色大 JSON 用 300 必截断 → 4000（与剧本/角色生成同档）
        Map<String, Object> result = llmClient.callJson(prompt, 4000);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("name", str(result.get("name"), "新场景"));
        output.put("description", str(result.get("description"), "一个普通的场景。"));

        // 配套角色宽容解析：缺 roles/非数组 → 空数组（旧调用方零破坏）；单角色缺层不崩（复用单角色归一逻辑）
        List<Map<String, Object>> roles = new ArrayList<>();
        if (result.get("roles") instanceof List<?> rawRoles) {
            int i = 0;
            for (Object o : rawRoles) {
                if (!(o instanceof Map<?, ?> rm)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> roleRaw = new LinkedHashMap<>((Map<String, Object>) rm);
                if (roleRaw.get("name") == null || String.valueOf(roleRaw.get("name")).isBlank()) {
                    roleRaw.put("name", "场景角色" + (i + 1));
                }
                roles.add(normalizeCharacterOutput(roleRaw));
                i++;
            }
        }
        output.put("roles", roles);
        return output;
    }

    /**
     * P-0811-B：生成角色（旧单参签名，委托新重载，向后兼容零破坏）。
     * 返回结构升级为表层 + 五层键（见 {@link #generateCharacter(String, String, String)}）。
     */
    public Map<String, Object> generateCharacter(String keywords) {
        return generateCharacter(keywords, null, null);
    }

    /**
     * P-0811-B：生成角色（五层 persona 拟合 + 场景上下文注入，核心改造）。
     *
     * <p>sceneName/sceneDescription 可选：有场景时 prompt 注入「当前场景：{name}（{description}）」
     * 并要求角色与场景背景契合、人物身份与场景自洽（修复“一般模式生成场景后生成角色，
     * 角色与场景毫无逻辑关联”的根因）；无场景时通用生成（与旧行为一致）。
     *
     * <p>输出升级为五层 persona JSON（严格按 docs/persona-五层卡-格式.md 契约）：表层
     * name/appearance/summary/personaDesc(键 persona)/voice/background + layer0[]（行为规则 5-8 条）
     * /layer1{}（身份）/layer2{}（表达风格）/layer3{}（情感模式）/layer4{}（冲突链与雷区）
     * /contrast{}（反差）/humanDetails[]（人味细节）。maxTokens 400→4000（D-023 教训：
     * 结构化大 JSON 用 400 必截断；4000 为 LLMClient 上限内，与剧本生成同档）。
     *
     * <p>宽容解析：LLM 输出缺层/缺字段不崩——五层缺失部分容忍（仅回退表层默认值，不挂残缺层），
     * 完全失败（callJson 返回空 map）回退现有旧 4 字段默认行为（与改造前一致）。
     */
    public Map<String, Object> generateCharacter(String keywords, String sceneName, String sceneDescription) {
        String kw = (keywords != null && !keywords.isEmpty())
            ? (keywords.length() > 100 ? keywords.substring(0, 100) : keywords)
            : "一个有趣的角色";

        // 场景上下文注入：有场景时要求角色与场景契合（P-0811-B 核心）；无场景时为空串零影响
        String sceneContext = "";
        if (sceneName != null && !sceneName.isEmpty()) {
            String desc = (sceneDescription != null && !sceneDescription.isEmpty())
                ? sceneDescription : "（无描述）";
            sceneContext = "当前场景：" + sceneName + "（" + desc + "）。生成的角色必须与场景背景契合、人物身份与场景自洽。\n\n";
        }
        return generateCharacterInternal(sceneContext + "用户提示：" + kw);
    }

    /**
     * P-0811-D：批量升级专用生成——以角色现有表层设定（persona/voice/background）为上下文关键词，
     * 保持身份一致（角色名固定为原角色名）；scene 上下文不传（升级目标是角色库既有角色，与场景无关）。
     * 与 {@link #generateCharacter(String, String, String)} 的区别：不做 100 字关键词截断
     * （升级需完整看到现有设定才能延续人格，100 字截断会丢身份锚点）。
     */
    public Map<String, Object> generateCharacterForUpgrade(String name, String persona, String voice, String background) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("角色「").append(name).append("」的现有设定：");
        if (persona != null && !persona.isBlank()) ctx.append("\n- 人格设定：").append(persona);
        if (voice != null && !voice.isBlank()) ctx.append("\n- 说话风格：").append(voice);
        if (background != null && !background.isBlank()) ctx.append("\n- 背景故事：").append(background);
        ctx.append("\n\n请基于以上现有设定生成五层 persona 卡：保持身份一致、人格延续，不要改变角色本质；"
                + "角色名固定为「").append(name).append("」，不要改名。");
        String hint = ctx.length() > 1000 ? ctx.substring(0, 1000) : ctx.toString();
        return generateCharacterInternal(hint);
    }

    /** P-0811-B/P-0811-D：五层 persona 生成核心（提示构建 + 宽容解析，两个入口共用）。 */
    private Map<String, Object> generateCharacterInternal(String userHint) {
        String prompt = String.format("""
            你是一个角色扮演游戏的主控（DM）。请生成一个角色。

            %s

            请返回 JSON，包含以下键：
            - name：角色名（string）
            - appearance：外观描述（string，30-80字，用于展示/生图）
            - summary：角色一句话背景摘要（string，15-40字）
            - personaDesc：人格设定摘要（string，60-100字）
            - voice：说话风格（string，30-60字）
            - background：背景故事（string，50-80字）
            - contrast：反差设定（object：surface 表面形象 / actual 真实内在 / hint 反差触发提示）
            - humanDetails：人味细节（array of string，3-6条：小缺点/小习惯/口头禅/情绪化表达。注意：肢体动作/小习惯最多 1 条，且必须绑定特定情境才触发，禁止写成每轮必现的高频固定开场动作）
            - layer0：行为规则（array of string，5-8条，每条为「当[场景]时，你[具体行为]」句式，最高优先级）
            - layer1：身份（object：gender 性别与代词 / age 年龄 / identity 身份 / world 世界背景 / relation 与玩家的关系）
            - layer2：表达风格（object：catchphrases 口头禅 array / sentenceStyle 句长句式 / emojiHabits 语气词与emoji习惯 / sampleLines 原话示例 array 至少4条）
            - layer3：情感模式（object：care 表达在乎 / displeasure 表达不满 / apology 道歉方式 / affection 说喜欢，可选 allowEmotionalWobble 是否允许情绪波动）
            - layer4：冲突链与雷区（object：triggers 触发点 array / conflictSequence 冲突序列 / coldWar 冷战模式 / reconcileSignal 和解信号 / boundaries 边界与雷区 array）

            只返回 JSON，不要其他文字。

            补充要求（人味细节设计，重要）：
            - 肢体动作/小习惯（如推眼镜、摸耳钉、转笔）最多 1 条，且必须设计为「特定情境才触发」
              （如「紧张时会不自觉推眼镜」「尴尬时摸耳钉」），禁止写成无情境限制、每轮必现的高频固定开场动作。
            - 口头禅/固定句式/动作若每轮必现会破坏自然感：同类表达应提供多个可变说法，
              让角色在不同情境下轮换使用，不要写死为单一固定模板。
            """, userHint);

        // D-023 纪律：结构化大 JSON 用 400 必截断 → 4000（LLMClient 上限内，与剧本生成同档）
        Map<String, Object> result = llmClient.callJson(prompt, 4000);
        // P-0811-E（追加）：归一逻辑抽为共享方法（generateCharacter 与场景配套角色复用），空输出走默认值不崩
        return normalizeCharacterOutput(result);
    }

    /**
     * 单角色输出归一（表层 + 五层宽容解析）：generateCharacter 与场景 roles 共用。
     * 表层键名（name/persona/voice/background）保留供上层落库零破坏；缺层/缺字段不崩。
     */
    private static Map<String, Object> normalizeCharacterOutput(Map<String, Object> result) {
        Map<String, Object> output = new LinkedHashMap<>();

        // 表层字段（旧 4 字段键名保留：name/persona/voice/background，供上层落库零破坏）
        output.put("name", str(result.get("name"), "路人"));
        Object personaDesc = result.get("personaDesc") != null ? result.get("personaDesc") : result.get("persona");
        output.put("persona", str(personaDesc, "普通角色"));
        output.put("voice", str(result.get("voice"), "正常"));
        output.put("background", str(result.get("background"), "未知"));
        if (result.get("appearance") != null) {
            output.put("appearance", str(result.get("appearance"), ""));
        }
        if (result.get("summary") != null) {
            output.put("summary", str(result.get("summary"), ""));
        }

        // 五层宽容解析：缺层/缺字段不崩——layer0/humanDetails 若被 LLM 返回成字符串则归一为单元素列表，
        // 其余类型不符的层原样保留（Persona 渲染端对非 Map/List 层同样宽容跳过，不炸）
        for (String key : PersonaCardLoader.LAYER_KEYS) {
            Object v = result.get(key);
            if (v == null) continue;
            if (("layer0".equals(key) || "humanDetails".equals(key)) && v instanceof String s) {
                output.put(key, List.of(s));
            } else if (v instanceof Map || v instanceof List || v instanceof String) {
                output.put(key, v);
            }
        }
        // 兼容：LLM 把外观写进 layer1.appearance（契约规定 appearance 在顶层）→ 提升为表层
        if (!output.containsKey("appearance") && result.get("layer1") instanceof Map<?, ?> l1
                && l1.get("appearance") != null) {
            output.put("appearance", String.valueOf(l1.get("appearance")));
        }
        return output;
    }

    /** 值转字符串：null → 默认值（容忍 LLM 返回非字符串类型，不抛 ClassCastException）。 */
    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isEmpty() ? def : s;
    }

    /**
     * P-0817-A：根据角色卡信息生成 TTS 音色描述（ttsTone）。
     * 在角色卡生成完成后调用，用角色的 name/persona/voice/appearance 作为上下文，
     * 让 LLM 生成适合 MiMo TTS 的音色描述（语气、语速、音色特征等）。
     *
     * @param name      角色名
     * @param persona   人格设定
     * @param voice     说话风格
     * @param appearance 外观描述（可选，辅助判断年龄/性别）
     * @return TTS 音色描述（20-40字），失败返回 null
     */
    public String generateTtsTone(String name, String persona, String voice, String appearance) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("角色「").append(name).append("」的信息：\n");
        if (persona != null && !persona.isBlank()) ctx.append("- 人格：").append(persona).append("\n");
        if (voice != null && !voice.isBlank()) ctx.append("- 说话风格：").append(voice).append("\n");
        if (appearance != null && !appearance.isBlank()) ctx.append("- 外观：").append(appearance).append("\n");

        String prompt = String.format("""
            你是一个 TTS 音色设计师。根据以下角色信息，生成一段简短的音色描述，用于指导语音合成系统。

            %s
            要求：
            - 开头必须标明性别声线（如“青年女声”“中年男声”“少年音”等）
            - 描述语气、语速、音色特征（如磁性/清脆/沙哑/甜美等）
            - 可包含情绪基调（如冷淡/温柔/活泼/慵懒等）
            - 20-40字，简洁精准
            - 只返回描述文本，不要其他文字

            示例：
            - 青年女声，清脆干练，语速偏快，语气理性克制
            - 中年男声，低沉沙哑，语速缓慢沉稳，偶尔带冷笑感
            - 少年音，活泼清脆，语速快，语气夸张多变
            - 老年男声，浑厚庄重，语速缓慢，语气威严
            """, ctx.toString());

        try {
            Map<String, Object> result = llmClient.callJson(prompt, 200);
            if (result != null && result.containsKey("ttsTone")) {
                String tone = str(result.get("ttsTone"), null);
                if (tone != null && !tone.isBlank()) return tone;
            }
            // 兼容 LLM 直接返回字符串（非 JSON）
            if (result != null && result.containsKey("text")) {
                String tone = str(result.get("text"), null);
                if (tone != null && !tone.isBlank()) return tone;
            }
        } catch (Exception e) {
            // ttsTone 生成失败不阻塞角色生成流程
        }
        return null;
    }
}
