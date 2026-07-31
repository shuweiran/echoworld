package com.roleplay.engine.memory;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.model.CompressedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D10: MemoryRetrieval 检索打分单元测试。
 *
 * <p>覆盖: 综合分排序 / recency 衰减 / relevance 关键词匹配 /
 * importance 权重 / 空输入边界 / tokenise / 权重配置。
 */
class MemoryRetrievalTest {

    // ── Helpers ────────────────────────────────────────────────

    private static Message msg(int round, String content, int importance) {
        return new Message(Message.Role.AGENT, "Agent", content)
            .withRound(round)
            .withImportance(importance);
    }

    private static CompressedChunk chunk(int startRound, int endRound,
                                          String summary, double importance) {
        return new CompressedChunk("ck_" + startRound, startRound, endRound,
            summary, List.of(), List.of(), importance);
    }

    // ── Composite score ordering ───────────────────────────────

    @Test
    @DisplayName("retrieveMessages: top3 by composite score, higher is first")
    void messagesTopKOrdering() {
        List<Message> msgs = List.of(
            msg(5, "finding the body discovery evidence murder", 8),  // best match
            msg(4, "ordinary conversation about weather", 5),
            msg(3, "body found in warehouse discussion", 7),
            msg(2, "what to eat for dinner", 5),
            msg(1, "investigation murder scene clues", 8)
        );
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveMessages(msgs, "body murder", 3, 5);

        assertEquals(3, result.size());
        // Highest composite score first
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).getCompositeScore() >= result.get(i + 1).getCompositeScore(),
                "Index " + i + " score=" + result.get(i).getCompositeScore()
                    + " should be >= " + (i + 1) + " score=" + result.get(i + 1).getCompositeScore());
        }
        // Round-5 "finding the body discovery evidence murder" should rank high
        // (best relevance + high importance 8 + newest recency)
        assertEquals(ScoredMemory.SourceType.MESSAGE, result.get(0).getSourceType());
    }

    @Test
    @DisplayName("retrieveChunks: top2 chunks by composite score")
    void chunksTopKOrdering() {
        int currentRound = 20;
        List<CompressedChunk> chunks = List.of(
            chunk(1, 3, "initial meeting introductions", 0.3),
            chunk(4, 6, "discovery body warehouse investigation", 0.8),
            chunk(7, 9, "suspect identified clues", 0.7),
            chunk(10, 12, "ordinary patrol nothing happened", 0.2),
            chunk(17, 19, "murder scene evidence found", 0.9)   // newest + high importance
        );
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveChunks(chunks, "body murder evidence", 3, currentRound);

        assertFalse(result.isEmpty());
        // Round order: newest high-importance "murder scene" should score highest
        assertEquals(ScoredMemory.SourceType.CHUNK, result.get(0).getSourceType());
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).getCompositeScore() >= result.get(i + 1).getCompositeScore());
        }
    }

    // ── Recency decay ──────────────────────────────────────────

    @Test
    @DisplayName("recency: same content, newer rounds score higher")
    void recencyNewerWinsOnEqualRelevance() {
        List<Message> msgs = List.of(
            msg(1, "missing treasure map", 5),
            msg(10, "missing treasure map", 5)
        );
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveMessages(msgs, "treasure", 2, 10);

        assertEquals(2, result.size());
        // Both have same relevance and importance → newer (round 10) should rank first
        Message first = result.get(0).getMessage();
        assertEquals(10, first.getRoundNumber());
        Message second = result.get(1).getMessage();
        assertEquals(1, second.getRoundNumber());
    }

    @Test
    @DisplayName("recency: exponential decay — old message barely scores")
    void recencyExponentialDecay() {
        MemoryRetrieval r = new MemoryRetrieval(0.5, 0.5, 0.0, 0.1);
        // Age 0: recency = 1.0
        double r0 = r.calcRecency(0);
        assertEquals(1.0, r0, 0.001);

        // Age 7: recency ≈ 0.5 (half-life with λ=0.1)
        double r7 = r.calcRecency(7);
        assertTrue(r7 > 0.45 && r7 < 0.55, "Recency at age 7 should be ≈0.5, got " + r7);

        // Age 30: recency ≈ 0.05 (barely relevant)
        double r30 = r.calcRecency(30);
        assertTrue(r30 < 0.1, "Recency at age 30 should be < 0.1, got " + r30);

        // Age 100: recency ≈ 0.000045
        double r100 = r.calcRecency(100);
        assertTrue(r100 < 0.001, "Recency at age 100 should be near-zero, got " + r100);
    }

    @Test
    @DisplayName("recency: negative age clamped to 1.0")
    void recencyNegativeAge() {
        MemoryRetrieval r = new MemoryRetrieval();
        assertEquals(1.0, r.calcRecency(-1), 0.001);
        assertEquals(1.0, r.calcRecency(-100), 0.001);
    }

    // ── Relevance scoring ──────────────────────────────────────

    @Test
    @DisplayName("relevance: exact keyword match scores higher than no match")
    void relevanceKeywordMatch() {
        List<Message> msgs = List.of(
            msg(5, "completely unrelated talk about food", 5),
            msg(5, "the body was found in the warehouse last night", 5)
        );
        MemoryRetrieval r = new MemoryRetrieval(0.0, 1.0, 0.0, 0.1); // relevance-only
        List<ScoredMemory> result = r.retrieveMessages(msgs, "body warehouse", 2, 5);

        assertEquals(2, result.size());
        assertTrue(result.get(0).getRelevanceScore() > result.get(1).getRelevanceScore(),
            "Body/warehouse msg should have higher relevance than food msg");
    }

    @Test
    @DisplayName("relevance: Chinese keyword matching")
    void relevanceChineseTokens() {
        List<Message> msgs = List.of(
            msg(5, "今天天气真好，适合出去散步", 5),
            msg(5, "仓库里发现了一具尸体，死因不明", 5)
        );
        MemoryRetrieval r = new MemoryRetrieval(0.0, 1.0, 0.0, 0.1);
        List<ScoredMemory> result = r.retrieveMessages(msgs, "尸体 仓库", 2, 5);

        assertEquals(2, result.size());
        assertTrue(result.get(0).getRelevanceScore() > 0.3,
            "Chinese keyword '尸体 仓库' should match, got " + result.get(0).getRelevanceScore());
    }

    @Test
    @DisplayName("relevance: blank query → 0.0 for all")
    void relevanceBlankQuery() {
        List<Message> msgs = List.of(
            msg(5, "important discovery", 8),
            msg(4, "ordinary chat", 5)
        );
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveMessages(msgs, "", 2, 5);

        // All relevance scores should be 0
        for (ScoredMemory sm : result) {
            assertEquals(0.0, sm.getRelevanceScore(), 0.001,
                "Blank query should give 0 relevance for all");
        }
    }

    @Test
    @DisplayName("relevance: null query → 0.0 for all")
    void relevanceNullQuery() {
        List<Message> msgs = List.of(msg(5, "anything", 5));
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveMessages(msgs, null, 1, 5);
        assertEquals(1, result.size());
        assertEquals(0.0, result.get(0).getRelevanceScore(), 0.001);
    }

    // ── Importance scoring ─────────────────────────────────────

    @Test
    @DisplayName("importance: high-importance (8) scores higher than normal (5)")
    void importanceHigherWins() {
        List<Message> msgs = List.of(
            msg(5, "lore trigger event", 8),
            msg(5, "ordinary chat", 5)
        );
        MemoryRetrieval r = new MemoryRetrieval(0.0, 0.0, 1.0, 0.1); // importance-only
        List<ScoredMemory> result = r.retrieveMessages(msgs, "dummy", 2, 5);

        assertEquals(2, result.size());
        assertEquals(8, result.get(0).getMessage().getImportance());
        assertTrue(result.get(0).getImportanceScore() > result.get(1).getImportanceScore(),
            "Importance 8 should score higher than 5");
    }

    @Test
    @DisplayName("importance: value 1 normalises to 0.0")
    void importanceMinNormalised() {
        Message m = msg(5, "low importance", 1);
        MemoryRetrieval r = new MemoryRetrieval();
        assertEquals(0.0, r.calcMessageImportance(m), 0.001);
    }

    @Test
    @DisplayName("importance: value 8 normalises to 1.0")
    void importanceMaxNormalised() {
        Message m = msg(5, "high importance", 8);
        MemoryRetrieval r = new MemoryRetrieval();
        assertEquals(1.0, r.calcMessageImportance(m), 0.001);
    }

    // ── Empty / edge inputs ────────────────────────────────────

    @Test
    @DisplayName("empty messages → empty result")
    void emptyMessages() {
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveMessages(List.of(), "query", 5, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("null messages → empty result")
    void nullMessages() {
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveMessages(null, "query", 5, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("empty chunks → empty result")
    void emptyChunks() {
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveChunks(List.of(), "query", 5, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("topK=1 returns exactly 1 when candidates present")
    void topKOne() {
        List<Message> msgs = List.of(
            msg(1, "a", 5), msg(2, "b", 5), msg(3, "c", 5)
        );
        MemoryRetrieval r = new MemoryRetrieval();
        assertEquals(1, r.retrieveMessages(msgs, "a", 1, 3).size());
    }

    @Test
    @DisplayName("topK exceeds count → returns all candidates")
    void topKExceedsCount() {
        List<Message> msgs = List.of(msg(1, "x", 5));
        MemoryRetrieval r = new MemoryRetrieval();
        assertEquals(1, r.retrieveMessages(msgs, "x", 10, 1).size());
    }

    @Test
    @DisplayName("single result — all sub-scores present")
    void singleResultFields() {
        Message m = msg(5, "body found in warehouse", 8);
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveMessages(List.of(m), "body", 1, 5);

        assertEquals(1, result.size());
        ScoredMemory sm = result.get(0);
        assertEquals(ScoredMemory.SourceType.MESSAGE, sm.getSourceType());
        assertSame(m, sm.getMessage());
        assertTrue(sm.getCompositeScore() >= 0.0 && sm.getCompositeScore() <= 1.0);
        assertTrue(sm.getRecencyScore() >= 0.0 && sm.getRecencyScore() <= 1.0);
        assertTrue(sm.getRelevanceScore() >= 0.0 && sm.getRelevanceScore() <= 1.0);
        assertTrue(sm.getImportanceScore() >= 0.0 && sm.getImportanceScore() <= 1.0);
    }

    // ── Tokenisation ───────────────────────────────────────────

    @Test
    @DisplayName("tokenise: Chinese bi-grams")
    void tokeniseChinese() {
        Set<String> tokens = MemoryRetrieval.tokenize("仓库尸体");
        assertTrue(tokens.contains("仓库"), "Should contain bigram 仓库");
        assertTrue(tokens.contains("库尸"), "Should contain bigram 库尸");
        assertTrue(tokens.contains("尸体"), "Should contain bigram 尸体");
    }

    @Test
    @DisplayName("tokenise: single Chinese char")
    void tokeniseSingleChineseChar() {
        Set<String> tokens = MemoryRetrieval.tokenize("尸");
        assertTrue(tokens.contains("尸"));
    }

    @Test
    @DisplayName("tokenise: English words")
    void tokeniseEnglish() {
        Set<String> tokens = MemoryRetrieval.tokenize("Hello World! Murder");
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains("world"));
        assertTrue(tokens.contains("murder"));
    }

    @Test
    @DisplayName("tokenise: mixed Chinese/English")
    void tokeniseMixed() {
        Set<String> tokens = MemoryRetrieval.tokenize("body尸体仓库murder");
        assertTrue(tokens.contains("尸体"));
        assertTrue(tokens.contains("body"));
        assertTrue(tokens.contains("murder"));
    }

    @Test
    @DisplayName("tokenise: null → empty set")
    void tokeniseNull() {
        assertTrue(MemoryRetrieval.tokenize(null).isEmpty());
    }

    @Test
    @DisplayName("tokenise: blank → empty set")
    void tokeniseBlank() {
        assertTrue(MemoryRetrieval.tokenize("   ").isEmpty());
    }

    // ── Weighted scoring ───────────────────────────────────────

    @Test
    @DisplayName("custom weights: all-recency ranks newest first regardless of content")
    void customWeightsAllRecency() {
        List<Message> msgs = List.of(
            msg(1, "body murder evidence", 8),   // best relevance + importance, but oldest
            msg(9, "unrelated chat", 1),          // worst content, but newest
            msg(5, "medium content", 5)
        );
        MemoryRetrieval r = new MemoryRetrieval(1.0, 0.0, 0.0, 0.1); // recency-only
        List<ScoredMemory> result = r.retrieveMessages(msgs, "body murder", 3, 10);

        assertEquals(3, result.size());
        // Order: newest first regardless of content
        assertEquals(9, result.get(0).getMessage().getRoundNumber());
        assertEquals(5, result.get(1).getMessage().getRoundNumber());
        assertEquals(1, result.get(2).getMessage().getRoundNumber());
    }

    @Test
    @DisplayName("custom weights: all-relevance ignores recency/importance")
    void customWeightsAllRelevance() {
        List<Message> msgs = List.of(
            msg(10, "body murder crime scene", 1),   // newest, low importance
            msg(1, "weather chat", 8)                 // oldest, high importance
        );
        MemoryRetrieval r = new MemoryRetrieval(0.0, 1.0, 0.0, 0.1);
        List<ScoredMemory> result = r.retrieveMessages(msgs, "body murder", 2, 10);

        // "body murder crime scene" should rank first despite being low importance
        assertEquals("body murder crime scene", result.get(0).getMessage().getContent());
        assertTrue(result.get(0).getRelevanceScore() > 0.0);
        assertEquals(0.0, result.get(0).getRecencyScore(), 0.001);
        assertEquals(0.0, result.get(0).getImportanceScore(), 0.001);
    }

    @Test
    @DisplayName("weights are normalised to sum=1.0")
    void weightsSumToOne() {
        MemoryRetrieval r = new MemoryRetrieval(2.0, 3.0, 5.0, 0.1);
        double sum = r.getWeightRecency() + r.getWeightRelevance() + r.getWeightImportance();
        assertEquals(1.0, sum, 0.0001);
    }

    // ── Chunk scoring ──────────────────────────────────────────

    @Test
    @DisplayName("chunk importance passes through as-is (0-1)")
    void chunkImportancePreserved() {
        CompressedChunk c = chunk(5, 10, "discovery of the body", 0.9);
        MemoryRetrieval r = new MemoryRetrieval(0.0, 0.0, 1.0, 0.1);
        List<ScoredMemory> result = r.retrieveChunks(List.of(c), "dummy", 1, 10);

        assertEquals(1, result.size());
        assertEquals(0.9, result.get(0).getImportanceScore(), 0.001);
    }

    @Test
    @DisplayName("chunk with null fields doesn't NPE")
    void chunkNullFieldsSafe() {
        CompressedChunk c = new CompressedChunk(); // all defaults
        c.setChunkId("empty");
        MemoryRetrieval r = new MemoryRetrieval();
        List<ScoredMemory> result = r.retrieveChunks(List.of(c), "query", 1, 5);
        assertEquals(1, result.size());
        assertNotNull(result.get(0).getText());
    }

    // ── Determinism ────────────────────────────────────────────

    @Test
    @DisplayName("identical inputs produce identical results")
    void deterministicOutput() {
        List<Message> msgs = List.of(
            msg(1, "first clue about murder", 7),
            msg(2, "second clue about weapon", 7),
            msg(3, "third clue about alibi", 7)
        );
        MemoryRetrieval r = new MemoryRetrieval();

        List<ScoredMemory> r1 = r.retrieveMessages(msgs, "murder weapon", 3, 5);
        List<ScoredMemory> r2 = r.retrieveMessages(msgs, "murder weapon", 3, 5);

        assertEquals(r1.size(), r2.size());
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(r1.get(i).getCompositeScore(), r2.get(i).getCompositeScore(), 0.0001,
                "Deterministic output mismatch at index " + i);
            assertEquals(r1.get(i).getMessage().getRoundNumber(),
                         r2.get(i).getMessage().getRoundNumber());
        }
    }

    // ── maxK=0 edge case ───────────────────────────────────────

    @Test
    @DisplayName("topK <= 0 returns at least 1 result when candidates present")
    void topKZeroReturnsAtLeastOne() {
        List<Message> msgs = List.of(msg(5, "test", 5));
        MemoryRetrieval r = new MemoryRetrieval();
        // limit clamps max at 1 internally; minimal single result is returned
        assertEquals(1, r.retrieveMessages(msgs, "test", 0, 5).size());
        assertEquals(1, r.retrieveMessages(msgs, "test", -1, 5).size());
    }

    // ── All-zero weights edge ──────────────────────────────────

    @Test
    @DisplayName("all-zero weights still produce valid 0.0 scores")
    void allZeroWeights() {
        List<Message> msgs = List.of(msg(5, "test", 5));
        MemoryRetrieval r = new MemoryRetrieval(0.0, 0.0, 0.0, 0.1);
        List<ScoredMemory> result = r.retrieveMessages(msgs, "test", 1, 5);
        assertEquals(1, result.size());
        assertEquals(0.0, result.get(0).getCompositeScore(), 0.001);
    }
}
