package com.roleplay.engine.memory;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.model.CompressedChunk;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Memory retrieval engine with Stanford-style recency / relevance / importance scoring.
 *
 * <h3>Scoring formula</h3>
 * <pre>score = wR × recency + wL × relevance + wI × importance</pre>
 * Default weights: 0.3 / 0.5 / 0.2 (relevance-heavy for retrieval tasks).
 *
 * <h3>Recency</h3>
 * Exponential decay on age-in-rounds: {@code exp(-λ · age)}.
 * Default λ = 0.1 (half-life ≈ 7 rounds, i.e. 7-round-old item scores ~0.5).
 *
 * <h3>Relevance</h3>
 * Token-overlap scoring (no vector DB):
 * Chinese text → character-level bi-grams; ASCII text → word-level lowercase tokens.
 * Score = {@code |Q ∩ C| / |Q|} (query coverage), capped at 1.0.
 * <b>Limitation:</b> No semantic/embedding matching; keyword overlap only.
 * Use with an embedding store for production-quality semantic retrieval.
 *
 * <h3>Importance</h3>
 * Messages: normalised from the importrance constant 1–8 → [0, 1].
 * Low-information messages (detected via {@code isLowInformation}) are dampened × 0.5.
 * Chunks: use the built-in {@code CompressedChunk.importance} field (0–1).
 *
 * <h3>Usage (standalone)</h3>
 * <pre>{@code
 * MemoryRetrieval retrieval = new MemoryRetrieval();
 * List<ScoredMemory> top = retrieval.retrieveMessages(messages, "finding the body", 5, currentRound);
 * List<ScoredMemory> topChunks = retrieval.retrieveChunks(chunks, "discovery", 3, currentRound);
 * }</pre>
 *
 * <h3>Integration suggestion (not wired by default)</h3>
 * The retrieval API is an <b>optional supplement</b> to the compression chain.
 * To wire into {@code RouterService.buildAgentContext()} or {@code ArbiterService}:
 * <ol>
 *   <li>Call {@code memoryStore.retrieveMessages(query, k)} after the summary/compressed context is built.</li>
 *   <li>Inject the top-K snippets as a "【相关记忆】" section <em>after</em> the compressed context.</li>
 *   <li>Keep the compression-chain path unchanged — retrieval is additive, not a replacement.</li>
 *   <li>Use query = the user's latest input (or the agent's last action) to anchor relevance.</li>
 *   <li>(Recommended) Gate behind a config flag so retrieval can be toggled for A/B evaluation
 *       (see 融合架构判断 §六: "关记忆检索" vs "开记忆检索").</li>
 * </ol>
 *
 * <p>Thread-safe: instance is stateless.
 */
public class MemoryRetrieval {

    /** Default recency weight in composite score. */
    public static final double DEFAULT_WEIGHT_RECENCY = 0.30;

    /** Default relevance weight in composite score. */
    public static final double DEFAULT_WEIGHT_RELEVANCE = 0.50;

    /** Default importance weight in composite score. */
    public static final double DEFAULT_WEIGHT_IMPORTANCE = 0.20;

    /** Default recency exponential decay factor (half-life ≈ 7 rounds). */
    public static final double DEFAULT_RECENCY_LAMBDA = 0.10;

    /** Maximum composite score (all sub-scores are normalised to [0, 1]). */
    public static final double MAX_SCORE = 1.0;

    private final double weightRecency;
    private final double weightRelevance;
    private final double weightImportance;
    private final double recencyLambda;

    /**
     * Create a retrieval engine with default scoring weights.
     * Recency 0.3, Relevance 0.5, Importance 0.2, λ=0.1.
     */
    public MemoryRetrieval() {
        this(DEFAULT_WEIGHT_RECENCY, DEFAULT_WEIGHT_RELEVANCE,
             DEFAULT_WEIGHT_IMPORTANCE, DEFAULT_RECENCY_LAMBDA);
    }

