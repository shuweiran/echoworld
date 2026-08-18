package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.conversation.ConversationGroup;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0816-R（剧本杀 UI 重设计阶段二后端接口包）验收测试 —— 依据
 * docs/ui-prototype/对比与API接入方案.md §3.2/§3.3 + 决策记录.md U1/U2/C5 口径。
 *
 * <p>覆盖：
 * <ul>
 *   <li>API-3（GET /api/script/locks）：心锁列表 —— 规则推导（content 提及角色名 → 1 锁）、
 *       终态 LLM 标注 unlock_role 宽容解析（字符串/数组、角色 id→角色名归一、标注优先于推导）</li>
 *   <li>API-4（POST /api/script/unlock）：出示证据破锁 —— 阶段守卫/持有校验/解锁线索命中校验/
 *       破锁归零 + unlockedLocks 记录 + SSE script_locks 定向广播 + 幂等（重复出示提示已解锁）/
 *       快照落库恢复（roleLocks/unlockedRoles 随快照整包 JSON）</li>
 *   <li>API-5（POST /api/script/press）：质询发言 —— 阶段守卫/目标校验/message_id 定位/缺省最近发言/
 *       pressed+pressed_by 标记写 discussionTranscript（并发安全容器）/同人幂等+多人质询/
 *       SSE script_press 定向广播/非法 message_id</li>
 *   <li>API-8（GET /api/script/relations）：关系矩阵 —— 内容提及 ★ 直接关联 / 持有者 ◯ / 其余 –</li>
 *   <li>SSE：script_locks / script_press 经 broadcastToSession 定向通道（§3.3，决策 D1）</li>
 * </ul>
 *
 * <p>直构 ScriptGameService（mock LLMClient + ApprovalService + CaptureSSE；快照用例 mock
 * DatabaseService 捕获落库内容），与 ScriptGameUiMvpTest / ScriptGameResumeTest 风格一致。
 */
class ScriptGameLocksPressTest {

    private static final String SESSION = "test-script-locks-press";

