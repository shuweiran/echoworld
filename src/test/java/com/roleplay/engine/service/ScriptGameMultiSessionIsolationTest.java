package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P-0819-N：多人/并发对局的令牌、投票和终局状态必须互相隔离。 */
class ScriptGameMultiSessionIsolationTest {

    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "并发宅邸");
        script.put("background", "两局同时进行的测试剧本。");
        script.put("truth", "凶手是 Bob。");
        script.put("roles", List.of("Alice", "Bob", "Carol"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(Map.of("id", "c1", "location", "客厅", "content", "指纹")));
        script.put("secrets", Map.of("Alice", "你看到了争吵", "Bob", "你是凶手", "Carol", "你欠债"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    private ApprovalService autoApprove() {
        ApprovalService approval = mock(ApprovalService.class);
        try {
            when(approval.submitForApproval(any(), anyString(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return approval;
    }

    @Test
    @DisplayName("P-0819-N-1 两局并发：角色令牌、投票和状态完全隔离")
    void concurrentSessionsKeepPlayerKeysAndVotesIsolated() {
        ScriptGameService service = new ScriptGameService(mockLlm(), autoApprove());
        service.initGame("room-A", "并发宅邸 A", List.of("Alice", "Bob", "Carol"));
        service.initGame("room-B", "并发宅邸 B", List.of("Alice", "Bob", "Carol"));

        String keyA = service.getPlayerKeys("room-A").get("Alice");
        String keyB = service.getPlayerKeys("room-B").get("Alice");
        assertNotNull(keyA);
        assertNotNull(keyB);
        assertNotEquals(keyA, keyB, "不同对局不能复用玩家令牌");
        assertEquals("room-A", service.findSessionByPlayerKey(keyA));
        assertEquals("room-B", service.findSessionByPlayerKey(keyB));

        service.startVoting("room-A");
        service.startVoting("room-B");
        service.castVote("room-A", "Alice", "Bob");

        assertEquals(Map.of("Alice", "Bob"), service.getGame("room-A").votes);
        assertTrue(service.getGame("room-B").votes.isEmpty(), "A 局投票不能污染 B 局");
        assertEquals("room-B", service.getGame("room-B").toMap("Alice").get("session_id"));
        assertEquals("room-A", service.getGame("room-A").toMap("Alice").get("session_id"));
    }

    @Test
    @DisplayName("P-0819-N-2 玩家视图：同名玩家在不同对局只看到各自剧本")
    void sameNamedPlayersResolveToTheirOwnSessionView() {
        ScriptGameService service = new ScriptGameService(mockLlm(), autoApprove());
        service.initGame("room-A", "主题 A", List.of("Alice", "Bob", "Carol"));
        service.initGame("room-B", "主题 B", List.of("Alice", "Bob", "Carol"));

        Map<String, Object> viewA = service.getGame("room-A").toMap("Alice");
        Map<String, Object> viewB = service.getGame("room-B").toMap("Alice");
        assertEquals("room-A", viewA.get("session_id"));
        assertEquals("room-B", viewB.get("session_id"));
        assertNotEquals(viewA.get("role_key"), viewB.get("role_key"));
        assertNotNull(viewA.get("name"));
        assertNotNull(viewB.get("name"));
    }
}
