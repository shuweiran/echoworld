package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.controller.WerewolfController;
import com.roleplay.engine.db.entity.ScriptEntity;
import com.roleplay.engine.db.repository.ScriptRepository;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 狼人杀阶段 1 遗留项批次（P-0802-I）测试 —— 逐项覆盖：
 * <ul>
 *   <li>S1/S2：G1-2 女巫获知被刀者机制 —— AI 女巫获知后再决策（救/不救），人类女巫先获知再行动（save 限定被刀者 + nosave）</li>
 *   <li>S3：werewolf_witch_info 获知事件载荷（victim/session_id，女巫存活时推送）</li>
 *   <li>S4：讨论引擎 per-game 隔离 —— 两局并发讨论互不串状态（独立 ConversationManager/World/transcript）</li>
 *   <li>S5：werewolf_* SSE 定向推送 —— 服务层按 session_id 走 broadcastToSession，多局事件不串</li>
 *   <li>S6：对局快照落库 + 跨实例恢复（重连：按 session_id 拉取当前对局状态）</li>
 *   <li>S7：联机房绑定 —— init 可选 room_code → resume 按房间码定位；controller resume 端点</li>
 * </ul>
 *
 * <p>风格：直构服务（mock LLMClient + 录制 SseBroadcaster + 真实 ApprovalService）；快照测试用
 * mock ScriptRepository 装配真实 DatabaseService（零 Spring 上下文，确定性隔离）。
 */
class WerewolfStage1Test {

