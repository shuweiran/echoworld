package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptGameFlowServiceTest {

    private ScriptGameFlowService flow;

    @AfterEach
    void tearDown() {
        if (flow != null) flow.shutdown();
    }

    @Test
    void readyFullGameReturnsToInteractiveOpeningBeforeInvestigation() throws Exception {
        Fixture f = fixture();
        ScriptGameService.ScriptGame g = f.game();
        assertEquals(ScriptGameService.Phase.INVESTIGATION, g.phase, "legacy executor initially lands in investigation");

        Map<String, Object> opening = flow.ensureOpening(f.sessionId(), "Alice");

        assertEquals(Boolean.TRUE, opening.get("opening_ready"));
        assertEquals(ScriptGameService.Phase.SETUP, g.phase);
        assertEquals("opening", flow.status(f.sessionId(), "Alice").get("flow_state"));
        assertNotNull(g.scriptSchema, "full script must remain ready while phase is opening/setup");
        assertFalse(g.generating);
    }

    @Test
    void knowledgeViewContainsOnlyPublicAndOwnedPrivateClues() {
        Fixture f = fixture();
        ScriptGameService.ScriptGame g = f.game();

        ScriptGameFlowService.KnowledgeView before = flow.knowledgeView(g, "Alice");
        assertTrue(before.knownClueIds().contains("public-clock"));
        assertFalse(before.knownClueIds().contains("hidden-glove"));
        assertFalse(before.knownClueIds().contains("hidden-letter"));

        // 模拟 Alice 已合法持有一条私有线索；知识视图应只增加本人持有项，不带另一条隐藏线索。
        g.playerClues.get("Alice").add("hidden-glove");
        ScriptGameFlowService.KnowledgeView after = flow.knowledgeView(g, "Alice");
        assertTrue(after.knownClueIds().contains("public-clock"));
        assertTrue(after.knownClueIds().contains("hidden-glove"));
        assertFalse(after.knownClueIds().contains("hidden-letter"));
    }

    @Test
    void playerConfirmationStartsAiInvestigationThroughAuthoritativeSearch() throws Exception {
        Fixture f = fixture();
        ScriptGameService.ScriptGame g = f.game();
        flow.ensureOpening(f.sessionId(), "Alice");

        int bobApBefore = g.playerAp.getOrDefault("Bob", 0);
        Map<String, Object> started = flow.startInvestigation(f.sessionId(), "Alice");

        assertEquals("investigation", started.get("phase"));
        assertEquals(Boolean.TRUE, started.get("ai_investigation_started"));
        assertEquals(ScriptGameService.Phase.INVESTIGATION, g.phase);

        long deadline = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < deadline) {
            if (!g.playerClues.getOrDefault("Bob", List.of()).isEmpty()
                    || g.playerAp.getOrDefault("Bob", bobApBefore) < bobApBefore) break;
            Thread.sleep(20);
        }
        assertTrue(g.playerAp.getOrDefault("Bob", bobApBefore) < bobApBefore,
                "AI search must consume AP through ScriptGameService.search");
        assertFalse(g.playerClues.getOrDefault("Bob", List.of()).isEmpty(),
                "AI search must persist personal clue ownership");

        // Alice 没有搜到 Bob 的私有线索，知识隔离仍成立。
        ScriptGameFlowService.KnowledgeView alice = flow.knowledgeView(g, "Alice");
        for (String id : g.playerClues.getOrDefault("Bob", List.of())) {
            if (!"public-clock".equals(id)) assertFalse(alice.knownClueIds().contains(id));
        }
    }

    @Test
    void openingReplyGuardBlocksExactHiddenClueLeak() throws Exception {
        Fixture f = fixture(true);
        ScriptGameService.ScriptGame g = f.game();
        flow.ensureOpening(f.sessionId(), "Alice");

        Map<String, Object> result = flow.openingSay(f.sessionId(), "Alice", "大家先说说目前知道什么。 ");
        assertEquals(Boolean.TRUE, result.get("ok"));
        assertEquals(Boolean.TRUE, result.get("npc_triggered"));

        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            boolean hasAiReply = g.publicChatTranscript.stream()
                    .anyMatch(t -> "Bob".equals(t.get("speaker")) || "Carol".equals(t.get("speaker")));
            if (hasAiReply) break;
            Thread.sleep(20);
        }

        // mock 故意让模型说出未持有的“带血手套”；服务端 guard 应丢弃并使用安全降级句。
        assertTrue(g.publicChatTranscript.stream()
                .filter(t -> "Bob".equals(t.get("speaker")) || "Carol".equals(t.get("speaker")))
                .noneMatch(t -> String.valueOf(t.get("message")).contains("带血手套")));
    }

    private Fixture fixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean leakHiddenClue) {
        String sid = "flow-" + System.nanoTime();
        LLMClient llm = mock(LLMClient.class);
        SSEController sse = mock(SSEController.class);

        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "钟楼疑案");
        script.put("background", "暴雨夜，钟楼内发生了一起案件。所有人只知道公开现场情况。");
        script.put("truth", "凶手是某位角色；该字段绝不能进入角色知识 prompt。");
        script.put("roles", List.of("Alice", "Bob", "Carol"));
        script.put("locations", List.of("大厅", "书房", "钟楼"));
        script.put("clues", List.of(
                Map.of("id", "public-clock", "title", "停摆的钟", "location", "大厅",
                        "content", "大厅时钟停在十一点。", "public", true),
                Map.of("id", "hidden-glove", "title", "带血手套", "location", "书房",
                        "content", "手套内侧有新鲜血迹。", "public", false, "ap_cost", 1),
                Map.of("id", "hidden-letter", "title", "撕碎的信", "location", "钟楼",
                        "content", "信纸只剩半句约见内容。", "public", false, "ap_cost", 1)));
        script.put("secrets", Map.of("Alice", "你害怕钟声", "Bob", "你隐瞒一次争执", "Carol", "你曾去过钟楼"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Message> messages = invocation.getArgument(0);
            String prompt = messages.isEmpty() ? "" : messages.get(messages.size() - 1).getContent();
            if (prompt.contains("候选地点")) return "书房";
            if (leakHiddenClue) return "我已经发现了带血手套，这就是关键证据。";
            return "先别急着下结论，我们把各自能确认的情况说清楚。";
        });

        ScriptGameService games = new ScriptGameService(llm, new ApprovalService(), null, sse);
        games.initGame(sid, "钟楼", List.of("Alice", "Bob", "Carol"), "full", false);
        games.designateHumanPlayer(sid, "Alice");
        flow = new ScriptGameFlowService(games, llm, sse);
        return new Fixture(sid, games, games.getGame(sid));
    }

    private record Fixture(String sessionId, ScriptGameService games, ScriptGameService.ScriptGame game) {}
}
