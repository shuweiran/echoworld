package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1（剧本杀可玩性修复，任务 2/3）：玩家退出托管 / ENDED 后重开 / LLM 降级标记。
 *
 * <p>直接构造 ScriptGameService（mock LLM + 审批自动通过 mock）。
 *
 * <p>覆盖：① leaveGame → 托管标记 + 已投票作废 + castVote 拒绝（AI 代管但标记清楚）；
 * ② quorum 在线数排除托管玩家；③ 退出拒绝路径（非本局玩家 / ENDED / 身份校验）；
 * ④ restartGame 仅 ENDED 可重开，非终态拒绝；⑤ restart 新局全新状态（同玩家/同主题/同模式、
 * 票/托管/roleKey 全重置、复用 sessionId）；⑥ llm_degraded：LLM 空输出 → true（离线模板模式），
 * LLM 正常 → false；⑦ 快照落库含托管/降级/主题/投票计时键。
 */
class ScriptGameLeaveRestartTest {

    private static final String SESSION = "lifecycle-p1-session";

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

    /** LLM 空输出（无 key/LLM 失败）→ generateScript 走 defaultScript 兜底（降级场景）。 */
    private LLMClient emptyLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(null);
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

    /** 找出扮演指定角色的玩家。 */
    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    @Test
    @DisplayName("L-1 退出托管：角色标记托管、已投票作废、再投票被拒（AI 代管但标记清楚）")
    void leaveMarksTrusteeAndVoidsVote() {
        ScriptGameService svc = new ScriptGameService(mockLlm(), autoApprove());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.startVoting(SESSION);
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        svc.castVote(SESSION, "Alice", "Bob");
        Map<String, Object> left = svc.leaveGame(SESSION, "Alice", "");

        assertEquals(Boolean.TRUE, left.get("trusted"), "退出应返回托管标记");
        assertTrue(String.valueOf(left.get("result")).contains("托管"), "应提示转为托管");
        assertTrue(game.trustees.contains("Alice"), "退出玩家应标记托管");
        assertFalse(game.votes.containsKey("Alice"), "托管玩家的已投票应作废");
        assertTrue(svc.castVote(SESSION, "Alice", "Bob").contains("托管"), "托管玩家投票被拒");
        // toMap 暴露托管列表（前端 🤖 标记数据源）
        @SuppressWarnings("unchecked")
        List<String> trustees = (List<String>) game.toMap("Alice").get("trustees");
        assertTrue(trustees.contains("Alice"), "toMap 应暴露托管列表");
    }

    @Test
    @DisplayName("L-2 quorum 在线数排除托管玩家：2 人托管后 1 票即满足门槛")
    void quorumExcludesTrustees() {
        ScriptGameService svc = new ScriptGameService(mockLlm(), autoApprove());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.startVoting(SESSION);
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        svc.leaveGame(SESSION, "Bob", "");
        svc.leaveGame(SESSION, "Carol", "");
        // 在线=Alice 1 人，quorum=ceil(1/2)=1；Alice 1 票即满足 → 正常揭晓
        svc.castVote(SESSION, "Alice", "Bob");
        Map<String, Object> res = svc.resolveVote(SESSION);

        assertNull(res.get("quorum_fail"), "托管不计入在线数，1 票应满足 quorum");
        assertEquals(Integer.valueOf(1), res.get("online_players"), "在线数应排除托管玩家");
        assertEquals(Integer.valueOf(1), res.get("quorum"));
        assertEquals(ScriptGameService.Phase.REVEAL, game.phase);
    }

    @Test
    @DisplayName("L-3 退出拒绝路径：非本局玩家 / 身份校验失败 / ENDED 后")
    void leaveRejectedWhenNotInGameOrEnded() {
        ScriptGameService svc = new ScriptGameService(mockLlm(), autoApprove());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // 非本局玩家
        assertTrue(svc.leaveGame(SESSION, "路人甲", "").get("error").toString().contains("不在本局"),
                "非本局玩家退出应拒绝");
        // 身份校验：错误 player_key 拒绝
        String aliceKey = svc.getRoleKey(SESSION, "Alice");
        Map<String, Object> denied = svc.leaveGame(SESSION, "Alice", "wrong-key");
        assertTrue(denied.get("error").toString().contains("身份校验失败"), "错误 key 应拒绝: " + denied);

        // 推进到 ENDED
        svc.startVoting(SESSION);
        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");
        svc.castVote(SESSION, "Carol", "Bob");
        svc.resolveVote(SESSION);
        assertEquals(ScriptGameService.Phase.REVEAL, game.phase);
        svc.confirmEnded(SESSION);
        assertEquals(ScriptGameService.Phase.ENDED, game.phase);

        Map<String, Object> after = svc.leaveGame(SESSION, "Alice", aliceKey);
        assertTrue(after.get("error").toString().contains("已结束"), "ENDED 后退出应拒绝: " + after);
    }

