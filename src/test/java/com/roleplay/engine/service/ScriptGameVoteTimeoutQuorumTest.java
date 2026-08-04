package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1（剧本杀可玩性修复，任务 1）：投票超时 + quorum 门槛。
 *
 * <p>直接构造 ScriptGameService（mock LLM + 审批自动通过 mock），建局后直达投票
 * （initGame → startVoting，不走讨论引擎——避免后台讨论线程的 enterVotePhase 重置计时基准，
 * 保证超时判定确定性）。
 *
 * <p>覆盖：① 投票超时 → 未投票玩家按弃票处理并转为托管（票作废、提示携带名单）；
 * ② quorum 不足 → 清票重投一轮（留在 VOTE、不清 winner）；③ 重投仍不足 → 按已投计 +
 * 低参与度判定；④ quorum 满足 → 正常揭晓（回归守卫）；⑤ 平票优先于 quorum（D6 既有语义零破坏）；
 * ⑥ 超时开关关闭 → 旧行为（可无限等、无弃票/托管）；⑦ quorum 开关关闭 → 少数票即可定局（旧行为）。
 */
class ScriptGameVoteTimeoutQuorumTest {

    private static final String SESSION = "vote-p1-session";

    /** 默认剧本：3 角色（管家=真凶），truth 明示"凶手是管家"。 */
    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false, "related_role", "管家")));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    /** 审批自动通过（submitForApproval 直接返回原 round）—— resolveVote 同步返回，测试不阻塞。 */
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

    private ScriptGameService newService(long voteTimeoutMs) {
        ScriptGameService svc = new ScriptGameService(mockLlm(), autoApprove());
        svc.setVoteTimeoutMs(voteTimeoutMs);
        return svc;
    }

    /** 建局并直达投票阶段（不走讨论引擎，计时基准确定）。 */
    private void toVote(ScriptGameService svc, List<String> players) {
        svc.initGame(SESSION, "庄园", players);
        svc.startVoting(SESSION);
    }

    @Test
    @DisplayName("P1-1 投票超时：超时后未投票玩家按弃票处理并转为托管（票作废、提示携带名单）")
    void voteTimeoutAbstainsAndTrusteesNonVoters() throws Exception {
        ScriptGameService svc = newService(50);
        toVote(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        svc.castVote(SESSION, "Alice", "Bob");
        Thread.sleep(120); // 超过 50ms 投票窗口

        Map<String, Object> res = svc.resolveVote(SESSION);

        assertEquals(Boolean.TRUE, res.get("vote_timeout"), "应标记投票超时");
        @SuppressWarnings("unchecked")
        List<String> abstained = (List<String>) res.get("abstained");
        assertNotNull(abstained, "应携带弃票名单");
        assertTrue(abstained.contains("Bob") && abstained.contains("Carol"),
                "未投票的 Bob/Carol 应被弃票: " + abstained);
        assertTrue(String.valueOf(res.get("result")).contains("按弃票处理"),
                "应提示按弃票处理: " + res.get("result"));
        assertTrue(game.trustees.contains("Bob") && game.trustees.contains("Carol"),
                "超时无操作玩家应转为托管（AI 代管）");
        assertFalse(game.trustees.contains("Alice"), "已投票玩家不应托管");
        // 在线=1（Bob/Carol 托管），quorum=ceil(1/2)=1；Alice 1 票满足 → 正常揭晓（自动审批）
        // （托管玩家的票作废语义由 L-1 退出托管用例覆盖：castVote 拒绝 + votes 移除）
        assertEquals(ScriptGameService.Phase.REVEAL, game.phase, "超时+弃票后有效票满足 quorum 应正常揭晓");
        assertEquals("Bob", res.get("most_voted"));
    }

    @Test
    @DisplayName("P1-2 quorum 不足：首次清票重投一轮（提示人数不足），留在 VOTE、不清 winner")
    void quorumFailClearsAndRevotes() {
        ScriptGameService svc = newService(60000); // 60s 超时窗口，不触发超时
        toVote(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // 3 人局仅 1 票（quorum=ceil(3/2)=2）→ 不足 → 清票重投
        svc.castVote(SESSION, "Alice", "Bob");
        Map<String, Object> res = svc.resolveVote(SESSION);

        assertEquals(Boolean.TRUE, res.get("quorum_fail"), "应标记 quorum 不足");
        assertEquals(Boolean.TRUE, res.get("revote"));
        assertTrue(String.valueOf(res.get("result")).contains("人数不足"), "应提示人数不足: " + res.get("result"));
        assertEquals(ScriptGameService.Phase.VOTE, game.phase, "首次不足应留在 VOTE 清票重投");
        assertTrue(game.votes.isEmpty(), "首次不足应清票");
        assertEquals(1, game.quorumFailCount, "重投计数应置 1");
        assertEquals("", game.winner, "不得误设 winner");
        assertEquals(Integer.valueOf(3), res.get("online_players"));
        assertEquals(Integer.valueOf(2), res.get("quorum"));
    }

    @Test
    @DisplayName("P1-3 重投仍不足：按已投计并标记低参与度判定，正常揭晓")
    void quorumFailTwiceProceedsWithLowParticipation() {
        ScriptGameService svc = newService(60000);
        toVote(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        svc.castVote(SESSION, "Alice", "Bob");
        Map<String, Object> r1 = svc.resolveVote(SESSION);
        assertEquals(Boolean.TRUE, r1.get("quorum_fail"));

        // 重投一轮：仍只有 1 票 → 按已投计 + 低参与度判定
        svc.castVote(SESSION, "Alice", "Bob");
        Map<String, Object> r2 = svc.resolveVote(SESSION);

        assertEquals(Boolean.TRUE, r2.get("low_participation"), "重投仍不足应标记低参与度判定");
        assertTrue(String.valueOf(r2.get("result")).contains("低参与度判定"), "揭晓文案应含低参与度提示");
        assertTrue(game.lowParticipation, "对局低参与度标记应落库（随快照）");
        assertEquals(ScriptGameService.Phase.REVEAL, game.phase, "重投仍不足应按已投计正常揭晓");
        assertEquals("Bob", r2.get("most_voted"));
    }

    @Test
    @DisplayName("P1-4 quorum 满足：正常揭晓（回归守卫，不影响既有判定链路）")
    void quorumSatisfiedProceeds() {
        ScriptGameService svc = newService(60000);
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Carol", "Bob");
        Map<String, Object> res = svc.resolveVote(SESSION);

        assertNull(res.get("quorum_fail"), "2/3 票满足 quorum=2 不应清票");
        assertEquals(ScriptGameService.Phase.REVEAL, svc.getGame(SESSION).phase);
        assertEquals("Bob", res.get("most_voted"));
        assertEquals(Integer.valueOf(2), res.get("vote_count"));
        assertEquals(Integer.valueOf(3), res.get("online_players"));
        assertEquals(Integer.valueOf(2), res.get("quorum"));
    }

    @Test
    @DisplayName("P1-5 平票优先于 quorum：清票重投语义零破坏（D6 既有行为）")
    void tieStillClearsBeforeQuorum() {
        ScriptGameService svc = newService(60000);
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");
        Map<String, Object> res = svc.resolveVote(SESSION);

        assertEquals(Boolean.TRUE, res.get("tie"), "平票语义保留");
        assertEquals(Boolean.TRUE, res.get("revote"));
        assertNull(res.get("quorum_fail"), "平票清票重投，不叠加 quorum 分支");
        assertTrue(svc.getGame(SESSION).votes.isEmpty());
        assertEquals(ScriptGameService.Phase.VOTE, svc.getGame(SESSION).phase);
    }

    @Test
    @DisplayName("P1-6 超时开关关闭：旧行为（可无限等，无弃票/托管）")
    void timeoutDisabledKeepsOldBehavior() throws Exception {
        ScriptGameService svc = newService(50);
        svc.setVoteTimeoutEnabled(false);
        toVote(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        svc.castVote(SESSION, "Alice", "Bob");
        Thread.sleep(120); // 即使超过窗口也不触发
        Map<String, Object> res = svc.resolveVote(SESSION);

        assertNull(res.get("vote_timeout"), "超时开关关闭不应触发超时弃票");
        assertTrue(game.trustees.isEmpty(), "开关关闭不应产生托管");
        assertEquals(Boolean.TRUE, res.get("quorum_fail"), "quorum 为独立开关，仍应生效");
    }

    @Test
    @DisplayName("P1-7 quorum 开关关闭：少数票即可定局（旧行为）")
    void quorumDisabledAllowsMinorityVerdict() {
        ScriptGameService svc = newService(60000);
        svc.setQuorumEnabled(false);
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        svc.castVote(SESSION, "Alice", "Bob");
        Map<String, Object> res = svc.resolveVote(SESSION);

        assertNull(res.get("quorum_fail"), "quorum 关闭后 1 票即可定局");
        assertEquals(ScriptGameService.Phase.REVEAL, svc.getGame(SESSION).phase);
        assertEquals("Bob", res.get("most_voted"));
    }
}
