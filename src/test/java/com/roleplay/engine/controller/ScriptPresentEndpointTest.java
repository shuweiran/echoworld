package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-0816-T（阶段三 API-9）HTTP 层验收测试 —— POST /api/script/present 端点的容器级验证
 * （@SpringBootTest MOCK + MockMvc = 真实 Spring 容器 + 真实 controller 路由 + UTF-8 中文参数，
 * 对齐 CharacterRenameValidationTest 先例；RANDOM_PORT/H2 mem/mock LLM 走 application-test.yml，D-008 基建）。
 *
 * <p>覆盖（HTTP 语义与 task 书 curl 验证等价）：
 * ① 阶段守卫：非 DISCUSSION 阶段 present → HTTP 200 + {error:"当前不是讨论阶段", phase}（业务错误约定）
 * ② 鉴权 403：错误 player_key → HTTP 403（鉴权先于阶段守卫）
 * ③ 线索不存在：未知 clue_id → {error:"线索不存在"}
 * ④ 搜证（UTF-8 中文参数正确传递）+ transfer_clue（既有端点）HTTP 层复验：investigation 阶段可转交
 * ⑤ 讨论自动收束进 VOTE 后 present 拒绝（§3.2「投票后拒绝」守卫语义）
 *
 * <p>说明：present 成功路径（ok + 「🃏 出示」system 行全员可见 + 幂等 already + 快照落库）由 service 层
 * ScriptGamePresentTest 锁定——HTTP 层 discussion 窗口受「讨论引擎自动收束」（start_discussion 同步驱动、
 * mock LLM 极快）约束不可稳定观测（start_discussion 响应 phase 为 controller 硬编码，返回时引擎已在后台收束）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScriptPresentEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LLMClient llmClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SESSION_PREFIX = "ep-present-";

    /** 新格式剧本：3 角色 + 3 地点 + 3 线索（c1 客厅非公开可转交 / c2 书房公开 / c3 地下室非公开）。 */
    private void mockLlm() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of(
                Map.of("id", "role_1", "name", "管家"),
                Map.of("id", "role_2", "name", "女仆"),
                Map.of("id", "role_3", "name", "园丁")));
        script.put("locations", List.of("客厅", "书房", "地下室"));
        script.put("clues", List.of(
                Map.of("id", "c1", "location", "客厅", "title", "碎玻璃", "content", "管家在客厅留下的碎玻璃", "public", false, "transferable", true),
                Map.of("id", "c2", "location", "书房", "title", "密信", "content", "一封没有署名的密信", "public", true, "transferable", false),
                Map.of("id", "c3", "location", "地下室", "title", "染血手套", "content", "一副染血的手套", "public", false, "transferable", false)));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道密信", "园丁", "你目击了凶手"));
        when(llmClient.callJson(anyString(), anyInt())).thenReturn(script);
        when(llmClient.callSync(anyList())).thenReturn("嗯。");
    }

    private String postJson(String url, Object body) throws Exception {
        MvcResult r = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
        return r.getResponse().getContentAsString();
    }

    private String getJson(String url) throws Exception {
        MvcResult r = mockMvc.perform(get(url)).andReturn();
        return r.getResponse().getContentAsString();
    }

    /** 建局（UTF-8 中文玩家名）+ 取 session_id（init 自行生成）/ 各玩家 role_key。 */
    private Map<String, Object> setupGame(int seq) throws Exception {
        mockLlm();
        Map<String, Object> initBody = new LinkedHashMap<>();
        initBody.put("players", List.of("林深", "苏晚", "顾言"));
        initBody.put("theme", "庄园疑云");
        initBody.put("mode", "full");
        // outline_only=false：同步完整生成（init 直达 INVESTIGATION，测试免等待后台 generate_full）
        initBody.put("outline_only", false);
        String initRes = postJson("/api/script/init", initBody);
        JsonNode init = mapper.readTree(initRes);
        assertNotNull(init.get("session_id"), "init 返回 session_id");
        String sid = init.get("session_id").asText();

        // init 只向首位真人玩家发放其 role_key；后续本人视图必须携带该凭证，不能按玩家名匿名领取。
        String key = init.has("role_key") ? init.get("role_key").asText() : "";
        assertFalse(key.isBlank(), "init 应向首位真人玩家发放非空 role_key");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", sid);
        out.put("player", "林深");
        out.put("role_key", key);
        return out;
    }

    @Test
    @DisplayName("API-9 HTTP: 阶段守卫（investigation/vote 拒绝含 phase 键）→ 403 → 线索不存在 → 转交成功（UTF-8）")
    void presentHttpFlow() throws Exception {        Map<String, Object> g = setupGame(1);
        String sid = (String) g.get("session_id");
        String player = (String) g.get("player");
        String key = (String) g.get("role_key");

        // ① 阶段守卫：investigation 阶段出示 → HTTP 200 + {error, phase}（业务错误约定）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sid);
        body.put("player", player);
        body.put("clue_id", "c1");
        body.put("player_key", key);
        String guardRes = postJson("/api/script/present", body);
        JsonNode guard = mapper.readTree(guardRes);
        assertTrue(guard.get("error").asText().contains("当前不是讨论阶段"), "阶段守卫：" + guardRes);
        assertEquals("investigation", guard.get("phase").asText(), "响应含 phase 键");

        // ② 鉴权 403：错误 player_key → HTTP 403（鉴权先于阶段守卫）
        Map<String, Object> badBody = new LinkedHashMap<>();
        badBody.put("session_id", sid);
        badBody.put("player", player);
        badBody.put("clue_id", "c1");
        badBody.put("player_key", "wrong-key");
        mockMvc.perform(post("/api/script/present")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(badBody)))
                .andExpect(status().isForbidden());

        // ③ 非法 clue_id + 非 discussion 阶段 → 阶段守卫优先（守卫先行使不可绕过，对齐 D-032 K4 先例；
        //    线索不存在校验在 discussion 阶段触达，由 service 层 ScriptGamePresentTest 锁定）
        body.put("clue_id", "CL-99");
        String noClueRes = postJson("/api/script/present", body);
        JsonNode noClue = mapper.readTree(noClueRes);
        assertTrue(noClue.get("error").asText().contains("当前不是讨论阶段"), "阶段守卫优先于参数校验：" + noClueRes);
        assertEquals("investigation", noClue.get("phase").asText());
        body.put("clue_id", "c1");
        Map<String, Object> searchBody = new LinkedHashMap<>();
        searchBody.put("session_id", sid);
        searchBody.put("player", player);
        searchBody.put("player_key", key);
        searchBody.put("location", "客厅");
        String searchRes = postJson("/api/script/search", searchBody);
        JsonNode sRes = mapper.readTree(searchRes);
        assertTrue(sRes.get("clues") != null && sRes.get("clues").size() >= 1, "搜证得线索：" + searchRes);

        // ⑤ transfer_clue（既有端点）HTTP 层复验：investigation 阶段 c1（transferable=true）转交成功
        Map<String, Object> tfBody = new LinkedHashMap<>();
        tfBody.put("session_id", sid);
        tfBody.put("player", player);
        tfBody.put("target_player", "苏晚");
        tfBody.put("clue_id", "c1");
        tfBody.put("player_key", key);
        String tfRes = postJson("/api/script/transfer_clue", tfBody);
        JsonNode tf = mapper.readTree(tfRes);
        assertTrue(tf.has("result") && tf.get("result").asText().contains("转交给了"), "转交成功：" + tfRes);
        assertEquals("苏晚", tf.get("to").asText(), "接收方为苏晚");

        // ⑥ 讨论收束后（start_discussion → 讨论引擎自动跑完 → 自动进 VOTE）：
        //    present 在 VOTE 阶段拒绝（§3.2「投票后拒绝」守卫语义；
        //    present 成功路径（ok+system 行+幂等）由 service 层 ScriptGamePresentTest 锁定——
        //    HTTP 层 discussion 窗口受「讨论引擎自动收束」约束不可稳定观测）
        Map<String, Object> dsBody = new LinkedHashMap<>();
        dsBody.put("session_id", sid);
        dsBody.put("player", player);
        dsBody.put("player_key", key);
        postJson("/api/script/start_discussion", dsBody);
        // 讨论自动收束进 VOTE（mock LLM 极快）；等至非 discussion 态
        String phase = "";
        for (int i = 0; i < 30; i++) {
            String stRes = getJson("/api/script/status?player="
                    + java.net.URLEncoder.encode(player, java.nio.charset.StandardCharsets.UTF_8)
                    + "&player_key=" + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8));
            phase = mapper.readTree(stRes).path("phase").asText();
            if (!"discussion".equals(phase)) break;
            Thread.sleep(100);
        }
        body.put("clue_id", "c2"); // 公开线索（无需持有）
        String lateRes = postJson("/api/script/present", body);
        JsonNode late = mapper.readTree(lateRes);
        assertTrue(late.get("error").asText().contains("当前不是讨论阶段"), "投票后拒绝：" + lateRes);
        assertEquals("vote", late.get("phase").asText(), "收束后 phase=vote");
    }
}
