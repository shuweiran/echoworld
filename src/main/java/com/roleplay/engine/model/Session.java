package com.roleplay.engine.model;

import com.roleplay.engine.core.Message;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Complete conversation session — the durable state of one dialog.
 * Maps from Python models/domain.py → Session.
 */
public class Session {
    private String sessionId;
    private String createdAt = Instant.now().toString();
    private String updatedAt = Instant.now().toString();
    private List<Message> messages = new ArrayList<>();
    private List<String> summaries = new ArrayList<>();
    private List<StructuredSummary> structuredSummaries = new ArrayList<>();
    private List<CompressedChunk> compressedChunks = new ArrayList<>();
    private int roundCount = 0;
    private List<String> agentNames = new ArrayList<>();
    private Map<String, Object> config = new HashMap<>();
    private String currentScene = "";
    private List<Map<String, Object>> currentTracks = new ArrayList<>();
    private List<Map<String, Object>> roundLog = new ArrayList<>();
    private String version = "v4";

    public Session() {}

    public Session(String sessionId) { this.sessionId = sessionId; }

    public Session(String sessionId, List<String> agentNames) {
        this.sessionId = sessionId;
        this.agentNames = new ArrayList<>(agentNames);
    }

    public void addMessage(Message msg) {
        messages.add(msg);
        updatedAt = Instant.now().toString();
        if (msg.getRole() == Message.Role.AGENT || msg.getRole() == Message.Role.USER) {
            roundCount = Math.max(roundCount, msg.getRoundNumber());
        }
    }

    public void addRoundLog(Map<String, Object> logEntry) {
        roundLog.add(logEntry);
    }

    public List<Map<String, Object>> getRoundLog() { return roundLog; }

    /** Return messages visible to a specific agent (empty visibleTo = visible to all). */
    public List<Message> getMessagesVisibleTo(String agentName) {
        if (agentName == null || agentName.isEmpty()) return messages;
        return messages.stream()
            .filter(m -> m.getVisibleTo().isEmpty() || m.getVisibleTo().contains(agentName))
            .collect(Collectors.toList());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("session_id", sessionId);
        m.put("created_at", createdAt);
        m.put("updated_at", updatedAt);
        m.put("round_count", roundCount);
        m.put("agent_names", agentNames);
        m.put("config", config);
        m.put("summaries", summaries);
        m.put("structured_summaries", structuredSummaries.stream().map(StructuredSummary::toShortString).collect(Collectors.toList()));
        m.put("compressed_chunks", compressedChunks.stream().map(CompressedChunk::toMap).collect(Collectors.toList()));
        m.put("current_scene", currentScene);
        m.put("current_tracks", currentTracks);
        m.put("round_log", roundLog);
        return m;
    }

    // ── Getters/Setters ──
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }
    public List<String> getSummaries() { return summaries; }
    public List<StructuredSummary> getStructuredSummaries() { return structuredSummaries; }
    public List<CompressedChunk> getCompressedChunks() { return compressedChunks; }
    public int getRoundCount() { return roundCount; }
    public void setRoundCount(int roundCount) { this.roundCount = roundCount; }
    public List<String> getAgentNames() { return agentNames; }
    public void setAgentNames(List<String> agentNames) { this.agentNames = agentNames; }
    public Map<String, Object> getConfig() { return config; }
    public String getCurrentScene() { return currentScene; }
    public void setCurrentScene(String scene) { this.currentScene = scene; }
    public List<Map<String, Object>> getCurrentTracks() { return currentTracks; }
    public void setCurrentTracks(List<Map<String, Object>> tracks) { this.currentTracks = tracks; }
    public String getVersion() { return version; }
}
