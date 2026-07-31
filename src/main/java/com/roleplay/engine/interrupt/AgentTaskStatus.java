package com.roleplay.engine.interrupt;

/**
 * Agent 任务状态机（需求文档第八条 §八 推荐任务状态机）。
 *
 * <pre>
 *           IDLE
 *            |
 *            v
 *        PLANNING
 *            |
 *            v
 *         RUNNING
 *        /       \
 *  CANCELLED    DONE
 *        |
 *        v
 *   (CANCELLED / INTERRUPTED)
 * </pre>
 *
 * <p>终态说明：
 * <ul>
 *   <li>{@link #CANCELLED} —— 被主动取消（硬停止 HARD / 软停止 SOFT），如死亡、玩家打断、退出</li>
 *   <li>{@link #INTERRUPTED} —— 意图/状态失效被终止（状态停止 STATE_INVALID），如轨道变化、目标取消</li>
 *   <li>{@link #DONE} —— 正常完成</li>
 * </ul>
 */
public enum AgentTaskStatus {
    /** 已创建，尚未开始执行。 */
    IDLE,
    /** 规划中（上下文构建 / 排队等待）。 */
    PLANNING,
    /** 执行中（LLM 生成中）。 */
    RUNNING,
    /** 因状态失效被终止（STATE_INVALID 停止类型）。 */
    INTERRUPTED,
    /** 被主动取消（HARD / SOFT 停止类型）。 */
    CANCELLED,
    /** 正常完成。 */
    DONE;

    /** 是否处于活跃状态（仍可被取消）。 */
    public boolean isActive() {
        return this == IDLE || this == PLANNING || this == RUNNING;
    }

    /** 是否为终态。 */
    public boolean isTerminal() {
        return this == INTERRUPTED || this == CANCELLED || this == DONE;
    }
}