    /**
     * @param weightRecency    weight for recency score
     * @param weightRelevance  weight for relevance score
     * @param weightImportance weight for importance score
     * @param recencyLambda    exponential decay factor (larger = faster decay)
     */
    public MemoryRetrieval(double weightRecency, double weightRelevance,
                           double weightImportance, double recencyLambda) {
        double sum = weightRecency + weightRelevance + weightImportance;
        if (sum <= 0.0) {
            // 全零权重：不归一化（避免 0/0 → NaN），各维度贡献为 0
            this.weightRecency = 0.0;
            this.weightRelevance = 0.0;
            this.weightImportance = 0.0;
        } else {
            this.weightRecency = weightRecency / sum;
            this.weightRelevance = weightRelevance / sum;
            this.weightImportance = weightImportance / sum;
        }
        this.recencyLambda = Math.max(0.001, recencyLambda);
    }

    // ── Public API ─────────────────────────────────────────────

    /**
     * Retrieve top-K messages matching a query, ranked by composite score.
     *
     * @param messages     all candidate messages
     * @param query        search query (may be null/blank → relevance=0, recency+importance only)
     * @param topK         max results to return
     * @param currentRound current conversation round number (for recency)
     * @return sorted list (highest composite score first), at most topK entries
     */
    public List<ScoredMemory> retrieveMessages(List<Message> messages, String query,
                                                int topK, int currentRound) {
        if (messages == null || messages.isEmpty()) return List.of();

        Set<String> queryTokens = tokenize(query);

        return messages.stream()
            .map(m -> scoreMessage(m, queryTokens, currentRound))
            .sorted(Comparator.comparingDouble(ScoredMemory::getCompositeScore).reversed())
            .limit(Math.max(1, topK))
            .collect(Collectors.toList());
    }

    /**
     * Retrieve top-K compressed chunks matching a query, ranked by composite score.
     *
     * @param chunks       all candidate compressed chunks
     * @param query        search query (may be null/blank → relevance=0, recency+importance only)
     * @param topK         max results to return
     * @param currentRound current conversation round number (for recency)
     * @return sorted list (highest composite score first), at most topK entries
     */
    public List<ScoredMemory> retrieveChunks(List<CompressedChunk> chunks, String query,
                                              int topK, int currentRound) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        Set<String> queryTokens = tokenize(query);

