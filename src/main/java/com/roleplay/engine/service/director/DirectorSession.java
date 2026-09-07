package com.roleplay.engine.service.director;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authoritative, story-scoped state owned by the player-facing Director Agent.
 *
 * <p>The cast roster and the set of characters currently on stage are deliberately
 * separate concepts. LLM text is never authoritative: mutations are validated by
 * the server and only then reflected in this object.</p>
 */
public final class DirectorSession {
    public static final int MAX_MESSAGES = 60;

    private final String preflightId;
    private String runtimeSessionId = "";
    private final String sceneId;
    private String sceneDescription;
    private final LinkedHashMap<String, Object> player = new LinkedHashMap<>();
    private final LinkedHashMap<String, Map<String, Object>> cast = new LinkedHashMap<>();
    private final List<String> relationships = new ArrayList<>();
    private final List<String> entryOrder = new ArrayList<>();
    private final LinkedHashSet<String> onstage = new LinkedHashSet<>();
    private final List<String> sceneNotes = new ArrayList<>();
    private final List<DirectorMessage> messages = new ArrayList<>();
    private boolean confirmed;
    private final String createdAt;
    private String updatedAt;

    public DirectorSession(String preflightId, String sceneId, String sceneDescription,
                           Map<String, Object> player,
                           Collection<? extends Map<String, Object>> characters,
                           Collection<String> relationships,
                           Collection<String> entryOrder,
                           Collection<String> onstage) {
        this(preflightId, sceneId, sceneDescription, player, characters, relationships,
                entryOrder, onstage, false, "", Instant.now().toString(), Instant.now().toString());
    }

    private DirectorSession(String preflightId, String sceneId, String sceneDescription,
                            Map<String, Object> player,
                            Collection<? extends Map<String, Object>> characters,
                            Collection<String> relationships,
                            Collection<String> entryOrder,
                            Collection<String> onstage,
                            boolean confirmed, String runtimeSessionId,
                            String createdAt, String updatedAt) {
        this.preflightId = required(preflightId, "preflightId");
        this.sceneId = required(sceneId, "sceneId");
        this.sceneDescription = clean(sceneDescription, 5000);
        if (player != null) this.player.putAll(safeObject(player));
        if (characters != null) {
            for (Map<String, Object> raw : characters) {
                if (raw == null) continue;
                Map<String, Object> c = safeObject(raw);
                String name = clean(c.get("name"), 80);
                if (!name.isBlank()) {
                    c.put("name", name);
                    cast.put(name, c);
                }
            }
        }
        String playerName = playerName();
        if (!playerName.isBlank() && !cast.containsKey(playerName)) {
            Map<String, Object> snapshot = new LinkedHashMap<>(this.player);
            snapshot.put("name", playerName);
            cast.put(playerName, snapshot);
        }
        replaceStrings(this.relationships, relationships, 80, 500);
        setEntryOrder(entryOrder);
        if (onstage != null) {
            for (String name : onstage) if (cast.containsKey(name)) this.onstage.add(name);
        } else {
            this.onstage.addAll(cast.keySet());
        }
        if (!playerName.isBlank()) this.onstage.add(playerName);
        this.confirmed = confirmed;
        this.runtimeSessionId = clean(runtimeSessionId, 100);
        this.createdAt = createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt;
        this.updatedAt = updatedAt == null || updatedAt.isBlank() ? this.createdAt : updatedAt;
        normalize();
    }

    public synchronized StageResult setStage(String name, boolean present) {
        String target = clean(name, 80);
        if (!cast.containsKey(target)) return new StageResult(false, "角色不在当前剧本角色表中: " + target);
        if (!present && target.equals(playerName())) {
            return new StageResult(false, "玩家当前扮演的角色不能被主控静默移出场景: " + target);
        }
        boolean changed = present ? onstage.add(target) : onstage.remove(target);
        touch();
        return new StageResult(true, changed
                ? target + (present ? " 已进场" : " 已离场")
                : target + (present ? " 已经在场" : " 已经离场"));
    }