    /**
     * 新格式剧本（schema v1）：3 角色（管家/女仆/园丁，带 id）+ 5 地点 + 5 线索。
     * 线索心锁语义：
     *  - c1 客厅「管家在客厅留下的碎玻璃」→ 推导提及管家 → 管家 1 锁（解锁线索 c1）
     *  - c2 书房「密信」+ unlock_role=女仆 → 标注 → 女仆 1 锁（解锁线索 c2，内容未提及女仆）
     *  - c3 花园「园丁的铲子沾着泥土」→ 推导提及园丁 → 园丁 1 锁（解锁线索 c3，公开线索）
     *  - c4 地下室「染血手套」→ 无标注无提及 → 无锁
     *  - c5 卧室「一张合影」+ unlock_role=role_1 → 标注角色 id → 归一管家 → 管家第 2 锁
     */
    private LLMClient locksLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of(
                Map.of("id", "role_1", "name", "管家"),
                Map.of("id", "role_2", "name", "女仆"),
                Map.of("id", "role_3", "name", "园丁")));
        script.put("locations", List.of("客厅", "书房", "花园", "地下室", "卧室"));
        script.put("clues", List.of(
                Map.of("id", "c1", "location", "客厅", "content", "管家在客厅留下的碎玻璃", "public", false),
                Map.of("id", "c2", "location", "书房", "content", "密信", "unlock_role", "女仆", "public", false),
                Map.of("id", "c3", "location", "花园", "content", "园丁的铲子沾着泥土", "public", true),
                Map.of("id", "c4", "location", "地下室", "content", "染血手套", "public", false),
                Map.of("id", "c5", "location", "卧室", "content", "一张合影", "unlock_role", "role_1", "public", false)));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道密信", "园丁", "你目击了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenReturn("我确实有些事没说完，但现在还不是时候。");
        return llm;
    }

    private ScriptGameService newService() {
        return new ScriptGameService(locksLlm(), new ApprovalService());
    }

    private ScriptGameService newServiceWithDb(DatabaseService db) {
        return new ScriptGameService(locksLlm(), new ApprovalService(), db, new CaptureSSE());
    }

    /** 捕获定向 SSE 事件（script_locks / script_press 走 broadcastToSession 通道）。 */
    private static class CaptureSSE extends SSEController {
        final List<Map.Entry<String, Object>> events = new ArrayList<>();

        @Override
        public void broadcastToSession(String sessionId, String eventType, Object data) {
            events.add(Map.entry(eventType, data));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> locksOf(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("locks");
    }

    private static Map<String, Object> lockRow(List<Map<String, Object>> locks, String role) {
        for (Map<String, Object> l : locks) {
            if (role.equals(l.get("role"))) return l;
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
    //  API-3: GET /api/script/locks —— 心锁列表（规则推导 + LLM 标注宽容解析）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-3a: 心锁推导 —— 内容提及角色名→1 锁；unlock_role 标注优先（含角色 id 归一、数组形式）")
    void locksDerivedFromClues() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        Map<String, Object> res = svc.getLocks(SESSION);
        assertEquals(Boolean.TRUE, res.get("ok"));
        List<Map<String, Object>> locks = locksOf(res);

        // 管家：c1（推导提及）+ c5（标注 role_1 归一）= 2 锁
        Map<String, Object> butler = lockRow(locks, "管家");
        assertNotNull(butler, "管家应有心锁");
        assertEquals(2, butler.get("lock_count"));
        assertEquals(List.of("c1", "c5"), butler.get("unlock_clue_ids"));
        assertEquals(Boolean.FALSE, butler.get("unlocked"));

        // 女仆：c2（标注 unlock_role=女仆，内容未提及）→ 1 锁 —— 标注优先于推导
        Map<String, Object> maid = lockRow(locks, "女仆");
        assertNotNull(maid);
        assertEquals(1, maid.get("lock_count"));
        assertEquals(List.of("c2"), maid.get("unlock_clue_ids"));

        // 园丁：c3（推导提及）→ 1 锁
        Map<String, Object> gardener = lockRow(locks, "园丁");
        assertNotNull(gardener);
        assertEquals(1, gardener.get("lock_count"));
        assertEquals(List.of("c3"), gardener.get("unlock_clue_ids"));

        // c4 无锁（无标注无提及）→ 不出现
        assertEquals(3, locks.size(), "仅 3 个角色有心锁，c4 不产生锁");
    }

    @Test
    @DisplayName("API-3b: 标注数组形式 + 标注存在时内容提及不叠加（标注即唯一来源）")
    void locksAnnotationArrayFormAndPrecedence() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("name", "双角色");
        script.put("background", "b");
        script.put("truth", "t");
        script.put("roles", List.of(Map.of("id", "r1", "name", "甲"), Map.of("id", "r2", "name", "乙")));
        script.put("locations", List.of("客厅"));
        // c1 内容提及甲，但标注数组 [乙] —— 标注优先，仅乙得 1 锁（甲不因内容提及叠加）
        script.put("clues", List.of(
                Map.of("id", "c1", "location", "客厅", "content", "甲的脚印", "unlock_role", List.of("乙"), "public", false)));
        script.put("secrets", Map.of("甲", "s1", "乙", "s2"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenReturn("嗯。");
        ScriptGameService svc = new ScriptGameService(llm, new ApprovalService());
        svc.initGame(SESSION, "双角色", List.of("Alice", "Bob"));

        List<Map<String, Object>> locks = locksOf(svc.getLocks(SESSION));
        assertEquals(1, locks.size(), "仅乙有心锁（标注优先，甲不叠加）");
        assertEquals("乙", locks.get(0).get("role"));
        assertEquals(List.of("c1"), locks.get(0).get("unlock_clue_ids"));
    }

    // ═══════════════════════════════════════════════════════════
    //  API-4: POST /api/script/unlock —— 出示证据破锁
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-4a: 校验链 —— 阶段守卫 / 未持有线索 / 解锁线索不命中 明确错误")
    void unlockValidationChain() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String butlerPlayer = playerWithRole(game, "管家");

        // ① 阶段守卫：VOTE 阶段不可破锁（含 phase 键）
        game.phase = ScriptGameService.Phase.VOTE;
        Map<String, Object> phaseErr = svc.unlockLock(SESSION, "Alice", "管家", "c1");
        assertTrue(phaseErr.get("error").toString().contains("当前阶段不可破锁"));
        assertEquals("vote", phaseErr.get("phase"));
        game.phase = ScriptGameService.Phase.INVESTIGATION;

        // ② 未持有线索（Alice 未搜证）→ 拒绝
        Map<String, Object> notHeld = svc.unlockLock(SESSION, "Alice", "管家", "c1");
        assertTrue(notHeld.get("error").toString().contains("线索不存在或未持有"), notHeld.toString());

        // ③ 持有但不命中解锁线索（Alice 搜证得 c4，用它破管家）→ 明确错误
        svc.search(SESSION, "Alice", "地下室");
        Map<String, Object> wrongClue = svc.unlockLock(SESSION, "Alice", "管家", "c4");
        assertTrue(wrongClue.get("error").toString().contains("这张线索解不开 TA 的心锁"), wrongClue.toString());

        // ④ 目标角色不在本局 / 玩家不在本局
        Map<String, Object> badRole = svc.unlockLock(SESSION, "Alice", "路人甲", "c4");
        assertTrue(badRole.get("error").toString().contains("目标角色不在本局"));
        Map<String, Object> badPlayer = svc.unlockLock(SESSION, "路人乙", "管家", "c4");
        assertTrue(badPlayer.get("error").toString().contains("玩家不在本局中"));

        // ⑤ 对局不存在
        Map<String, Object> noGame = svc.unlockLock("no-such", "Alice", "管家", "c1");
        assertTrue(noGame.get("error").toString().contains("游戏不存在"));
    }

    @Test
    @DisplayName("API-4b: 破锁成功 —— 锁数归零 + unlockedLocks 记录 + SSE script_locks 定向广播 + 幂等重复")
    void unlockSuccessAndIdempotent() {
        CaptureSSE sse = new CaptureSSE();
        ScriptGameService svc = new ScriptGameService(locksLlm(), new ApprovalService(), null, sse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // Alice 搜证客厅得 c1（管家的解锁线索）
        svc.search(SESSION, "Alice", "客厅");
        Map<String, Object> r = svc.unlockLock(SESSION, "Alice", "管家", "c1");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("管家", r.get("role"));
        assertEquals(Boolean.TRUE, r.get("unlocked"));
        assertEquals("c1", r.get("unlock_clue_id"));
        assertTrue(r.get("message").toString().contains("解开了"));

        // 状态落库：roleLocks 归零 + unlockedRoles + unlockedLocks 记录
        assertEquals(0, game.roleLocks.get("管家"), "破锁后锁数归零");
        assertTrue(game.unlockedRoles.contains("管家"), "已破锁角色集合");
        assertEquals(1, game.unlockedLocks.size(), "破锁记录一条");
        assertEquals("管家", game.unlockedLocks.get(0).get("role"));
        assertEquals("Alice", game.unlockedLocks.get(0).get("by"));

        // SSE script_locks 定向广播（含 session_id + 新锁状态）
        assertFalse(sse.events.isEmpty(), "应有 SSE 推送");
        assertTrue(sse.events.stream().anyMatch(e -> "script_locks".equals(e.getKey())), "推送 script_locks 事件");
        Map<String, Object> payload = (Map<String, Object>) sse.events.stream()
                .filter(e -> "script_locks".equals(e.getKey())).findFirst().get().getValue();
        assertEquals(SESSION, payload.get("session_id"));

        // 幂等：重复出示 → already=true 提示已解锁，不新增记录
        int before = game.unlockedLocks.size();
        Map<String, Object> dup = svc.unlockLock(SESSION, "Alice", "管家", "c1");
        assertEquals(Boolean.TRUE, dup.get("ok"));
        assertEquals(Boolean.TRUE, dup.get("already"), "重复出示标记 already");
        assertEquals(game.unlockedLocks.size(), before, "不重复记录破锁日志");
    }

    @Test
    @DisplayName("API-4c: 快照落库恢复 —— 破锁后清内存，roleLocks/unlockedRoles 随快照恢复（零迁移）")
    void unlockPersistedInSnapshot() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        when(db.saveScript(anyString(), anyMap())).thenAnswer(inv -> {
            saved.set(inv.getArgument(1));
            return Map.of("name", inv.getArgument(0));
        });
        when(db.getLatestScriptSnapshot(eq(SESSION))).thenAnswer(inv ->
                saved.get() == null ? Optional.empty() : Optional.of(saved.get()));

        ScriptGameService svc = newServiceWithDb(db);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String aliceKey = svc.getRoleKey(SESSION, "Alice");

        svc.search(SESSION, "Alice", "客厅");
        svc.unlockLock(SESSION, "Alice", "管家", "c1");
        assertNotNull(saved.get(), "破锁后快照已落库");
        // 快照内含心锁键（role_locks / unlocked_roles / unlocked_locks）
        assertEquals(0, saved.get().get("role_locks") instanceof Map<?, ?> m
                ? ((Map<?, ?>) m).get("管家") : null, "快照内 role_locks 管家=0");
        assertTrue(((List<?>) saved.get().get("unlocked_roles")).contains("管家"), "快照内 unlocked_roles 含管家");
        assertEquals(1, ((List<?>) saved.get().get("unlocked_locks")).size(), "快照内破锁记录 1 条");

        // 清内存（模拟重启）→ 从快照恢复
        clearGamesMemory(svc);
        assertNull(svc.getGame(SESSION), "内存对局已清空");
        Map<String, Object> view = svc.resumeGame(SESSION, aliceKey);
        assertEquals(Boolean.TRUE, view.get("restored"), "从快照重建");
        ScriptGameService.ScriptGame restored = svc.getGame(SESSION);
        assertEquals(0, restored.roleLocks.get("管家"), "恢复后管家锁数 0");
        assertTrue(restored.unlockedRoles.contains("管家"), "恢复后已破锁集合");
        assertEquals(1, restored.unlockedLocks.size(), "恢复后破锁记录");

        // 恢复后的对局幂等语义保持：重复出示仍提示已解锁（不重新破锁）
        Map<String, Object> dup = svc.unlockLock(SESSION, "Alice", "管家", "c1");
        assertEquals(Boolean.TRUE, dup.get("already"), "恢复后重复出示仍 already");
    }

    // ═══════════════════════════════════════════════════════════
    //  API-5: POST /api/script/press —— 质询发言
    //  ═══════════════════════════════════════════════════════════

    private void setupDiscussionTranscript(ScriptGameService.ScriptGame game) {
        Map<String, String> t1 = new LinkedHashMap<>();
        t1.put("speaker", "Alice");
        t1.put("message", "我在客厅发现了碎玻璃。");
        t1.put("round", "1");
        Map<String, String> t2 = new LinkedHashMap<>();
        t2.put("speaker", "Bob");
        t2.put("message", "我在书房找到一封密信。");
        t2.put("round", "1");
        Map<String, String> t3 = new LinkedHashMap<>();
        t3.put("speaker", "Carol");
        t3.put("message", "花园的铲子有问题。");
        t3.put("round", "1");
        game.discussionTranscript.add(t1);
        game.discussionTranscript.add(t2);
        game.discussionTranscript.add(t3);
    }

    @Test
    @DisplayName("API-5a: 质询 —— 阶段守卫 / 目标校验 / 缺省标记目标最近发言")
    void pressPhaseGuardAndDefaults() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // ① 非讨论阶段拒绝
        Map<String, Object> phaseErr = svc.press(SESSION, "Alice", "Bob", "");
        assertTrue(phaseErr.get("error").toString().contains("当前不是讨论阶段"));

        // ② 目标不在本局 / 玩家不在本局
        game.phase = ScriptGameService.Phase.DISCUSSION;
        Map<String, Object> badTarget = svc.press(SESSION, "Alice", "路人甲", "");
        assertTrue(badTarget.get("error").toString().contains("目标不在本局"));
        Map<String, Object> badPlayer = svc.press(SESSION, "路人乙", "Bob", "");
        assertTrue(badPlayer.get("error").toString().contains("玩家不在本局中"));

        // ③ 目标角色尚无发言 → 明确错误（Carol 在本局但未发言）
        Map<String, String> t1 = new LinkedHashMap<>();
        t1.put("speaker", "Alice");
        t1.put("message", "我在客厅发现了碎玻璃。");
        t1.put("round", "1");
        Map<String, String> t2 = new LinkedHashMap<>();
        t2.put("speaker", "Bob");
        t2.put("message", "我在书房找到一封密信。");
        t2.put("round", "1");
        game.discussionTranscript.add(t1);
        game.discussionTranscript.add(t2);
        Map<String, Object> noTurn = svc.press(SESSION, "Alice", "Carol", "");
        assertTrue(noTurn.get("error").toString().contains("尚无发言可质询"), noTurn.toString());

        // ④ 缺省 message_id → 标记目标角色最近一条发言（Bob 的 t2）
        Map<String, Object> r = svc.press(SESSION, "Alice", "Bob", "");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("Bob", r.get("target"));
        assertEquals("msg_1", r.get("message_id"), "Bob 最近发言为转录下标 1");
        assertEquals("Alice", game.discussionTranscript.get(1).get("pressed_by"));
        assertEquals("true", game.discussionTranscript.get(1).get("pressed"));
        assertFalse(game.discussionTranscript.get(0).containsKey("pressed"), "其他发言不受影响");
    }

    @Test
    @DisplayName("API-5b: 质询 —— message_id 指定发言（speaker 为准）+ 同人幂等 + 多人可质询 + SSE script_press")
    void pressByIdAndMultiPlayerIdempotent() {
        CaptureSSE sse = new CaptureSSE();
        ScriptGameService svc = new ScriptGameService(locksLlm(), new ApprovalService(), null, sse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        game.phase = ScriptGameService.Phase.DISCUSSION;
        setupDiscussionTranscript(game);

        // 按 message_id 质询 Carol 的 t3（speaker=Carol，target 参数以发言 speaker 为准）
        Map<String, Object> r = svc.press(SESSION, "Alice", "Carol", "msg_2");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("Carol", r.get("target"), "显式 message_id 以发言 speaker 为准");
        assertEquals("msg_2", r.get("message_id"));
        assertEquals("Alice", game.discussionTranscript.get(2).get("pressed_by"));

        // 同人幂等：Alice 再质询同一发言 → ok 但不重复标记
        svc.press(SESSION, "Alice", "Carol", "msg_2");
        assertEquals("Alice", game.discussionTranscript.get(2).get("pressed_by"), "同人幂等不重复标记");

        // 多人质询：Bob 也可质询同一发言（追加 pressed_by）
        Map<String, Object> r2 = svc.press(SESSION, "Bob", "Carol", "msg_2");
        assertEquals(Boolean.TRUE, r2.get("ok"));
        assertEquals("Alice,Bob", game.discussionTranscript.get(2).get("pressed_by"), "多人质询逗号追加");

        // SSE script_press 定向广播（含 session_id / target / pressed_by / contradiction）
        assertTrue(sse.events.stream().anyMatch(e -> "script_press".equals(e.getKey())), "推送 script_press 事件");
        Map<String, Object> payload = (Map<String, Object>) sse.events.stream()
                .filter(e -> "script_press".equals(e.getKey())).reduce((a, b) -> b).get().getValue();
        assertEquals(SESSION, payload.get("session_id"));
        assertEquals("Carol", payload.get("target"));
        assertEquals("Bob", payload.get("pressed_by"));
        assertEquals(Boolean.TRUE, payload.get("contradiction"));

        // 非法 message_id → 该发言不存在
        Map<String, Object> badId = svc.press(SESSION, "Alice", "Carol", "msg_99");
        assertTrue(badId.get("error").toString().contains("该发言不存在"));
        Map<String, Object> badId2 = svc.press(SESSION, "Alice", "Carol", "abc");
        assertTrue(badId2.get("error").toString().contains("该发言不存在"));
    }

    @Test
    @DisplayName("API-5e: 讨论进行中（转录未拷入对局）—— press 定位活动讨论组实时发言并标记（双源同序）")
    void pressLocatesLiveGroupTurn() throws Exception {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        game.phase = ScriptGameService.Phase.DISCUSSION;

        // 反射触达懒创建讨论引擎（startDiscussion 内部同路径）——讨论进行中：转录为空，发言在讨论组内
        java.lang.reflect.Method m = ScriptGameService.class.getDeclaredMethod("ensureDiscussionEngine", String.class);
        m.setAccessible(true);
        ConversationManager cm = (ConversationManager) m.invoke(svc, SESSION);
        List<com.roleplay.engine.simulation.AgentState> members = new ArrayList<>();
        Map<String, com.roleplay.engine.simulation.track.TrackAssignment> tracks = new LinkedHashMap<>();
        for (String p : List.of("Alice", "Bob", "Carol")) {
            members.add(new com.roleplay.engine.simulation.AgentState(p, 0, 0));
            tracks.put(p, com.roleplay.engine.simulation.track.TrackAssignment.of(
                    p, com.roleplay.engine.core.Track.Mode.MERGED, List.of("Alice", "Bob", "Carol"), "test"));
        }
        ConversationGroup group = cm.createScriptDiscussionGroup("script_discussion_" + SESSION, members, tracks);
        group.recordTurn("Alice", "我在客厅发现了碎玻璃。");
        group.recordTurn("Bob", "我在书房找到一封密信。");
        // 对局转录此时仍为空（讨论结束才整体拷入）
        assertTrue(game.discussionTranscript.isEmpty(), "讨论进行中转录未拷入对局");

        // 按 message_id 定位活动组发言（msg_1 = Bob）并标记
        Map<String, Object> r = svc.press(SESSION, "Carol", "Bob", "msg_1");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("Bob", r.get("target"));
        assertEquals("msg_1", r.get("message_id"));
        assertEquals("Carol", group.getMessageHistory().get(1).get("pressed_by"), "活动组发言被打上 pressed 标记");
        assertEquals("true", group.getMessageHistory().get(1).get("pressed"));
        assertNull(group.getMessageHistory().get(0).get("pressed"), "其他发言不受影响");

        // 缺省 message_id → 目标最近发言（活动组历史内）
        Map<String, Object> r2 = svc.press(SESSION, "Alice", "Bob", "");
        assertEquals(Boolean.TRUE, r2.get("ok"));
        assertEquals("msg_1", r2.get("message_id"));
        assertEquals("Carol,Alice", group.getMessageHistory().get(1).get("pressed_by"), "多人质询追加");

        // 目标无发言（Carol 未发言）→ 尚无发言可质询
        Map<String, Object> noTurn = svc.press(SESSION, "Alice", "Carol", "");
        assertTrue(noTurn.get("error").toString().contains("尚无发言可质询"));
    }

    // ═══════════════════════════════════════════════════════════
    //  API-8: GET /api/script/relations —— 关系矩阵（内容推导）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-8a: 关系矩阵 —— 内容提及★ / 持有者◯ / 其余–；不泄 secret")
    void relationsMatrixDerivation() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        // 让扮演管家的玩家持有 c1（客厅）与 c2（书房）：
        //  - 管家×c1：内容提及 → ★（持有不覆盖提及，★ 优先）
        //  - 管家×c2：内容未提及但持有 → ◯（持有者标记）
        String butlerPlayer = playerWithRole(game, "管家");
        svc.search(SESSION, butlerPlayer, "客厅");
        svc.search(SESSION, butlerPlayer, "书房");

        Map<String, Object> res = svc.getRelations(SESSION);
        assertEquals(Boolean.TRUE, res.get("ok"));
        assertEquals(List.of("管家", "女仆", "园丁"), res.get("roles"));
        assertTrue(((List<?>) res.get("clues")).containsAll(List.of("c1", "c2", "c3", "c4", "c5")));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> matrix = (Map<String, Map<String, String>>) res.get("matrix");
        assertEquals("★", matrix.get("管家").get("c1"), "管家×c1：内容提及 → ★（持有不覆盖提及）");
        assertEquals("◯", matrix.get("管家").get("c2"), "管家×c2：内容未提及但持有 → ◯");
        assertEquals("–", matrix.get("女仆").get("c2"), "女仆×c2：未提及未持有 → –");
        assertEquals("–", matrix.get("园丁").get("c4"), "园丁×c4：无关联 → –");
        assertEquals("★", matrix.get("园丁").get("c3"), "园丁×c3：内容提及 → ★");

        // relations 列表：direct（内容提及）/ holder（持有）两类
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations = (List<Map<String, Object>>) res.get("relations");
        assertTrue(relations.stream().anyMatch(rr -> "direct".equals(rr.get("type"))
                && "管家".equals(rr.get("from")) && "c1".equals(rr.get("clue"))), "direct 关系行");
        assertTrue(relations.stream().anyMatch(rr -> "holder".equals(rr.get("type"))
                && "管家".equals(rr.get("from")) && "c2".equals(rr.get("clue"))), "holder 关系行（持有者角色）");
        // 不泄 secret：响应整体不含秘密文案
        assertFalse(res.toString().contains("贪图遗产"), "矩阵响应不泄 secret");
    }

    // ═══════════════════════════════════════════════════════════

    /** 清空内存对局表（模拟重启；快照仍在 DatabaseService）。 */
    private void clearGamesMemory(ScriptGameService svc) throws Exception {
        Field f = ScriptGameService.class.getDeclaredField("games");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> games = (Map<String, ?>) f.get(svc);
        games.clear();
    }
}