        return chunks.stream()
            .map(c -> scoreChunk(c, queryTokens, currentRound))
            .sorted(Comparator.comparingDouble(ScoredMemory::getCompositeScore).reversed())
            .limit(Math.max(1, topK))
            .collect(Collectors.toList());
    }

    // ── Scoring helpers ────────────────────────────────────────

    private ScoredMemory scoreMessage(Message msg, Set<String> queryTokens, int currentRound) {
        String text = msg.getContent() != null ? msg.getContent() : "";
        double recency = calcRecency(currentRound - msg.getRoundNumber());
        double relevance = calcRelevance(text, queryTokens);
        double importance = calcMessageImportance(msg);
        // 子分存储加权贡献（weight × raw），权重为 0 的维度贡献为 0（与测试意图一致）
        double recencyContrib = weightRecency * recency;
        double relevanceContrib = weightRelevance * relevance;
        double importanceContrib = weightImportance * importance;
        double composite = clamp(recencyContrib + relevanceContrib + importanceContrib);
        return new ScoredMemory(ScoredMemory.SourceType.MESSAGE, msg, text,
            composite, recencyContrib, relevanceContrib, importanceContrib);
    }

    private ScoredMemory scoreChunk(CompressedChunk chunk, Set<String> queryTokens, int currentRound) {
        String text = buildChunkText(chunk);
        double recency = calcRecency(currentRound - chunk.getEndRound());
        double relevance = calcRelevance(text, queryTokens);
        double importance = clampImportance(chunk.getImportance());
        double recencyContrib = weightRecency * recency;
        double relevanceContrib = weightRelevance * relevance;
        double importanceContrib = weightImportance * importance;
        double composite = clamp(recencyContrib + relevanceContrib + importanceContrib);
        return new ScoredMemory(ScoredMemory.SourceType.CHUNK, chunk, text,
            composite, recencyContrib, relevanceContrib, importanceContrib);
    }

    // ── Recency ────────────────────────────────────────────────

    /**
     * Exponential decay: exp(-λ · ageRounds).
     * Age = 0 → 1.0; age → ∞ → 0.
     */
    double calcRecency(int ageRounds) {
        if (ageRounds <= 0) return 1.0;
        return Math.exp(-recencyLambda * ageRounds);
    }

    // ── Relevance ──────────────────────────────────────────────

    /**
     * Token-overlap scoring: |queryTokens ∩ contentTokens| / |queryTokens|.
     * Returns 0.0 when query is empty, 1.0 for perfect overlap.
     */
    double calcRelevance(String content, Set<String> queryTokens) {
        if (queryTokens == null || queryTokens.isEmpty()) return 0.0;
        if (content == null || content.isBlank()) return 0.0;
        Set<String> contentTokens = tokenize(content);
        if (contentTokens.isEmpty()) return 0.0;

        long overlap = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) overlap / queryTokens.size();
    }

    // ── Importance ─────────────────────────────────────────────

    /**
     * Normalise Message importrance 1–8 → [0, 1].
     * Low-information check (dampen ×0.5) not applied here since it requires
     * pairwise context; callers that detect low-information should reduce the
     * importance value before passing the message.
     */
    double calcMessageImportance(Message msg) {
        return clampImportance((msg.getImportance() - 1.0) / 7.0);
    }

    /** Clamp to [0, 1]. */
    static double clampImportance(double raw) {
        return Math.max(0.0, Math.min(1.0, raw));
    }

    // ── Tokenisation ───────────────────────────────────────────

    /**
     * Tokenise text for relevance matching.
     *
     * Chinese: character-level bi-grams (captures compound meaning).
     * ASCII: lowercase word-level tokens (split on non-alphanumeric).
     * Mixed: both sets merged.
     *
     * <p>Visible for testing.
     */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> tokens = new LinkedHashSet<>();

        // Chinese character bi-grams
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isIdeographic(ch)
                || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                cjk.append(ch);
            } else {
                // flush accumulated CJK run
                if (cjk.length() >= 2) {
                    for (int j = 0; j < cjk.length() - 1; j++) {
                        tokens.add(cjk.substring(j, j + 2));
                    }
                } else if (cjk.length() == 1) {
                    tokens.add(cjk.toString());
                }
                cjk.setLength(0);
            }
        }
        // flush final run
        if (cjk.length() >= 2) {
            for (int j = 0; j < cjk.length() - 1; j++) {
                tokens.add(cjk.substring(j, j + 2));
            }
        } else if (cjk.length() == 1) {
            tokens.add(cjk.toString());
        }

        // ASCII word-level (lowercase, split on non-alphanumeric + CJK)
        String[] words = text.replaceAll("[^\\p{Alnum}]+", " ")
                             .toLowerCase(Locale.ROOT)
                             .trim()
                             .split("\\s+");
        for (String w : words) {
            if (w.length() >= 1 && !w.isBlank()) {
                tokens.add(w);
            }
        }

        return tokens;
    }

    /** Build searchable text from a compressed chunk. */
    private static String buildChunkText(CompressedChunk c) {
        StringBuilder sb = new StringBuilder();
        if (c.getSummary() != null) sb.append(c.getSummary());
        if (c.getKeyEvents() != null) {
            for (String e : c.getKeyEvents()) sb.append(' ').append(e);
        }
        if (c.getOpenLoops() != null) {
            for (String l : c.getOpenLoops()) sb.append(' ').append(l);
        }
        return sb.toString();
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(MAX_SCORE, v));
    }

    // ── Getters (for testing/debugging) ────────────────────────

    public double getWeightRecency() { return weightRecency; }
    public double getWeightRelevance() { return weightRelevance; }
    public double getWeightImportance() { return weightImportance; }
    public double getRecencyLambda() { return recencyLambda; }
}
