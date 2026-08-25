package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.track.EavesdropSummarizer;
import com.roleplay.engine.simulation.track.SpatialTrackResolver;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TrackStrategy} — the Phase 2 unified replacement for
 * Group/Debate strategies.
 *
 * <p>Core scenarios:
 * <ul>
 *   <li>mixed-track group (A/B=MERGED, C=WEAK, D=ISOLATED) — context visibility
 *       must follow each member's track mode (full / summary / none)</li>
 *   <li>no-track fallback — legacy path must not crash and must produce contexts</li>
 *   <li>WEAK summary degradation without LLM — rule-based fallback, no exception</li>
 *   <li>ISOLATED members never record group turns</li>
 * </ul>
 */
class TrackStrategyTest {

    private static final double HEAR_RANGE = 200.0;
    private static final double CONVERSATION_DISTANCE = 5.0;

    private AgentState agent(String name, double x, double y) {
        AgentState s = new AgentState(name, x, y);
        s.setHearRange(HEAR_RANGE);
        return s;
    }

    private Map<String, Agent> agentLookup(AgentState... states) {
        Map<String, Agent> agents = new HashMap<>();
        for (AgentState s : states) {
            agents.put(s.getAgentName(),
                    new Agent(new Persona(s.getAgentName(), "测试人格" + s.getAgentName()), "test", null));
        }
        return agents;
    }

    private TrackStrategy strategy(Map<String, Agent> agents) {
        java.util.function.Function<String, Agent> lookup = name -> agents.get(name);
        java.util.function.Function<String, String> narration = s -> "测试场景";
        java.util.function.Function<String, TopicManager> topicSupplier = gid -> new TopicManager();
        return new TrackStrategy(lookup, narration, new EavesdropSummarizer(), topicSupplier);
    }

