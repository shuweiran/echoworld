package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.WerewolfController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 狼人杀状态机冒烟测试（调研报告 docs/狼人杀重构调研-20260802.md G0-7：后端零测试覆盖 → 补测试骨架）。
 *
 * <p>背景：狼人杀后端（WerewolfService/WerewolfController）属 AGENTS.md 禁动文件，本批次仅补测试
 * 锁定现有行为基线，不做任何后端改造。测试覆盖：
 * <ul>
 *   <li>W-1：init 全量进局 —— players 全量进 GameState、6 人默认角色分布（2 狼/预言家/女巫/猎人/村民）</li>
 *   <li>W-2：controller init 的 body{players, roles} 分支 —— 全量进局 + 自定义角色生效（前端修复后走的路径）</li>
 *   <li>W-3：夜间行动 —— 狼刀/预言家验/女巫救毒 + phase 守卫（非夜晚拒绝）</li>
 *   <li>W-4：resolveNight 结算 —— 狼刀死亡结算 → DAY_DISCUSS</li>
 *   <li>W-5：猎人开枪 —— 锁定现状：resolveNight 将 hunterCanShoot 置 false 后 hunterShoot 被拒（G1-1 升级证据，禁动未修）</li>
 *   <li>W-6：投票全流程（含 D7 审批门批准）—— startVoting → 全员投票 → 挂起审批 → 批准 → 放逐 → 回 NIGHT round++</li>
 *   <li>W-7：平票 —— 互投平票无人放逐</li>
 *   <li>W-8：胜判定 villager 胜 —— 两狼先后出局 → ENDED winner=villager</li>
 *   <li>W-9：胜判定 werewolf 胜 —— 狼刀预言家后 wolves≥villagers → ENDED winner=werewolf</li>
 *   <li>W-10：toMap 视角脱敏 —— 狼人互见 / 村民不见狼人角色 / 预言家查验结果</li>
 *   <li>W-11：终态守卫 —— ENDED 后夜间行动/投票被 phase 守卫拒绝</li>
 * </ul>
 *
 * <p>直接构造 WerewolfService（真实 ApprovalService），与 ScriptGameDmTest 风格一致；审批门
 * 分支采用「异步调用 + 等待 pending + approve」模式；每个测试独立 sessionId 防串扰。
 */
class WerewolfGameSmokeTest {

    private WerewolfService newService() {
        return new WerewolfService(new ApprovalService());
    }

    /** 6 人标准局：2 狼 / 1 预言家 / 1 女巫 / 1 猎人 / 1 村民（assignDefaultRoles n≥6 分支）。 */
    private List<String> sixPlayers() {
        return new ArrayList<>(List.of("苏哲", "林诗", "老王", "小美", "阿强", "me"));
    }

    /** 找指定角色的玩家名（首个匹配）。 */
    private String playerWithRole(WerewolfService.GameState g, WerewolfService.Role role) {
        return g.roles.entrySet().stream()
            .filter(e -> e.getValue() == role)
            .map(Map.Entry::getKey)
            .findFirst().orElseThrow(() -> new AssertionError("局内无角色 " + role));
    }

