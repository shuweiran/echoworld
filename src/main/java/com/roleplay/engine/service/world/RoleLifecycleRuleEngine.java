package com.roleplay.engine.service.world;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 纯规则评估器：只产出候选命令，不持有或修改世界状态。 */
public final class RoleLifecycleRuleEngine {
    private final RoleLifecyclePolicy policy;

    public RoleLifecycleRuleEngine(RoleLifecyclePolicy policy) {
        this.policy = policy;
    }

    public List<WorldCommand> evaluateAll(Collection<RoleLifecycleSnapshot> roles, Instant now) {
        if (roles == null || roles.isEmpty()) return List.of();
        Instant timestamp = now == null ? Instant.now() : now;
        List<WorldCommand> result = new ArrayList<>();
        for (RoleLifecycleSnapshot role : roles) evaluate(role, timestamp).ifPresent(result::add);
        return List.copyOf(result);
    }

    public java.util.Optional<WorldCommand> evaluate(RoleLifecycleSnapshot role, Instant now) {
        if (role == null || role.status() == RoleLifecycleStatus.EXITED || role.status() == RoleLifecycleStatus.ARCHIVED) {
            return java.util.Optional.empty();
        }
        Instant timestamp = now == null ? Instant.now() : now;
        if (timestamp.isBefore(role.lastInteractionAt())) return java.util.Optional.empty();
        if (role.hasPendingWork()) return java.util.Optional.empty();

        // 被玩家注意到的群演先晋升，避免 TTL 扫描把正在形成剧情价值的角色回收。
        if (role.status() == RoleLifecycleStatus.ACTIVE && role.tier() == RoleTier.AMBIENT
                && role.interactionCount() >= policy.ambientPromotionInteractions()) {
            return java.util.Optional.of(promote(role, RoleTier.TEMPORARY, timestamp));
        }
        if (role.status() == RoleLifecycleStatus.ACTIVE && role.tier() == RoleTier.TEMPORARY
                && role.interactionCount() >= policy.temporaryPromotionInteractions()) {
            return java.util.Optional.of(promote(role, RoleTier.SUPPORTING, timestamp));
        }
        Duration idle = Duration.between(role.lastInteractionAt(), timestamp);
        if ((role.status() == RoleLifecycleStatus.PASSIVE || role.status() == RoleLifecycleStatus.DORMANT)
                && idle.compareTo(policy.passiveAfter()) < 0) {
            return java.util.Optional.of(command(role, WorldCommandType.RESUME_ROLE,
                    Map.of("roleId", role.roleId(), "targetStatus", RoleLifecycleStatus.ACTIVE.name()),
                    "角色重新被互动，恢复活动调度", timestamp));
        }
        if (role.tier() == RoleTier.AMBIENT && idle.compareTo(policy.ambientTtl()) >= 0) {
            return java.util.Optional.of(command(role, WorldCommandType.RETIRE_ROLE,
                    Map.of("roleId", role.roleId(), "targetStatus", RoleLifecycleStatus.EXITED.name()),
                    "群演 TTL 到期，回收一次性角色", timestamp));
        }

        if (idle.compareTo(policy.archiveAfter()) >= 0) {
            if (role.tier() == RoleTier.CORE) {
                // 核心角色只可休眠，绝不由闲置规则永久退出或归档。
                return role.status() == RoleLifecycleStatus.DORMANT ? java.util.Optional.empty()
                        : java.util.Optional.of(suspend(role, RoleLifecycleStatus.DORMANT,
                        "核心角色长期闲置，仅休眠并保留完整状态", timestamp));
            }
            return java.util.Optional.of(command(role, WorldCommandType.RETIRE_ROLE,
                    Map.of("roleId", role.roleId(), "targetStatus", RoleLifecycleStatus.ARCHIVED.name()),
                    "非核心角色长期闲置，归档但保留恢复能力", timestamp));
        }
        if (idle.compareTo(policy.dormantAfter()) >= 0 && role.status() != RoleLifecycleStatus.DORMANT) {
            return java.util.Optional.of(suspend(role, RoleLifecycleStatus.DORMANT,
                    "角色持续闲置，停止移动、对话与独立 LLM 调用", timestamp));
        }
        if (idle.compareTo(policy.passiveAfter()) >= 0 && role.status() == RoleLifecycleStatus.ACTIVE) {
            return java.util.Optional.of(suspend(role, RoleLifecycleStatus.PASSIVE,
                    "角色暂未触发，降低调度频率", timestamp));
        }
        return java.util.Optional.empty();
    }

    private WorldCommand promote(RoleLifecycleSnapshot role, RoleTier target, Instant now) {
        return command(role, WorldCommandType.PROMOTE_ROLE,
                Map.of("roleId", role.roleId(), "fromTier", role.tier().name(), "targetTier", target.name()),
                "有效互动分达到晋升阈值", now);
    }

    private WorldCommand suspend(RoleLifecycleSnapshot role, RoleLifecycleStatus target, String reason, Instant now) {
        return command(role, WorldCommandType.SUSPEND_ROLE,
                Map.of("roleId", role.roleId(), "targetStatus", target.name()), reason, now);
    }

    private WorldCommand command(RoleLifecycleSnapshot role, WorldCommandType type,
                                 Map<String, Object> payload, String reason, Instant now) {
        return new WorldCommand(null, type, role.sessionId(), payload,
                List.of(new WorldPrecondition("role." + role.roleId() + ".lifecycleStatus", "EQ", role.status().name()),
                        new WorldPrecondition("role." + role.roleId() + ".tier", "EQ", role.tier().name())),
                reason, now);
    }
}
