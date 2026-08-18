package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D6/D7 自测：剧本杀揭晓判定（精确匹配 / 平票重投 / 非法票拦截）+ 审批门接入（挂起 / 批准 / 驳回回滚）。
 *
 * <p>直接构造 ScriptGameService（mock LLMClient + 真实 ApprovalService，不加载 Spring 上下文），
 * 与 ApprovalServiceTest 的阻塞式 submitForApproval 测试风格一致：resolveVote 在后台线程挂起，
 * 主线程 approve/reject 放行。
 */
class ScriptGameServiceTest {

    private static final String SESSION = "test-script-session";

    private ScriptGameService newService(ApprovalService approval) {
        return new ScriptGameService(mockLlm(), approval);
    }

    /** 默认剧本：3 角色（管家=真凶），truth 明示“凶手是管家”。 */
    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new java.util.LinkedHashMap<>();
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

    /** 建局并推进到投票阶段。 */
    private ScriptGameService.ScriptGame toVote(ScriptGameService svc, List<String> players) {
        svc.initGame(SESSION, "庄园", players);
        svc.startDiscussion(SESSION);
        svc.startVoting(SESSION);
        return svc.getGame(SESSION);
    }

    /** 找出扮演指定角色的玩家。 */
    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    @Test
    @DisplayName("D6 平票 → 清空投票并复用 VOTE 阶段重投（不进入 REVEAL、不设 winner）")
    void tieClearsVotesAndReVotes() {
        ScriptGameService svc = newService(new ApprovalService());
        toVote(svc, List.of("Alice", "Bob"));

        assertEquals("Alice 投票给了 Bob", svc.castVote(SESSION, "Alice", "Bob"));
        assertEquals("Bob 投票给了 Alice", svc.castVote(SESSION, "Bob", "Alice"));

        // 平票不触发审批，同步返回
        Map<String, Object> res = svc.resolveVote(SESSION);
        assertEquals(Boolean.TRUE, res.get("tie"));
        assertEquals(Boolean.TRUE, res.get("revote"));
        assertEquals("平票，无人被定罪，已清空投票，请重新投票", res.get("result"));

        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        assertEquals(ScriptGameService.Phase.VOTE, game.phase, "平票后应留在投票阶段重投");
        assertTrue(game.votes.isEmpty(), "平票后投票应被清空以便重投");
        assertEquals("", game.winner, "平票不得误设 winner");
    }

    @Test
    @DisplayName("D6 非法票面被拦截（投票对象必须是本局玩家/角色名）")
    void invalidVoteRejected() {
        ScriptGameService svc = newService(new ApprovalService());
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        assertTrue(svc.castVote(SESSION, "Alice", "路人甲").contains("无效"));
        assertTrue(svc.castVote(SESSION, "Bob", "").contains("不能为空"));
        assertTrue(svc.castVote(SESSION, "Carol", "Alice").contains("投票给了"));
        assertTrue(svc.getGame(SESSION).votes.size() == 1, "非法票不得入库，合法票保留");
    }

    @Test
    @DisplayName("D7 揭晓审批：批准后正确命中真凶，进入 REVEAL")
    void correctRevealApproved() throws Exception {
        ApprovalService approval = new ApprovalService();
        ScriptGameService svc = newService(approval);
        ScriptGameService.ScriptGame game = toVote(svc, List.of("Alice", "Bob", "Carol"));
        String murderer = playerWithRole(game, "管家");
        List<String> others = game.players.stream().filter(p -> !p.equals(murderer)).toList();

        svc.castVote(SESSION, others.get(0), murderer);
        svc.castVote(SESSION, others.get(1), murderer);
        svc.castVote(SESSION, murderer, others.get(0));

        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(SESSION));
        Thread.sleep(150);
        assertEquals("pending", approval.getStatus(SESSION), "揭晓应挂起等待 DM 审批");
        assertTrue(approval.approve(SESSION));

