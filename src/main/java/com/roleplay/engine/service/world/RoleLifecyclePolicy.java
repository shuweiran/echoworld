package com.roleplay.engine.service.world;

import java.time.Duration;
import java.util.Objects;

/** 可配置阈值；持续时间必须单调递增，避免一次扫描跨越多个互相冲突的状态。 */
public record RoleLifecyclePolicy(
        Duration ambientTtl,
        Duration passiveAfter,
        Duration dormantAfter,
        Duration archiveAfter,
        int ambientPromotionInteractions,
        int temporaryPromotionInteractions) {

    public RoleLifecyclePolicy {
        ambientTtl = positive(ambientTtl, "ambientTtl");
        passiveAfter = positive(passiveAfter, "passiveAfter");
        dormantAfter = positive(dormantAfter, "dormantAfter");
        archiveAfter = positive(archiveAfter, "archiveAfter");
        if (dormantAfter.compareTo(passiveAfter) <= 0 || archiveAfter.compareTo(dormantAfter) <= 0) {
            throw new IllegalArgumentException("idle thresholds must satisfy passive < dormant < archive");
        }
        if (ambientPromotionInteractions < 1 || temporaryPromotionInteractions < ambientPromotionInteractions) {
            throw new IllegalArgumentException("promotion thresholds are invalid");
        }
    }

    public static RoleLifecyclePolicy defaults() {
        return new RoleLifecyclePolicy(Duration.ofMinutes(5), Duration.ofMinutes(10),
                Duration.ofMinutes(30), Duration.ofHours(2), 2, 6);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
