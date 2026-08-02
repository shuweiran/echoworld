package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.controller.WerewolfController;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 狼人杀后端授权批次（P-0802-F）修复验证测试 —— 逐项覆盖任务清单：
 * <ul>
 *   <li>F1/F2：customRoles 宽容解析（D-014 纪律：大小写不敏感 + 中英文别名）</li>
 *   <li>F3：init 返回 session_id + 注册 RouterService（G0-1）</li>
 *   <li>F4：werewolf_* SSE 事件流（夜间结算/阶段/讨论发言/等待真人，G0-3）</li>
 *   <li>F5：autoPlay 整局自动推进到 ENDED（AI 夜间行动器 + 讨论引擎 + 投票自动结算闭环）</li>
 *   <li>F6：白天讨论人类发言接入对话引擎（discussionSay → transcript）</li>
 *   <li>F7：AI 猎人夜间死亡自动反杀（G1-1 升级）</li>
 *   <li>F8：白天讨论接对话引擎产出发言记录（G0-4，含 werewolf_speech 推送）</li>
 * </ul>
 *
 * <p>风格：直构服务（mock LLMClient + 录制 SseBroadcaster + 真实 ApprovalService），
 * 与 ScriptGameDiscussionTest / WerewolfGameSmokeTest 一致；autoPlay 用种子固定 planner + 短审批延迟保证确定性。
 */
class WerewolfGameFixTest {

    /** 录制式 SSE 广播器：捕获 (event, data) 供断言。 */
    static class RecordingSse implements SseBroadcaster {
        final List<Map.Entry<String, Map<?, ?>>> events = new CopyOnWriteArrayList<>();
        @Override
        public void broadcast(String eventType, Object data) {
            events.add(Map.entry(eventType, data instanceof Map<?, ?> m ? m : Map.of()));
        }
        List<Map<?, ?>> of(String type) {
            return events.stream().filter(e -> e.getKey().equals(type)).map(Map.Entry::getValue).toList();
        }
        boolean has(String type) { return events.stream().anyMatch(e -> e.getKey().equals(type)); }
    }

