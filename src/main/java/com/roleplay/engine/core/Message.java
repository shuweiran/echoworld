package com.roleplay.engine.core;

import java.time.Instant;
import java.util.*;

/**
 * A single conversation message with track-aware visibility.
 *
 * <p>The fundamental unit of conversation. Every message knows:
 * <ul>
 *   <li>Who said it （role + name）</li>
 *   <li>What was said （content）</li>
 *   <li>Which track it belongs to （trackId）</li>
 *   <li>Who can see it （visibleTo — empty = visible to all）</li>
 *   <li>How important it is （importance, for memory pruning）</li>
 * </ul>
 *
 * <p>Maps from Python {@code models/domain.py → Message}.
 */
public class Message {

    public enum Role {
        SYSTEM,
        AGENT,
        USER,
        ARBITER
    }

    /** Importance constants — matches Python IMPORTANCE_* */
    public static final int IMPORTANCE_USER_INTERRUPT = 8;
    public static final int IMPORTANCE_LORE_TRIGGER = 7;
    public static final int IMPORTANCE_NORMAL = 5;
    public static final int IMPORTANCE_REPETITIVE = 1;

    private Role role;
    private String name;
    private String content;
    private Instant timestamp = Instant.now();
    private int importance = IMPORTANCE_NORMAL;
    private String trackId = "main";
    private List<String> visibleTo = new ArrayList<>();
    private int roundNumber;
    private String modeId = "";
    /** P-0818-E（视觉审核）：可选图像（data URL，如 data:image/png;base64,…）；非空时 content 以多模态数组发送 */
    private String image = null;

    public Message() {}

    public Message(Role role, String name, String content) {
        this.role = role;
        this.name = name;
        this.content = content;
    }

    public Message(Role role, String name, String content, String trackId) {
        this(role, name, content);
        this.trackId = trackId;
    }

    public Message(Role role, String name, String content, String trackId, String image) {
        this(role, name, content, trackId);
        this.image = image;
    }

    // ── Builder methods ────────────────────────────────────────

    public Message withVisibility(String... agents) {
        this.visibleTo = Arrays.asList(agents);
        return this;
    }

    public Message withVisibility(List<String> agents) {
        this.visibleTo = new ArrayList<>(agents);
        return this;
    }

    public Message withImportance(int importance) {
        this.importance = importance;
        return this;
    }

    public Message withRound(int round) {
        this.roundNumber = round;
        return this;
    }

    public Message withModeId(String modeId) {
        this.modeId = modeId;
        return this;
    }

    public Message withTrackId(String trackId) {
        this.trackId = trackId;
        return this;
    }

    /** Is this message visible to the given agent? */
    public boolean isVisibleTo(String agentName) {
        return visibleTo.isEmpty() || visibleTo.contains(agentName);
    }

    // ── Serialization ──────────────────────────────────────────

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role.name().toLowerCase());
        m.put("name", name);
        m.put("content", content);
        m.put("timestamp", timestamp.toString());
        m.put("importance", importance);
        m.put("track_id", trackId);
        m.put("visible_to", visibleTo);
        m.put("round_number", roundNumber);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Message fromMap(Map<String, Object> data) {
        Message msg = new Message();
        String roleStr = (String) data.getOrDefault("role", "agent");
        msg.role = switch (roleStr) {
            case "system" -> Role.SYSTEM;
            case "user" -> Role.USER;
            case "arbiter" -> Role.ARBITER;
            default -> Role.AGENT;
        };
        msg.name = (String) data.getOrDefault("name", "Unknown");
        msg.content = (String) data.getOrDefault("content", "");
        msg.trackId = (String) data.getOrDefault("track_id", "main");
        msg.importance = ((Number) data.getOrDefault("importance", IMPORTANCE_NORMAL)).intValue();
        msg.roundNumber = ((Number) data.getOrDefault("round_number", 0)).intValue();
        msg.visibleTo = new ArrayList<>((List<String>) data.getOrDefault("visible_to", List.of()));
        return msg;
    }

    // ── Getters/Setters ────────────────────────────────────────

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getTimestamp() { return timestamp; }
    public int getImportance() { return importance; }
    public void setImportance(int importance) { this.importance = importance; }
    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }
    public List<String> getVisibleTo() { return visibleTo; }
    public void setVisibleTo(List<String> visibleTo) { this.visibleTo = visibleTo; }
    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
    public String getModeId() { return modeId; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public void setModeId(String modeId) { this.modeId = modeId; }

    @Override
    public String toString() {
        return "[" + role + "|" + name + "] " + content.substring(0, Math.min(content.length(), 60));
    }
}
