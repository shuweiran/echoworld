package com.roleplay.engine.service;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.memory.MemoryRetrieval;
import com.roleplay.engine.memory.ScoredMemory;
import com.roleplay.engine.model.CompressedChunk;
import com.roleplay.engine.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Three-tier memory management for the roleplay system.
 *
 * <p>Tiers:
 * <ol>
 *   <li><b>Short-term</b> — weighted window of recent conversation</li>
 *   <li><b>Working memory</b> — ongoing context</li>
 *   <li><b>Long-term</b> — periodic summary compression</li>
 * </ol>
 *
 * <p>Maps from Python core/memory.py → MemoryStore. Skips ShardedMemory (dead code in Python).
 */
@Service
public class MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private final int shortTermRounds;
    private Session session;
    private Compressor compressor;

    public static final int IMPORTANCE_USER_INTERRUPT = 8;
    public static final int IMPORTANCE_LORE_TRIGGER = 7;
    public static final int IMPORTANCE_NORMAL = 5;
    public static final int IMPORTANCE_REPETITIVE = 1;

    public MemoryStore() {
        this.shortTermRounds = 20;
    }

    public MemoryStore(int shortTermRounds) {
        this.shortTermRounds = shortTermRounds;
    }

    public void setCompressor(Compressor compressor) { this.compressor = compressor; }
    public Compressor getCompressor() { return compressor; }

    // ── Session management ────────────────────────────────────

    public Session createSession(String sessionId, List<String> agentNames, Map<String, Object> config) {
        session = new Session(sessionId, agentNames);
        if (config != null) session.getConfig().putAll(config);
        return session;
    }

    public void setSession(Session session) { this.session = session; }
    public Session getSession() { return session; }
    public boolean hasSession() { return session != null; }

    // ── Message operations ────────────────────────────────────

    public void addMessage(Message msg, Integer importanceOverride) {
        if (session == null) throw new IllegalStateException("No active session");
        if (importanceOverride != null) msg.setImportance(importanceOverride);
        session.addMessage(msg);
    }

    public void addMessage(Message msg) {
        int imp = msg.getRole() == Message.Role.USER
            ? IMPORTANCE_USER_INTERRUPT : IMPORTANCE_NORMAL;
        addMessage(msg, imp);
    }

    public int incrementRound() {
        if (session != null) session.setRoundCount(session.getRoundCount() + 1);
        return session != null ? session.getRoundCount() : 0;
    }

    // ── Context building ──────────────────────────────────────

    /** Return messages visible to an agent (track isolation filtering). */
    public List<Message> getAgentContext(String agentName, int maxMessages) {
        if (session == null) return List.of();
        List<Message> visible = session.getMessagesVisibleTo(agentName);

        List<Message> important = visible.stream()
            .filter(m -> m.getImportance() >= 8)
            .collect(Collectors.toList());
        List<Message> normal = visible.stream()
            .filter(m -> m.getImportance() >= 5 && m.getImportance() < 8)
            .skip(Math.max(0, visible.size() - maxMessages))
            .collect(Collectors.toList());

        Set<Message> seen = new HashSet<>(important);
        List<Message> result = new ArrayList<>(important);
        for (Message m : normal) {
            if (seen.add(m)) result.add(m);
        }

        result.sort(Comparator.comparing(Message::getTimestamp));
        int from = Math.max(0, result.size() - maxMessages);
        return result.subList(from, result.size());
    }

    /** Get compressed context string using compressor. */
    public String getCompressedContext(int maxChunks, int maxRecent) {
        if (session == null || compressor == null) return getSummaryContext();

        List<CompressedChunk> chunks = session.getCompressedChunks();
        List<Map<String, String>> recent = getShortTermContextRaw(3);

        return compressor.getCompressedContext(chunks, recent, maxChunks, maxRecent);
    }

    /** Get recent messages as raw map list (for compressor input). */
    public List<Map<String, String>> getShortTermContextRaw(int maxRounds) {
        if (session == null) return List.of();
        int maxMsgs = maxRounds * 2 + 4;
        List<Message> msgs = session.getMessages();
        int from = Math.max(0, msgs.size() - maxMsgs);
        return msgs.subList(from, msgs.size()).stream()
            .map(m -> {
                Map<String, String> map = new LinkedHashMap<>();
                map.put("role", m.getRole().name().toLowerCase());
                map.put("name", m.getName());
                map.put("content", m.getContent());
                return map;
            })
            .collect(Collectors.toList());
    }

    /** Get short-term context as messages. */
    public List<Message> getShortTermContext(int maxRounds) {
        if (session == null) return List.of();
        int maxMsgs = maxRounds * 2 + 4;
        List<Message> msgs = session.getMessages();
        int from = Math.max(0, msgs.size() - maxMsgs);
        return msgs.subList(from, msgs.size());
    }

    /** Build summary context string from compressed chunks or summaries. */
    public String getSummaryContext() {
        if (session == null) return "";
        List<CompressedChunk> chunks = session.getCompressedChunks();
        if (!chunks.isEmpty()) {
            int from = Math.max(0, chunks.size() - 5);
            String context = chunks.subList(from, chunks.size()).stream()
                .map(CompressedChunk::getContextString)
                .collect(Collectors.joining("\n"));
            return "【剧情概况】\n" + context;
        }
        List<String> summaries = session.getSummaries();
        if (!summaries.isEmpty()) {
            List<String> parts = new ArrayList<>();
            parts.add("【之前的对话摘要】");
            for (int i = 0; i < summaries.size(); i++) {
                parts.add("第" + (i + 1) + "段: " + summaries.get(i));
            }
            return String.join("\n", parts);
        }
        return "";
    }

    public void setCurrentTracks(List<Map<String, Object>> tracks) {
        if (session != null) session.setCurrentTracks(tracks);
    }

    // ── Retrieval (scored search, complements compression chain) ──

    private MemoryRetrieval retrieval = new MemoryRetrieval();

    /** Override the default retrieval engine (e.g. for custom weights). */
    public void setRetrieval(MemoryRetrieval retrieval) { this.retrieval = retrieval; }
    public MemoryRetrieval getRetrieval() { return retrieval; }

    /**
     * Retrieve top-K messages matching a query by recency/relevance/importance.
     * Complements the compression chain; does not modify it.
     */
    public List<ScoredMemory> retrieveMessages(String query, int topK) {
        if (session == null) return List.of();
        return retrieval.retrieveMessages(
            session.getMessages(), query, topK, session.getRoundCount());
    }

    /**
     * Retrieve top-K compressed chunks matching a query.
     * Complements the compression chain; does not modify it.
     */
    public List<ScoredMemory> retrieveChunks(String query, int topK) {
        if (session == null) return List.of();
        return retrieval.retrieveChunks(
            session.getCompressedChunks(), query, topK, session.getRoundCount());
    }

    /**
     * Build a combined retrieval context string — top-K messages + top-K chunks
     * for the given query. Returns empty string if nothing matches.
     * Use as an optional add-on to {@link #getSummaryContext()} or
     * {@link #getCompressedContext(int, int)}.
     */
    public String getRetrievalContext(String query, int topMessages, int topChunks) {
        if (session == null || query == null || query.isBlank()) return "";

        List<ScoredMemory> msgResults = retrieveMessages(query, topMessages);
        List<ScoredMemory> chunkResults = retrieveChunks(query, topChunks);

        StringBuilder sb = new StringBuilder();
        if (!chunkResults.isEmpty()) {
            sb.append("【相关记忆摘要】\n");
            for (ScoredMemory sm : chunkResults) {
                CompressedChunk ck = sm.getChunk();
                sb.append("- ").append(ck.getContextString()).append("\n");
            }
        }
        if (!msgResults.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("【相关历史消息】\n");
            for (ScoredMemory sm : msgResults) {
                Message m = sm.getMessage();
                String snippet = m.getContent() != null
                    ? m.getContent().substring(0, Math.min(150, m.getContent().length()))
                    : "";
                sb.append("[").append(m.getName()).append("]: ").append(snippet).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 为角色补充与当前输入相关的较早记忆。
     *
     * <p>最近消息已经由 {@link #getAgentContext(String, int)} 完整注入，故此处排除它们，
     * 避免把刚发生的对话重复塞进上下文。消息检索严格复用角色可见性过滤；压缩块没有
     * 轨道可见性元数据，调用方只能在全员可见的 merged 轨道开启其检索。</p>
     */
    public String getRelevantMemoryContext(String agentName, String query, int topMessages,
                                           int topChunks, int recentMessageWindow,
                                           boolean includeCompressedChunks) {
        if (session == null || query == null || query.isBlank()) return "";

        List<Message> visible = session.getMessagesVisibleTo(agentName);
        int olderEnd = Math.max(0, visible.size() - Math.max(0, recentMessageWindow));
        List<Message> olderMessages = visible.subList(0, olderEnd);
        List<ScoredMemory> messageResults = retrieval.retrieveMessages(
                olderMessages, query, topMessages, session.getRoundCount());
        List<ScoredMemory> chunkResults = includeCompressedChunks
                ? retrieval.retrieveChunks(session.getCompressedChunks(), query, topChunks, session.getRoundCount())
                : List.of();

        StringBuilder sb = new StringBuilder();
        if (!chunkResults.isEmpty()) {
            sb.append("【相关记忆摘要】\n");
            for (ScoredMemory sm : chunkResults) {
                sb.append("- ").append(sm.getChunk().getContextString()).append("\n");
            }
        }
        if (!messageResults.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("【相关历史消息】\n");
            for (ScoredMemory sm : messageResults) {
                Message m = sm.getMessage();
                String snippet = m.getContent() == null ? "" : m.getContent();
                sb.append("[").append(m.getName()).append("]: ")
                        .append(snippet, 0, Math.min(150, snippet.length())).append("\n");
            }
        }
        return sb.toString();
    }

    // ── Low information detection ─────────────────────────────

    public static boolean isLowInformation(Message msg, Message prevMsg) {
        if (msg.getContent() == null || msg.getContent().length() < 30) return true;
        if (prevMsg != null && msg.getContent().equals(prevMsg.getContent())) return true;
        if (prevMsg != null) {
            String c1 = msg.getContent().substring(0, Math.min(50, msg.getContent().length()));
            String c2 = prevMsg.getContent().substring(0, Math.min(50, prevMsg.getContent().length()));
            var set1 = c1.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
            var set2 = c2.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
            var union = new HashSet<>(set1); union.addAll(set2);
            var intersect = new HashSet<>(set1); intersect.retainAll(set2);
            if (!union.isEmpty() && (double) intersect.size() / union.size() > 0.8) return true;
        }
        return false;
    }
}
