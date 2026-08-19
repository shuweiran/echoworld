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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0805-B：私聊闭环（AI 应答/秘密守卫/历史/快照）+ WebSearch 搜证 + 对局时钟。
 */
class ScriptGamePrivateChatTest {

    private static final String SESSION = "private-chat-session";

    /** 剧本：killer_id=role_b（女仆真凶），truth 不含凶手名（复用 B2 结构）。 */
    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("name", "雪夜凶案");
        script.put("background", "雪夜庄园，主人被发现死于书房。");
        script.put("truth", "真相：凶器是书房花瓶，动机是遗产之争。");
        script.put("killer_id", "role_b");
        script.put("roles", List.of(
            Map.of("id", "role_a", "name", "管家"),
            Map.of("id", "role_b", "name", "女仆"),
            Map.of("id", "role_c", "name", "园丁")));
        script.put("locations", List.of("书房", "客厅"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "书房", "content", "碎花瓶", "public", false, "related_role", "女仆")));
        script.put("secrets", Map.of("管家", "你见过女仆进书房", "女仆", "你动了花瓶", "园丁", "你在雪地看到脚印"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenReturn("我确实去过年夜的书房，但只是替夫人取披风。");
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

    private ScriptGameService newService() {
        return new ScriptGameService(mockLlm(), autoApprove());
    }

    private void toInvestigation(ScriptGameService svc, List<String> players) {
        svc.initGame(SESSION, "雪夜凶案", players);
    }

    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    @Test
    @DisplayName("P1 私聊：AI 角色应答 + 历史记录 + 快照持久化")
    void privateSayRepliesAndPersists() {
        ScriptGameService svc = newService();
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String maid = playerWithRole(game, "女仆"); // 真凶角色
        String sender = game.players.stream().filter(p -> !p.equals(maid)).findFirst().orElseThrow();

        Map<String, Object> res = svc.privateSay(SESSION, sender, maid, "那晚你去过书房吗？");

        assertEquals(Boolean.TRUE, res.get("ok"));
        assertEquals(sender, res.get("from"));
        assertEquals(maid, res.get("to"));
        assertNotNull(res.get("reply"), "AI 角色应有应答");
        assertFalse(String.valueOf(res.get("reply")).isBlank());
        assertNotNull(res.get("history"), "应返回历史");
        assertEquals(2, ((List<?>) res.get("history")).size(), "1 条发送 + 1 条应答");

        // 历史查询（任意方向键可查）
        List<Map<String, Object>> hist = svc.getPrivateChatHistory(SESSION, maid, sender);
        assertEquals(2, hist.size());
        assertEquals(sender, hist.get(0).get("from"));
        assertEquals(maid, hist.get(1).get("from"));
    }

    @Test
    @DisplayName("P2 私聊守卫：目标角色回应「我是凶手」类 → 被守卫拦截，不直接认罪")
    void privateGuardBlocksConfession() {
        ScriptGameService svc = newService();
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String maid = playerWithRole(game, "女仆");

        // 直接调用 guardPrivateSecret（反射）
        try {
            java.lang.reflect.Method m = ScriptGameService.class.getDeclaredMethod(
                    "guardPrivateSecret", ScriptGameService.ScriptGame.class, String.class, String.class);
            m.setAccessible(true);
            boolean hit = (Boolean) m.invoke(svc, game, maid, "实不相瞒，我是凶手，我杀了老爷。");
            assertTrue(hit, "认罪句应被守卫拦截");
            boolean miss = (Boolean) m.invoke(svc, game, maid, "我那晚只是路过书房。");
            assertFalse(miss, "正常句不应拦截");
        } catch (Exception e) {
            fail("反射调用守卫失败: " + e);
        }
    }

    @Test
    @DisplayName("P3 私聊校验：不在局中/和自己聊/空消息 → 拒绝")
    void privateValidation() {
        ScriptGameService svc = newService();
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));

        assertTrue(String.valueOf(svc.privateSay(SESSION, "Alice", "Eve", "hi").get("error")).contains("本局"));
        assertTrue(String.valueOf(svc.privateSay(SESSION, "Alice", "Alice", "hi").get("error")).contains("不能和自己"));
        assertTrue(String.valueOf(svc.privateSay(SESSION, "Alice", "Bob", "  ").get("error")).contains("不能为空"));
        assertTrue(String.valueOf(svc.privateSay("nonexistent", "Alice", "Bob", "hi").get("error")).contains("游戏不存在"));
    }

    @Test
    @DisplayName("P4 对局时钟：started_at / elapsed_ms 随 toMap 下发")
    void gameClockExposed() {
        ScriptGameService svc = newService();
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));
        Map<String, Object> st = svc.getGame(SESSION).toMap("Alice");
        assertTrue(st.containsKey("started_at"), "toMap 应含 started_at");
        assertTrue(st.containsKey("elapsed_ms"), "toMap 应含 elapsed_ms");
        assertTrue((Long) st.get("elapsed_ms") >= 0);
    }

    @Test
    @DisplayName("P5 WebSearch 搜证：默认关不影响既有结果；开启时附加 web_results（mock 无网 → 空/降级不阻塞）")
    void webSearchEnrichment() {
        ScriptGameService svc = newService();
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String maid = playerWithRole(game, "女仆");

        // 默认关 → 搜证正常无 web_results
        Map<String, Object> off = svc.search(SESSION, "Alice", "书房");
        assertFalse(off.containsKey("web_results"), "默认关不应有 web_results");
        assertTrue(((List<?>) off.get("found")).size() >= 1, "搜证正常");

        // 开启 → 走真实 WebSearchService（测试环境无网 → 内部 catch 返回空；不阻塞不抛）
        svc.setWebSearchEnabled(true);
        ScriptGameService svc2 = newService();
        svc2.setWebSearchEnabled(true);
        svc2.initGame(SESSION, "雪夜凶案", List.of("Alice", "Bob", "Carol"));
        Map<String, Object> on = svc2.search(SESSION, "Alice", "书房");
        assertTrue(((List<?>) on.get("found")).size() >= 1, "开启后搜证仍正常（联网失败静默）");
        // 无论是否有网，result 文案不变
        assertTrue(String.valueOf(on.get("result")).startsWith("搜证成功"));
    }

    @Test
    @DisplayName("P6 阶段倒计时：INVESTIGATION 超时 → 轮询触发自动进 DISCUSSION；DISCUSSION 超时 → 自动进 VOTE")
    void phaseTimeoutAutoAdvances() throws Exception {
        ScriptGameService svc = newService();
        svc.setPhaseTimeoutMs(50);
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        assertEquals(ScriptGameService.Phase.INVESTIGATION, game.phase);

        // 未超时 → getGame 不推进
        assertNotNull(svc.getGame(SESSION));
        assertEquals(ScriptGameService.Phase.INVESTIGATION, game.phase);

        Thread.sleep(120); // 超 50ms

        // 轮询（getGame）触发惰性推进 → INVESTIGATION → DISCUSSION
        assertNotNull(svc.getGame(SESSION));
        assertEquals(ScriptGameService.Phase.DISCUSSION, game.phase,
                "INVESTIGATION 超时应自动进 DISCUSSION");

        Thread.sleep(120); // 再超 50ms

        // 再轮询 → DISCUSSION → VOTE
        assertNotNull(svc.getGame(SESSION));
        assertEquals(ScriptGameService.Phase.VOTE, game.phase,
                "DISCUSSION 超时应自动进 VOTE");
    }

    @Test
    @DisplayName("P7 阶段倒计时：VOTE 超时不自动推进（复用投票超时/弃票，不未经审批直接揭晓）")
    void phaseTimeoutSkipsVote() throws Exception {
        ScriptGameService svc = newService();
        svc.setPhaseTimeoutMs(30);
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // 手动推进到 VOTE
        svc.startDiscussion(SESSION);
        Thread.sleep(80); // DISCUSSION 超时
        svc.getGame(SESSION); // 触发推进 → VOTE
        assertEquals(ScriptGameService.Phase.VOTE, game.phase);

        Thread.sleep(80); // VOTE 超时
        svc.getGame(SESSION); // 轮询
        assertEquals(ScriptGameService.Phase.VOTE, game.phase, "VOTE 不应被阶段倒计时推进");
    }

    @Test
    @DisplayName("P8 对局时钟 toMap 含 phase_started_at/phase_elapsed_ms")
    void phaseClockExposed() {
        ScriptGameService svc = newService();
        toInvestigation(svc, List.of("Alice", "Bob", "Carol"));
        Map<String, Object> st = svc.getGame(SESSION).toMap("Alice");
        assertTrue(st.containsKey("phase_started_at"), "toMap 应含 phase_started_at");
        assertTrue(st.containsKey("phase_elapsed_ms"), "toMap 应含 phase_elapsed_ms");
    }
}