    public synchronized void setEntryOrder(Collection<String> order) {
        entryOrder.clear();
        if (order != null) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String raw : order) {
                String name = clean(raw, 80);
                if (cast.containsKey(name) && seen.add(name)) entryOrder.add(name);
            }
        }
        for (String name : cast.keySet()) if (!entryOrder.contains(name)) entryOrder.add(name);
        touch();
    }

    public synchronized void addRelationship(String relation) {
        String value = clean(relation, 500);
        if (!value.isBlank() && !relationships.contains(value)) {
            relationships.add(value);
            if (relationships.size() > 80) relationships.remove(0);
            touch();
        }
    }

    public synchronized void addSceneNote(String note) {
        String value = clean(note, 800);
        if (!value.isBlank()) {
            sceneNotes.add(value);
            if (sceneNotes.size() > 80) sceneNotes.remove(0);
            touch();
        }
    }

    public synchronized void addMessage(String role, String content) {
        String text = clean(content, 4000);
        if (text.isBlank()) return;
        messages.add(new DirectorMessage(role == null ? "" : role, text, Instant.now().toString()));
        while (messages.size() > MAX_MESSAGES) messages.remove(0);
        touch();
    }

    public synchronized String authoritativeDirective() {
        StringBuilder out = new StringBuilder();
        out.append("【主控权威状态】\n");
        out.append("玩家扮演：").append(playerName().isBlank() ? "（无玩家角色）" : playerName()).append('\n');
        String persona = clean(player.get("persona"), 1200);
        if (!persona.isBlank()) out.append("玩家角色设定：").append(persona).append('\n');
        out.append("已登记角色：").append(String.join("、", cast.keySet())).append('\n');
        out.append("当前在场：").append(onstage.isEmpty() ? "（无）" : String.join("、", onstage)).append('\n');
        List<String> off = offstage();
        out.append("当前离场：").append(off.isEmpty() ? "（无）" : String.join("、", off)).append('\n');
        out.append("计划出场顺序：").append(entryOrder.isEmpty() ? "（未设定）" : String.join(" → ", entryOrder)).append('\n');
        if (!relationships.isEmpty()) out.append("关系：").append(String.join("；", relationships)).append('\n');
        if (!sceneNotes.isEmpty()) out.append("场景补充：").append(String.join("；", sceneNotes)).append('\n');
        out.append("规则：角色表不等于当前在场；离场角色不得发言、行动或被调度。以上状态由服务器维护，不得自行改写。");
        return out.toString();
    }

    public synchronized String stateSummary() {
        return "你扮演「" + (playerName().isBlank() ? "未指定" : playerName()) + "」；当前在场："
                + (onstage.isEmpty() ? "无" : String.join("、", onstage)) + "；离场："
                + (offstage().isEmpty() ? "无" : String.join("、", offstage)) + "；出场顺序："
                + (entryOrder.isEmpty() ? "未设定" : String.join(" → ", entryOrder)) + "。";
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preflight_id", preflightId);
        out.put("runtime_session_id", runtimeSessionId);
        out.put("scene_id", sceneId);
        out.put("scene_description", sceneDescription);
        out.put("player", new LinkedHashMap<>(player));
        out.put("cast", cast.values().stream().map(LinkedHashMap::new).toList());
        out.put("relationships", List.copyOf(relationships));
        out.put("entry_order", List.copyOf(entryOrder));
        out.put("onstage", List.copyOf(onstage));
        out.put("offstage", offstage());
        out.put("scene_notes", List.copyOf(sceneNotes));
        out.put("confirmed", confirmed);
        out.put("messages", messages.stream().map(DirectorMessage::toMap).toList());
        out.put("created_at", createdAt);
        out.put("updated_at", updatedAt);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static DirectorSession fromMap(Map<String, Object> raw) {
        Map<String, Object> player = raw.get("player") instanceof Map<?, ?> p ? castMap(p) : Map.of();
        List<Map<String, Object>> cast = new ArrayList<>();
        if (raw.get("cast") instanceof Collection<?> items) {
            for (Object item : items) if (item instanceof Map<?, ?> m) cast.add(castMap(m));
        }
        DirectorSession session = new DirectorSession(
                String.valueOf(raw.getOrDefault("preflight_id", "")),
                String.valueOf(raw.getOrDefault("scene_id", "")),
                String.valueOf(raw.getOrDefault("scene_description", "")),
                player, cast, strings(raw.get("relationships")), strings(raw.get("entry_order")),
                strings(raw.get("onstage")), Boolean.TRUE.equals(raw.get("confirmed")),
                String.valueOf(raw.getOrDefault("runtime_session_id", "")),
                String.valueOf(raw.getOrDefault("created_at", "")),
                String.valueOf(raw.getOrDefault("updated_at", "")));
        session.sceneNotes.addAll(strings(raw.get("scene_notes")));
        if (raw.get("messages") instanceof Collection<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String role = String.valueOf(m.get("role"));
                String content = String.valueOf(m.get("content"));
                String at = String.valueOf(m.get("at"));
                if (!content.isBlank()) session.messages.add(new DirectorMessage(role, content, at));
            }
            while (session.messages.size() > MAX_MESSAGES) session.messages.remove(0);
        }
        session.normalize();
        return session;
    }

    private synchronized void normalize() {
        onstage.removeIf(name -> !cast.containsKey(name));
        String playerName = playerName();
        if (!playerName.isBlank()) onstage.add(playerName);
        setEntryOrder(new ArrayList<>(entryOrder));
    }

    private List<String> offstage() {
        List<String> result = new ArrayList<>();
        for (String name : cast.keySet()) if (!onstage.contains(name)) result.add(name);
        return result;
    }

    public String preflightId() { return preflightId; }
    public String sceneId() { return sceneId; }
    public synchronized String sceneDescription() { return sceneDescription; }
    public synchronized String playerName() { return clean(player.get("name"), 80); }
    public synchronized Map<String, Object> player() { return new LinkedHashMap<>(player); }
    public synchronized List<Map<String, Object>> cast() { return cast.values().stream().map(LinkedHashMap::new).toList(); }
    public synchronized List<String> relationships() { return List.copyOf(relationships); }
    public synchronized List<String> entryOrder() { return List.copyOf(entryOrder); }
    public synchronized List<String> onstage() { return List.copyOf(onstage); }
    public synchronized List<String> offstageNames() { return offstage(); }
    public synchronized List<DirectorMessage> messages() { return List.copyOf(messages); }
    public synchronized boolean confirmed() { return confirmed; }
    public synchronized void confirm() { confirmed = true; touch(); }
    public synchronized String runtimeSessionId() { return runtimeSessionId; }
    public synchronized void attachRuntime(String id) { runtimeSessionId = clean(id, 100); touch(); }
    public synchronized boolean knowsCharacter(String name) { return cast.containsKey(clean(name, 80)); }

    private void touch() { updatedAt = Instant.now().toString(); }

    private static void replaceStrings(List<String> target, Collection<String> values, int maxItems, int maxLen) {
        target.clear();
        if (values == null) return;
        for (String raw : values) {
            String value = clean(raw, maxLen);
            if (!value.isBlank() && !target.contains(value)) target.add(value);
            if (target.size() >= maxItems) break;
        }
    }

    private static Map<String, Object> safeObject(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> { if (k != null && v != null) out.put(clean(k, 80), v); });
        return out;
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> { if (k != null && v != null) out.put(String.valueOf(k), v); });
        return out;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof Collection<?> items)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : items) if (item != null && !String.valueOf(item).isBlank()) out.add(String.valueOf(item));
        return out;
    }

    private static String required(String value, String field) {
        String result = clean(value, 200);
        if (result.isBlank()) throw new IllegalArgumentException(field + " required");
        return result;
    }

    private static String clean(Object value, int max) {
        if (value == null) return "";
        String s = String.valueOf(value).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record StageResult(boolean accepted, String detail) {}

    public record DirectorMessage(String role, String content, String at) {
        public DirectorMessage {
            Objects.requireNonNullElse(role, "");
            Objects.requireNonNullElse(content, "");
            Objects.requireNonNullElse(at, "");
        }
        public Map<String, Object> toMap() {
            return Map.of("role", role, "content", content, "at", at);
        }
    }
}