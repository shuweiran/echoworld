package com.roleplay.engine.simulation.movement;

/**
 * Phase 4 Movement Constraint 输出 — 单个角色的空间约束目标。
 *
 * <p>轨道（Track）不直接移动角色（需求文档第八条），而是产出"空间约束"：
 * 角色期望前往的坐标 + 原因说明。由 {@code SimulationService} 在移动 tick 前
 * 把这些目标喂给 {@code AgentState}（setTarget），MovementSystem 照常寻路。
 *
 * @param agentName 目标角色
 * @param targetX   期望坐标 X
 * @param targetY   期望坐标 Y
 * @param reason    约束原因（MERGED 聚集 / WEAK 保持听觉距离 / ISOLATED 避让）
 */
public record MovementTarget(String agentName, double targetX, double targetY, String reason) {

    public MovementTarget {
        reason = reason == null ? "" : reason;
    }
}
