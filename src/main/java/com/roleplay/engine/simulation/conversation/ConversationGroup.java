package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.track.TrackAssignment;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationGroup {

    private final String groupId;
    private final ConversationMode mode;
    private final LinkedHashMap<String, AgentState> participants;
    private final Set<String> frozenAgents;
    private volatile int turnCount = 0;
    private volatile int roundCount = 0;
    private volatile String currentSpeaker;
    private final List<String> turnHistory = new ArrayList<>();
    private final List<Map<String, String>> messageHistory = new ArrayList<>();
    private volatile long createdAt;
    private volatile long lastActivity;
    private volatile boolean active = true;
    private String topic = "";
    private final Map<String, Double> engagement = new ConcurrentHashMap<>();
    /** Phase 1 Track fusion: spatial assignments computed at group creation. */
    private volatile Map<String, TrackAssignment> trackAssignments = Map.of();
    /** Phase 2 Track fusion: cached WEAK-track eavesdrop summary (EavesdropSummarizer
     *  product) so TrackStrategy does not call the LLM on every round. */
    private volatile String trackSummary = "";
    /** messageHistory size when {@link #trackSummary} was last computed. */
    private volatile int trackSummaryHistorySize = 0;
    /** 参与者上限（方案A 用户加入）：0 语义不用，默认 {@link Integer#MAX_VALUE}=不限；
     *  DYAD 对偶组由 ConversationManager 建组时传 2（一对一的语义上限，防 join 破坏 1v1）。 */
    private final int maxParticipants;

    public ConversationGroup(String groupId, ConversationMode mode, List<AgentState> members) {
        this(groupId, mode, members, Integer.MAX_VALUE);
    }

    /** @param maxParticipants 参与者上限；满员后 {@link #addParticipant} 拒绝（方案A 满员校验）。 */
    public ConversationGroup(String groupId, ConversationMode mode, List<AgentState> members, int maxParticipants) {
        this.groupId = groupId;
        this.mode = mode;
        this.participants = new LinkedHashMap<>();
        this.frozenAgents = ConcurrentHashMap.newKeySet();
        this.maxParticipants = Math.max(1, maxParticipants);
        for (AgentState s : members) {
            participants.put(s.getAgentName(), s);
            engagement.put(s.getAgentName(), 1.0);
        }
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastActivity = now;
    }

    public String getGroupId() { return groupId; }
    public ConversationMode getMode() { return mode; }
    public Map<String, AgentState> getParticipants() { return participants; }
    /** 参与者上限；Integer.MAX_VALUE=不限（方案A 加入时满员校验用）。 */
    public int getMaxParticipants() { return maxParticipants; }

    /** 成员表快照（synchronized：与 {@link #addParticipant}/{@link #removeParticipant} 互斥，
     *  防后台 executeRound 迭代期间 join/leave 并发修改 LinkedHashMap 抛 CME）。 */
    public synchronized List<AgentState> getParticipantList() { return new ArrayList<>(participants.values()); }
    public synchronized int getParticipantCount() { return participants.size(); }

    /**
     * 方案A：加入成员（join 原语的地基）。重复加入返回 false；达到 {@link #maxParticipants} 上限返回 false。
     * 新成员参与度初始化为 1.0，默认非冻结（冻结与否由调用方决定，对齐 startGroup 语义）。
     */
    public synchronized boolean addParticipant(AgentState member) {
        if (member == null) return false;
        if (participants.containsKey(member.getAgentName())) return false;
        if (participants.size() >= maxParticipants) return false;
        participants.put(member.getAgentName(), member);
        engagement.put(member.getAgentName(), 1.0);
        frozenAgents.remove(member.getAgentName());
        touchActivity();
        return true;
    }

    /** 方案A：移除成员（leave 原语的地基）。不在组内返回 false。 */
    public synchronized boolean removeParticipant(String name) {
        AgentState removed = participants.remove(name);
        if (removed == null) return false;
        engagement.remove(name);
        frozenAgents.remove(name);
        touchActivity();
        return true;
    }
    public int getTurnCount() { return turnCount; }
    public int getRoundCount() { return roundCount; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActivity() { return lastActivity; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }

    public String getCurrentSpeaker() { return currentSpeaker; }
    public void setCurrentSpeaker(String name) { this.currentSpeaker = name; }

    public List<String> getTurnHistory() { return turnHistory; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public double getEngagement(String name) { return engagement.getOrDefault(name, 1.0); }
    public void setEngagement(String name, double v) { engagement.put(name, Math.max(0, Math.min(1, v))); }

    public Set<String> getFrozenAgents() { return frozenAgents; }

    public Map<String, TrackAssignment> getTrackAssignments() { return trackAssignments; }
    public void setTrackAssignments(Map<String, TrackAssignment> assignments) {
        this.trackAssignments = assignments == null ? Map.of() : Map.copyOf(assignments);
    }
    public TrackAssignment getTrackAssignment(String name) { return trackAssignments.get(name); }
    public String getTrackSummary() { return trackSummary; }
    public void setTrackSummary(String summary) { this.trackSummary = summary == null ? "" : summary; }
    public int getTrackSummaryHistorySize() { return trackSummaryHistorySize; }
    public void setTrackSummaryHistorySize(int size) { this.trackSummaryHistorySize = size; }
    public void freeze(String name) { frozenAgents.add(name); }
    public void unfreeze(String name) { frozenAgents.remove(name); }
    public boolean isFrozen(String name) { return frozenAgents.contains(name); }

    public void touchActivity() { this.lastActivity = System.currentTimeMillis(); }

    public void recordTurn(String speaker, String message) {
        turnCount++;
        roundCount = (turnCount + participants.size() - 1) / participants.size();
        turnHistory.add(speaker);
        if (turnHistory.size() > 20) turnHistory.remove(0);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("speaker", speaker);
        entry.put("message", message);
        entry.put("round", String.valueOf(roundCount));
        messageHistory.add(entry);
        if (messageHistory.size() > 30) messageHistory.remove(0);
        touchActivity();
    }

    public List<Map<String, String>> getMessageHistory() { return messageHistory; }

    public synchronized boolean containsAgent(String name) { return participants.containsKey(name); }

    public synchronized AgentState getParticipant(String name) { return participants.get(name); }

    public boolean allFrozen() {
        return frozenAgents.size() >= participants.size();
    }

    public long idleMs() { return System.currentTimeMillis() - lastActivity; }
}
