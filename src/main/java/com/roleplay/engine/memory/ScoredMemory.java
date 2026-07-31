package com.roleplay.engine.memory;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.model.CompressedChunk;

/**
 * Scored memory entry — returned by {@link MemoryRetrieval} searches.
 *
 * <p>Each result holds the composite score, per-component scores for transparency,
 * a reference to the source object (Message or CompressedChunk), and the text
 * snippet used for relevance matching.
 */
public class ScoredMemory {

    public enum SourceType { MESSAGE, CHUNK }

    private final SourceType sourceType;
    private final Object source;
    private final String text;
    private final double compositeScore;
    private final double recencyScore;
    private final double relevanceScore;
    private final double importanceScore;

    public ScoredMemory(SourceType sourceType, Object source, String text,
                        double compositeScore, double recencyScore,
                        double relevanceScore, double importanceScore) {
        this.sourceType = sourceType;
        this.source = source;
        this.text = text != null ? text : "";
        this.compositeScore = compositeScore;
        this.recencyScore = recencyScore;
        this.relevanceScore = relevanceScore;
        this.importanceScore = importanceScore;
    }

    // ── Getters ────────────────────────────────────────────────

    public SourceType getSourceType() { return sourceType; }
    public Object getSource() { return source; }

    /** Convenience: cast source to Message (caller must check sourceType). */
    public Message getMessage() { return (Message) source; }

    /** Convenience: cast source to CompressedChunk (caller must check sourceType). */
    public CompressedChunk getChunk() { return (CompressedChunk) source; }

    /** The text content that was scored. */
    public String getText() { return text; }

    /** Composite score (0–1), higher = more relevant. */
    public double getCompositeScore() { return compositeScore; }

    /** Recency sub-score (0–1), higher = newer. */
    public double getRecencyScore() { return recencyScore; }

    /** Relevance sub-score (0–1), higher = better query match. */
    public double getRelevanceScore() { return relevanceScore; }

    /** Importance sub-score (0–1), higher = more important. */
    public double getImportanceScore() { return importanceScore; }

    @Override
    public String toString() {
        return String.format("ScoredMemory[type=%s score=%.3f (Rc=%.2f Rl=%.2f Im=%.2f) text=%.50s]",
            sourceType, compositeScore, recencyScore, relevanceScore, importanceScore, text);
    }
}
