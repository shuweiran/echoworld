package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
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
 * P-0816-T（阶段三，API-9）：出示证据到对话流 —— ScriptGameService.present 测试。
 *
 * <p>对齐 ScriptGameLocksPressTest 模式（同批项目测试范式）：
 *  ① 出示成功 → 「🃏 出示：CL-xx 线索名」system 行插入 discussionTranscript（全员可见）；
 *  ② 出示前校验：线索存在且属于本局 + 玩家持有（公开线索可直接出示）；
 *  ③ 幂等：presentedClues 记录，重复出示提示已出示（不重复插入/不重复广播）；
 *  ④ 阶段守卫：仅 DISCUSSION 阶段可出示（其余阶段拒绝，含 phase 键）；
 *  ⑤ SSE script_present 定向广播（player + clue_id + 摘要）；
 *  ⑥ 快照落库/恢复：presented_clues 随快照整包 JSON 加键（零迁移），恢复后幂等语义保持。
 */
class ScriptGamePresentTest {

    private static final String SESSION = "test-script-present";

    /**
     * 新格式剧本（schema v1）：3 角色 + 5 地点 + 5 线索（带 title）。
     *  - c1 客厅「碎玻璃」 public=false（非公开，需持有才能出示）
     *  - c2 书房「密信」 public=false
     *  - c3 花园「带泥的铲子」 public=true（公开线索，全员可见可直接出示）
     *  - c4 地下室「染血手套」 public=false
     *  - c5 卧室「合影」 public=false
     */
    private LLMClient presentLlm() {
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
                Map.of("id", "c1", "location", "客厅", "title", "碎玻璃", "content", "管家在客厅留下的碎玻璃", "public", false),
                Map.of("id", "c2", "location", "书房", "title", "密信", "content", "一封没有署名的密信", "public", false),
                Map.of("id", "c3", "location", "花园", "title", "带泥的铲子", "content", "园丁的铲子沾着泥土", "public", true),
                Map.of("id", "c4", "location", "地下室", "title", "染血手套", "content", "一副染血的手套", "public", false),
                Map.of("id", "c5", "location", "卧室", "title", "合影", "content", "一张泛黄的合影", "public", false)));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道密信", "园丁", "你目击了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenReturn("我确实有些事没说完，但现在还不是时候。");
        return llm;
    }

    private ScriptGameService newService() {
        return new ScriptGameService(presentLlm(), new ApprovalService());
    }

    /** 捕获定向 SSE 事件（script_present 走 broadcastToSession 通道）。 */
    private static class CaptureSSE extends SSEController {
        final List<Map.Entry<String, Object>> events = new ArrayList<>();

        @Override
        public void broadcastToSession(String sessionId, String eventType, Object data) {
            events.add(Map.entry(eventType, data));
        }
    }

    /** 找出扮演指定角色的玩家。 */
    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
                .filter(e -> role.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst().orElse("");
    }

    /** 转录最后一条（出示行）。 */
    private Map<String, String> lastTurn(ScriptGameService svc) {
        List<Map<String, String>> t = svc.getDiscussionTranscript(SESSION);
        return t.isEmpty() ? null : t.get(t.size() - 1);
    }

    // ═══════════════════════════════════════════════════════════
    //  API-9: POST /api/script/present —— 出示证据到对话流
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("API-9a: 校验链 —— 阶段守卫（含 phase 键）/ 玩家校验 / 线索不存在")
    void presentValidationChain() {
        ScriptGameService svc = newService();
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // ① 阶段守卫：非 DISCUSSION 阶段拒绝（含 phase 键）
        game.phase = ScriptGameService.Phase.INVESTIGATION;
        Map<String, Object> phaseErr = svc.present(SESSION, "Alice", "c1");
        assertTrue(phaseErr.get("error").toString().contains("当前不是讨论阶段"), phaseErr.toString());
        assertEquals("investigation", phaseErr.get("phase"), "响应含 phase 键（前端引导）");
        game.phase = ScriptGameService.Phase.VOTE;
        Map<String, Object> voteErr = svc.present(SESSION, "Alice", "c1");
        assertTrue(voteErr.get("error").toString().contains("当前不是讨论阶段"), voteErr.toString());
        assertEquals("vote", voteErr.get("phase"));
        game.phase = ScriptGameService.Phase.DISCUSSION;

        // ② 玩家不在本局
        Map<String, Object> badPlayer = svc.present(SESSION, "路人乙", "c1");
        assertTrue(badPlayer.get("error").toString().contains("玩家不在本局中"), badPlayer.toString());

        // ③ 线索不存在（不属于本局）
        Map<String, Object> noClue = svc.present(SESSION, "Alice", "CL-99");
        assertTrue(noClue.get("error").toString().contains("线索不存在"), noClue.toString());

        // ④ 缺少线索 id
        Map<String, Object> noId = svc.present(SESSION, "Alice", "");
        assertTrue(noId.get("error").toString().contains("缺少线索 id"), noId.toString());

        // ⑤ 对局不存在
        Map<String, Object> noGame = svc.present("no-such", "Alice", "c1");
        assertTrue(noGame.get("error").toString().contains("游戏不存在"), noGame.toString());

        // 全程无转录插入（校验失败零副作用）
        assertTrue(svc.getDiscussionTranscript(SESSION).isEmpty(), "校验失败不插入转录");
    }

    @Test
    @DisplayName("API-9b: 出示成功 —— 「🃏 出示：CL-xx 线索名」system 行入转录（全员可见）+ SSE script_present 定向广播")
    void presentSuccessAndTranscriptAndSse() {
        CaptureSSE sse = new CaptureSSE();
        ScriptGameService svc = new ScriptGameService(presentLlm(), new ApprovalService(), null, sse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // Alice 在搜证阶段搜证客厅得 c1（持有）—— search 要求 INVESTIGATION 阶段
        svc.search(SESSION, "Alice", "客厅");
        assertTrue(game.playerClues.getOrDefault("Alice", List.of()).contains("c1"), "Alice 持有 c1");
        game.phase = ScriptGameService.Phase.DISCUSSION;

        Map<String, Object> r = svc.present(SESSION, "Alice", "c1");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("c1", ((Map<?, ?>) r.get("presented")).get("clue_id"));
        assertEquals("碎玻璃", ((Map<?, ?>) r.get("presented")).get("title"));
        assertEquals("msg_0", r.get("transcript_id"), "出示行转录下标 0");

        // 转录 system 行：格式「🃏 出示：c1 碎玻璃」+ speaker=system + by=player
        Map<String, String> turn = lastTurn(svc);
        assertNotNull(turn);
        assertEquals("system", turn.get("speaker"));
        assertEquals("🃏 出示：c1 碎玻璃", turn.get("message"), "出示行格式（任务书 C8 口径）");
        assertEquals("Alice", turn.get("by"));

        // 全员可见：toMap 匿名视图的 discussion 含该行
        Map<String, Object> view = game.toMap("");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> discussion = (List<Map<String, String>>) view.get("discussion");
        assertNotNull(discussion, "toMap 暴露 discussion 转录");
        assertTrue(discussion.stream().anyMatch(t -> "system".equals(t.get("speaker"))
                && t.get("message").contains("🃏 出示：c1")), "出示行全员可见");

        // 幂等记录
        assertTrue(game.presentedClues.contains("c1"), "presentedClues 已记录 c1");

        // SSE script_present 定向广播（含 session_id / player / clue_id / title）
        assertFalse(sse.events.isEmpty(), "应有 SSE 推送");
        assertTrue(sse.events.stream().anyMatch(e -> "script_present".equals(e.getKey())), "推送 script_present 事件");
        Map<String, Object> payload = (Map<String, Object>) sse.events.stream()
                .filter(e -> "script_present".equals(e.getKey())).findFirst().get().getValue();
        assertEquals(SESSION, payload.get("session_id"));
        assertEquals("Alice", payload.get("player"));
        assertEquals("c1", payload.get("clue_id"));
        assertEquals("碎玻璃", payload.get("title"));
    }

    @Test
    @DisplayName("API-9c: 幂等 —— 重复出示提示已出示，不重复插入转录/不重复广播")
    void presentIdempotent() {
        CaptureSSE sse = new CaptureSSE();
        ScriptGameService svc = new ScriptGameService(presentLlm(), new ApprovalService(), null, sse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        svc.search(SESSION, "Alice", "客厅"); // 搜证阶段得 c1
        game.phase = ScriptGameService.Phase.DISCUSSION;
        Map<String, Object> r1 = svc.present(SESSION, "Alice", "c1");
        assertEquals(Boolean.TRUE, r1.get("ok"));
        int turnsAfterFirst = svc.getDiscussionTranscript(SESSION).size();
        int sseAfterFirst = (int) sse.events.stream().filter(e -> "script_present".equals(e.getKey())).count();

        // 重复出示：already=true，转录行数不变，不重复广播
        Map<String, Object> dup = svc.present(SESSION, "Alice", "c1");
        assertEquals(Boolean.TRUE, dup.get("ok"));
        assertEquals(Boolean.TRUE, dup.get("already"), "重复出示标记 already");
        assertEquals("该线索已出示过", dup.get("message"));
        assertEquals(turnsAfterFirst, svc.getDiscussionTranscript(SESSION).size(), "不重复插入转录");
        assertEquals(sseAfterFirst, (int) sse.events.stream().filter(e -> "script_present".equals(e.getKey())).count(),
                "不重复广播 script_present");

        // 其他玩家重复出示同样幂等（presentedClues 是线索级）
        Map<String, Object> dupByOther = svc.present(SESSION, "Bob", "c1");
        assertEquals(Boolean.TRUE, dupByOther.get("already"), "他人重复出示同样提示已出示");
        assertEquals(turnsAfterFirst, svc.getDiscussionTranscript(SESSION).size(), "仍不重复插入");
    }

    @Test
    @DisplayName("API-9d: 持有校验 —— 未持有非公开线索拒绝；公开线索全员可直接出示")
    void presentHeldClueAndPublic() {
        CaptureSSE sse = new CaptureSSE();
        ScriptGameService svc = new ScriptGameService(presentLlm(), new ApprovalService(), null, sse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        game.phase = ScriptGameService.Phase.DISCUSSION;

        // ① 未持有非公开线索（Alice 未搜证 c2）→ 拒绝
        Map<String, Object> notHeld = svc.present(SESSION, "Alice", "c2");
        assertTrue(notHeld.get("error").toString().contains("未持有该线索"), notHeld.toString());

        // ② 公开线索（c3 public=true）未持有也可出示
        Map<String, Object> pubOk = svc.present(SESSION, "Alice", "c3");
        assertEquals(Boolean.TRUE, pubOk.get("ok"));
        assertEquals("带泥的铲子", ((Map<?, ?>) pubOk.get("presented")).get("title"));
        Map<String, String> turn = lastTurn(svc);
        assertEquals("🃏 出示：c3 带泥的铲子", turn.get("message"), "公开线索出示行");
    }

    @Test
    @DisplayName("API-9e: 快照落库/恢复 —— presented_clues 随快照加键（零迁移），恢复后幂等语义保持")
    void presentPersistedInSnapshot() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        when(db.saveScript(anyString(), anyMap())).thenAnswer(inv -> {
            saved.set(inv.getArgument(1));
            return Map.of("name", inv.getArgument(0));
        });
        when(db.getLatestScriptSnapshot(eq(SESSION))).thenAnswer(inv ->
                saved.get() == null ? Optional.empty() : Optional.of(saved.get()));

        ScriptGameService svc = new ScriptGameService(presentLlm(), new ApprovalService(), db, new CaptureSSE());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String aliceKey = svc.getRoleKey(SESSION, "Alice");

        svc.search(SESSION, "Alice", "客厅"); // 搜证阶段得 c1
        game.phase = ScriptGameService.Phase.DISCUSSION;
        svc.present(SESSION, "Alice", "c1");
        assertNotNull(saved.get(), "出示后快照已落库");
        // 快照内含 presented_clues 键
        assertTrue(((List<?>) saved.get().get("presented_clues")).contains("c1"), "快照内 presented_clues 含 c1");
        // 快照内转录含出示 system 行
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> snapTranscript = (List<Map<String, Object>>) saved.get().get("discussion_transcript");
        assertTrue(snapTranscript.stream().anyMatch(t -> "system".equals(t.get("speaker"))
                && String.valueOf(t.get("message")).contains("🃏 出示：c1")), "快照转录含出示行");

        // 清内存（模拟重启）→ 从快照恢复
        clearGamesMemory(svc);
        assertNull(svc.getGame(SESSION), "内存对局已清空");
        Map<String, Object> view = svc.resumeGame(SESSION, aliceKey);
        assertEquals(Boolean.TRUE, view.get("restored"), "从快照重建");
        ScriptGameService.ScriptGame restored = svc.getGame(SESSION);
        assertTrue(restored.presentedClues.contains("c1"), "恢复后 presentedClues 保持");
        // 恢复后转录含出示行（全员可见恢复）
        assertTrue(restored.discussionTranscript.stream().anyMatch(t -> "system".equals(t.get("speaker"))
                && t.get("message").contains("🃏 出示：c1")), "恢复后转录含出示行");

        // 恢复后的对局幂等语义保持：重复出示仍提示已出示（不重复插入）
        int turnsBefore = restored.discussionTranscript.size();
        Map<String, Object> dup = svc.present(SESSION, "Alice", "c1");
        assertEquals(Boolean.TRUE, dup.get("already"), "恢复后重复出示仍 already");
        assertEquals(turnsBefore, restored.discussionTranscript.size(), "恢复后不重复插入");
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
