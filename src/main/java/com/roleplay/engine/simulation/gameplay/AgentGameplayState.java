package com.roleplay.engine.simulation.gameplay;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-authoritative, extensible quantitative state for one character.
 * Values are always clamped to their declared range; clients never own this state.
 */
public final class AgentGameplayState {
    private final Map<String, Metric> metrics = new LinkedHashMap<>();
    private long revision;

    public AgentGameplayState() {
        define("health", "生命", 100, 0, 100, "点");
        define("stamina", "体力", 100, 0, 100, "点");
        define("focus", "专注", 70, 0, 100, "点");
        define("hunger", "饥饿", 10, 0, 100, "%");
        define("stress", "压力", 10, 0, 100, "%");
        define("strength", "力量", 5, 0, 10, "级");
        define("agility", "敏捷", 5, 0, 10, "级");
        define("insight", "洞察", 5, 0, 10, "级");
        define("presence", "魅力", 5, 0, 10, "级");
        define("willpower", "意志", 5, 0, 10, "级");
        define("inventory_capacity", "背包容量", 12, 1, 64, "格");
        revision = 0;
    }

    public synchronized Metric define(String key, String label, double value,
                                      double min, double max, String unit) {
        String normalized = normalizeKey(key);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) {
            throw new IllegalArgumentException("invalid metric bounds");
        }
        Metric metric = new Metric(normalized, clean(label, normalized), clamp(value, min, max),
                min, max, clean(unit, ""));
        metrics.put(normalized, metric);
        revision++;
        return metric;
    }

    public synchronized Metric adjust(String key, double delta) {
        if (!Double.isFinite(delta)) throw new IllegalArgumentException("metric delta must be finite");
        Metric current = require(key);
        Metric updated = current.withValue(clamp(current.value() + delta, current.min(), current.max()));
        metrics.put(updated.key(), updated);
        revision++;
        return updated;
    }

    public synchronized Metric set(String key, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("metric value must be finite");
        Metric current = require(key);
        Metric updated = current.withValue(clamp(value, current.min(), current.max()));
        metrics.put(updated.key(), updated);
        revision++;
        return updated;
    }

    public synchronized double value(String key, double fallback) {
        Metric metric = metrics.get(normalizeKey(key));
        return metric == null ? fallback : metric.value();
    }

    public synchronized int inventoryCapacity() {
        return Math.max(1, (int) Math.floor(value("inventory_capacity", 12)));
    }

    public synchronized long revision() { return revision; }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        metrics.forEach((key, metric) -> values.put(key, metric.toMap()));
        return Map.of("revision", revision, "metrics", values);
    }

    /** Tolerant checkpoint restore; unknown metrics remain extensible. */
    public synchronized void restore(Object rawGameplay) {
        if (!(rawGameplay instanceof Map<?, ?> gameplay)) return;
        Object rawMetrics = gameplay.get("metrics");
        if (!(rawMetrics instanceof Map<?, ?> entries)) return;
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            String key = normalizeKey(String.valueOf(entry.getKey()));
            double value = number(raw.get("value"), 0);
            double min = number(raw.get("min"), 0);
            double max = number(raw.get("max"), Math.max(100, value));
            if (min > max) continue;
            metrics.put(key, new Metric(key, clean(raw.get("label"), key), clamp(value, min, max),
                    min, max, clean(raw.get("unit"), "")));
        }
        revision = Math.max(revision, longNumber(gameplay.get("revision"), revision));
    }

    private Metric require(String key) {
        Metric metric = metrics.get(normalizeKey(key));
        if (metric == null) throw new IllegalArgumentException("unknown metric: " + key);
        return metric;
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("metric key required");
        String key = value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        if (!key.matches("[a-z][a-z0-9_.]{0,63}")) throw new IllegalArgumentException("invalid metric key");
        return key;
    }

    private static String clean(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
    private static long longNumber(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    public record Metric(String key, String label, double value, double min, double max, String unit) {
        Metric withValue(double next) { return new Metric(key, label, next, min, max, unit); }
        public Map<String, Object> toMap() {
            return Map.of("key", key, "label", label, "value", value,
                    "min", min, "max", max, "unit", unit);
        }
    }
}
