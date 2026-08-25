package com.roleplay.engine.service.world;

import java.time.Instant;
import java.util.Objects;

/** 规则评估使用的不可变角色快照。 */
public record RoleLifecycleSnapshot(
        String sessionId,
        String roleId,
        RoleTier tier,
        RoleLifecycleStatus status,
        Instant createdAt,
        Instant lastInteractionAt,
        int interactionCount,
        boolean hasPendingWork) {

    public RoleLifecycleSnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId").trim();
        roleId = Objects.requireNonNull(roleId, "roleId").trim();
        tier = Objects.requireNonNull(tier, "tier");
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        lastInteractionAt = lastInteractionAt == null ? createdAt : lastInteractionAt;
        interactionCount = Math.max(0, interactionCount);
        if (sessionId.isEmpty() || roleId.isEmpty()) throw new IllegalArgumentException("sessionId/roleId must not be blank");
    }
}
