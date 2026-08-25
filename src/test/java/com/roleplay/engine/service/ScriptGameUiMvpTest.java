package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * P-0816-G（剧本杀 UI 重设计阶段一 MVP 后端接口包）验收测试 —— 依据
 * docs/ui-prototype/对比与API接入方案.md §3.2/§3.3 + 决策记录.md C/D 口径。
 *
 * <p>覆盖（新增/扩展接口逐个）：
 * <ul>
 *   <li>API-1（GET /api/script/actions）：行动建议集 —— 搜证阶段 ask/research/present 三类生成、
 *       已搜地点回看优先排序、enabled=false+reason（AP 不足 / 出示需讨论阶段）、非相关阶段空列表</li>
 *   <li>API-2（POST /api/script/action）：行动执行 —— ask 委托 privateSay、research 未搜委托 search、
 *       已搜回看不扣 AP（U7，{replayed:true, clues:[]}）、present 委托 discussionSay、AP 不足拒绝、未知行动</li>
 *   <li>API-10（GET /api/script/vote/status）：投票进度聚合 —— 只出聚合不出投票人（C13）、
 *       托管排除、非 VOTE 只回 {phase}</li>
 *   <li>API-11（POST /api/script/vote 扩展）：弃票 —— 独立 abstainedVoters 集合（U8）、
 *       quorum 在线数仍计、不参与票型统计、重复表态拒绝、3 参旧路径向后兼容</li>
 *   <li>API-13（GET /api/script/goal）：目标 HUD 规则模板（U4/U14）—— 搜证 x/y、质询计数 0 占位、投票 x/y</li>
 *   <li>SSE：script_vote_progress / script_goal 经 broadcastToSession 定向通道推送（§3.3，决策 D1）</li>
 * </ul>
 *
 * <p>直构 ScriptGameService（mock LLMClient + ApprovalService / CaptureSSE），与
 * ScriptGameApTransferTest / ScriptGameEndedTest 风格一致；行动点配置走 @Value 默认值。
 */
class ScriptGameUiMvpTest {

    private static final String SESSION = "test-script-ui-mvp";