    private static final String SAMPLE_LINE = "我认为狼人就藏在我们中间。情绪：平静。";

    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        org.mockito.Mockito.when(llm.callSync(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            Thread.sleep(30);
            return SAMPLE_LINE;
        });
        return llm;
    }

    /** 显式角色 6 人局：A/B 狼、C 预言家、D 女巫、E 猎人、F 村民（me）。 */
    private Map<String, String> sixRoles() {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("A", "wolf");
        roles.put("B", "werewolf");
        roles.put("C", "预言家");
        roles.put("D", "witch");
        roles.put("E", "hunter");
        roles.put("F", "villager");
        return roles;
    }

    private List<String> sixPlayers() {
        return new ArrayList<>(List.of("A", "B", "C", "D", "E", "F"));
    }

    /** 轮询等待条件成立，超时抛断言失败。 */
    private void await(String desc, long timeoutMs, java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("等待超时: " + desc);
    }

    // ═══════════════════════════════════════════════════════════
    //  F1 / F2：customRoles 宽容解析（D-014 纪律）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("F1: parseRole 宽容解析 —— 小写/大小写混合/中英文别名/空白 → 枚举；非法 → null")
    void lenientRoleParse() {
        assertEquals(WerewolfService.Role.WEREWOLF, WerewolfService.parseRole("wolf"));
        assertEquals(WerewolfService.Role.WEREWOLF, WerewolfService.parseRole("WEREWOLF"));
        assertEquals(WerewolfService.Role.WEREWOLF, WerewolfService.parseRole(" 狼人 "));
        assertEquals(WerewolfService.Role.SEER, WerewolfService.parseRole("预言家"));
        assertEquals(WerewolfService.Role.SEER, WerewolfService.parseRole("seer"));
        assertEquals(WerewolfService.Role.WITCH, WerewolfService.parseRole("witch"));
        assertEquals(WerewolfService.Role.WITCH, WerewolfService.parseRole("女巫"));
        assertEquals(WerewolfService.Role.HUNTER, WerewolfService.parseRole("  hunter  "));
        assertEquals(WerewolfService.Role.HUNTER, WerewolfService.parseRole("猎人"));
        assertEquals(WerewolfService.Role.VILLAGER, WerewolfService.parseRole("villager"));
        assertEquals(WerewolfService.Role.VILLAGER, WerewolfService.parseRole("平民"));
        assertNull(WerewolfService.parseRole("xxxyyy"), "非法角色返回 null");
        assertNull(WerewolfService.parseRole(null));
    }

    @Test
    @DisplayName("F2: initGame 用别名 customRoles 不再抛异常 —— wolf/预言家 等别名正确解析进角色表")
    void initWithAliasRoles() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        String sid = "fix-f2";
        Map<String, Object> state = svc.initGame(sid, sixPlayers(), sixRoles());
        assertEquals("night", state.get("phase"));
        assertEquals(6, ((List<?>) state.get("alive")).size());
        WerewolfService.GameState g = svc.getGame(sid);
        assertEquals(WerewolfService.Role.WEREWOLF, g.roles.get("A"), "wolf → WEREWOLF");
        assertEquals(WerewolfService.Role.WEREWOLF, g.roles.get("B"), "werewolf → WEREWOLF");
        assertEquals(WerewolfService.Role.SEER, g.roles.get("C"), "预言家 → SEER");
        assertEquals(WerewolfService.Role.WITCH, g.roles.get("D"), "witch → WITCH");
        assertEquals(WerewolfService.Role.HUNTER, g.roles.get("E"), "hunter → HUNTER");
        assertEquals(WerewolfService.Role.VILLAGER, g.roles.get("F"), "villager → VILLAGER");
    }

    @Test
    @DisplayName("F2b: initGame 非法角色回退村民不抛异常")
    void initWithUnknownRoleFallsBackVillager() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        String sid = "fix-f2b";
        Map<String, String> roles = Map.of("A", "wolf", "B", "not_a_role");
        svc.initGame(sid, sixPlayers(), roles);
        assertEquals(WerewolfService.Role.WEREWOLF, svc.getGame(sid).roles.get("A"));
        assertEquals(WerewolfService.Role.VILLAGER, svc.getGame(sid).roles.get("B"), "非法角色回退村民");
    }

    // ═══════════════════════════════════════════════════════════
    //  F3：init 返回 session_id + 注册 RouterService（G0-1）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("F3: controller init 返回 session_id + 注册 router —— 前端可拿到对局标识")
    void initReturnsSessionIdAndRegistersRouter() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        RouterService routerMock = mock(RouterService.class);
        WerewolfController ctl = new WerewolfController(svc, routerMock);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("players", sixPlayers());
        body.put("roles", sixRoles());
        var resp = ctl.init("F", "", "", body);
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> state = resp.getBody();
        assertNotNull(state.get("session_id"), "init 响应必须返回 session_id");
        String sid = (String) state.get("session_id");
        assertEquals(12, sid.length(), "12 位 session_id");
        verify(routerMock).setWerewolfGame(any());
        // status 响应同样带 session_id
        var status = ctl.getStatus("", "F");
        assertEquals(sid, status.getBody().get("session_id"), "status 返回同一 session_id");
    }

    // ═══════════════════════════════════════════════════════════
    //  F4：werewolf_* SSE 事件流（G0-3）+ F8：讨论引擎发言（G0-4）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("F4: SSE 事件流 —— init 玩家/角色 + 夜间结算 + 阶段流转 + 讨论发言 + 等待真人投票")
    void sseEventFlowWithDiscussion() throws Exception {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), mockLlm(), sse);
        svc.setPlanner(new WerewolfAiPlanner(42L));
        svc.setAutoApproveMs(100);
        svc.setWitchPoisonProbability(0.0);
        String sid = "fix-f4";

        svc.initGame(sid, sixPlayers(), sixRoles());
        svc.setHumanPlayers(sid, Set.of("F"));
        svc.setAutoPlay(sid, true);
        svc.notifyGameInit(sid, "F");
        svc.startNight(sid);

        // init 事件：玩家列表 + 人类角色（villager）
        assertTrue(sse.has("werewolf_player_update"), "init 推送玩家列表");
        assertTrue(sse.of("werewolf_my_role").stream().anyMatch(m -> "villager".equals(m.get("role"))), "人类角色推送");

        // 夜间结算（AI 全员行动完毕 → 自动 resolveNight）
        await("夜间结算推送", 10_000, () -> sse.has("werewolf_night_result"));
        // 阶段流转到白天讨论（夜晚无死亡：女巫首夜救被刀者）
        await("进入白天讨论", 10_000, () -> sse.of("werewolf_phase").stream()
                .anyMatch(m -> "day_discuss".equals(m.get("phase"))));

        // 讨论引擎驱动发言（F8）：werewolf_speech 事件 + transcript 落盘
        await("讨论发言推送", 15_000, () -> sse.has("werewolf_speech"));
        assertFalse(svc.getGame(sid).discussionTranscript.isEmpty(), "讨论 transcript 非空");

        // 讨论结束自动进投票 + AI 投票后等待真人投票
        await("进入投票", 15_000, () -> sse.of("werewolf_phase").stream()
                .anyMatch(m -> "day_vote".equals(m.get("phase"))));
        await("等待真人投票", 15_000, () -> sse.has("werewolf_wait_human"));
        assertEquals(WerewolfService.Phase.DAY_VOTE, svc.getGame(sid).phase);

        // 真人投票 → 全员投完 → 自动结算（审批门自动批准 100ms）
        svc.castVote(sid, "F", "A");
        await("投票结算推送", 10_000, () -> sse.of("werewolf_vote_update").stream()
                .anyMatch(m -> m.containsKey("exiled")));
    }

    // ═══════════════════════════════════════════════════════════
    //  F5：autoPlay 整局自动推进到 ENDED（全 AI 局）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("F5: autoPlay 整局闭环 —— 全 AI 局自动打完到 ENDED，winner 非空 + game_over 推送")
    void autoPlayFullGameToEnded() throws Exception {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), mockLlm(), sse);
        svc.setPlanner(new WerewolfAiPlanner(123L));
        svc.setAutoApproveMs(100);
        svc.setWitchPoisonProbability(0.0);
        String sid = "fix-f5";

        svc.initGame(sid, sixPlayers(), sixRoles());
        svc.setHumanPlayers(sid, Set.of()); // 全 AI
        svc.setAutoPlay(sid, true);
        svc.startNight(sid);

        await("整局打完到 ENDED", 30_000, () -> {
            WerewolfService.GameState g = svc.getGame(sid);
            return g.phase == WerewolfService.Phase.ENDED;
        });
        WerewolfService.GameState g = svc.getGame(sid);
        assertTrue(!g.winner.isEmpty(), "有胜负判定，winner=" + g.winner);
        assertTrue(sse.has("werewolf_game_over"), "终局推送");
        assertFalse(g.discussionTranscript.isEmpty(), "讨论引擎至少跑了一轮");
        assertTrue(sse.of("werewolf_night_result").size() >= 1, "至少一次夜间结算推送");
    }

    // ═══════════════════════════════════════════════════════════
    //  F6：白天讨论人类发言接入对话引擎
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("F6: discussionSay —— 人类白天发言进入讨论引擎 transcript")
    void humanSayLandsInTranscript() throws Exception {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), mockLlm(), sse);
        svc.setPlanner(new WerewolfAiPlanner(42L));
        String sid = "fix-f6";

        svc.initGame(sid, sixPlayers(), sixRoles());
        svc.setHumanPlayers(sid, Set.of("F"));
        svc.setAutoPlay(sid, true);
        svc.startNight(sid);

        // 等讨论激活后人类发言
        await("讨论激活", 10_000, () -> svc.getGame(sid).discussionActive);
        Map<String, Object> say = svc.discussionSay(sid, "F", "我认为 A 就是狼人，大家投 A");
        assertEquals(Boolean.TRUE, say.get("ok"), "发言入队成功");

        // 发言最终出现在 transcript
        await("人类发言入 transcript", 15_000, () -> svc.getGame(sid).discussionTranscript.stream()
                .anyMatch(t -> "F".equals(t.get("speaker")) && t.get("message") != null
                        && t.get("message").contains("我认为 A 就是狼人")));
    }

    // ═══════════════════════════════════════════════════════════
    //  F7：AI 猎人夜间死亡自动反杀（G1-1 升级）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("F7: AI 猎人夜间死亡自动反杀 —— 狼刀猎人 → 猎人带走一名存活 → 结算完成")
    void aiHunterAutoShootOnNightDeath() throws Exception {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), mockLlm(), sse);
        svc.setPlanner(new WerewolfAiPlanner(1L));
        String sid = "fix-f7";

        svc.initGame(sid, sixPlayers(), sixRoles());
        WerewolfService.GameState g = svc.getGame(sid);
        svc.setHumanPlayers(sid, Set.of("F"));
        svc.setAutoPlay(sid, true);
        // 直接构造：狼刀目标 = 猎人 E（决策预置，绕过随机）；女巫解药预置已用（否则首夜会救 E，猎人死不了）
        g.wolfTarget = "E";
        g.nightDecisions.add("kill");
        g.witchUsedAntidote = true;
        svc.startNight(sid);

        await("猎人反杀结算完成", 10_000, () -> !g.alive.contains("E"));
        assertTrue(g.eliminated.stream().anyMatch(e -> "E".equals(e.get("name"))), "猎人进入淘汰记录");
        assertTrue(g.eliminated.stream().anyMatch(e -> "被猎人反击击杀".equals(e.get("reason"))),
                "AI 猎人自动反杀一名玩家");
        // 4 人存活（6 - 猎人 - 反杀目标），狼 2 ≥ 好人 2 → 狼胜终局
        assertEquals(4, g.alive.size(), "猎人+反杀目标双亡后剩 4 人");
        assertEquals("werewolf", g.winner, "狼队人数 ≥ 好人 → 狼胜");
        assertEquals(WerewolfService.Phase.ENDED, g.phase);
    }
}