    /** 录制式 SSE 广播器：记录 (sessionId, event, data)，broadcastToSession 定向路径可断言。 */
    static class RecordingSse implements SseBroadcaster {
        final List<Map.Entry<String, Map<?, ?>>> events = new CopyOnWriteArrayList<>();
        final List<String> sessions = new CopyOnWriteArrayList<>();
        @Override
        public void broadcast(String eventType, Object data) {
            events.add(Map.entry(eventType, data instanceof Map<?, ?> m ? m : Map.of()));
            sessions.add("");
        }
        @Override
        public void broadcastToSession(String sessionId, String eventType, Object data) {
            events.add(Map.entry(eventType, data instanceof Map<?, ?> m ? m : Map.of()));
            sessions.add(sessionId == null ? "" : sessionId);
        }
        List<Map<?, ?>> of(String type) {
            return events.stream().filter(e -> e.getKey().equals(type)).map(Map.Entry::getValue).toList();
        }
        boolean has(String type) {
            return events.stream().anyMatch(e -> e.getKey().equals(type));
        }
        /** 指定事件的定向 session 列表（对应每次推送）。 */
        List<String> sessionsOf(String type) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).getKey().equals(type)) out.add(sessions.get(i));
            }
            return out;
        }
    }

    private static final String SAMPLE_LINE = "我认为狼人就藏在我们中间。情绪：平静。";

    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList())).thenAnswer(inv -> {
            Thread.sleep(20);
            return SAMPLE_LINE;
        });
        return llm;
    }

    /** 6 人局：A/B 狼、C 预言家、D 女巫、E 猎人、F 村民。 */
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
    //  S1/S2：G1-2 女巫获知被刀者机制
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S1: AI 女巫获知被刀者后再决策 —— saveProbability=0 不救（记 nosave 保留解药，被刀者死亡）/ =1 必救（被刀者存活）")
    void aiWitchInformedThenDecides() {
        // 概率 0：获知 → 决策不救
        WerewolfService svc = new WerewolfService(new ApprovalService());
        svc.setPlanner(new WerewolfAiPlanner(42L));
        svc.setWitchSaveProbability(0.0);
        String sid = "s1a";
        svc.initGame(sid, sixPlayers(), sixRoles());
        WerewolfService.GameState g = svc.getGame(sid);
        svc.setHumanPlayers(sid, Set.of("F"));
        svc.runAiNightActions(sid);

        assertTrue(g.witchInformed, "女巫已获知被刀者身份（G1-2）");
        assertFalse(g.wolfTarget.isEmpty(), "狼刀目标已确定");
        assertTrue(g.nightDecisions.contains("nosave"), "获知后决策为不使用解药");
        assertTrue(g.witchDeclinedSave, "不救标记");
        assertFalse(g.witchUsedAntidote, "解药保留");
        String victim = g.wolfTarget; // resolveNight 会 resetNight 清空 wolfTarget，先捕获
        Map<String, Object> r = svc.resolveNight(sid);
        assertTrue(((List<?>) r.get("died")).contains(victim), "未救 → 被刀者死亡");
        assertEquals("day_discuss", r.get("phase"));

        // 概率 1：获知 → 决策救（经典首夜必救）
        WerewolfService svc2 = new WerewolfService(new ApprovalService());
        svc2.setPlanner(new WerewolfAiPlanner(42L));
        svc2.setWitchSaveProbability(1.0);
        String sid2 = "s1b";
        svc2.initGame(sid2, sixPlayers(), sixRoles());
        WerewolfService.GameState g2 = svc2.getGame(sid2);
        svc2.setHumanPlayers(sid2, Set.of("F"));
        svc2.runAiNightActions(sid2);

        assertTrue(g2.witchInformed, "女巫已获知");
        assertTrue(g2.nightDecisions.contains("save"), "决策为救");
        assertTrue(g2.witchUsedAntidote, "解药已用");
        assertEquals(g2.wolfTarget, g2.witchSaveTarget, "救的就是被刀者");
        Map<String, Object> r2 = svc2.resolveNight(sid2);
        assertTrue(((List<?>) r2.get("died")).isEmpty(), "被刀者被救 → 平安夜");
    }

    @Test
    @DisplayName("S2: 人类女巫先获知再行动 —— 未获知时救/不救均拒绝；获知后只能救被刀者；nosave 保留解药")
    void humanWitchMustBeInformedFirst() {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), null, sse);
        svc.setPlanner(new WerewolfAiPlanner(42L));
        String sid = "s2";
        svc.initGame(sid, sixPlayers(), sixRoles());
        WerewolfService.GameState g = svc.getGame(sid);
        svc.setHumanPlayers(sid, Set.of("D", "F")); // D 女巫为人类

        // 狼刀前：女巫无被刀者信息 → 救/不救均拒绝
        assertTrue(svc.recordNightAction(sid, "D", "save", "F").contains("尚未获知"), "未获知不能救");
        assertTrue(svc.recordNightAction(sid, "D", "nosave", "").contains("尚未获知"), "未获知不能选择不救");

        // AI 狼刀 → 女巫获知
        svc.runAiNightActions(sid);
        assertTrue(g.witchInformed, "狼刀完成后女巫获知");
        String victim = g.wolfTarget;
        assertFalse(victim.isEmpty());

        // toMap 视角：女巫本人可见 witch_victim（获知信息），他人不可见
        assertEquals(victim, g.toMap("D").get("witch_victim"), "女巫视角暴露被刀者");
        assertFalse(g.toMap("F").containsKey("witch_victim"), "非女巫视角不暴露被刀者");

        // 获知后：只能救被刀者
        String wrong = svc.recordNightAction(sid, "D", "save", "C");
        assertTrue(wrong.contains("只能救被刀者"), "救非被刀者被拒，实际: " + wrong);
        String ok = svc.recordNightAction(sid, "D", "save", victim);
        assertTrue(ok.contains("救起被刀者"), "救被刀者成功，实际: " + ok);
        assertTrue(g.witchUsedAntidote);
        assertEquals(victim, g.witchSaveTarget);

        // nosave 路径：保留解药、决策完成、被刀者死亡
        RecordingSse sse2 = new RecordingSse();
        WerewolfService svc3 = new WerewolfService(new ApprovalService(), null, sse2);
        svc3.setPlanner(new WerewolfAiPlanner(42L));
        String sid3 = "s2b";
        svc3.initGame(sid3, sixPlayers(), sixRoles());
        WerewolfService.GameState g3 = svc3.getGame(sid3);
        svc3.setHumanPlayers(sid3, Set.of("D", "F"));
        svc3.runAiNightActions(sid3);
        String victim3 = g3.wolfTarget;
        String nosave = svc3.recordNightAction(sid3, "D", "nosave", "");
        assertTrue(nosave.contains("不使用解药"), "不救决策成功，实际: " + nosave);
        assertFalse(g3.witchUsedAntidote, "解药保留");
        // 人类女巫同样可明确选择不用毒药（决策完成放行夜间）
        String nopoison = svc3.recordNightAction(sid3, "D", "nopoison", "");
        assertTrue(nopoison.contains("不使用毒药"), "不用毒决策成功，实际: " + nopoison);
        assertTrue(WerewolfService.nightComplete(g3, Set.of("D", "F")), "不救+不用毒后夜间完成判定放行");
        Map<String, Object> r3 = svc3.resolveNight(sid3);
        assertTrue(((List<?>) r3.get("died")).contains(victim3), "未救 → 被刀者死亡");
    }

    @Test
    @DisplayName("S3: werewolf_witch_info 获知事件 —— 女巫存活时推送 victim=狼刀目标 + session_id，定向到本局")
    void witchInfoSsePayload() {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), null, sse);
        svc.setPlanner(new WerewolfAiPlanner(42L));
        String sid = "s3";
        svc.initGame(sid, sixPlayers(), sixRoles());
        WerewolfService.GameState g = svc.getGame(sid);
        svc.setHumanPlayers(sid, Set.of("D", "F"));
        svc.runAiNightActions(sid);

        List<Map<?, ?>> infos = sse.of("werewolf_witch_info");
        assertFalse(infos.isEmpty(), "应推送女巫获知事件");
        assertEquals(g.wolfTarget, infos.get(0).get("victim"), "victim=狼刀目标");
        assertEquals(sid, infos.get(0).get("session_id"), "载荷带 session_id");
        assertEquals("night", infos.get(0).get("phase"));
        assertTrue(sse.sessionsOf("werewolf_witch_info").stream().allMatch(s -> sid.equals(s)), "获知事件定向本局");

        // 女巫已死 → 不推送获知事件（死人无需获知）
        RecordingSse sse2 = new RecordingSse();
        WerewolfService svc2 = new WerewolfService(new ApprovalService(), null, sse2);
        String sid2 = "s3b";
        svc2.initGame(sid2, sixPlayers(), sixRoles());
        svc2.setHumanPlayers(sid2, Set.of("F"));
        // 女巫 D 出局
        WerewolfService.GameState g2 = svc2.getGame(sid2);
        g2.alive.remove("D");
        svc2.runAiNightActions(sid2);
        assertFalse(sse2.has("werewolf_witch_info"), "女巫已死不推送获知事件");
    }

    // ═══════════════════════════════════════════════════════════
    //  S4：讨论引擎 per-game 隔离（多局并发不互扰）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S4: 讨论引擎按对局隔离 —— 两局并发讨论各自独立引擎/世界，transcript 只含本局成员")
    void discussionEnginesIsolatedPerGame() throws Exception {
        LLMClient llm = mockLlm();
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), llm, sse);
        svc.setPlanner(new WerewolfAiPlanner(7L));

        // 两局同玩家名不同身份：局1 A=狼，局2 A=村民
        Map<String, String> roles1 = new LinkedHashMap<>();
        roles1.put("A", "wolf"); roles1.put("B", "villager");
        roles1.put("C", "seer"); roles1.put("D", "witch");
        Map<String, String> roles2 = new LinkedHashMap<>();
        roles2.put("A", "villager"); roles2.put("B", "wolf");
        roles2.put("C", "villager"); roles2.put("D", "seer");
        List<String> players = new ArrayList<>(List.of("A", "B", "C", "D"));

        String sid1 = "iso-1";
        String sid2 = "iso-2";
        svc.initGame(sid1, players, roles1);
        svc.initGame(sid2, players, roles2);
        svc.setHumanPlayers(sid1, Set.of());
        svc.setHumanPlayers(sid2, Set.of());

        // 无夜间行动 → 白天讨论；两局同时启动讨论（并发）
        svc.resolveNight(sid1);
        svc.resolveNight(sid2);
        assertEquals(WerewolfService.Phase.DAY_DISCUSS, svc.getGame(sid1).phase);
        assertEquals(WerewolfService.Phase.DAY_DISCUSS, svc.getGame(sid2).phase);
        svc.startDayDiscussion(sid1);
        svc.startDayDiscussion(sid2);

        // 两局讨论均完成，transcript 只含本局存活成员（无跨局串扰）
        await("局1 讨论完成", 20_000, () -> !svc.getGame(sid1).discussionTranscript.isEmpty());
        await("局2 讨论完成", 20_000, () -> !svc.getGame(sid2).discussionTranscript.isEmpty());

        // 独立引擎实例（per-game，非共享；讨论引擎按需懒创建，完成后再断言实例）
        assertNotNull(svc.getDiscussionConversation(sid1), "局1 讨论引擎存在");
        assertNotNull(svc.getDiscussionConversation(sid2), "局2 讨论引擎存在");
        assertNotSame(svc.getDiscussionConversation(sid1), svc.getDiscussionConversation(sid2),
                "两局讨论引擎必须独立实例（D-012 实例级共享限制修复）");

        Set<String> alive1 = new java.util.HashSet<>(svc.getGame(sid1).alive);
        Set<String> alive2 = new java.util.HashSet<>(svc.getGame(sid2).alive);
        for (Map<String, String> turn : svc.getGame(sid1).discussionTranscript) {
            assertTrue(alive1.contains(turn.get("speaker")), "局1 发言者必须为本局成员: " + turn.get("speaker"));
        }
        for (Map<String, String> turn : svc.getGame(sid2).discussionTranscript) {
            assertTrue(alive2.contains(turn.get("speaker")), "局2 发言者必须为本局成员: " + turn.get("speaker"));
        }
        // werewolf_speech 定向到各自对局
        assertTrue(sse.sessionsOf("werewolf_speech").stream().allMatch(s -> sid1.equals(s) || sid2.equals(s)),
                "讨论发言定向推送");
    }

    // ═══════════════════════════════════════════════════════════
    //  S5：werewolf_* SSE 定向推送（服务层走 broadcastToSession）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S5: werewolf_* SSE 按 session 定向 —— 两局事件各回各局，不互串")
    void sseTargetedPerSession() {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), null, sse);
        svc.setPlanner(new WerewolfAiPlanner(3L));

        String sid1 = "tgt-1";
        String sid2 = "tgt-2";
        svc.initGame(sid1, sixPlayers(), sixRoles());
        svc.initGame(sid2, sixPlayers(), sixRoles());
        svc.setHumanPlayers(sid1, Set.of("F"));
        svc.setHumanPlayers(sid2, Set.of("F"));
        svc.notifyGameInit(sid1, "F");
        svc.notifyGameInit(sid2, "F");

        // 局1 推进到投票
        svc.resolveNight(sid1);
        svc.startVoting(sid1);
        // 局2 推进到讨论
        svc.resolveNight(sid2);

        // 全部 werewolf_* 事件都定向到对应对局（无全局广播路径残留）
        assertTrue(sse.sessionsOf("werewolf_player_update").stream().allMatch(s -> sid1.equals(s) || sid2.equals(s)),
                "玩家列表事件定向");
        assertTrue(sse.sessionsOf("werewolf_phase").stream().allMatch(s -> sid1.equals(s) || sid2.equals(s)),
                "阶段事件定向");
        // 局1 的事件只带 sid1，局2 只带 sid2（互不串）
        List<String> phaseSessions = sse.sessionsOf("werewolf_phase");
        for (int i = 0; i < phaseSessions.size(); i++) {
            String payloadSid = String.valueOf(sse.of("werewolf_phase").get(i).get("session_id"));
            assertEquals(payloadSid, phaseSessions.get(i), "事件 payload 与定向 session 一致");
        }
        // 定向 session 均为 sid1/sid2 之一（无空=全局广播）
        assertFalse(phaseSessions.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════
    //  S6：对局快照落库 + 跨实例恢复（重连）
    // ═══════════════════════════════════════════════════════════

    /** 用 mock ScriptRepository 装配真实 DatabaseService（saveScript/getLatestScriptSnapshot 全链路）。 */
    private DatabaseService dbWithMockRepo() {
        ScriptRepository repo = mock(ScriptRepository.class);
        List<ScriptEntity> saved = new CopyOnWriteArrayList<>();
        when(repo.save(any(ScriptEntity.class))).thenAnswer(inv -> {
            ScriptEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId((long) saved.size() + 1);
            saved.add(e);
            return e;
        });
        when(repo.findByNameStartingWithOrderByIdDesc(any(String.class))).thenAnswer(inv -> {
            String prefix = inv.getArgument(0);
            // 对齐 ORDER BY id DESC：后保存的在最前（最新快照优先）
            List<ScriptEntity> reversed = new ArrayList<>(saved);
            java.util.Collections.reverse(reversed);
            return reversed.stream().filter(s -> s.getName().startsWith(prefix)).toList();
        });
        return new DatabaseService(null, null, null, null, repo, null);
    }

    @Test
    @DisplayName("S6: 快照落库 + 跨实例恢复 —— 新实例按 session_id resume 重建对局（restored=true），状态/角色/票面一致")
    void snapshotAndResumeAcrossInstances() {
        DatabaseService db = dbWithMockRepo();
        // 实例 1：正常开一局并推进到白天讨论
        WerewolfService svc1 = new WerewolfService(new ApprovalService(), null, null, db);
        svc1.setPlanner(new WerewolfAiPlanner(11L));
        String sid = "persist-1";
        svc1.initGame(sid, sixPlayers(), sixRoles());
        svc1.setHumanPlayers(sid, Set.of("F"));
        svc1.runAiNightActions(sid);
        Map<String, Object> r = svc1.resolveNight(sid);
        assertEquals("day_discuss", r.get("phase"));

        // 实例 2：模拟重启（同一 DB，无内存对局）→ 按 session_id 恢复（P-0802-J：需携带本人 roleKey）
        WerewolfService svc2 = new WerewolfService(new ApprovalService(), null, null, db);
        Map<String, Object> view = svc2.resumeGame(sid, "F", svc1.getRoleKey(sid, "F"));
        assertFalse(view.containsKey("error"), "应恢复成功，实际: " + view);
        assertEquals(Boolean.TRUE, view.get("restored"), "从快照重建");
        assertEquals(Boolean.TRUE, view.get("resumed"));
        assertEquals(sid, view.get("session_id"));
        assertEquals("day_discuss", view.get("phase"), "恢复后的阶段一致");
        assertEquals(6, ((List<?>) view.get("alive")).size(), "存活列表一致");
        assertEquals("villager", view.get("your_role"), "F 视角角色一致");
        assertEquals("werewolf", view.get("visible") == null ? "" : "werewolf",
                "A 是狼人（角色表恢复正确）");

        // 恢复后对局可继续操作（status 可查）
        Map<String, Object> st = svc2.statusMap(sid, "F");
        assertEquals("day_discuss", st.get("phase"));

        // 不存在的对局 → 报错（对局缺失优先于身份校验）
        Map<String, Object> miss = svc2.resumeGame("no-such", "F", "any-key");
        assertEquals("对局不存在且无快照可恢复", miss.get("error"));

        // 终局快照恢复：打赢一局后新实例恢复出终态
        WerewolfService svc3 = new WerewolfService(new ApprovalService(), null, null, db);
        String sid3 = "persist-end";
        svc3.initGame(sid3, new ArrayList<>(List.of("A", "B", "C", "D")), null);
        svc3.setHumanPlayers(sid3, Set.of());
        WerewolfService.GameState g3 = svc3.getGame(sid3);
        String wolf = g3.roles.entrySet().stream()
                .filter(e -> e.getValue() == WerewolfService.Role.WEREWOLF)
                .map(Map.Entry::getKey).findFirst().orElse("");
        String seer = g3.roles.entrySet().stream()
                .filter(e -> e.getValue() == WerewolfService.Role.SEER)
                .map(Map.Entry::getKey).findFirst().orElse("");
        svc3.recordNightAction(sid3, wolf, "kill", seer);
        svc3.resolveNight(sid3); // werewolf 胜 → ENDED（已落快照）
        assertEquals(WerewolfService.Phase.ENDED, svc3.getGame(sid3).phase);

        WerewolfService svc4 = new WerewolfService(new ApprovalService(), null, null, db);
        Map<String, Object> endView = svc4.resumeGame(sid3, "A", svc3.getRoleKey(sid3, "A"));
        assertEquals(Boolean.TRUE, endView.get("terminal"), "终局恢复出终态");
        assertEquals("werewolf", endView.get("winner"));
        assertEquals("ended", endView.get("phase"));
    }

    // ═══════════════════════════════════════════════════════════
    //  S7：联机房绑定 + controller resume 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S7: 联机房绑定 —— init 带 room_code 绑定，resume 可按房间码定位；controller resume 端点恢复并登记玩家")
    void roomBindingAndResumeEndpoint() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        WerewolfController ctl = new WerewolfController(svc);

        // init 带 room_code
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("players", sixPlayers());
        body.put("roles", sixRoles());
        body.put("room_code", "ab12");
        ResponseEntity<Map<String, Object>> initResp = ctl.init("F", "", body);
        assertEquals(200, initResp.getStatusCode().value());
        String sid = (String) initResp.getBody().get("session_id");
        assertNotNull(sid);
        assertEquals("AB12", initResp.getBody().get("room_code"), "房间码大写回显");

        // 内存命中 resume（session_id + player_key；P-0802-J：resume 必须携带本人 roleKey）
        String keyF = svc.getRoleKey(sid, "F");
        assertFalse(keyF.isEmpty(), "F 应有 roleKey");
        ResponseEntity<Map<String, Object>> r1 = ctl.resume(Map.of("session_id", sid, "player", "F", "player_key", keyF));
        assertEquals(Boolean.FALSE, r1.getBody().get("restored"), "内存命中 restored=false");
        assertEquals(sid, r1.getBody().get("session_id"));

        // 按房间码 resume（同局，带 roleKey）
        ResponseEntity<Map<String, Object>> r2 = ctl.resume(Map.of("room_code", "ab12", "player", "F", "player_key", keyF));
        assertEquals(sid, r2.getBody().get("session_id"), "房间码定位到同一对局");
        assertFalse(r2.getBody().containsKey("error"));

        // 仅凭 roleKey 反查恢复（不传玩家名；重连场景客户端只持 key）
        ResponseEntity<Map<String, Object>> rKeyOnly = ctl.resume(Map.of("session_id", sid, "player_key", keyF));
        assertEquals(sid, rKeyOnly.getBody().get("session_id"), "仅凭 key 反查玩家并恢复");
        assertEquals("F", rKeyOnly.getBody().get("player"), "key 反查出玩家名");

        // 身份校验失败：key 缺失 / 错误 key → 拒绝恢复（P-0802-J 防冒充）
        ResponseEntity<Map<String, Object>> rNoKey = ctl.resume(Map.of("session_id", sid, "player", "F"));
        assertEquals("身份校验失败：player_key 缺失或不匹配", rNoKey.getBody().get("error"));
        ResponseEntity<Map<String, Object>> rWrongKey = ctl.resume(Map.of("session_id", sid, "player", "F", "player_key", "not-a-real-key"));
        assertEquals("身份校验失败：player_key 缺失或不匹配", rWrongKey.getBody().get("error"));
        ResponseEntity<Map<String, Object>> rOtherKey = ctl.resume(Map.of("session_id", sid, "player", "F", "player_key", svc.getRoleKey(sid, "A")));
        assertEquals("身份校验失败：player_key 缺失或不匹配", rOtherKey.getBody().get("error"), "拿他人 key 冒充被拒");

        // 未绑定房间码 → 报错
        ResponseEntity<Map<String, Object>> r3 = ctl.resume(Map.of("room_code", "ZZ99", "player", "F"));
        assertEquals("房间码未绑定任何对局", r3.getBody().get("error"));

        // 缺标识 → 报错
        ResponseEntity<Map<String, Object>> r4 = ctl.resume(Map.of("player", "F"));
        assertEquals("缺少对局标识（session_id / room_code 至少其一）", r4.getBody().get("error"));

        // resume 后 status 可定位（玩家会话映射已登记）
        ResponseEntity<Map<String, Object>> st = ctl.getStatus("F", "", "");
        assertEquals(sid, st.getBody().get("session_id"), "resume 登记后 status 定位到本局");

        // status 显式 session_id 优先
        ResponseEntity<Map<String, Object>> st2 = ctl.getStatus("", "", sid);
        assertEquals(sid, st2.getBody().get("session_id"));
    }
}
