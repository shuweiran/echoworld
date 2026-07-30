package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;
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

    public ConversationGroup(String groupId, ConversationMode mode, List<AgentState> members) {
        this.groupId = groupId;
        this.mode = mode;
        this.participants = new LinkedHashMap<>();
        this.frozenAgents = ConcurrentHashMap.newKeySet();
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
    public List<AgentState> getParticipantList() { return new ArrayList<>(participants.values()); }
    public int getParticipantCount() { return participants.size(); }
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

    public boolean containsAgent(String name) { return participants.containsKey(name); }

    public AgentState getParticipant(String name) { return participants.get(name); }

    public boolean allFrozen() {
        return frozenAgents.size() >= participants.size();
    }

    public long idleMs() { return System.currentTimeMillis() - lastActivity; }
}
