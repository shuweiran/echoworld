package com.roleplay.engine.simulation.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一般模式 2D 社会状态：关系、长期社会记忆、社会目标和世界事件。
 * 该状态独立于 ConversationGroup，角色离开一次对话后仍然保留。
 */
public final class SocialState {
    private static final int MAX_MEMORIES_PER_AGENT = 40;
    private static final int MAX_EVENTS = 80;

    private final Map<String, Map<String, Relationship>> relationships = new ConcurrentHashMap<>();
    private final Map<String, List<SocialMemory>> memories = new ConcurrentHashMap<>();
    private final Map<String, SocialGoal> goals = new ConcurrentHashMap<>();
    private final List<SocialEvent> events = new CopyOnWriteArrayList<>();
    private final Set<String> knownAgents = ConcurrentHashMap.newKeySet();

    public void registerAgent(String name) {
        if (name == null || name.isBlank()) return;
        knownAgents.add(name);
        relationships.computeIfAbsent(name, ignored -> new ConcurrentHashMap<>());
        memories.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>());
    }

    public void removeAgent(String name) {
        if (name == null) return;
        knownAgents.remove(name);
        relationships.remove(name);
        memories.remove(name);
        goals.remove(name);
        for (Map<String, Relationship> rels : relationships.values()) rels.remove(name);
    }

    public void clear() {
        relationships.clear();
        memories.clear();
        goals.clear();
        events.clear();
        knownAgents.clear();
    }

    public void setGoal(String agent, String goal, String targetAgent) {
        registerAgent(agent);
        goals.put(agent, new SocialGoal(agent, clean(goal), clean(targetAgent), "ACTIVE", System.currentTimeMillis()));
        addEvent("goal_set", agent + " 的社会目标变为：" + clean(goal), List.of(agent, clean(targetAgent)));
    }

    public void clearGoal(String agent) {
        goals.remove(agent);
        addEvent("goal_cleared", agent + " 的社会目标已清除", List.of(agent));
    }

    /** ConversationManager 每轮结束后回调一次。metadata 字段不会被当作角色名。 */
    public void recordConversation(Map<String, Object> conversation) {
        if (conversation == null) return;
        if ("conversation_departure".equals(String.valueOf(conversation.get("event")))) {
            String agent = String.valueOf(conversation.getOrDefault("agent", ""));
            addEvent("conversation_departure", agent + " 离开了当前对话：" + conversation.getOrDefault("reason", ""), List.of(agent));
            return;
        }
        String group = String.valueOf(conversation.getOrDefault("group", ""));
        List<String> participants = new ArrayList<>();
        for (String key : conversation.keySet()) {
            if (!Set.of("group", "mode", "tick", "round", "topic").contains(key)) participants.add(key);
        }
        participants.removeIf(name -> !knownAgents.contains(name));
        if (participants.size() < 2) return;
        String summary = summarize(conversation);
        boolean conflict = containsConflict(summary);
        for (String a : participants) {
            registerAgent(a);
            for (String b : participants) {
                if (a.equals(b)) continue;
                registerAgent(b);
                Relationship relation = relationships.get(a).computeIfAbsent(b, ignored -> new Relationship());
                relation.observe(conflict);
                addMemory(a, new SocialMemory(b, conflict ? "冲突/分歧" : "共同对话",
                        summary, System.currentTimeMillis(), conflict ? 8 : 5));
            }
        }
        addEvent(conflict ? "social_conflict" : "social_contact",
                (conflict ? "对话产生分歧：" : "角色发生社交：") + group, participants);
    }

    public void addEvent(String type, String description, List<String> participants) {
        events.add(new SocialEvent(type, description, participants == null ? List.of() : List.copyOf(participants),
                System.currentTimeMillis()));
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> relOut = new LinkedHashMap<>();
        relationships.forEach((agent, rels) -> {
            Map<String, Object> each = new LinkedHashMap<>();
            rels.forEach((target, relation) -> each.put(target, relation.toMap()));
            relOut.put(agent, each);
        });
        Map<String, Object> memoryOut = new LinkedHashMap<>();
        memories.forEach((agent, list) -> memoryOut.put(agent, new ArrayList<>(list.stream().map(SocialMemory::toMap).toList())));
        Map<String, Object> goalOut = new LinkedHashMap<>();
        goals.forEach((agent, goal) -> goalOut.put(agent, goal.toMap()));
        out.put("relationships", relOut);
        out.put("memories", memoryOut);
        out.put("goals", goalOut);
        out.put("events", new ArrayList<>(events.stream().map(SocialEvent::toMap).toList()));
        return out;
    }

    public Map<String, Object> forAgent(String agent) {
        Map<String, Object> all = toMap();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        Object relations = all.get("relationships");
        Object memoryValues = all.get("memories");
        Object goalValues = all.get("goals");
        result.put("relationships", valueOr(agent, Map.of(), relations));
        result.put("memories", valueOr(agent, List.of(), memoryValues));
        result.put("goal", valueOr(agent, Map.of(), goalValues));
        return result;
    }

    private static Object valueOr(String key, Object fallback, Object source) {
        if (source instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value == null ? fallback : value;
        }
        return fallback;
    }

    private void addMemory(String owner, SocialMemory memory) {
        List<SocialMemory> list = memories.computeIfAbsent(owner, ignored -> new CopyOnWriteArrayList<>());
        list.add(memory);
        while (list.size() > MAX_MEMORIES_PER_AGENT) list.remove(0);
    }

    private static String summarize(Map<String, Object> conversation) {
        StringBuilder sb = new StringBuilder();
        conversation.forEach((key, value) -> {
            if (Set.of("group", "mode", "tick", "round", "topic").contains(key)) return;
            if (!sb.isEmpty()) sb.append("；");
            String text = String.valueOf(value).replaceAll("\\s+", " ");
            sb.append(key).append("：").append(text, 0, Math.min(text.length(), 100));
        });
        return sb.toString();
    }

    private static boolean containsConflict(String text) {
        return text.contains("不满") || text.contains("质疑") || text.contains("反驳") || text.contains("冲突")
                || text.contains("生气") || text.contains("愤怒") || text.contains("讨厌") || text.contains("不信");
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public static final class Relationship {
        private double familiarity;
        private double trust;
        private double affinity;
        private double conflict;
        private int encounters;
        private long updatedAt;

        synchronized void observe(boolean negative) {
            familiarity = clamp(familiarity + 0.08);
            encounters++;
            if (negative) {
                trust = clamp(trust - 0.06);
                affinity = clamp(affinity - 0.04);
                conflict = clamp(conflict + 0.10);
            } else {
                trust = clamp(trust + 0.02);
                affinity = clamp(affinity + 0.03);
                conflict = clamp(conflict - 0.01);
            }
            updatedAt = System.currentTimeMillis();
        }

        synchronized Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("familiarity", round(familiarity));
            m.put("trust", round(trust));
            m.put("affinity", round(affinity));
            m.put("conflict", round(conflict));
            m.put("encounters", encounters);
            m.put("updatedAt", updatedAt);
            return m;
        }
    }

    public record SocialMemory(String subject, String type, String summary, long timestamp, int importance) {
        Map<String, Object> toMap() {
            return Map.of("subject", subject, "type", type, "summary", summary, "timestamp", timestamp, "importance", importance);
        }
    }

    public record SocialGoal(String agent, String goal, String targetAgent, String status, long updatedAt) {
        Map<String, Object> toMap() {
            return Map.of("agent", agent, "goal", goal, "targetAgent", targetAgent, "status", status, "updatedAt", updatedAt);
        }
    }

    public record SocialEvent(String type, String description, List<String> participants, long timestamp) {
        Map<String, Object> toMap() {
            return Map.of("type", type, "description", description, "participants", participants, "timestamp", timestamp);
        }
    }

    private static double clamp(double value) { return Math.max(-1, Math.min(1, value)); }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
}