    /** 投票结算：异步触发 resolveVote → 等审批挂起 → 批准 → 取结果（D7 审批门批准路径）。 */
    private Map<String, Object> resolveVoteWithApproval(WerewolfService svc, ApprovalService approval,
                                                        String sid) throws Exception {
        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(sid));
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !"pending".equals(approval.getStatus(sid))) {
            Thread.sleep(20);
        }
        assertEquals("pending", approval.getStatus(sid), "投票结算应挂起等待审批");
        assertTrue(approval.approve(sid), "审批应可批准");
        return fut.get(5, TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════════
    //  W-1 / W-2：init 全量进局
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("W-1: init 全量进局 —— 6 人全部进入 GameState，默认角色分布 2狼/预言家/女巫/猎人/村民")
    void initAllPlayersEnterGameState() {
        WerewolfService svc = newService();
        Map<String, Object> state = svc.initGame("w-w1", sixPlayers(), null);

        assertEquals("night", state.get("phase"));
        assertEquals(1, state.get("round"));
        @SuppressWarnings("unchecked")
        List<String> alive = (List<String>) state.get("alive");
        assertEquals(6, alive.size(), "全部 6 名玩家进局");
        assertEquals("苏哲", alive.get(0), "玩家名保持传入顺序");

        WerewolfService.GameState g = svc.getGame("w-w1");
        assertEquals(6, g.roles.size(), "6 名玩家均有角色");
        assertEquals(2, g.roles.values().stream().filter(r -> r == WerewolfService.Role.WEREWOLF).count(), "2 狼");
        assertEquals(1, g.roles.values().stream().filter(r -> r == WerewolfService.Role.SEER).count(), "1 预言家");
        assertEquals(1, g.roles.values().stream().filter(r -> r == WerewolfService.Role.WITCH).count(), "1 女巫");
        assertEquals(1, g.roles.values().stream().filter(r -> r == WerewolfService.Role.HUNTER).count(), "1 猎人");
        assertEquals(1, g.roles.values().stream().filter(r -> r == WerewolfService.Role.VILLAGER).count(), "1 村民");
    }

    @Test
    @DisplayName("W-2: controller init body{players,roles} 分支 —— 全量进局 + 自定义职业生效（前端修复后路径）")
    void controllerInitBodyPlayersAndRoles() {
        WerewolfService svc = newService();
        WerewolfController ctl = new WerewolfController(svc);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("players", sixPlayers());
        Map<String, String> roles = new LinkedHashMap<>();
        // P-0802-H 修复（D-014 宽容解析）：wolf/预言家 等别名已支持，不再抛异常
        roles.put("苏哲", "wolf");
        roles.put("林诗", "seer");
        body.put("roles", roles);

        ResponseEntity<Map<String, Object>> resp = ctl.init("me", "", "", body);
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> state = resp.getBody();
        @SuppressWarnings("unchecked")
        List<String> alive = (List<String>) state.get("alive");
        assertEquals(6, alive.size(), "body.players 全量进局（不再退化为 1 人局）");
        assertTrue(alive.contains("苏哲") && alive.contains("林诗"), "AI 角色已进入 GameState");

        // controller 生成的 sessionId 为随机 12 位且不返回（G0-1 缺口，禁动未修）；经 status 端点按玩家名定位取局
        ResponseEntity<Map<String, Object>> status = ctl.getStatus("", "苏哲");
        assertEquals(200, status.getStatusCode().value());
        assertEquals("werewolf", status.getBody().get("your_role"), "自定义角色 werewolf 生效");
        ResponseEntity<Map<String, Object>> seerStatus = ctl.getStatus("", "林诗");
        assertEquals("seer", seerStatus.getBody().get("your_role"), "自定义角色 seer 生效");
    }

    // ═══════════════════════════════════════════════════════════
    //  W-3 / W-4：夜间行动与结算
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("W-3: 夜间行动 —— 狼刀/预言家验/女巫救毒 + 非夜晚 phase 守卫拒绝")
    void nightActionsAndPhaseGuard() {
        WerewolfService svc = newService();
        String sid = "w-w3";
        svc.initGame(sid, sixPlayers(), null);
        WerewolfService.GameState g = svc.getGame(sid);

        String wolf = playerWithRole(g, WerewolfService.Role.WEREWOLF);
        String seer = playerWithRole(g, WerewolfService.Role.SEER);
        String witch = playerWithRole(g, WerewolfService.Role.WITCH);
        String villager = playerWithRole(g, WerewolfService.Role.VILLAGER);

        assertTrue(svc.recordNightAction(sid, wolf, "kill", villager).contains("狼人已选择目标"), "狼人刀目标成功");
        assertTrue(svc.recordNightAction(sid, seer, "check", wolf).contains("身份是 werewolf"), "预言家查验返回身份");
        assertTrue(svc.recordNightAction(sid, witch, "save", villager).contains("解药"), "女巫使用解药");
        assertTrue(svc.recordNightAction(sid, witch, "poison", seer).contains("毒药"), "女巫使用毒药");
        assertTrue(svc.recordNightAction(sid, wolf, "kill", wolf).contains("你不能执行此行动"), "狼不能刀自己");
        // 非狼角色不能 kill
        assertTrue(svc.recordNightAction(sid, villager, "kill", wolf).contains("你不能执行此行动"), "村民不能刀人");
        // 女巫解药/毒药各限一次
        assertTrue(svc.recordNightAction(sid, witch, "save", wolf).contains("无法使用解药"), "解药仅一次");
        assertTrue(svc.recordNightAction(sid, witch, "poison", wolf).contains("无法使用毒药"), "毒药仅一次");

        // phase 守卫：DAY_VOTE 阶段夜间行动被拒
        svc.resolveNight(sid);
        svc.startVoting(sid);
        assertEquals("day_vote", svc.getGame(sid).phase.name().toLowerCase());
        assertTrue(svc.recordNightAction(sid, wolf, "kill", villager).contains("当前不是夜晚阶段"), "非夜晚拒绝夜间行动");
    }

    @Test
    @DisplayName("W-4: resolveNight 结算 —— 狼刀+毒药双重死亡 → 淘汰记录 → DAY_DISCUSS")
    void resolveNightSettlement() {
        WerewolfService svc = newService();
        String sid = "w-w4";
        svc.initGame(sid, sixPlayers(), null);
        WerewolfService.GameState g = svc.getGame(sid);

        String wolf = playerWithRole(g, WerewolfService.Role.WEREWOLF);
        String witch = playerWithRole(g, WerewolfService.Role.WITCH);
        String seer = playerWithRole(g, WerewolfService.Role.SEER);
        // 毒另一狼（毒村民会致 2 狼 ≥ 2 村民直接狼胜，验不到 day_discuss）
        String wolf2 = g.roles.entrySet().stream()
            .filter(e -> e.getValue() == WerewolfService.Role.WEREWOLF && !e.getKey().equals(wolf))
            .map(Map.Entry::getKey).findFirst().orElseThrow();

        svc.recordNightAction(sid, wolf, "kill", seer);
        svc.recordNightAction(sid, witch, "poison", wolf2);

        Map<String, Object> result = svc.resolveNight(sid);
        @SuppressWarnings("unchecked")
        List<String> died = (List<String>) result.get("died");
        assertEquals(2, died.size(), "狼刀+毒药双亡");
        assertTrue(died.contains(seer) && died.contains(wolf2));
        assertEquals("day_discuss", result.get("phase"), "夜晚结算后进入白天讨论");
        assertEquals(4, g.alive.size(), "6 人死 2 剩 4");
        assertEquals(2, g.eliminated.size(), "淘汰记录 2 条");
    }

    // ═══════════════════════════════════════════════════════════
    //  W-5：猎人开枪（现状 bug 锁定，禁动未修）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("W-5: 猎人开枪 —— 修复后：夜间死亡保留一次开枪机会，可反杀狼人（G1-1）")
    void hunterShootBlockedAfterNightDeath() {
        WerewolfService svc = newService();
        String sid = "w-w5";
        svc.initGame(sid, sixPlayers(), null);
        WerewolfService.GameState g = svc.getGame(sid);

        String wolf = playerWithRole(g, WerewolfService.Role.WEREWOLF);
        String hunter = playerWithRole(g, WerewolfService.Role.HUNTER);
        String villager = playerWithRole(g, WerewolfService.Role.VILLAGER);

        // 猎人未死不能开枪
        assertTrue(svc.hunterShoot(sid, hunter, wolf).contains("只有被淘汰的猎人才能开枪"), "未死猎人不能开枪");

        // 狼刀猎人 → 夜间死亡
        svc.recordNightAction(sid, wolf, "kill", hunter);
        svc.resolveNight(sid);
        assertFalse(g.alive.contains(hunter), "猎人被狼刀死亡");
        assertTrue(g.eliminated.stream().anyMatch(e -> hunter.equals(e.get("name"))), "猎人进入淘汰记录");

        // P-0802-F 修复（G1-1）：夜间死亡不再置 hunterCanShoot=false，猎人保留一次开枪机会
        String msg = svc.hunterShoot(sid, hunter, wolf);
        assertTrue(msg.contains("开枪击杀了"), "猎人夜间死亡后可开枪反杀，实际返回: " + msg);
        assertFalse(g.alive.contains(wolf), "被枪杀的狼人出局");
        assertTrue(g.eliminated.stream().anyMatch(e -> wolf.equals(e.get("name"))), "狼人进入淘汰记录");
        // 开枪机会被消费：再次开枪被拒
        assertTrue(svc.hunterShoot(sid, hunter, villager).contains("猎人已经开过枪了"), "开枪机会仅一次");
    }

    // ═══════════════════════════════════════════════════════════
    //  W-6 / W-7：投票全流程
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("W-6: 投票全流程 —— 讨论→投票→全员投票→审批批准→放逐最高票→回 NIGHT round++")
    void voteFlowWithApproval() throws Exception {
        WerewolfService svc = newService();
        ApprovalService approval = new ApprovalService();
        WerewolfService svc2 = new WerewolfService(approval);
        String sid = "w-w6";
        svc2.initGame(sid, sixPlayers(), null);
        WerewolfService.GameState g = svc2.getGame(sid);

        // 夜晚无行动 → 白天讨论
        svc2.resolveNight(sid);
        assertEquals(WerewolfService.Phase.DAY_DISCUSS, g.phase);

        // 白天讨论 → 投票
        svc2.startVoting(sid);
        assertEquals(WerewolfService.Phase.DAY_VOTE, g.phase);
        assertEquals(0, g.votes.size(), "进入投票清空票表");

        // 全员投票：5 人投狼人（狼人自己不能投自己，投别人）
        String wolf = playerWithRole(g, WerewolfService.Role.WEREWOLF);
        String other = g.alive.stream().filter(p -> !p.equals(wolf)).findFirst().orElseThrow();
        for (String p : g.alive) {
            if (!p.equals(wolf)) {
                assertTrue(svc2.castVote(sid, p, wolf).contains("投票给了"), p + " 投票给狼人");
            } else {
                assertTrue(svc2.castVote(sid, p, other).contains("投票给了"), "狼人投他人");
            }
        }
        assertEquals(6, g.votes.size(), "6 人全部投票");
        assertTrue(svc2.castVote(sid, wolf, wolf).contains("不能投自己"), "不能投自己");

        // 结算（D7 审批门：挂起 → 批准）
        Map<String, Object> result = resolveVoteWithApproval(svc2, approval, sid);
        assertEquals(wolf, result.get("exiled"), "最高票狼人被放逐");
        assertEquals("approved", result.get("approval"));
        assertEquals("night", result.get("phase"), "放逐后回夜晚");
        assertEquals(2, result.get("round"), "轮次 +1");
        assertFalse(g.alive.contains(wolf), "狼人被淘汰");
    }

    @Test
    @DisplayName("W-7: 平票 —— 互投平票无人被放逐，回 NIGHT round++")
    void tieVoteNoExile() throws Exception {
        WerewolfService svc = newService();
        ApprovalService approval = new ApprovalService();
        WerewolfService svc2 = new WerewolfService(approval);
        String sid = "w-w7";
        svc2.initGame(sid, sixPlayers(), null);
        WerewolfService.GameState g = svc2.getGame(sid);

        svc2.resolveNight(sid);
        svc2.startVoting(sid);

        // 两人互投 → 平票（其余 4 人不投）
        String p1 = g.alive.get(0);
        String p2 = g.alive.get(1);
        svc2.castVote(sid, p1, p2);
        svc2.castVote(sid, p2, p1);

        Map<String, Object> result = resolveVoteWithApproval(svc2, approval, sid);
        assertEquals("", result.get("exiled"), "平票无人被放逐");
        assertTrue(result.get("reason").toString().contains("平票"), "平票原因说明");
        assertEquals("night", result.get("phase"), "平票后回夜晚");
        assertEquals(2, result.get("round"));
        assertEquals(6, g.alive.size(), "无人死亡");
    }

    // ═══════════════════════════════════════════════════════════
    //  W-8 / W-9：胜判定
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("W-8: 胜判定 villager 胜 —— 两狼先后出局 → ENDED winner=villager")
    void villagerWinWhenAllWolvesOut() throws Exception {
        WerewolfService svc = newService();
        ApprovalService approval = new ApprovalService();
        WerewolfService svc2 = new WerewolfService(approval);
        String sid = "w-w8";
        svc2.initGame(sid, sixPlayers(), null);
        WerewolfService.GameState g = svc2.getGame(sid);

        List<String> wolves = g.roles.entrySet().stream()
            .filter(e -> e.getValue() == WerewolfService.Role.WEREWOLF)
            .map(Map.Entry::getKey).toList();
        assertEquals(2, wolves.size());

        // 第一轮：白天放逐狼 1
        svc2.resolveNight(sid);
        svc2.startVoting(sid);
        for (String p : g.alive) {
            if (!p.equals(wolves.get(0))) svc2.castVote(sid, p, wolves.get(0));
            else svc2.castVote(sid, p, wolves.get(1));
        }
        Map<String, Object> r1 = resolveVoteWithApproval(svc2, approval, sid);
        assertEquals(wolves.get(0), r1.get("exiled"));
        assertEquals("", r1.get("winner"), "狼 1 出局后未判胜（剩 1 狼）");
        assertEquals("night", r1.get("phase"));

        // 第二轮：白天放逐狼 2 → 狼 0 村民胜
        svc2.resolveNight(sid);
        svc2.startVoting(sid);
        List<String> aliveNow = new ArrayList<>(g.alive);
        for (String p : aliveNow) {
            if (!p.equals(wolves.get(1))) svc2.castVote(sid, p, wolves.get(1));
            else svc2.castVote(sid, p, aliveNow.stream().filter(q -> !q.equals(p)).findFirst().orElseThrow());
        }
        Map<String, Object> r2 = resolveVoteWithApproval(svc2, approval, sid);
        assertEquals(wolves.get(1), r2.get("exiled"));
        assertEquals("villager", r2.get("winner"), "狼全灭村民胜");
        assertEquals("ended", r2.get("phase"), "胜判定进入终态");
        assertEquals(Boolean.TRUE, r2.get("game_over"));
    }

    @Test
    @DisplayName("W-9: 胜判定 werewolf 胜 —— 狼刀预言家后 wolves≥villagers → ENDED winner=werewolf")
    void werewolfWinWhenOutnumber() {
        // 4 人局：2 狼 1 预言家 1 女巫（assignDefaultRoles n≥4 分支）
        WerewolfService svc = newService();
        String sid = "w-w9";
        svc.initGame(sid, new ArrayList<>(List.of("A", "B", "C", "D")), null);
        WerewolfService.GameState g = svc.getGame(sid);
        assertEquals(2, g.roles.values().stream().filter(r -> r == WerewolfService.Role.WEREWOLF).count());

        String wolf = playerWithRole(g, WerewolfService.Role.WEREWOLF);
        String seer = playerWithRole(g, WerewolfService.Role.SEER);
        assertTrue(svc.recordNightAction(sid, wolf, "kill", seer).contains("狼人已选择目标"));
        Map<String, Object> result = svc.resolveNight(sid);
        assertEquals("werewolf", result.get("winner"), "狼 2 人 ≥ 村民 1 人 → 狼胜");
        assertEquals("ended", result.get("phase"));
        assertEquals(Boolean.TRUE, result.get("game_over"));
    }

    // ═══════════════════════════════════════════════════════════
    //  W-10 / W-11：视角脱敏与终态守卫
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("W-10: toMap 视角脱敏 —— 狼人互见、村民不见狼人角色、预言家查验结果仅本人")
    void toMapViewDesensitization() {
        WerewolfService svc = newService();
        String sid = "w-w10";
        svc.initGame(sid, sixPlayers(), null);
        WerewolfService.GameState g = svc.getGame(sid);

        String wolf = playerWithRole(g, WerewolfService.Role.WEREWOLF);
        String villager = playerWithRole(g, WerewolfService.Role.VILLAGER);

        // 狼人视角（P-0802-F 修复 G1-3）：toMap 输出 visible —— 狼人互见彼此与自己的身份
        Map<String, Object> wolfView = g.toMap(wolf);
        assertTrue(wolfView.containsKey("visible"), "toMap 应输出 visible 键（G1-3 修复）");
        @SuppressWarnings("unchecked")
        Map<String, String> wolfVisible = (Map<String, String>) wolfView.get("visible");
        assertEquals("werewolf", wolfView.get("your_role"));
        assertEquals("werewolf", wolfVisible.get(wolf), "狼人可见自己身份");
        List<String> wolves = g.roles.entrySet().stream()
            .filter(e -> e.getValue() == WerewolfService.Role.WEREWOLF)
            .map(Map.Entry::getKey).toList();
        for (String w : wolves) {
            assertEquals("werewolf", wolfVisible.get(w), "狼人可见同伴 " + w + " 身份");
        }
        assertEquals(2, wolfVisible.size(), "狼人 visible 恰为两名狼人（自己+同伴）");

        // 村民视角：visible 只含自己的角色（your_role）
        Map<String, Object> villagerView = g.toMap(villager);
        assertEquals("villager", villagerView.get("your_role"));
        assertTrue(villagerView.containsKey("visible"), "村民视角也有 visible 键");
        @SuppressWarnings("unchecked")
        Map<String, String> villagerVisible = (Map<String, String>) villagerView.get("visible");
        assertEquals(1, villagerVisible.size(), "村民 visible 仅自己");
        assertEquals("villager", villagerVisible.get(villager));
        assertFalse(villagerVisible.containsKey(wolf), "村民不可见狼人身份");

        String seer = playerWithRole(g, WerewolfService.Role.SEER);
        svc.recordNightAction(sid, seer, "check", wolf);
        Map<String, Object> seerView = g.toMap(seer);
        assertEquals("werewolf", seerView.get("seer_result"), "预言家看到查验结果");
        assertFalse(villagerView.containsKey("seer_result"), "他人视角无查验结果");
    }

    @Test
    @DisplayName("W-11: 终态守卫 —— ENDED 后夜间行动/投票被 phase 守卫拒绝")
    void endedPhaseGuard() {
        WerewolfService svc = newService();
        String sid = "w-w11";
        svc.initGame(sid, new ArrayList<>(List.of("A", "B", "C", "D")), null);
        WerewolfService.GameState g = svc.getGame(sid);

        String wolf = playerWithRole(g, WerewolfService.Role.WEREWOLF);
        String seer = playerWithRole(g, WerewolfService.Role.SEER);
        svc.recordNightAction(sid, wolf, "kill", seer);
        svc.resolveNight(sid); // werewolf 胜 → ENDED
        assertEquals(WerewolfService.Phase.ENDED, g.phase);
        assertEquals("werewolf", g.winner);

        assertTrue(svc.recordNightAction(sid, wolf, "kill", seer).contains("当前不是夜晚阶段"), "ENDED 后夜间行动拒绝");
        assertTrue(svc.castVote(sid, wolf, seer).contains("当前不是投票阶段"), "ENDED 后投票拒绝");
        assertTrue(svc.castVote(sid, "A", "B").contains("当前不是投票阶段"), "ENDED 后他人投票也拒绝");
    }
}
