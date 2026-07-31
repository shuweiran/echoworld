package com.roleplay.engine.interrupt;

/**
 * 三种主动停止类型（需求文档第八条 §四）。
 *
 * <pre>
 * 1. HARD         硬停止：立即停止（中断 LLM 流/线程）。适合：死亡、删除 NPC、玩家退出。
 * 2. SOFT         软停止：不直接杀掉——完成当前 token，保存未完成状态，切换任务。适合：被打断。
 * 3. STATE_INVALID 状态停止：系统判断意图失效（如玩家已离开），停止生成。最重要。
 * </pre>
 */
public enum StopType {
    /** 硬停止：立即中断（token 置位 + 中断关联线程）。 */
    HARD,
    /** 软停止：协作式（token 置位，等当前 LLM 调用结束后在检查点退出，保存未完成内容）。 */
    SOFT,
    /** 状态停止：意图失效（token 置位 + 中断关联线程），状态机落到 INTERRUPTED。 */
    STATE_INVALID;

    /**
     * 由请求字符串解析（API 用）：hard / soft / state。
     *
     * @return 无法识别时返回 {@link #HARD}（安全默认）
     */
    public static StopType fromString(String s) {
        if (s == null) return HARD;
        return switch (s.trim().toLowerCase()) {
            case "soft", "soft_interrupt", "soft-interrupt" -> SOFT;
            case "state", "state_invalid", "state-invalid", "invalid" -> STATE_INVALID;
            default -> HARD;
        };
    }
}
