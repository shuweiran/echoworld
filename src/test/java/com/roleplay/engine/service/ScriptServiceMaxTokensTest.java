package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 缺陷防回归测试（D-023，2026-08-01）：
 * 真机验证发现 LLM 剧本生成 JSON 截断（3/3 生成失败）——根因 ScriptService.generateScript
 * 的 callJson maxTokens=600 过小，4 角色完整剧本 JSON（schema v1）需 2000-4000 tokens，
 * 600 被硬截断 → 全员走 defaultScript 兜底，SpeechGate 静默分支不可观测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>长 JSON：mock LLM 返回 2000+ token 的完整剧本 JSON（5 角色 × 长 intro/secret + 5 线索 +
 *       secrets + background + truth），generateScript 解析成功且 roles/secrets/clues/killer_id/truth 字段齐全</li>
 *   <li>maxTokens 锁定：generateScript 必须携带 maxTokens=4000 调用 callJson（600 旧值回归即失败）</li>
 *   <li>兜底对照：LLM 空输出仍走 defaultScript（既有行为不回归）</li>
 * </ul>
 *
 * <p>说明：现有测试基建（application-test.yml llm.api-base=localhost:9999）为 Mockito mock
 * LLMClient 直返（无真实 HTTP 输出长度限制），故长输出用 mock 直返大 JSON 验证解析/归一链路；
 * maxTokens 参数由 verify(eq(4000)) 独立锁定。
 */
class ScriptServiceMaxTokensTest {

    /** 长字段填充（中文 ~1 字 ≈ 1 token，构造 2000+ token 的完整剧本）。 */
    private static String longText(String prefix, int chars) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < chars) {
            sb.append("这是一个用于填充剧本长度的叙述性文本片段，包含动机、关系与时间线细节。");
        }
        return sb.substring(0, chars);
    }

    /** 5 角色完整剧本（v1 格式），正文约 4000+ 字符 ≈ 3000+ tokens。 */
    private static Map<String, Object> longScript() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("metadata", Map.of("title", "雾都庄园连环案", "player_min", 4, "player_max", 5,
            "tags", List.of("本格推理", "豪门恩怨")));
        script.put("background", longText("背景：", 300));

        List<Map<String, Object>> roles = new ArrayList<>();
        String[] names = {"侦探", "管家", "女仆", "园丁", "律师"};
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", "role_" + (i + 1));
            r.put("name", names[i]);
            r.put("intro", longText(names[i] + "介绍：", 200));
            r.put("is_hidden", i == 4);
            r.put("secret", longText(names[i] + "的秘密：", 200));
            r.put("ap_bonus", i == 0 ? 2 : 0);
            r.put("talkativeness", i % 2 == 0 ? 0.7 : 0.3);
            roles.add(r);
        }
        script.put("roles", roles);

        script.put("locations", List.of("客厅", "书房", "花园", "地下室", "阁楼"));

        List<Map<String, Object>> clues = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clues.add(Map.of("id", "clue_" + (i + 1), "title", "线索" + (i + 1),
                "location", List.of("客厅", "书房", "花园", "地下室", "阁楼").get(i),
                "content", longText("线索内容：", 150),
                "transferable", i % 2 == 0, "visible_to_owner_only", i % 2 == 1, "ap_cost", 1));
        }
        script.put("clues", clues);

        Map<String, String> secrets = new LinkedHashMap<>();
        for (String n : names) secrets.put(n, longText(n + "的秘密：", 100));
        script.put("secrets", secrets);
        script.put("killer_id", "role_2");
        script.put("truth", "凶手是管家，因为管家为夺取遗产而在雨夜行凶。");
        return script;
    }

    @Test
    @DisplayName("D-023-1: mock 返回 2000+ token 完整剧本 JSON → generateScript 解析成功且字段齐全（roles/secrets/clues/killer_id/truth）")
    void longJsonParsedCompletely() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(longScript());

        Map<String, Object> script = new ScriptService(llm)
            .generateScript("雾都", List.of("Alice", "Bob", "Carol", "Dave", "Eve"));

        assertEquals(1, script.get("schema_version"));
        assertTrue(script.containsKey("metadata"));
        assertEquals("雾都庄园连环案", ScriptSchemaV1.title(script));

        List<Map<String, Object>> roles = ScriptSchemaV1.roleObjects(script);
        assertEquals(5, roles.size(), "roles[] 必须完整保留 5 角色（截断会丢角色）");
        assertEquals(List.of("侦探", "管家", "女仆", "园丁", "律师"),
            ScriptSchemaV1.roleNames(script), "角色名顺序保持");

        // 长字段不被截断
        assertTrue(((String) roles.get(0).get("intro")).length() >= 150, "intro 长文本应完整保留");
        assertTrue(((String) roles.get(0).get("secret")).length() >= 150, "secret 长文本应完整保留");

        // 字段齐全
        Map<String, String> secrets = ScriptSchemaV1.secretsByRole(script);
        assertEquals(5, secrets.size(), "secrets 键集合==roles（5 项）");
        List<Map<String, Object>> clues = ScriptSchemaV1.clueList(script);
        assertEquals(5, clues.size(), "clues[] 5 条完整保留");
        assertEquals("role_2", ScriptSchemaV1.killerId(script), "killer_id 透传");
        assertTrue(ScriptSchemaV1.truth(script).contains("凶手是管家"), "truth 完整保留");
        assertEquals(List.of("客厅", "书房", "花园", "地下室", "阁楼"),
            ScriptSchemaV1.locations(script), "locations 完整保留");
    }

    @Test
    @DisplayName("D-023-2: generateScript 必须以 maxTokens=4000 调用 callJson（600 旧值回归即失败）")
    void maxTokensIs4000() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(longScript());

        new ScriptService(llm).generateScript("雾都", List.of("A", "B", "C", "D"));

        verify(llm).callJson(anyString(), eq(4000));
    }

    @Test
    @DisplayName("D-023-3: LLM 空输出仍走 defaultScript 兜底且符合 schema（既有 A1-3 行为不回归）")
    void emptyOutputFallsBack() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());

        Map<String, Object> script = new ScriptService(llm)
            .generateScript("庄园", List.of("Alice", "Bob"));

        assertEquals(1, script.get("schema_version"));
        assertEquals(Set.of("嫌疑人_Alice", "嫌疑人_Bob"), ScriptSchemaV1.secretsByRole(script).keySet(),
            "兜底剧本 secrets 键集合==roles（A1-3）");
        assertTrue(script.containsKey("killer_id"), "兜底剧本含 killer_id 键");
    }
}
