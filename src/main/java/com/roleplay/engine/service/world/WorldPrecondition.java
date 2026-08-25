package com.roleplay.engine.service.world;

import java.util.Objects;

/** 命令执行前由权威世界重新核验的条件；协议层不自行解释或执行。 */
public record WorldPrecondition(String field, String operator, Object expected) {
    public WorldPrecondition {
        field = Objects.requireNonNull(field, "field").trim();
        operator = operator == null || operator.isBlank() ? "EQ" : operator.trim().toUpperCase();
        if (field.isEmpty()) {
            throw new IllegalArgumentException("precondition field must not be blank");
        }
    }
}