        Map<String, Object> res = fut.get(5, TimeUnit.SECONDS);
        assertEquals("approved", res.get("approval"));
        assertEquals(murderer, res.get("most_voted"));
        assertEquals(murderer, res.get("murderer"), "应从真相精确识别真凶");
        assertEquals(Boolean.TRUE, res.get("correct"));
        assertEquals("剧本杀成功！真凶被找到", res.get("result"));
        assertEquals(ScriptGameService.Phase.REVEAL, svc.getGame(SESSION).phase);
        assertEquals(murderer, svc.getGame(SESSION).winner);
    }

    @Test
    @DisplayName("D7 揭晓审批：批准后显示冤枉好人")
    void wrongAccusationRevealed() throws Exception {
        ApprovalService approval = new ApprovalService();
        ScriptGameService svc = newService(approval);
        ScriptGameService.ScriptGame game = toVote(svc, List.of("Alice", "Bob", "Carol"));
        String scapegoat = playerWithRole(game, "女仆");
        String murderer = playerWithRole(game, "管家");
        String third = game.players.stream()
            .filter(p -> !p.equals(scapegoat) && !p.equals(murderer))
            .findFirst().orElse("");

        svc.castVote(SESSION, murderer, scapegoat);
        svc.castVote(SESSION, third, scapegoat);
        svc.castVote(SESSION, scapegoat, murderer);

        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(SESSION));
        Thread.sleep(150);
        approval.approve(SESSION);

        Map<String, Object> res = fut.get(5, TimeUnit.SECONDS);
        assertEquals(scapegoat, res.get("most_voted"));
        assertEquals(murderer, res.get("murderer"));
        assertEquals(Boolean.FALSE, res.get("correct"));
        assertEquals("冤枉了好人...", res.get("result"));
    }

    @Test
    @DisplayName("D7 揭晓审批：驳回 → 回滚至投票阶段并清空票数重投")
    void rejectedRevealRollsBack() throws Exception {
        ApprovalService approval = new ApprovalService();
        ScriptGameService svc = newService(approval);
        ScriptGameService.ScriptGame game = toVote(svc, List.of("Alice", "Bob", "Carol"));
        String murderer = playerWithRole(game, "管家");
        List<String> others = game.players.stream().filter(p -> !p.equals(murderer)).toList();

        svc.castVote(SESSION, others.get(0), murderer);
        svc.castVote(SESSION, others.get(1), murderer);
        svc.castVote(SESSION, murderer, others.get(0));

        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(SESSION));
        Thread.sleep(150);
        assertEquals("pending", approval.getStatus(SESSION));
        assertTrue(approval.reject(SESSION, "判定有误，请重投"));

        Map<String, Object> res = fut.get(5, TimeUnit.SECONDS);
        assertEquals("rejected", res.get("approval"));
        assertEquals(Boolean.TRUE, res.get("revote"));
        ScriptGameService.ScriptGame g = svc.getGame(SESSION);
        assertEquals(ScriptGameService.Phase.VOTE, g.phase, "驳回后应回滚至投票阶段");
        assertTrue(g.votes.isEmpty(), "驳回后票数应清空以便重投");
        assertEquals("", g.winner, "驳回后不得进入揭晓");
    }

    @Test
    @DisplayName("P-0819-A 角色选择名与后端身份保持一致")
    void selectedPlayerNamesRemainTheirRoles() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new java.util.LinkedHashMap<>();
        script.put("name", "人物绑定测试");
        script.put("background", "背景");
        script.put("truth", "凶手是林晚秋");
        script.put("roles", List.of("沈墨", "林晚秋"));
        script.put("locations", List.of("客厅"));
        script.put("clues", List.of(Map.of("id", "c1", "location", "客厅", "content", "公开线索", "public", true)));
        script.put("secrets", Map.of("沈墨", "秘密A", "林晚秋", "秘密B"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);

        ScriptGameService svc = new ScriptGameService(llm, new ApprovalService());
        svc.initGame("selected-role-binding", "主题", List.of("沈墨", "林晚秋"));
        assertEquals("沈墨", svc.getGame("selected-role-binding").assignments.get("沈墨"));
        assertEquals("林晚秋", svc.getGame("selected-role-binding").assignments.get("林晚秋"));
    }

    @Test
    @DisplayName("D6 精确匹配：得票者名是真相中真凶名的子串时不误判（旧 contains 逻辑会误判成功）")
    void noSubstringFalsePositive() throws Exception {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new java.util.LinkedHashMap<>();
        script.put("name", "名侦探");
        script.put("background", "背景");
        script.put("truth", "凶手是张三丰，因为他武功最高。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "线索", "public", true, "related_role", "")));
        script.put("secrets", Map.of("管家", "秘密A", "女仆", "秘密B", "园丁", "秘密C"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);

        ApprovalService approval = new ApprovalService();
        ScriptGameService svc = new ScriptGameService(llm, approval);
        svc.initGame(SESSION, "庄园", List.of("张三", "张三丰", "王五"));
        svc.startDiscussion(SESSION);
        svc.startVoting(SESSION);

        // “张三”得票最高 —— 但其名是真相中真凶“张三丰”的子串，旧 truth.contains(mostVoted) 会误判成功
        svc.castVote(SESSION, "张三丰", "张三");
        svc.castVote(SESSION, "王五", "张三");
        svc.castVote(SESSION, "张三", "王五");

        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(SESSION));
        Thread.sleep(150);
        approval.approve(SESSION);

        Map<String, Object> res = fut.get(5, TimeUnit.SECONDS);
        assertEquals("张三", res.get("most_voted"));
        assertEquals("张三丰", res.get("murderer"), "应从真相精确识别真凶为张三丰（最长全名优先）");
        assertEquals(Boolean.FALSE, res.get("correct"), "旧 truth.contains(张三) 会误判成功，新逻辑必须判冤");
    }
}
