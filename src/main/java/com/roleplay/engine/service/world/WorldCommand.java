package com.roleplay.engine.service.world;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 主控与权威世界之间的不可变命令信封。
 *
 * <p>它只表达意图，不代表命令已获准或已经改变世界。</p>
 */
public record WorldCommand(
        String id,
        WorldCommandType type,
        String sessionId,
        Map<String, Object> payload,
        List<WorldPrecondition> preconditions,
        String reason,
        Instant createdAt) {

    public WorldCommand {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        type = Objects.requireNonNull(type, "type");
        sessionId = Objects.requireNonNull(sessionId, "sessionId").trim();
        if (sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        reason = reason == null ? "" : reason.trim();
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static WorldCommand of(WorldCommandType type, String sessionId,
                                  Map<String, Object> payload, String reason, Instant createdAt) {
        return new WorldCommand(null, type, sessionId, payload, List.of(), reason, createdAt);
    }
}
