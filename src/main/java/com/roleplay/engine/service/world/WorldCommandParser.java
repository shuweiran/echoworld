package com.roleplay.engine.service.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 宽容读取主控输出：支持代码围栏、单命令、actions 数组及 snake_case 字段。 */
public final class WorldCommandParser {
    private final ObjectMapper mapper;

    public WorldCommandParser() {
        this(new ObjectMapper());
    }

    public WorldCommandParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 无法识别的单项会被忽略；整体不是 JSON 时返回空列表，避免主控文本污染命令总线。
     */
    public List<WorldCommand> parse(String raw, String defaultSessionId) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(extractJson(raw));
            JsonNode actions = root.isArray() ? root : root.has("actions") ? root.get("actions") : root;
            List<WorldCommand> result = new ArrayList<>();
            if (actions.isArray()) {
                actions.forEach(node -> parseOne(node, defaultSessionId).ifPresent(result::add));
            } else {
                parseOne(actions, defaultSessionId).ifPresent(result::add);
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private java.util.Optional<WorldCommand> parseOne(JsonNode node, String defaultSessionId) {
        if (node == null || !node.isObject()) return java.util.Optional.empty();
        try {
            String rawType = text(node, "type", "command_type", "commandType");
            WorldCommandType type = WorldCommandType.valueOf(normalizeEnum(rawType));
            String session = firstNonBlank(text(node, "sessionId", "session_id", "session"), defaultSessionId);
            if (session == null || session.isBlank()) return java.util.Optional.empty();

            Map<String, Object> payload = objectMap(node.get("payload"));
            if (payload.isEmpty()) {
                payload = inferredPayload(node);
            }
            List<WorldPrecondition> preconditions = parsePreconditions(node.get("preconditions"));
            Instant createdAt = parseInstant(node.get("createdAt"), node.get("created_at"));
            return java.util.Optional.of(new WorldCommand(
                    text(node, "id", "command_id"), type, session, payload, preconditions,
                    text(node, "reason"), createdAt));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private Map<String, Object> inferredPayload(JsonNode node) {
        Map<String, Object> result = objectMap(node);
        for (String envelope : List.of("id", "command_id", "type", "command_type", "commandType",
                "session", "session_id", "sessionId", "preconditions", "reason", "createdAt", "created_at")) {
            result.remove(envelope);
        }
        return result;
    }

    private List<WorldPrecondition> parsePreconditions(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<WorldPrecondition> result = new ArrayList<>();
        for (JsonNode item : node) {
            try {
                String field = text(item, "field", "path");
                String operator = text(item, "operator", "op");
                JsonNode expected = item.has("expected") ? item.get("expected") : item.get("value");
                result.add(new WorldPrecondition(field, operator, mapper.convertValue(expected, Object.class)));
            } catch (RuntimeException ignored) {
                // 单个坏条件不应让同批其他安全命令丢失；执行器仍可追加自己的强制条件。
            }
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) return new LinkedHashMap<>();
        return new LinkedHashMap<>(mapper.convertValue(node, Map.class));
    }

    private static Instant parseInstant(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isNull()) continue;
            try {
                if (node.isNumber()) return Instant.ofEpochMilli(node.asLong());
                return Instant.parse(node.asText());
            } catch (DateTimeParseException | ArithmeticException ignored) {
                // 尝试下一个别名，最终回退当前时间。
            }
        }
        return Instant.now();
    }

    private static String extractJson(String raw) {
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) value = value.substring(firstLine + 1, closing).trim();
        }
        int object = value.indexOf('{');
        int array = value.indexOf('[');
        int start = object < 0 ? array : array < 0 ? object : Math.min(object, array);
        int end = Math.max(value.lastIndexOf('}'), value.lastIndexOf(']'));
        return start >= 0 && end >= start ? value.substring(start, end + 1) : value;
    }

    private static String normalizeEnum(String value) {
        if (value == null) return "";
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
