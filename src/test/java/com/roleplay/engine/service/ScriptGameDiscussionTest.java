package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GAP-3 验收测试（蓝图 Step 3v）：剧本杀 DISCUSSION 阶段接对话引擎。
 *
 * <p>覆盖验收标准：
 * <ul>
 *   <li>A3-1：startDiscussion 后产生讨论组且 phase==DISCUSSION</li>
 *   <li>A3-2：持秘密角色讨论上下文不含秘密明文（WEAK 摘要）</li>
 *   <li>A3-3：讨论结束可正常进入 VOTE</li>
 *   <li>A3-4：WorldDirectorService.getGoal 对持秘密角色返回注入目标</li>
 * </ul>
 *
 * <p>直接构造 ScriptGameService（mock LLMClient + 真实 ApprovalService），与
 * ScriptGameServiceTest 风格一致。讨论轮次在后台虚拟线程驱动（mock callSync 带
 * 50ms 延迟保证 A3-1 断言窗口），结束自动进 VOTE。
 */
class ScriptGameDiscussionTest {

    private static final String SESSION = "test-script-discussion";

    private static final String SECRET_TEXT = "管家秘密：我偷走了保险箱里的遗嘱";
    private static final String SAMPLE_LINE = "我认为凶手就在我们中间【情绪：平静】";

    /**
     * 剧本：管家持有秘密（真凶，走 WEAK），女仆/园丁无秘密（走 MERGED）。
     * callJson → 剧本；callSync → 讨论发言（50ms/次，保证 A3-1 断言窗口）。
     */
    private LLMClient mockLlm() {
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
        script.put("secrets", Map.of("管家", SECRET_TEXT));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenAnswer(inv -> {
            Thread.sleep(50);
            return SAMPLE_LINE;
        });
        return llm;
    }

    private ScriptGameService newService() {
        return new ScriptGameService(mockLlm(), new ApprovalService());
    }

    /** 找出扮演指定角色的玩家。 */
    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    /** 轮询等待讨论结束（phase==VOTE 且发言记录落盘），超时 10s 判失败。 */
    private void awaitDiscussionFinished(ScriptGameService svc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ScriptGameService.ScriptGame g = svc.getGame(SESSION);
            if (g != null && g.phase == ScriptGameService.Phase.VOTE
                    && !g.discussionActive && !g.discussionTranscript.isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        ScriptGameService.ScriptGame g = svc.getGame(SESSION);
        fail("讨论未在超时内结束进入 VOTE: phase=" + (g == null ? "null" : g.phase)
                + " active=" + (g == null ? "null" : g.discussionActive)
                + " turns=" + (g == null ? 0 : g.discussionTranscript.size()));
    }

    @Test
    @DisplayName("A3-1: startDiscussion 后产生讨论组且 phase==DISCUSSION")
    void createsDiscussionGroupAndEntersDiscussion() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        assertEquals(ScriptGameService.Phase.INVESTIGATION, svc.getGame(SESSION).phase);

        assertTrue(svc.startDiscussion(SESSION), "INVESTIGATION 应可进入讨论");

        ScriptGameService.ScriptGame g = svc.getGame(SESSION);
        assertEquals(ScriptGameService.Phase.DISCUSSION, g.phase, "A3-1: 建组后 phase 应为 DISCUSSION");
        assertTrue(g.discussionActive, "A3-1: 讨论组应已建立且进行中");
        assertTrue(svc.isDiscussionRunning(SESSION), "A3-1: isDiscussionRunning 应为 true");
        assertEquals(2, g.round, "讨论轮次应从第 2 轮开始（round++）");
    }

    @Test
    @DisplayName("A3-2: 持秘密角色讨论上下文不含秘密明文（WEAK 摘要），未持秘密角色为 MERGED 全文")
    void secretContextDoesNotContainSecretPlaintext() throws Exception {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String secretPlayer = playerWithRole(game, "管家");
        String mergedPlayer = playerWithRole(game, "女仆");
        assertFalse(secretPlayer.isEmpty());
        assertFalse(mergedPlayer.isEmpty());

        svc.startDiscussion(SESSION);
        awaitDiscussionFinished(svc);

        ScriptGameService.ScriptGame g = svc.getGame(SESSION);
        String secretCtx = g.discussionContexts.get(secretPlayer);
        String mergedCtx = g.discussionContexts.get(mergedPlayer);

        assertNotNull(secretCtx, "持秘密角色应产生讨论上下文");
        assertNotNull(mergedCtx, "未持秘密角色应产生讨论上下文");

        // 秘密明文绝不进入任何讨论上下文
        assertFalse(secretCtx.contains(SECRET_TEXT), "WEAK 上下文不得含秘密明文");
        assertFalse(secretCtx.contains("保险箱"), "WEAK 上下文不得含秘密细节");
        assertFalse(secretCtx.contains("遗嘱"), "WEAK 上下文不得含秘密细节");
        assertFalse(mergedCtx.contains(SECRET_TEXT), "MERGED 上下文同样不得含他人秘密明文");

        // WEAK 只给摘要不给全文：持秘密角色看不到其他成员的具体发言
        assertFalse(secretCtx.contains("我认为凶手就在我们中间"), "WEAK 只给摘要，不得含发言全文");
        // MERGED 拿到全文：未持秘密角色能看到讨论发言
        assertTrue(mergedCtx.contains("我认为凶手就在我们中间"), "MERGED 应拿到讨论全文");
    }

    @Test
    @DisplayName("A3-3: 讨论结束自动进入 VOTE 且发言记录落盘")
    void discussionEndsIntoVote() throws Exception {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));

        svc.startDiscussion(SESSION);
        awaitDiscussionFinished(svc);

        ScriptGameService.ScriptGame g = svc.getGame(SESSION);
        assertEquals(ScriptGameService.Phase.VOTE, g.phase, "A3-3: 讨论结束应自动进入 VOTE");
        assertFalse(g.discussionActive, "A3-3: 讨论结束后 discussionActive 应为 false");
        assertFalse(g.discussionTranscript.isEmpty(), "A3-3: 讨论发言记录应非空");
        assertEquals(g.discussionTranscript.size(), svc.getDiscussionTranscript(SESSION).size());
        // 讨论产物通过 toMap 暴露（前端可消费）
        assertTrue(g.toMap("Alice").containsKey("discussion"), "toMap 应暴露讨论记录");
    }

    @Test
    @DisplayName("A3-4: WorldDirectorService.getGoal 对持秘密角色返回注入目标（隐藏秘密），未持秘密为查明真相")
    void secretHolderGetsInjectedGoal() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String secretPlayer = playerWithRole(game, "管家");
        String mergedPlayer = playerWithRole(game, "女仆");

        svc.startDiscussion(SESSION);

        assertEquals(ScriptGameService.GOAL_HIDE_SECRET, svc.getDiscussionGoal(SESSION, secretPlayer),
                "A3-4: 持秘密角色应注入隐藏秘密目标");
        assertEquals(ScriptGameService.GOAL_FIND_TRUTH, svc.getDiscussionGoal(SESSION, mergedPlayer),
                "A3-4: 未持秘密角色应注入查明真相目标");
    }
}