    /** A/B 密谈(MERGED)，C 远处旁观(WEAK)，D 超出听觉范围(ISOLATED)。 */
    private ConversationGroup mixedGroup() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);
        AgentState c = agent("C", 50, 0);
        AgentState d = agent("D", 500, 0);

        Map<String, TrackAssignment> assignments =
                new SpatialTrackResolver(CONVERSATION_DISTANCE).resolve(List.of(a, b, c, d));
        assertEquals(Track.Mode.MERGED, assignments.get("A").type());
        assertEquals(Track.Mode.MERGED, assignments.get("B").type());
        assertEquals(Track.Mode.WEAK, assignments.get("C").type());
        assertEquals(Track.Mode.ISOLATED, assignments.get("D").type());

        ConversationGroup group = new ConversationGroup(
                "A+B+C+D", ConversationMode.GROUP_DISCUSSION, List.of(a, b, c, d));
        group.setTrackAssignments(assignments);
        group.setCurrentSpeaker("A");

        // Seed some conversation history before the round.
        group.recordTurn("A", "我们在仓库后面发现了那批失踪的货物");
        group.recordTurn("B", "真的吗？这太不可思议了");
        return group;
    }

    // ── a. Mixed-track context building ────────────────────────

    @Test
    @DisplayName("混合轨道：A/B(MERGED) 拿到全文，C(WEAK) 只有摘要，D(ISOLATED) 无任何对话内容")
    void mixedTrackContextVisibility() {
        ConversationGroup group = mixedGroup();
        TrackStrategy strategy = strategy(agentLookup(
                agent("A", 0, 0), agent("B", 3, 0), agent("C", 50, 0), agent("D", 500, 0)));

        Map<String, Map<String, String>> contexts = new HashMap<>();
        strategy.prepareContext(group, contexts);

        assertEquals(4, contexts.size());

        String aCtx = contexts.get("A").get("context");
        String bCtx = contexts.get("B").get("context");
        String cCtx = contexts.get("C").get("context");
        String dCtx = contexts.get("D").get("context");

        assertNotNull(aCtx);
        assertNotNull(bCtx);
        assertNotNull(cCtx);
        assertNotNull(dCtx);

        // MERGED: full chat history is visible.
        assertTrue(aCtx.contains("那批失踪的货物"), "A (MERGED) should see full history");
        assertTrue(aCtx.contains("太不可思议"), "A (MERGED) should see full history");
        assertTrue(bCtx.contains("那批失踪的货物"), "B (MERGED) should see full history");

        // WEAK: summary only — never the full dialogue lines.
        assertFalse(cCtx.contains("那批失踪的货物"), "C (WEAK) must not receive full dialogue");
        assertFalse(cCtx.contains("太不可思议"), "C (WEAK) must not receive full dialogue");
        assertTrue(cCtx.contains("旁观") || cCtx.contains("听不清"),
                "C (WEAK) should get a summarized observation");

        // ISOLATED: zero conversation content — only the alone/outside scene.
        assertFalse(dCtx.contains("那批失踪的货物"), "D (ISOLATED) must not receive any dialogue");
        assertFalse(dCtx.contains("仓库"), "D (ISOLATED) must not receive any dialogue");
        assertFalse(dCtx.contains("不可思议"), "D (ISOLATED) must not receive any dialogue");
        assertTrue(dCtx.contains("独白"), "D (ISOLATED) should be directed to an inner monologue");
    }

    @Test
    @DisplayName("信息泄漏不变量：WEAK 与 ISOLATED 均不得收到私密设施位置原文")
    void privateInfrastructureSecretNeverLeaksToWeakOrIsolatedAgents() {
        AgentState alice = agent("Alice", 0, 0);
        AgentState bob = agent("Bob", 3, 0);
        AgentState charlie = agent("Charlie", 50, 0);
        AgentState diana = agent("Diana", 500, 0);

        ConversationGroup group = new ConversationGroup(
                "infrastructure-secret",
                ConversationMode.GROUP_DISCUSSION,
                List.of(alice, bob, charlie, diana));
        group.setTrackAssignments(new SpatialTrackResolver(CONVERSATION_DISTANCE)
                .resolve(List.of(alice, bob, charlie, diana)));
        group.setCurrentSpeaker("Alice");
        group.recordTurn("Alice", "闸门密钥在北侧控制柜");

        TrackStrategy strategy = strategy(agentLookup(alice, bob, charlie, diana));
        Map<String, Map<String, String>> contexts = new HashMap<>();
        strategy.prepareContext(group, contexts);

        String merged = contexts.get("Bob").get("context");
        String weak = contexts.get("Charlie").get("context");
        String isolated = contexts.get("Diana").get("context");

        assertTrue(merged.contains("闸门密钥在北侧控制柜"),
                "MERGED participant should receive the full authorized context");
        for (String sensitiveFragment : List.of("闸门密钥", "北侧", "控制柜")) {
            assertFalse(weak.contains(sensitiveFragment),
                    "WEAK listener must not receive sensitive fragment: " + sensitiveFragment);
            assertFalse(isolated.contains(sensitiveFragment),
                    "ISOLATED agent must not receive sensitive fragment: " + sensitiveFragment);
        }
    }

    @Test
    @DisplayName("混合轨道：role 标注正确（active/listener/isolated）")
    void mixedTrackRoles() {
        ConversationGroup group = mixedGroup();
        TrackStrategy strategy = strategy(agentLookup(
                agent("A", 0, 0), agent("B", 3, 0), agent("C", 50, 0), agent("D", 500, 0)));

        Map<String, Map<String, String>> contexts = new HashMap<>();
        strategy.prepareContext(group, contexts);

        assertEquals("active", contexts.get("A").get("role"));
        assertEquals("active", contexts.get("B").get("role"));
        assertEquals("listener", contexts.get("C").get("role"));
        assertEquals("isolated", contexts.get("D").get("role"));
    }

    // ── b. No-track fallback ───────────────────────────────────

    @Test
    @DisplayName("无 trackAssignments：fallback 正常出上下文，不崩溃")
    void fallbackWithoutTrackAssignments() {
        AgentState a = agent("A", 0, 0);
        AgentState b = agent("B", 3, 0);
        AgentState c = agent("C", 50, 0);
        ConversationGroup group = new ConversationGroup(
                "abc", ConversationMode.GROUP_DISCUSSION, List.of(a, b, c));
        group.setCurrentSpeaker("A");   // no setTrackAssignments → legacy path
        group.recordTurn("A", "大家好，今天聊点什么");

        TrackStrategy strategy = strategy(agentLookup(a, b, c));

        Map<String, Map<String, String>> contexts = new HashMap<>();
        strategy.prepareContext(group, contexts);   // must not throw

        assertEquals(3, contexts.size());
        for (Map<String, String> ctx : contexts.values()) {
            assertNotNull(ctx.get("context"));
            assertFalse(ctx.get("context").isBlank(), "fallback context must not be blank");
        }

        // Legacy shouldContinue path must work too.
        assertTrue(strategy.shouldContinue(group));
    }

    // ── c. WEAK summary degradation without LLM ────────────────

    @Test
    @DisplayName("WEAK 摘要降级：无 LLM 时规则降级，不抛异常")
    void weakSummaryRuleBasedFallback() {
        // Direct rule-based fallback on the summarizer itself.
        EavesdropSummarizer summarizer = new EavesdropSummarizer();   // null LLM
        List<Map<String, String>> history = List.of(
                Map.of("speaker", "A", "message", "我们在仓库后面发现了那批失踪的货物"),
                Map.of("speaker", "B", "message", "真的吗？这太不可思议了"));
        String summary = summarizer.summarize(history);
        assertNotNull(summary);
        assertFalse(summary.isBlank());
        assertTrue(summary.contains("交谈") || summary.contains("听不清"));

        // And through TrackStrategy: WEAK member context is produced without exception.
        AgentState a = agent("A", 0, 0);
        AgentState c = agent("C", 50, 0);
        ConversationGroup group = new ConversationGroup(
                "ac", ConversationMode.GROUP_DISCUSSION, List.of(a, c));
        group.setTrackAssignments(new SpatialTrackResolver(CONVERSATION_DISTANCE).resolve(List.of(a, c)));
        group.setCurrentSpeaker("A");
        group.recordTurn("A", "我们在仓库后面发现了那批失踪的货物");

        TrackStrategy strategy = strategy(agentLookup(a, c));

        Map<String, Map<String, String>> contexts = new HashMap<>();
        strategy.prepareContext(group, contexts);   // must not throw

        String cCtx = contexts.get("C").get("context");
        assertNotNull(cCtx);
        assertTrue(cCtx.contains("旁观") || cCtx.contains("听不清") || cCtx.contains("交谈"),
                "WEAK context should carry the rule-based observation");
        assertFalse(cCtx.contains("那批失踪的货物"), "WEAK context must not leak full dialogue");
    }

    @Test
    @DisplayName("WEAK 摘要缓存：历史未大幅增长时复用缓存，不重新计算")
    void weakSummaryIsCachedOnGroup() {
        AgentState a = agent("A", 0, 0);
        AgentState c = agent("C", 50, 0);
        ConversationGroup group = new ConversationGroup(
                "ac", ConversationMode.GROUP_DISCUSSION, List.of(a, c));
        group.setTrackAssignments(new SpatialTrackResolver(CONVERSATION_DISTANCE).resolve(List.of(a, c)));
        group.setCurrentSpeaker("A");
        group.recordTurn("A", "我们在仓库后面发现了那批失踪的货物");

        TrackStrategy strategy = strategy(agentLookup(a, c));

        Map<String, Map<String, String>> contexts = new HashMap<>();
        strategy.prepareContext(group, contexts);
        String firstSummary = group.getTrackSummary();
        assertNotNull(firstSummary);
        assertFalse(firstSummary.isBlank(), "summary should be cached after first round");

        // A few new turns below the refresh threshold → cache must be reused.
        group.recordTurn("C", "…");
        group.recordTurn("A", "继续说");
        Map<String, Map<String, String>> contexts2 = new HashMap<>();
        strategy.prepareContext(group, contexts2);
        assertEquals(firstSummary, group.getTrackSummary(),
                "summary must be cached (no re-computation within refresh window)");
    }

    // ── d. ISOLATED members never record group turns ──────────

    @Test
    @DisplayName("processResults：MERGED 记录发言，ISOLATED 只落内心独白不入群聊")
    void isolatedResponseIsNotRecordedAsTurn() {
        ConversationGroup group = mixedGroup();   // history already has 2 turns
        TrackStrategy strategy = strategy(agentLookup(
                agent("A", 0, 0), agent("B", 3, 0), agent("C", 50, 0), agent("D", 500, 0)));

        Map<String, String> responses = new HashMap<>();
        responses.put("A", "我同意你的看法【情绪：开心】");
        responses.put("D", "我独自想着心事");

        strategy.processResults(group, responses, null);

        // A (MERGED): turn recorded, emotion updated.
        List<Map<String, String>> history = group.getMessageHistory();
        assertEquals(3, history.size(), "only A's turn may be added to group history");
        assertEquals("A", history.get(2).get("speaker"));
        assertEquals("我同意你的看法", history.get(2).get("message"));
        assertEquals(Emotion.HAPPY, group.getParticipant("A").getEmotion());

        // D (ISOLATED): inner monologue kept on the state, NOT in group history.
        assertEquals("我独自想着心事", group.getParticipant("D").getCurrentMessage());
        for (Map<String, String> h : history) {
            assertNotEquals("D", h.get("speaker"), "ISOLATED member must never appear in group history");
        }
    }

    @Test
    @DisplayName("shouldContinue：默认 8 轮 / 30s，ISOLATED 成员不阻止继续")
    void shouldContinueDefaultPolicy() {
        ConversationGroup group = mixedGroup();
        TrackStrategy strategy = strategy(agentLookup(
                agent("A", 0, 0), agent("B", 3, 0), agent("C", 50, 0), agent("D", 500, 0)));

        // Fresh group with an ISOLATED member: still continues.
        assertTrue(strategy.shouldContinue(group));

        // Round count >= 8 → stop, regardless of members.
        ConversationGroup exhausted = mixedGroup();
        int guard = 0;
        while (exhausted.getRoundCount() < 8 && guard++ < 50) {
            exhausted.recordTurn("A", "继续聊");
        }
        assertTrue(exhausted.getRoundCount() >= 8);
        assertFalse(strategy.shouldContinue(exhausted));
    }
}