    @Test
    @DisplayName("R-1 restart 仅 ENDED 可重开：非终态拒绝且不影响原对局")
    void restartOnlyAfterEndedRejected() {
        ScriptGameService svc = new ScriptGameService(mockLlm(), autoApprove());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));

        Map<String, Object> res = svc.restartGame(SESSION);
        assertTrue(res.get("error").toString().contains("仅已结束"), "非 ENDED 重开应拒绝: " + res);
        assertEquals(ScriptGameService.Phase.INVESTIGATION, svc.getGame(SESSION).phase,
                "重开被拒不影响原对局");
    }

    @Test
    @DisplayName("R-2 restart 重开新局：同玩家/同主题/同模式，票/托管/roleKey 全重置，复用 sessionId")
    void restartStartsFreshGame() {
        ScriptGameService svc = new ScriptGameService(mockLlm(), autoApprove());
        svc.initGame(SESSION, "庄园疑云主题", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame g1 = svc.getGame(SESSION);
        String firstKey = svc.getRoleKey(SESSION, "Alice");

        // 打完一局到 ENDED
        svc.startVoting(SESSION);
        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");
        svc.castVote(SESSION, "Carol", "Bob");
        svc.resolveVote(SESSION);
        assertEquals(ScriptGameService.Phase.REVEAL, g1.phase);
        svc.confirmEnded(SESSION);
        assertEquals(ScriptGameService.Phase.ENDED, g1.phase);

        Map<String, Object> restarted = svc.restartGame(SESSION);
        assertEquals("investigation", restarted.get("phase"), "重开新局应回 INVESTIGATION（full 模式）");
        assertEquals(SESSION, restarted.get("session_id"), "复用 sessionId（前端轮询/SSE 定位不变）");
        assertEquals("庄园疑云", restarted.get("name"), "同剧本（mock 剧本名恒定，主题相同）");

        ScriptGameService.ScriptGame g2 = svc.getGame(SESSION);
        assertNotSame(g1, g2, "应创建新对局对象");
        assertEquals(List.of("Alice", "Bob", "Carol"), g2.players, "同玩家");
        assertEquals("庄园疑云主题", g2.theme, "同剧本主题留档");
        assertEquals("full", g2.mode, "同模式");
        assertEquals(ScriptGameService.Phase.INVESTIGATION, g2.phase);
        assertTrue(g2.votes.isEmpty(), "票型重置");
        assertTrue(g2.trustees.isEmpty(), "托管重置");
        assertFalse(g2.lowParticipation, "低参与度标记重置");
        assertFalse(firstKey.equals(svc.getRoleKey(SESSION, "Alice")), "roleKey 应重新发放（新局新令牌）");
        // 新局可正常投票（新票型从零开始）
        svc.startVoting(SESSION);
        assertTrue(svc.castVote(SESSION, "Alice", "Bob").contains("投票给了"));
    }

    @Test
    @DisplayName("D-1 LLM 降级标记：LLM 空输出 → llm_degraded=true（离线模板模式）；LLM 正常 → false")
    void llmDegradedFlagFromFallback() {
        // 降级场景：LLM 空输出 → defaultScript 兜底
        ScriptGameService svc = new ScriptGameService(emptyLlm(), autoApprove());
        Map<String, Object> state = svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        assertTrue(game.llmDegraded, "LLM 空输出 → 剧本走 defaultScript 兜底 → 降级标记");
        assertEquals(Boolean.TRUE, state.get("llm_degraded"), "toMap 暴露 llm_degraded（前端提示条数据源）");
        assertTrue(game.scriptSchema.containsKey("schema_version"), "兜底剧本仍符合 Schema v1（A1-3）");

        // 对照组：LLM 正常 → 不降级
        ScriptGameService svc2 = new ScriptGameService(mockLlm(), autoApprove());
        Map<String, Object> state2 = svc2.initGame("llm-ok", "庄园", List.of("Alice", "Bob", "Carol"));
        assertEquals(Boolean.FALSE, state2.get("llm_degraded"), "LLM 正常不降级");
    }

    @Test
    @DisplayName("S-1 快照落库：托管/降级/主题/投票计时随快照持久化（重连恢复不丢）")
    void snapshotPersistsTrusteesAndDegraded() {
        DatabaseService db = mock(DatabaseService.class);
        ScriptGameService svc = new ScriptGameService(mockLlm(), autoApprove(), db, null);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.leaveGame(SESSION, "Alice", "");

        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> contentCap = ArgumentCaptor.forClass((Class) Map.class);
        verify(db, atLeast(1)).saveScript(nameCap.capture(), contentCap.capture());

        // 取最后一次「对局快照:」落库（退出后的快照）
        Map<String, Object> snap = null;
        for (int i = 0; i < nameCap.getAllValues().size(); i++) {
            if (String.valueOf(nameCap.getAllValues().get(i)).startsWith("对局快照:")) {
                snap = contentCap.getAllValues().get(i);
            }
        }
        assertNotNull(snap, "应存在对局快照落库");
        assertEquals(List.of("Alice"), snap.get("trustees"), "托管标记随快照落库");
        assertEquals(Boolean.FALSE, snap.get("llm_degraded"), "降级标记随快照落库");
        assertTrue(snap.containsKey("theme"), "主题随快照落库（restart 用）");
        assertTrue(snap.containsKey("vote_started_at"), "投票计时随快照落库");
        assertTrue(snap.containsKey("quorum_fail_count"), "quorum 重投计数随快照落库");
        assertTrue(snap.containsKey("low_participation"), "低参与度标记随快照落库");
    }
}
