package com.roleplay.engine.service.world;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 对外只读的任务快照。 */
public record MapJob(
        String jobId,
        String sessionId,
        String idempotencyKey,
        MapJobStatus status,
        Map<String, Object> mapData,
        String error,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {

    public MapJob {
        mapData = mapData == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(mapData));
    }

    public boolean publishable() {
        return status == MapJobStatus.READY;
    }
}
