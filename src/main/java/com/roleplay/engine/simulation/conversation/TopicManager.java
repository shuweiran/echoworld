package com.roleplay.engine.simulation.conversation;

import java.util.*;

public class TopicManager {

    public enum TopicState { INITIATING, ACTIVE, STALE, CLOSED }

    public static class Topic {
        private final String topicId;
        private String description;
        private TopicState state = TopicState.INITIATING;
        private String initiator;
        private int roundsActive = 0;
        private final Map<String, Double> engagement = new LinkedHashMap<>();
        private final List<String> focusedAgents = new ArrayList<>();

        public Topic(String topicId, String description, String initiator, List<String> participants) {
            this.topicId = topicId;
            this.description = description;
            this.initiator = initiator;
            for (String p : participants) {
                engagement.put(p, p.equals(initiator) ? 0.8 : 0.3);
            }
        }

        public String getTopicId() { return topicId; }
        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
        public TopicState getState() { return state; }
        public void setState(TopicState s) { this.state = s; }
        public String getInitiator() { return initiator; }
        public int getRoundsActive() { return roundsActive; }
        public void incrementRounds() { roundsActive++; }

        public double getEngagement(String name) { return engagement.getOrDefault(name, 0.0); }
        public void setEngagement(String name, double v) {
            engagement.put(name, Math.max(0, Math.min(1, v)));
        }

        public double averageEngagement() {
            if (engagement.isEmpty()) return 0;
            double sum = 0;
            for (double v : engagement.values()) sum += v;
            return sum / engagement.size();
        }

        public double highestEngagement() {
            return engagement.values().stream().max(Double::compare).orElse(0.0);
        }

        public void updateAllEngagement(double factor) {
            for (String name : engagement.keySet()) {
                double old = engagement.get(name);
                setEngagement(name, old * factor + (1 - factor) * 0.1);
            }
        }

        public List<String> getFocusedAgents() { return focusedAgents; }
        public void setFocusedAgents(List<String> f) { focusedAgents.clear(); focusedAgents.addAll(f); }
    }

    private final Map<String, Topic> topics = new LinkedHashMap<>();
    private final List<String> topicHistory = new ArrayList<>();
    private Topic currentTopic;

    public Topic initiateTopic(String groupId, String description, String initiator, List<String> participants) {
        String topicId = groupId + "_" + System.currentTimeMillis();
        Topic topic = new Topic(topicId, description, initiator, participants);
        topics.put(topicId, topic);
        currentTopic = topic;
        topicHistory.add(topicId);
        if (topicHistory.size() > 20) topicHistory.remove(0);
        return topic;
    }

    public Topic getCurrentTopic() { return currentTopic; }

    public void setCurrentTopic(Topic topic) { this.currentTopic = topic; }

    public List<String> getTopicHistory() { return new ArrayList<>(topicHistory); }

    public boolean hasActiveTopic() {
        return currentTopic != null
                && currentTopic.getState() != TopicState.CLOSED
                && currentTopic.getState() != TopicState.STALE;
    }

    public void advanceTopic(List<String> activeSpeakers, Map<String, String> lastMessages, boolean newTopicProposed) {
        if (currentTopic == null || currentTopic.getState() == TopicState.CLOSED) return;

        currentTopic.incrementRounds();

        if (currentTopic.getState() == TopicState.INITIATING && currentTopic.getRoundsActive() >= 1) {
            currentTopic.setState(TopicState.ACTIVE);
        }

        double avgEngagement = currentTopic.averageEngagement();

        for (String name : activeSpeakers) {
            currentTopic.setEngagement(name, currentTopic.getEngagement(name) + 0.15);
        }
        if (newTopicProposed) {
            currentTopic.updateAllEngagement(0.85);
        }

        if (currentTopic.getRoundsActive() > 12) {
            currentTopic.setState(TopicState.STALE);
            return;
        }

        if (avgEngagement < 0.4 && currentTopic.getRoundsActive() > 3) {
            currentTopic.setState(TopicState.STALE);
            return;
        }

        if (avgEngagement < 0.2) {
            currentTopic.setState(TopicState.CLOSED);
        }
    }

    public void closeCurrentTopic() {
        if (currentTopic != null) {
            currentTopic.setState(TopicState.CLOSED);
        }
    }

    public Map<String, Object> getTopicContext() {
        if (currentTopic == null) return Map.of();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("description", currentTopic.getDescription());
        ctx.put("state", currentTopic.getState().name());
        ctx.put("rounds", currentTopic.getRoundsActive());
        ctx.put("engagement", new LinkedHashMap<>(currentTopic.engagement));
        ctx.put("initiator", currentTopic.getInitiator());
        return ctx;
    }

    public Topic getTopic() { return currentTopic; }
}