    /**
     * 旧格式剧本：3 角色（管家/女仆/园丁，无 ap_bonus → 全员初始 AP=3）；
     * 4 地点；线索 c1（客厅，ap_cost 缺省→1）/ c2（书房，ap_cost=2）/ c3（花园，公开）/ c4（地下室，不可转交）。
     */
    private LLMClient legacyLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房", "花园", "地下室"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false, "transferable", true),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", false, "transferable", false, "ap_cost", 2),
            Map.of("id", "c3", "location", "花园", "content", "公开脚印", "public", true),
            Map.of("id", "c4", "location", "地下室", "content", "染血手套", "public", false, "transferable", false)));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenReturn("我确实有些事没说完，但现在还不是时候。");
        return llm;
    }

    private ScriptGameService newService() {
        return new ScriptGameService(legacyLlm(), new ApprovalService());
    }

    /** 捕获定向 SSE 事件（script_vote_progress / script_goal 走 broadcastToSession 通道）。 */
    private static class CaptureSSE extends SSEController {
        final List<Map.Entry<String, Object>> events = new ArrayList<>();

        @Override
        public void broadcastToSession(String sessionId, String eventType, Object data) {
            events.add(Map.entry(eventType, data));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actionsOf(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("actions");
    }

    private static Map<String, Object> findAction(List<Map<String, Object>> actions, String type, String target) {
        for (Map<String, Object> a : actions) {
            if (type.equals(a.get("type")) && target.equals(a.get("target"))) return a;
        }
        return null;
    }

    /** 找出扮演指定角色的玩家。 */
    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    // ═══════════════════════════════════════════════════════════
    //  API-1: GET /api/script/actions —— 行动建议集
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-1a: 搜证阶段生成 ask/research/present 三类建议 —— 已搜地点回看优先、未私聊过目标去重")
    void actionsGeneratedInInvestigation() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        Map<String, Object> res = svc.listActions(SESSION, "Alice");
        assertEquals(Boolean.TRUE, res.get("ok"));
        assertEquals("investigation", res.get("phase"));
        assertEquals(3, res.get("ap"));
        assertEquals(3, res.get("ap_max"));
        List<Map<String, Object>> actions = actionsOf(res);

        // ask：另外两名玩家（排除自己、排除托管、排除已私聊过的）
        assertNotNull(findAction(actions, "ask", "Bob"), "应建议去问 Bob");
        assertNotNull(findAction(actions, "ask", "Carol"), "应建议去问 Carol");
        assertNull(findAction(actions, "ask", "Alice"), "不能建议问自己");

        // research：未搜过地点为「去搜」（预估扣费=线索 ap_cost 之和）；已搜过为「回看」
        Map<String, Object> researchLiving = findAction(actions, "research", "客厅");
        assertNotNull(researchLiving);
        assertEquals(1, researchLiving.get("ap_cost"), "客厅 c1 ap_cost=1");
        assertEquals(Boolean.TRUE, researchLiving.get("enabled"));

        // present：本人尚未持有线索 → 无 present 建议
        assertNull(findAction(actions, "present", "c1"), "未持有线索不应有出示建议");

        // 搜证后：客厅变「回看」且排在最前（已搜地点回看优先）；持有 c1 后可出示（搜证阶段 disabled+reason）
        svc.search(SESSION, "Alice", "客厅");
        Map<String, Object> res2 = svc.listActions(SESSION, "Alice");
        List<Map<String, Object>> actions2 = actionsOf(res2);
        Map<String, Object> replay = findAction(actions2, "research", "客厅");
        assertNotNull(replay, "已搜地点应出回看建议");
        assertEquals("回看客厅", replay.get("label"));
        assertEquals(0, replay.get("ap_cost"), "回看不扣 AP（决策 U7）");
        // 已搜（回看）建议应排在未搜（去搜）之前
        int idxReplay = -1;
        int idxGo = -1;
        for (int i = 0; i < actions2.size(); i++) {
            Map<String, Object> a = actions2.get(i);
            if ("research".equals(a.get("type")) && "客厅".equals(a.get("target"))) idxReplay = i;
            if ("research".equals(a.get("type")) && "书房".equals(a.get("target"))) idxGo = i;
        }
        assertTrue(idxReplay >= 0 && idxGo >= 0 && idxReplay < idxGo, "回看优先于去搜");
        Map<String, Object> present = findAction(actions2, "present", "c1");
        assertNotNull(present, "持有 c1 后应出出示建议");
        assertEquals(Boolean.FALSE, present.get("enabled"), "搜证阶段出示不可执行");
        assertTrue(present.get("reason").toString().contains("讨论阶段"), "给出引导原因");

        // 讨论阶段且 AP 充足 → present 可执行（直接置阶段，避免后台讨论引擎竞态）
        game.phase = ScriptGameService.Phase.DISCUSSION;
        Map<String, Object> presentEnabled = findAction(actionsOf(svc.listActions(SESSION, "Alice")), "present", "c1");
        assertEquals(Boolean.TRUE, presentEnabled.get("enabled"), "讨论阶段且 AP 充足可执行");
    }

    @Test
    @DisplayName("API-1b: AP 不足 → enabled=false + reason=行动点不足；讨论阶段出 ask/present；投票阶段空列表")
    void actionsRespectApAndPhase() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // 搜空 AP（客厅 1 + 书房 2 = 3）
        svc.search(SESSION, "Alice", "客厅");
        svc.search(SESSION, "Alice", "书房");
        assertEquals(0, game.playerAp.get("Alice"));

        Map<String, Object> res = svc.listActions(SESSION, "Alice");
        Map<String, Object> askBob = findAction(actionsOf(res), "ask", "Bob");
        assertNotNull(askBob);
        assertEquals(Boolean.FALSE, askBob.get("enabled"), "AP 不足 ask 禁用");
        assertEquals("行动点不足", askBob.get("reason"));
        Map<String, Object> research = findAction(actionsOf(res), "research", "地下室");
        assertNotNull(research);
        assertEquals(Boolean.FALSE, research.get("enabled"), "AP 不足去搜禁用");

        // 讨论阶段：present 建议存在（本测试 AP 已搜空 → 禁用+行动点不足原因）
        game.phase = ScriptGameService.Phase.DISCUSSION;
        Map<String, Object> resDisc = svc.listActions(SESSION, "Alice");
        Map<String, Object> present = findAction(actionsOf(resDisc), "present", "c1");
        assertNotNull(present, "讨论阶段应出出示建议");
        assertEquals(Boolean.FALSE, present.get("enabled"), "AP 已搜空 → 出示禁用");
        assertEquals("行动点不足", present.get("reason"));

        // 投票阶段：三类建议均不生成 → 空列表（前端隐藏行动条）
        svc.startVoting(SESSION);
        Map<String, Object> resVote = svc.listActions(SESSION, "Alice");
        assertTrue(actionsOf(resVote).isEmpty(), "投票阶段无行动建议");
    }

    // ═══════════════════════════════════════════════════════════
    //  API-2: POST /api/script/action —— 行动执行（内部委托既有方法）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-2a: ask 行动委托 privateSay —— 扣 AP 1、返回 AI 应答、已私聊目标不再建议")
    void askActionDelegatesToPrivateSay() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        Map<String, Object> r = svc.executeAction(SESSION, "Alice", "ask|Bob");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("ask|Bob", r.get("action_id"));
        assertEquals(1, r.get("ap_cost"));
        assertEquals(2, r.get("ap"), "ask 扣 1 AP（3→2）");
        assertFalse(String.valueOf(r.get("reply")).isBlank(), "应返回 AI 应答（委托 privateSay）");
        assertEquals(Boolean.FALSE, r.get("replayed"));

        // 私聊已发生 → 该目标不再出现在后续建议中（去重最近目标）
        Map<String, Object> res = svc.listActions(SESSION, "Alice");
        assertNull(findAction(actionsOf(res), "ask", "Bob"), "已私聊过不再建议问 Bob");
        assertNotNull(findAction(actionsOf(res), "ask", "Carol"), "未私聊过的仍建议");
    }

    @Test
    @DisplayName("API-2b: research 未搜过 → 委托 search 扣线索 ap_cost 之和；已搜过 → 回看不扣 AP（U7）")
    void researchActionDelegatesToSearchAndReplay() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // 未搜过：委托 search —— 书房 c2 ap_cost=2，AP 3→1，线索授予
        Map<String, Object> r1 = svc.executeAction(SESSION, "Alice", "research|书房");
        assertEquals(Boolean.TRUE, r1.get("ok"));
        assertEquals(List.of("c2"), r1.get("found"));
        assertEquals(2, r1.get("ap_cost"), "首搜扣线索 ap_cost 之和");
        assertEquals(1, r1.get("ap"));
        assertEquals(Boolean.FALSE, r1.get("replayed"));
        assertTrue(game.playerClues.getOrDefault("Alice", List.of()).contains("c2"));

        // 已搜过：回看不扣 AP（U7）—— {replayed:true, clues:[...]}，AP 保持 1
        Map<String, Object> r2 = svc.executeAction(SESSION, "Alice", "research|书房");
        assertEquals(Boolean.TRUE, r2.get("ok"));
        assertEquals(Boolean.TRUE, r2.get("replayed"), "已搜地点回看标记");
        assertEquals(0, r2.get("ap_cost"), "回看不扣 AP（决策 U7）");
        assertEquals(1, r2.get("ap"), "AP 不变");
        assertTrue(((List<?>) r2.get("clues")).stream().anyMatch(c -> "c2".equals(((Map<?, ?>) c).get("id"))),
                "回看返回该地点本人可见线索");
    }

    @Test
    @DisplayName("API-2c: present 行动 —— 非讨论阶段拒绝；讨论阶段委托 discussionSay 扣 AP 1")
    void presentActionPhaseGuardAndExecution() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        svc.search(SESSION, "Alice", "客厅"); // 持有 c1

        // 搜证阶段出示 → 拒绝（与 listActions disabled 一致）
        Map<String, Object> denied = svc.executeAction(SESSION, "Alice", "present|c1");
        assertTrue(denied.get("error").toString().contains("讨论阶段"), "搜证阶段出示拒绝");

        // 讨论阶段出示 → 委托 discussionSay 入讨论流，扣 AP 1（直接置阶段，避免后台讨论引擎竞态）
        game.phase = ScriptGameService.Phase.DISCUSSION;
        Map<String, Object> r = svc.executeAction(SESSION, "Alice", "present|c1");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.TRUE, r.get("presented"));
        assertEquals("c1", r.get("clue_id"));
        assertEquals(1, r.get("ap_cost"));
        assertEquals(1, r.get("ap"), "present 扣 1 AP（搜证 3→2 后再出示 2→1）");
        assertTrue(r.get("result").toString().contains("出示了线索"));
        // 人类发言事件已入队（讨论引擎下轮排空注入）
        assertFalse(game.pendingHumanEvents.isEmpty(), "出示应作为人类发言事件入队");

        // 未持有线索 → 拒绝
        Map<String, Object> notHeld = svc.executeAction(SESSION, "Alice", "present|c4");
        assertTrue(notHeld.get("error").toString().contains("未持有"), "未持有线索拒绝");
    }

    @Test
    @DisplayName("API-2d: 行动点不足拒绝 + 未知行动拒绝")
    void actionRejectedWhenApInsufficientOrUnknown() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // 搜空 AP
        svc.search(SESSION, "Alice", "客厅");
        svc.search(SESSION, "Alice", "书房");
        assertEquals(0, game.playerAp.get("Alice"));

        Map<String, Object> noAp = svc.executeAction(SESSION, "Alice", "ask|Bob");
        assertTrue(noAp.get("error").toString().contains(ScriptGameService.ERR_AP_INSUFFICIENT), "AP 不足拒绝");
        Map<String, Object> noApResearch = svc.executeAction(SESSION, "Alice", "research|地下室");
        assertTrue(noApResearch.get("error").toString().contains(ScriptGameService.ERR_AP_INSUFFICIENT), "AP 不足拒绝（委托 search 前拦截）");
        assertEquals(0, game.playerAp.get("Alice"), "拒绝后 AP 不变");

        Map<String, Object> unknown = svc.executeAction(SESSION, "Alice", "dance|Bob");
        assertTrue(unknown.get("error").toString().contains("未知行动"), "未知行动拒绝");
        Map<String, Object> badTarget = svc.executeAction(SESSION, "Alice", "ask|路人甲");
        assertTrue(badTarget.get("error").toString().contains("无效的问人目标"), "无效目标拒绝");
    }

    // ═══════════════════════════════════════════════════════════
    //  API-10: GET /api/script/vote/status —— 投票进度聚合（只出聚合不出投票人，C13）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-10a: 投票进度聚合 —— total/voted/abstained/pending/candidates 只出票数不出投票人；非 VOTE 只回 {phase}")
    void voteStatusAggregatesWithoutVoters() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));

        // 非 VOTE 阶段：只回 {phase}（前端隐藏统计区）
        Map<String, Object> pre = svc.voteStatus(SESSION);
        assertEquals(1, pre.size());
        assertEquals("investigation", pre.get("phase"));

        svc.startVoting(SESSION);
        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");
        svc.castVote(SESSION, "Carol", "", true); // 弃票

        Map<String, Object> r = svc.voteStatus(SESSION);
        assertEquals("vote", r.get("phase"));
        assertEquals(3, r.get("total"), "在线玩家数=本局玩家−托管");
        assertEquals(2, r.get("voted"), "正常投票 2 人");
        assertEquals(1, r.get("abstained"), "弃票 1 人（独立集合）");
        assertEquals(List.of(), r.get("pending"));
        assertEquals(List.of(), r.get("trustees"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) r.get("candidates");
        assertEquals(3, candidates.size(), "三名在线玩家均应为候选人");
        assertEquals(2L, candidates.stream().filter(c -> Integer.valueOf(1).equals(c.get("votes"))).count(), "两名候选人各有 1 票");
        assertEquals(1L, candidates.stream().filter(c -> Integer.valueOf(0).equals(c.get("votes"))).count(), "弃票者仍显示为 0 票候选人");
        assertTrue(candidates.stream().allMatch(c -> "".equals(c.get("point"))), "MVP point 空占位");
        // 只出聚合不出投票人：响应中不含「谁投了谁」的票面映射
        assertFalse(r.containsKey("votes"), "不泄露票面映射");
        assertFalse(r.toString().contains("Alice->"), "无 voter→suspect 明文");
    }

    @Test
    @DisplayName("API-10b: 托管玩家排除出统计（leave 后 total 下降、投票进度 SSE 同步推送）")
    void voteStatusExcludesTrustees() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.startVoting(SESSION);
        svc.castVote(SESSION, "Alice", "Bob");

        Map<String, Object> before = svc.voteStatus(SESSION);
        assertEquals(3, before.get("total"));

        svc.leaveGame(SESSION, "Bob", svc.getRoleKey(SESSION, "Bob"));
        Map<String, Object> after = svc.voteStatus(SESSION);
        assertEquals(2, after.get("total"), "托管玩家不计入在线数");
        assertEquals(1, after.get("voted"));
        assertEquals(List.of("Carol"), after.get("pending"), "剩余未投票玩家");
        assertEquals(List.of("Bob"), after.get("trustees"));
    }

    // ═══════════════════════════════════════════════════════════
    //  API-11: POST /api/script/vote 扩展 —— 弃票（独立 abstainedVoters，U8）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-11a: 弃票写独立 abstainedVoters 集合 —— 不进 votes/不参与票型统计，quorum 在线数仍计")
    void abstainWritesSeparateSet() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        svc.startVoting(SESSION);

        String result = svc.castVote(SESSION, "Carol", "", true);
        assertTrue(result.contains("弃票"), "弃票响应语义");

        assertTrue(game.abstainedVoters.contains("Carol"), "弃票写独立集合");
        assertFalse(game.votes.containsKey("Carol"), "不进 votes 票型统计");
        assertEquals(1, game.abstainedVoters.size());

        // quorum 在线数仍计；首票前候选人仍须完整返回且全为 0 票。
        Map<String, Object> status = svc.voteStatus(SESSION);
        assertEquals(3, status.get("total"), "弃票玩家仍计在线（quorum 口径）");
        assertEquals(3, ((List<?>) status.get("candidates")).size(), "无人投正常票也应返回全部候选人");
        assertEquals(1, status.get("abstained"));

        // 重复弃票拒绝
        String dup = svc.castVote(SESSION, "Carol", "", true);
        assertTrue(dup.contains("已弃票"), "重复弃票拒绝");

        // 弃票后可改投正常票（移出弃票集合，最终态=已投）
        String revote = svc.castVote(SESSION, "Carol", "Bob");
        assertTrue(revote.contains("投票给了 Bob"));
        assertFalse(game.abstainedVoters.contains("Carol"), "改投后移出弃票集合");
        assertEquals("Bob", game.votes.get("Carol"));
        Map<String, Object> status2 = svc.voteStatus(SESSION);
        assertEquals(1, status2.get("voted"), "仅 Carol 表态（弃票后改投）");
        assertEquals(0, status2.get("abstained"));
    }

    @Test
    @DisplayName("API-11b: 已投正常票后再弃票拒绝；3 参旧路径行为不变（改票覆盖保留）；非投票阶段拒绝")
    void abstainGuardAndBackwardCompat() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // 非投票阶段弃票 → 拒绝
        String notVotePhase = svc.castVote(SESSION, "Alice", "", true);
        assertTrue(notVotePhase.contains("当前不是投票阶段"));

        svc.startVoting(SESSION);
        svc.castVote(SESSION, "Alice", "Bob");
        String thenAbstain = svc.castVote(SESSION, "Alice", "", true);
        assertTrue(thenAbstain.contains("你已投票"), "已投正常票后再弃票拒绝");

        // 向后兼容：3 参路径仍允许改票覆盖（既有语义逐字节不变）
        String overwrite = svc.castVote(SESSION, "Alice", "Carol");
        assertTrue(overwrite.contains("投票给了 Carol"));
        assertEquals("Carol", game.votes.get("Alice"), "旧路径改票覆盖保留");
    }

    // ═══════════════════════════════════════════════════════════
    //  API-13: GET /api/script/goal —— 目标 HUD 规则模板（U4/U14）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-13: 按 phase 返回 {title, progress, detail} —— 搜证 x/y、质询计数 0 占位、投票 x/y")
    void goalRuleTemplatesByPhase() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // INVESTIGATION：搜证 x/y
        Map<String, Object> g1 = svc.getGoal(SESSION);
        assertEquals("investigation", g1.get("phase"));
        @SuppressWarnings("unchecked")
        Map<String, Object> goal1 = (Map<String, Object>) g1.get("goal");
        assertEquals("集齐线索", goal1.get("title"));
        assertEquals(Map.of("searched", 0, "total", 4), goal1.get("progress"));
        assertTrue(goal1.get("detail").toString().contains("0/4"));

        // 搜证后进度推进（零新状态：依赖既有 searchedLocations/locations）
        svc.search(SESSION, "Alice", "客厅");
        @SuppressWarnings("unchecked")
        Map<String, Object> goal1b = (Map<String, Object>) svc.getGoal(SESSION).get("goal");
        assertEquals(Map.of("searched", 1, "total", 4), goal1b.get("progress"), "搜证后进度推进");

        // DISCUSSION：质询计数 0 占位（press 计数阶段二才有，决策 U4）
        game.phase = ScriptGameService.Phase.DISCUSSION;
        @SuppressWarnings("unchecked")
        Map<String, Object> goal2 = (Map<String, Object>) svc.getGoal(SESSION).get("goal");
        assertEquals("找出矛盾发言", goal2.get("title"));
        assertEquals(Map.of("pressed", 0), goal2.get("progress"), "MVP 质询计数 0 占位");

        // VOTE：投票 x/y
        svc.startVoting(SESSION);
        svc.castVote(SESSION, "Alice", "Bob");
        @SuppressWarnings("unchecked")
        Map<String, Object> goal3 = (Map<String, Object>) svc.getGoal(SESSION).get("goal");
        assertEquals("指认真凶", goal3.get("title"));
        assertEquals(Map.of("voted", 1, "total", 3), goal3.get("progress"));
        assertTrue(goal3.get("detail").toString().contains("1/3"));

        // REVEAL / ENDED：等待揭晓 / 对局结束
        game.phase = ScriptGameService.Phase.REVEAL;
        assertEquals("等待揭晓", ((Map<?, ?>) svc.getGoal(SESSION).get("goal")).get("title"));
        game.phase = ScriptGameService.Phase.ENDED;
        assertEquals("对局结束", ((Map<?, ?>) svc.getGoal(SESSION).get("goal")).get("title"));
    }

    // ═══════════════════════════════════════════════════════════
    //  SSE：script_vote_progress / script_goal（broadcastToSession 定向，D1）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("SSE: 投票/弃票/退出 → script_vote_progress；阶段切换/搜证 → script_goal（均走定向通道）")
    void sseVoteProgressAndGoalEvents() {
        CaptureSSE sse = new CaptureSSE();
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService(), null, sse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));

        // init 阶段切换 → script_goal（investigation）
        assertTrue(sse.events.stream().anyMatch(e -> "script_goal".equals(e.getKey())),
                "阶段切换应推 script_goal");

        // 搜证 → script_goal（进度变化）
        sse.events.clear();
        svc.search(SESSION, "Alice", "客厅");
        assertTrue(sse.events.stream().anyMatch(e -> "script_goal".equals(e.getKey())),
                "搜证进度变化应推 script_goal");

        // 进投票 → script_goal（vote 模板）
        sse.events.clear();
        svc.startVoting(SESSION);
        assertTrue(sse.events.stream().anyMatch(e -> "script_goal".equals(e.getKey())
                && "vote".equals(((Map<?, ?>) e.getValue()).get("phase"))), "进投票应推 vote 模板 script_goal");

        // 投票 → script_vote_progress（含聚合载荷，无票面映射）
        sse.events.clear();
        svc.castVote(SESSION, "Alice", "Bob");
        Map<String, Object> progress = sse.events.stream()
                .filter(e -> "script_vote_progress".equals(e.getKey()))
                .map(Map.Entry::getValue)
                .map(v -> (Map<String, Object>) v)
                .findFirst().orElse(null);
        assertNotNull(progress, "投票应推 script_vote_progress");
        assertEquals(SESSION, progress.get("session_id"), "定向事件携带 session_id");
        assertEquals(3, progress.get("total"));
        assertEquals(1, progress.get("voted"));
        assertFalse(progress.containsKey("votes"), "SSE 载荷同样只出聚合不出投票人");

        // 弃票 → script_vote_progress（abstained 计数）
        sse.events.clear();
        svc.castVote(SESSION, "Carol", "", true);
        assertTrue(sse.events.stream().anyMatch(e -> "script_vote_progress".equals(e.getKey())
                && Integer.valueOf(1).equals(((Map<?, ?>) e.getValue()).get("abstained"))),
                "弃票应推 script_vote_progress 且 abstained=1");
    }

    @Test
    @DisplayName("单人前端局：NPC 保留为嫌疑人，但只要求扮演者投票")
    @SuppressWarnings("unchecked")
    void soloFrontendPlayerCanCompleteVoteWithoutWaitingForNpcInput() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.designateHumanPlayer(SESSION, "Alice");
        svc.startVoting(SESSION);

        Map<String, Object> progress = svc.voteStatus(SESSION);
        assertEquals(1, progress.get("total"), "仅扮演者需要在前端表态");
        assertEquals(0, progress.get("voted"));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) progress.get("candidates");
        assertEquals(3, candidates.size(), "NPC 仍必须作为嫌疑人候选展示");

        assertTrue(svc.castVote(SESSION, "Alice", "Bob").contains("投票给了"));
        Map<String, Object> goal = (Map<String, Object>) svc.getGoal(SESSION).get("goal");
        assertEquals(Map.of("voted", 1, "total", 1), goal.get("progress"));
    }
}
