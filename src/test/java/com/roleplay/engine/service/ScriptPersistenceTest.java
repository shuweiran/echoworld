package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.db.repository.ScriptRepository;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A4-3 验收（蓝图 Step 4v）：对局结束后剧本真实落库（H2 mem + 真实 ScriptRepository）。
 *
 * <p>@SpringBootTest + @MockBean LLMClient（与 LongTextStabilityTest 同模式）：
 * ScriptGameService 走 Spring 注入路径（真实 DatabaseService + SSEController），
 * 验证 initGame 落剧本 + confirmEnded 落对局结果 → findAll() 非空且内容完整。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptPersistenceTest {

    private static final String SESSION = "test-script-persist";

    @Autowired
    private ScriptGameService svc;

    @Autowired
    private ApprovalService approval;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private ScriptRepository scriptRepository;

    @MockBean
    private LLMClient llmClient;

    private void mockLlm() {
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
        when(llmClient.callJson(anyString(), anyInt())).thenReturn(script);
    }

    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    @Test
    @DisplayName("A4-3: 对局结束后 ScriptRepository.findAll() 非空，且结果含玩家/真凶/票型/真相")
    void scriptPersistedAfterGameEnds() throws Exception {
        mockLlm();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String murderer = playerWithRole(game, "管家");
        List<String> others = game.players.stream().filter(p -> !p.equals(murderer)).toList();

        svc.startVoting(SESSION);
        svc.castVote(SESSION, others.get(0), murderer);
        svc.castVote(SESSION, others.get(1), murderer);
        svc.castVote(SESSION, murderer, others.get(0));

        // 审批门（Spring 上下文默认 enabled=true）→ 后台揭晓 + 批准
        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(SESSION));
        Thread.sleep(150);
        assertEquals("pending", approval.getStatus(SESSION));
        assertTrue(approval.approve(SESSION));
        Map<String, Object> res = fut.get(5, TimeUnit.SECONDS);
        assertEquals(Boolean.TRUE, res.get("correct"));

        // initGame 落库已发生
        assertFalse(scriptRepository.findAll().isEmpty(), "initGame 后剧本应已落库");

        svc.confirmEnded(SESSION);
        assertEquals(ScriptGameService.Phase.ENDED, svc.getGame(SESSION).phase);

        // A4-3 验收：对局结束后 findAll 非空
        List<Map<String, Object>> scripts = databaseService.getAllScripts();
        assertFalse(scripts.isEmpty(), "A4-3: 对局结束后 ScriptRepository.findAll() 应非空");

        // 剧本条目（type=script）与对局结果条目（type=result）都存在
        // 注：全量测试共享同一 H2 内存库（DB_CLOSE_DELAY=-1），其他 @SpringBootTest 类（如批次 C3 ScriptGameResumeTest）
        // 也会落 result 行 —— 按本对局 session_id 过滤取自己的结果行（此前 findFirst 依赖“唯一 result 行”假设）。
        Map<String, Object> resultContent = scripts.stream()
            .map(s -> (Map<String, Object>) s.get("content"))
            .filter(c -> SESSION.equals(c.get("session_id")) && "result".equals(c.get("type")))
            .findFirst().orElse(null);
        assertNotNull(resultContent, "应存在 type=result 的对局结果记录");
        assertEquals(3, ((List<?>) resultContent.get("players")).size(), "结果应含全部玩家");
        assertEquals(murderer, resultContent.get("killer"), "结果应记录真凶玩家名");
        assertEquals(Boolean.TRUE, resultContent.get("correct"), "结果应记录判定是否正确");
        assertEquals(3, ((Map<?, ?>) resultContent.get("votes")).size(), "结果应含完整票型");
        assertTrue(((String) resultContent.get("truth")).contains("管家"), "结果应含真相文案");

        boolean scriptEntry = scripts.stream()
            .map(s -> (Map<String, Object>) s.get("content"))
            .anyMatch(c -> "script".equals(c.get("type")));
        assertTrue(scriptEntry, "initGame 落库的剧本条目应存在");
    }
}
