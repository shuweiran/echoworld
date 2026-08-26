package com.roleplay.engine.simulation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DM 创建的世界事实；其可见性由感知层决定，而不是直接塞给所有角色。 */
public record WorldEvent(Type type, Scope scope, String text, double x, double y, double radius,
                         Set<String> targets, long timestamp) {
    public enum Type { SOUND, VISUAL, ENVIRONMENT, ANNOUNCEMENT, PRIVATE, SYSTEM }
    public enum Scope { GLOBAL, AREA, TARGET }

    public static WorldEvent from(Map<String, Object> raw) {
        if (raw == null) return null;
        String text = String.valueOf(raw.getOrDefault("text", "")).trim();
        if (text.isBlank() || text.length() > 200) return null;
        Type type;
        Scope scope;
        try { type = Type.valueOf(String.valueOf(raw.getOrDefault("type", "ENVIRONMENT")).toUpperCase()); }
        catch (IllegalArgumentException ignored) { return null; }
        try { scope = Scope.valueOf(String.valueOf(raw.getOrDefault("scope", "GLOBAL")).toUpperCase()); }
        catch (IllegalArgumentException ignored) { return null; }
        double x = number(raw.get("x"), -1), y = number(raw.get("y"), -1), radius = number(raw.get("radius"), 0);
        if (scope == Scope.AREA && (x < 0 || y < 0 || radius <= 0)) return null;
        Set<String> targets = new LinkedHashSet<>();
        Object value = raw.get("targets");
        if (value instanceof List<?> list) list.forEach(v -> { if (v != null && !String.valueOf(v).isBlank()) targets.add(String.valueOf(v)); });
        if (scope == Scope.TARGET && targets.isEmpty()) return null;
        return new WorldEvent(type, scope, text, x, y, radius, Set.copyOf(targets), System.currentTimeMillis());
    }

    private static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
}
