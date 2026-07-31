package com.roleplay.engine.interrupt;

/**
 * Agent 任务类型（需求文档第八条：task.type=SPEAK / DIALOGUE / MOVE ...）。
 *
 * <p>未来 NPC 移动 / 推理 / 工具调用 / 多 Agent 协作都需要可停止的任务，
 * 此枚举为这些能力预留类型位。
 */
public enum TaskType {
    /** 对话发言（单人轮次生成，AgentExecutor 默认）。 */
    DIALOGUE,
    /** 讲话（2D 世界内即时发言）。 */
    SPEAK,
    /** 移动。 */
    MOVE,
    /** 推理。 */
    THINK,
    /** 工具调用。 */
    TOOL,
    /** 通用生成（未细分类型的兜底）。 */
    GENERATION,
    /** 主控 / 导演决策。 */
    DIRECTOR,
    /** 偷听摘要（WEAK 轨道）。 */
    EAVESDROP,
    /** 轨道决策。 */
    TRACK;

    /** 由请求字符串解析（API 用），无法识别时返回 {@link #GENERATION}。 */
    public static TaskType fromString(String s) {
        if (s == null) return GENERATION;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERATION;
        }
    }
}
