package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.ScriptController;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 C4 验收测试：DM（主持人）面板 —— 全量视图（对齐 Chronos state:dm_dashboard）
 * + 手动推进（对齐 Chronos dm:advance）+ controller 层 DM key 越权保护。
 *
 * <p>覆盖：
 * <ul>
 *   <li>C4-1：dmStatus 全量视图 —— 所有玩家角色/秘密/AP/线索数/投票状态/roleKey + 对局元数据
 *       （truth/killer_id/审批状态）；搜证后 clue_count 实时反映</li>
 *   <li>C4-2：dmStatus 未知对局 → error</li>
 *   <li>C4-3：advance 状态机推进 —— INVESTIGATION→DISCUSSION（接讨论引擎）→VOTE</li>
 *   <li>C4-4：advance VOTE→REVEAL 经 D7 审批门（挂起→批准→REVEAL）→REVEAL→ENDED（落库）
 *       →ENDED 幂等终态不越界</li>
 *   <li>C4-5：controller 层 —— DM key 未配置放开；配置后 无/错 key → 403、正确 key → 200</li>
 *   <li>C4-6：advance 未知对局 → error</li>
 * </ul>
 *
 * <p>直接构造 ScriptGameService（mock LLMClient + 真实 ApprovalService），与
 * ScriptGameDiscussionTest / ScriptGameResumeTest 风格一致；每个测试独立 sessionId 防串扰。
 */
class ScriptGameDmTest {

    /** 剧本：3 角色 3 秘密；c1（客厅，非公开）/ c2（书房，公开）。 */
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
        script.put("secrets", Map.of("管家", "秘闻_管家贪图遗产", "女仆", "秘闻_女仆知道密信", "园丁", "秘闻_园丁目击真凶"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenAnswer(inv -> {
            Thread.sleep(50);
            return "我认为凶手就在我们中间【情绪：平静】";
        });
        return llm;
    }

    private ScriptGameService newService() {
        return new ScriptGameService(mockLlm(), new ApprovalService());
    }

    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    /** 轮询等待讨论结束（phase==VOTE 且发言记录落盘），超时 10s 判失败（避免后台讨论线程 finally 覆盖后续阶段）。 */
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
        fail("讨论未在超时内结束: phase=" + (g == null ? "null" : g.phase)
                + " active=" + (g == null ? "null" : g.discussionActive)
                + " turns=" + (g == null ? 0 : g.discussionTranscript.size()));
    }

    // ═══════════════════════════════════════════════════════════
    //  C4-1/C4-2: dmStatus 全量视图
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C4-1: dmStatus 全量视图 —— 所有玩家角色/秘密/AP/线索数/投票/roleKey + 对局元数据")
    void dmStatusReturnsFullDashboard() {
        ScriptGameService svc = newService();
        String sid = "dm-c41";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(sid);
        String butlerPlayer = playerWithRole(game, "管家");

        // 管家搜证得 c1（clue_count 应反映）
        svc.search(sid, butlerPlayer, "客厅");

        Map<String, Object> dm = svc.dmStatus(sid);
        assertEquals("investigation", dm.get("phase"));
        assertEquals(sid, dm.get("session_id"));
        assertEquals(1, dm.get("round"));
        assertTrue(dm.get("name").toString().contains("庄园"), "DM 可见剧本名");
        assertTrue(dm.get("truth").toString().contains("管家"), "DM 可见真相");
        assertNotNull(dm.get("killer_id"), "DM 可见 schema killer_id 元数据");
        assertEquals("none", dm.get("approval_status"), "无待审时审批状态为 none");

        // 玩家表全量：3 名玩家，每行含角色/秘密/AP/线索数/投票/roleKey
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) dm.get("players");
        assertEquals(3, players.size(), "DM 可见全部玩家");
        for (Map<String, Object> row : players) {
            assertTrue(row.containsKey("name") && row.containsKey("role"), "玩家行含姓名与角色");
            assertTrue(row.containsKey("secret"), "玩家行含秘密（DM 全量不脱敏）");
            assertTrue(row.containsKey("ap") && row.containsKey("ap_max"), "玩家行含 AP");
            assertTrue(row.containsKey("clue_count"), "玩家行含线索数");
            assertTrue(row.containsKey("voted") && row.containsKey("vote"), "玩家行含投票状态");
            assertTrue(row.containsKey("role_key"), "玩家行含 roleKey（DM 分发令牌用）");
        }
        // 全部角色秘密 DM 可见
        assertTrue(players.toString().contains("秘闻_管家贪图遗产"), "DM 可见管家秘密");
        assertTrue(players.toString().contains("秘闻_女仆知道密信"), "DM 可见女仆秘密");
        assertTrue(players.toString().contains("秘闻_园丁目击真凶"), "DM 可见园丁秘密");

