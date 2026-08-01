package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GAP-4b/4c/8 验收测试（蓝图 Step 4v + GAP-8）：
 * <ul>
 *   <li>A4-4：判定流程结束后 phase == ENDED（终态，后续调用不越界）</li>
 *   <li>A4-3：对局结束后 ScriptRepository.findAll() 非空（剧本 + 对局结果双落库）</li>
 *   <li>GAP-8：状态变更触发 script_* SSE 推送（script_phase/script_status/script_reveal）</li>
 * </ul>
 *
 * <p>单测直接构造 ScriptGameService（mock LLMClient），与 ScriptGameServiceTest 风格一致；
 * A4-3 用 @SpringBootTest + H2 mem + @MockBean LLMClient 验证真实落库。
 */
class ScriptGameEndedTest {

    private static final String SESSION = "test-script-ended";

    /** 默认剧本：3 角色（管家=真凶），truth 明示“凶手是管家”。 */
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
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    /** 找出扮演指定角色的玩家。 */
    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    /** 建局并推进到投票阶段。 */
    private ScriptGameService.ScriptGame toVote(ScriptGameService svc, List<String> players) {
        svc.initGame(SESSION, "庄园", players);
        svc.startVoting(SESSION);
        return svc.getGame(SESSION);
    }

    /** 投出多数票指向真凶（2 票真凶 + 真凶投别人）。 */
    private void castMurdererVotes(ScriptGameService svc, ScriptGameService.ScriptGame game) {
        String murderer = playerWithRole(game, "管家");
        List<String> others = game.players.stream().filter(p -> !p.equals(murderer)).toList();
        svc.castVote(SESSION, others.get(0), murderer);
        svc.castVote(SESSION, others.get(1), murderer);
        svc.castVote(SESSION, murderer, others.get(0));
    }

    /** 审批门（真实 ApprovalService）走后台线程揭晓，主线程批准放行。 */
    private Map<String, Object> resolveWithApproval(ScriptGameService svc, ApprovalService approval) throws Exception {
        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(SESSION));
        Thread.sleep(150);
        assertEquals("pending", approval.getStatus(SESSION), "揭晓应挂起等待 DM 审批");
        assertTrue(approval.approve(SESSION), "批准应成功");
        return fut.get(5, TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════════
    //  A4-4: ENDED 终态触达
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("A4-4: 判定流程结束后 confirmEnded → phase==ENDED 且为终态（后续调用不越界）")
    void endedReachedAfterRevealConfirmed() throws Exception {
        ApprovalService approval = new ApprovalService();
        ScriptGameService svc = new ScriptGameService(mockLlm(), approval);
        ScriptGameService.ScriptGame game = toVote(svc, List.of("Alice", "Bob", "Carol"));
        String murderer = playerWithRole(game, "管家");

        castMurdererVotes(svc, game);
        Map<String, Object> res = resolveWithApproval(svc, approval);

        // 揭晓后停 REVEAL（保留展示语义，既有 D7 验收）
        assertEquals(ScriptGameService.Phase.REVEAL, svc.getGame(SESSION).phase);
        assertEquals(murderer, res.get("murderer"));
        assertEquals(Boolean.TRUE, res.get("correct"));

        // 前端确认 → ENDED
        Map<String, Object> ended = svc.confirmEnded(SESSION);
        assertEquals("ended", ended.get("phase"), "A4-4: 确认后 phase 应为 ended");
        ScriptGameService.ScriptGame g = svc.getGame(SESSION);
        assertEquals(ScriptGameService.Phase.ENDED, g.phase, "A4-4: 判定流程结束后 phase == ENDED");
        assertEquals(murderer, g.winner);
        assertEquals(murderer, g.murderer);
        assertTrue(g.correctVerdict);

        // 终态：之后调用不越界
        assertTrue(svc.search(SESSION, "Alice", "客厅").toString().contains("当前不是搜证阶段"), "ENDED 后 search 应拒绝");
        assertTrue(svc.castVote(SESSION, "Alice", "Bob").contains("当前不是投票阶段"), "ENDED 后投票应拒绝");
        assertTrue(svc.resolveVote(SESSION).toString().contains("当前不是投票阶段"), "ENDED 后揭晓应拒绝");
        assertFalse(svc.startDiscussion(SESSION), "ENDED 后进入讨论应失败");
        svc.startVoting(SESSION); // no-op，phase 不变
        assertEquals(ScriptGameService.Phase.ENDED, svc.getGame(SESSION).phase);
        // 重复确认幂等
        Map<String, Object> again = svc.confirmEnded(SESSION);
        assertEquals("ended", again.get("phase"), "重复 confirmEnded 应幂等返回 ended");
        assertEquals(ScriptGameService.Phase.ENDED, svc.getGame(SESSION).phase);
    }

    @Test
    @DisplayName("A4-4 边界: 非 REVEAL 阶段确认结束被拒绝")
    void confirmEndedRejectedOutsideReveal() {
        ScriptGameService svc = new ScriptGameService(mockLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        // INVESTIGATION 阶段不能直接收尾
        Map<String, Object> res = svc.confirmEnded(SESSION);
        assertTrue(res.containsKey("error"), "非 REVEAL 阶段确认结束应被拒绝");
        assertEquals(ScriptGameService.Phase.INVESTIGATION, svc.getGame(SESSION).phase);
    }

    // ═══════════════════════════════════════════════════════════
    //  GAP-8: script SSE 推送 + GAP-4c: 落库调用点
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("GAP-8+A4-4: 状态变更触发 script_phase/script_status/script_reveal；confirmEnded 触发落库")
    void sseBroadcastAndPersistWired() throws Exception {
        SSEController sse = mock(SSEController.class);
        DatabaseService db = mock(DatabaseService.class);
        ApprovalService approval = mock(ApprovalService.class);
        // 审批自动通过（直接返回原 round），resolveVote 同步返回
        when(approval.submitForApproval(any(), anyString(), anyLong()))
            .thenAnswer(inv -> inv.getArgument(0));

        ScriptGameService svc = new ScriptGameService(mockLlm(), approval, db, sse);
        ScriptGameService.ScriptGame game = toVote(svc, List.of("Alice", "Bob", "Carol"));
        String murderer = playerWithRole(game, "管家");
        castMurdererVotes(svc, game);

        Map<String, Object> res = svc.resolveVote(SESSION);
        assertEquals(Boolean.TRUE, res.get("correct"));
        assertEquals(ScriptGameService.Phase.REVEAL, svc.getGame(SESSION).phase);

        svc.confirmEnded(SESSION);
        assertEquals(ScriptGameService.Phase.ENDED, svc.getGame(SESSION).phase);

        // GAP-8: 阶段推送链 investigation → vote → reveal → ended
        verify(sse).broadcastScriptPhase(SESSION, "investigation");
        verify(sse).broadcastScriptPhase(SESSION, "vote");
        verify(sse).broadcastScriptPhase(SESSION, "reveal");
        verify(sse).broadcastScriptPhase(SESSION, "ended");
        // GAP-8: 揭晓结果推送 + 状态推送（init 一次 + ended 一次）
        verify(sse).broadcastScriptReveal(eq(SESSION), anyMap());
        verify(sse, atLeast(2)).broadcastScriptStatus(eq(SESSION), anyMap());
        // GAP-4c: 剧本 + 对局结果两次落库
        verify(db, atLeast(2)).saveScript(anyString(), anyMap());
    }
}
