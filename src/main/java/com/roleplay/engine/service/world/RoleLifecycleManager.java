package com.roleplay.engine.service.world;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生命周期元数据的线程安全仓库。
 *
 * <p>状态变更仅由集成层在命令获准后调用 {@link #applyAccepted}; 规则扫描本身不会调用它。</p>
 */
public final class RoleLifecycleManager {
    private final ConcurrentHashMap<Key, RoleLifecycleSnapshot> roles = new ConcurrentHashMap<>();

    public RoleLifecycleSnapshot register(String sessionId, String roleId, RoleTier tier, Instant now) {
        Instant timestamp = now == null ? Instant.now() : now;
        Key key = new Key(sessionId, roleId);
        return roles.computeIfAbsent(key, ignored -> new RoleLifecycleSnapshot(
                sessionId, roleId, tier, RoleLifecycleStatus.ACTIVE, timestamp, timestamp, 0, false));
    }

    public Optional<RoleLifecycleSnapshot> get(String sessionId, String roleId) {
        return Optional.ofNullable(roles.get(new Key(sessionId, roleId)));
    }

    public Optional<RoleLifecycleSnapshot> recordInteraction(String sessionId, String roleId, Instant now) {
        return recordInteraction(sessionId, roleId, 1, now);
    }

    public Optional<RoleLifecycleSnapshot> recordInteraction(String sessionId, String roleId,
                                                              int score, Instant now) {
        Instant timestamp = now == null ? Instant.now() : now;
        int safeScore = Math.max(0, score);
        return Optional.ofNullable(roles.computeIfPresent(new Key(sessionId, roleId), (ignored, current) ->
                new RoleLifecycleSnapshot(current.sessionId(), current.roleId(), current.tier(),
                        current.status(), current.createdAt(),
                        timestamp.isAfter(current.lastInteractionAt()) ? timestamp : current.lastInteractionAt(),
                        current.interactionCount() + safeScore,
                        current.hasPendingWork())));
    }

    public Optional<RoleLifecycleSnapshot> setPendingWork(String sessionId, String roleId, boolean pending) {
        return Optional.ofNullable(roles.computeIfPresent(new Key(sessionId, roleId), (ignored, current) ->
                new RoleLifecycleSnapshot(current.sessionId(), current.roleId(), current.tier(),
                        current.status(), current.createdAt(), current.lastInteractionAt(),
                        current.interactionCount(), pending)));
    }

    /** 只供通过权限、前置条件和预算校验后的执行器回写。 */
    public Optional<RoleLifecycleSnapshot> applyAccepted(String sessionId, String roleId,
                                                          RoleTier tier, RoleLifecycleStatus status) {
        return Optional.ofNullable(roles.computeIfPresent(new Key(sessionId, roleId), (ignored, current) ->
                new RoleLifecycleSnapshot(current.sessionId(), current.roleId(),
                        tier == null ? current.tier() : tier,
                        status == null ? current.status() : status,
                        current.createdAt(), current.lastInteractionAt(), current.interactionCount(),
                        current.hasPendingWork())));
    }

    public List<RoleLifecycleSnapshot> snapshots(String sessionId) {
        return roles.values().stream().filter(role -> role.sessionId().equals(sessionId)).toList();
    }

    public int removeSession(String sessionId) {
        int before = roles.size();
        roles.keySet().removeIf(key -> key.sessionId.equals(sessionId));
        return before - roles.size();
    }

    public boolean remove(String sessionId, String roleId) {
        return roles.remove(new Key(sessionId, roleId)) != null;
    }

    private record Key(String sessionId, String roleId) {
        private Key {
            if (sessionId == null || sessionId.isBlank() || roleId == null || roleId.isBlank()) {
                throw new IllegalArgumentException("sessionId/roleId must not be blank");
            }
        }
    }
}