        // roleKey 与服务层一致（分发令牌数据源）
        Map<String, Object> butlerRow = players.stream()
            .filter(r -> butlerPlayer.equals(r.get("name"))).findFirst().orElseThrow();
        assertEquals(svc.getRoleKey(sid, butlerPlayer), butlerRow.get("role_key"), "roleKey 与玩家令牌一致");
        assertEquals(1, butlerRow.get("clue_count"), "搜证后线索数实时反映（c1）");
        assertEquals(Boolean.FALSE, butlerRow.get("voted"), "未投票标记 false");
    }

    @Test
    @DisplayName("C4-2: dmStatus 未知对局 → error")
    void dmStatusUnknownSession() {
        ScriptGameService svc = newService();
        Map<String, Object> dm = svc.dmStatus("no-such-session");
        assertTrue(dm.get("error").toString().contains("游戏不存在"), "未知对局明确报错");
        assertEquals("not_found", dm.get("phase"));
    }

    // ═══════════════════════════════════════════════════════════
    //  C4-3/C4-4: advance 状态机推进
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C4-3: advance 状态机 —— INVESTIGATION→DISCUSSION（接讨论引擎）→VOTE")
    void advanceThroughDiscussionToVote() throws Exception {
        ScriptGameService svc = newService();
        String sid = "dm-c43";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        assertEquals(ScriptGameService.Phase.INVESTIGATION, svc.getGame(sid).phase);

        // INVESTIGATION → DISCUSSION（接讨论引擎，phase 立即为 discussion）
        Map<String, Object> r1 = svc.advancePhase(sid);
        assertEquals("discussion", r1.get("phase"), "advance 进入讨论阶段");
        assertEquals("discussion", r1.get("advanced"));
        assertEquals(ScriptGameService.Phase.DISCUSSION, svc.getGame(sid).phase);
        assertTrue(svc.getGame(sid).discussionActive, "讨论引擎已启动");

        // 等讨论结束自动进 VOTE（避免后台 finally 覆盖后续阶段），再验证 DISCUSSION→VOTE 推进
        awaitDiscussionFinished(svc, sid);
        // 重开一轮讨论验证 DM 手动推进：先回 INVESTIGATION 不可行（状态机单向），直接验证 VOTE 起步推进
        ScriptGameService.ScriptGame game = svc.getGame(sid);
        assertEquals(ScriptGameService.Phase.VOTE, game.phase, "讨论结束自动进 VOTE");

        // 再验证一次 DISCUSSION→VOTE 手动推进：新建对局，直接 startDiscussion 后不等讨论结束即推进
        String sid2 = "dm-c43b";
        svc.initGame(sid2, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.startDiscussion(sid2);
        assertEquals(ScriptGameService.Phase.DISCUSSION, svc.getGame(sid2).phase);
        Map<String, Object> r2 = svc.advancePhase(sid2);
        assertEquals("vote", r2.get("phase"), "DM 手动推进 DISCUSSION→VOTE（C3 已知限制的入口）");
        assertEquals("vote", r2.get("advanced"));
        assertEquals(ScriptGameService.Phase.VOTE, svc.getGame(sid2).phase);
    }

    @Test
    @DisplayName("C4-4: advance VOTE→REVEAL 经审批门（挂起→批准）→REVEAL→ENDED→幂等终态")
    void advanceVoteRevealEnded() throws Exception {
        ScriptGameService svc = newService();
        ApprovalService approval = new ApprovalService();
        ScriptGameService svc2 = new ScriptGameService(mockLlm(), approval);
        String sid = "dm-c44";
        svc2.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc2.getGame(sid);
        String murderer = playerWithRole(game, "管家");
        List<String> others = game.players.stream().filter(p -> !p.equals(murderer)).toList();

        // INVESTIGATION → VOTE（绕过讨论，直接起投票）
        svc2.startVoting(sid);
        svc2.castVote(sid, others.get(0), murderer);
        svc2.castVote(sid, others.get(1), murderer);
        svc2.castVote(sid, murderer, others.get(0));

        // VOTE → REVEAL：advance 内部走 resolveVote，挂起等审批
        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc2.advancePhase(sid));
        Thread.sleep(150);
        assertEquals("pending", approval.getStatus(sid), "advance VOTE 步挂起待 DM 审批");
        assertTrue(approval.approve(sid), "DM 批准揭晓");
        Map<String, Object> r1 = fut.get(5, TimeUnit.SECONDS);
        assertEquals(Boolean.TRUE, r1.get("correct"), "判定命中真凶");
        assertEquals("reveal", r1.get("phase"), "批准后进入 REVEAL");
        assertEquals("reveal", r1.get("advanced"));
        assertEquals(ScriptGameService.Phase.REVEAL, svc2.getGame(sid).phase);

        // REVEAL → ENDED（confirmEnded 落库路径；直构造 databaseService 为 null 仅跳过落库）
        Map<String, Object> r2 = svc2.advancePhase(sid);
        assertEquals("ended", r2.get("phase"), "advance 收尾进 ENDED");
        assertEquals("ended", r2.get("advanced"));
        assertEquals(ScriptGameService.Phase.ENDED, svc2.getGame(sid).phase);

        // ENDED → 幂等终态（不越界）
        Map<String, Object> r3 = svc2.advancePhase(sid);
        assertEquals("ended", r3.get("phase"), "ENDED 重复推进幂等");
        assertEquals(Boolean.TRUE, r3.get("terminal"), "终态标记");
        assertEquals(murderer, r3.get("murderer"), "终态含真凶");
        assertEquals(ScriptGameService.Phase.ENDED, svc2.getGame(sid).phase, "终态不越界");

        // DM 视图在 ENDED 下仍可读
        Map<String, Object> dm = svc2.dmStatus(sid);
        assertEquals("ended", dm.get("phase"));
        assertEquals(murderer, dm.get("murderer"));
    }

    // ═══════════════════════════════════════════════════════════
    //  C4-5/C4-6: controller 层 DM key 越权保护 / 未知对局
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C4-5: controller 层 —— DM key 未配置时安全拒绝；配置后无/错 key 403、正确 key 200")
    void controllerDmKeyGate() throws Exception {
        ScriptGameService svc = newService();
        String sid = "dm-c45";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));

        // ① 未配置 key 也安全拒绝：DM 状态/全员 roleKey 不得在未鉴权时泄露。
        ScriptController open = new ScriptController(svc, mock(RouterService.class), mock(SimulationService.class));
        ResponseEntity<Map<String, Object>> openResp = open.dmStatus(sid, "");
        assertEquals(403, openResp.getStatusCode().value(), "未配置 DM key 时安全拒绝");

        // ② 配置 key 后：无/错 key → 403
        ScriptController gated = new ScriptController(svc, mock(RouterService.class), mock(SimulationService.class));
        Field f = ScriptController.class.getDeclaredField("dmKey");
        f.setAccessible(true);
        f.set(gated, "top-secret-dm-key");

        ResponseEntity<Map<String, Object>> noKey = gated.dmStatus(sid, "");
        assertEquals(403, noKey.getStatusCode().value(), "缺 X-DM-Key → 403");
        assertTrue(noKey.getBody().get("error").toString().contains("DM 权限校验失败"), "明确提示 DM 权限失败");

        ResponseEntity<Map<String, Object>> wrongKey = gated.dmStatus(sid, "wrong-key");
        assertEquals(403, wrongKey.getStatusCode().value(), "错误 X-DM-Key → 403");

        ResponseEntity<Map<String, Object>> badAdvance = gated.advance(new LinkedHashMap<>(Map.of("session_id", sid)), "");
        assertEquals(403, badAdvance.getStatusCode().value(), "advance 无 key → 403");

        // ③ 正确 key → 200（DM 面板正常调用）
        ResponseEntity<Map<String, Object>> ok = gated.dmStatus(sid, "top-secret-dm-key");
        assertEquals(200, ok.getStatusCode().value(), "正确 X-DM-Key → 200");
        assertEquals("investigation", ok.getBody().get("phase"));

        ResponseEntity<Map<String, Object>> okAdvance = gated.advance(new LinkedHashMap<>(Map.of("session_id", sid)), "top-secret-dm-key");
        assertEquals(200, okAdvance.getStatusCode().value(), "advance 正确 key → 200");
        assertEquals("discussion", okAdvance.getBody().get("phase"), "正确 key 可正常推进");
    }

    @Test
    @DisplayName("C4-6: advance 未知对局 → error；controller 缺少 session_id → error")
    void advanceUnknownSession() {
        ScriptGameService svc = newService();
        Map<String, Object> r = svc.advancePhase("no-such-session");
        assertTrue(r.get("error").toString().contains("游戏不存在"), "未知对局明确报错");

        ScriptController ctl = new ScriptController(svc, mock(RouterService.class), mock(SimulationService.class));
        ResponseEntity<Map<String, Object>> resp = ctl.advance(new LinkedHashMap<>(Map.of("session_id", "")), "");
        assertEquals(403, resp.getStatusCode().value(), "未配置 DM key 时先拒绝未鉴权请求");
    }
}
