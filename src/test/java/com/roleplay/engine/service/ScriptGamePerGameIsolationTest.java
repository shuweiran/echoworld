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
 * P-0802-J：剧本杀讨论引擎 per-game 隔离验收测试。
 *
 * <p>覆盖 D-012 已知限制「讨论引擎为 service 实例级共享（多局并发讨论会互覆世界）」的剧本杀侧修复：
 * 同一 ScriptGameService 实例上两局并发讨论，各自独立 SimulationWorld / ConversationManager /
 * WorldDirectorService（对齐狼人杀侧 P-0802-I 三 Map 模式），发言记录/人类发言/目标注入互不串扰。
 *
 * <p>直接构造 ScriptGameService（mock LLMClient + 真实 ApprovalService），讨论轮次在后台虚拟线程驱动
 * （mock callSync 带 50ms 延迟，与 ScriptGameDiscussionTest 同款）。
 */
class ScriptGamePerGameIsolationTest {

    private static final String SESSION_A = "iso-session-a";
    private static final String SESSION_B = "iso-session-b";
    private static final String SECRET_TEXT = "管家秘密：我偷走了保险箱里的遗嘱";
    private static final String SAMPLE_LINE = "我认为凶手就在我们中间【情绪：平静】";

    /** 剧本：管家持有秘密（真凶，走 WEAK），其余两人无秘密（走 MERGED）。 */
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

    /** 轮询等待指定对局讨论结束（phase==VOTE 且发言记录落盘），超时 10s 判失败。 */
    private void awaitDiscussionFinished(ScriptGameService svc, String sid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ScriptGameService.ScriptGame g = svc.getGame(sid);
            if (g != null && g.phase == ScriptGameService.Phase.VOTE
                    && !g.discussionActive && !g.discussionTranscript.isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        ScriptGameService.ScriptGame g = svc.getGame(sid);
        fail("对局 " + sid + " 讨论未在超时内结束进入 VOTE: phase=" + (g == null ? "null" : g.phase)
                + " active=" + (g == null ? "null" : g.discussionActive)
                + " turns=" + (g == null ? 0 : g.discussionTranscript.size()));
    }

    @Test
    @DisplayName("I-1: 同一 service 两局并发讨论 —— 引擎/世界/导演实例按对局隔离（互不覆盖）")
    void perGameDiscussionEnginesAreDistinctInstances() throws Exception {
        ScriptGameService svc = newService();
        svc.initGame(SESSION_A, "庄园A", List.of("Alice", "Bob", "Carol"));
        svc.initGame(SESSION_B, "庄园B", List.of("David", "Eve", "Frank"));

        assertTrue(svc.startDiscussion(SESSION_A), "A 局应可进入讨论");
        assertTrue(svc.startDiscussion(SESSION_B), "B 局应可进入讨论（并发不阻塞 A 局）");

        // 三件套实例均按对局隔离（同一 service 实例上不同 session 不同引擎）
        assertNotSame(svc.getDiscussionConversation(SESSION_A), svc.getDiscussionConversation(SESSION_B),
                "ConversationManager 按对局隔离");
        assertNotSame(svc.getDiscussionWorld(SESSION_A), svc.getDiscussionWorld(SESSION_B),
                "SimulationWorld 按对局隔离");
        assertNotSame(svc.getDiscussionDirector(SESSION_A), svc.getDiscussionDirector(SESSION_B),
                "WorldDirectorService 按对局隔离");
    }

    @Test
    @DisplayName("I-2: 两局并发讨论 —— 发言记录只含本局成员，A 局人类发言不串入 B 局，目标互不串扰")
    void discussionsDoNotCrossContaminate() throws Exception {
        ScriptGameService svc = newService();
        svc.initGame(SESSION_A, "庄园A", List.of("Alice", "Bob", "Carol"));
        svc.initGame(SESSION_B, "庄园B", List.of("David", "Eve", "Frank"));

        svc.startDiscussion(SESSION_A);
        svc.startDiscussion(SESSION_B);

        // A 局人类发言只进 A 局讨论流
        Map<String, Object> say = svc.discussionSay(SESSION_A, "Alice", "我认为凶手就是 Bob", false);
        assertEquals(Boolean.TRUE, say.get("ok"), "A 局人类发言应成功入队");

        awaitDiscussionFinished(svc, SESSION_A);
        awaitDiscussionFinished(svc, SESSION_B);

        // 发言记录只含本局成员
        List<String> playersA = List.of("Alice", "Bob", "Carol");
        List<String> playersB = List.of("David", "Eve", "Frank");
        for (Map<String, String> turn : svc.getDiscussionTranscript(SESSION_A)) {
            assertTrue(playersA.contains(turn.get("speaker")),
                    "A 局发言者必须是 A 局成员，实际: " + turn.get("speaker"));
        }
        for (Map<String, String> turn : svc.getDiscussionTranscript(SESSION_B)) {
            assertTrue(playersB.contains(turn.get("speaker")),
                    "B 局发言者必须是 B 局成员，实际: " + turn.get("speaker"));
        }

        // A 局人类发言出现在 A 局记录（经门控排空或收尾排空，二者其一必入）
        boolean humanSayInA = svc.getDiscussionTranscript(SESSION_A).stream()
                .anyMatch(t -> "Alice".equals(t.get("speaker"))
                        && t.get("message") != null && t.get("message").contains("我认为凶手就是 Bob"));
        assertTrue(humanSayInA, "A 局人类发言应在 A 局讨论记录中");

        // A 局发言不串入 B 局记录
        assertFalse(svc.getDiscussionTranscript(SESSION_B).stream()
                        .anyMatch(t -> playersA.contains(t.get("speaker"))),
                "A 局发言不得串入 B 局讨论记录");

        // 目标注入按局隔离：A 局持秘密角色目标=隐藏秘密；B 局导演对 A 局玩家无目标（导演独立）
        ScriptGameService.ScriptGame gameA = svc.getGame(SESSION_A);
        String secretPlayerA = playerWithRole(gameA, "管家");
        assertFalse(secretPlayerA.isEmpty(), "A 局应存在持秘密的管家角色");
        assertEquals(ScriptGameService.GOAL_HIDE_SECRET, svc.getDiscussionGoal(SESSION_A, secretPlayerA),
                "A 局持秘密角色目标=隐藏秘密");
        assertEquals("", svc.getDiscussionGoal(SESSION_B, secretPlayerA),
                "B 局导演不含 A 局玩家目标（导演实例独立）");
    }
}
