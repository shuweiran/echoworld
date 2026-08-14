package com.roleplay.engine.simulation.schedule;

/**
 * P-0813-I：日程行为类型（混合架构——主控驱动的「时刻→地点→行为」日程骨架）。
 *
 * <p>五类行为对齐星露谷日程表机制（调研《星露谷地图交互-20260813》§7 借鉴点 2：
 * 行为骨架由日程驱动，LLM 只负责填台词与临时反应）：
 * <ul>
 *   <li>{@link #WANDER 闲逛} —— 区域内小半径游荡（对应 MovementSystem 的 waypoint 移动，
 *       到达后随机选新点，永不闲着）；</li>
 *   <li>{@link #SOCIAL 社交} —— 去高人流区域（广场）走动，愿意与附近角色搭话；
 *       群建立仍走 InteractionDetector / ConversationManager 机制，本行为不直接建群；</li>
 *   <li>{@link #SOLO 独处} —— 去安静区域停留（到达即站立，不主动搭话）；</li>
 *   <li>{@link #WORK 工作} —— 去工作/停留点专注做事（到达即站立，被打扰才回应）；</li>
 *   <li>{@link #OBSERVE 观察} —— 原地站立观察（清目标，不主动搭话）。</li>
 * </ul>
 *
 * <p>行为仅决定「去哪 / 在窗口内做什么」，台词与行为细节仍由角色 LLM 生成
 * （窗口经 {@link ScheduleWindow#promptText()} 注入 Agent 系统提示）。
 */
public enum ScheduleBehavior {

    WANDER("闲逛", "你可以在区域内随意走动，看看周围的风景，偶尔和附近的人闲聊几句。"),
    SOCIAL("社交", "你愿意和附近的人搭话，主动开启话题或自然回应他人。"),
    SOLO("独处", "你在安静地独处，不主动搭话；若有人向你搭话，可以礼貌回应。"),
    WORK("工作", "你在专注做自己的事（读书、整理、忙碌），不主动搭话；被打扰时停下自然回应。"),
    OBSERVE("观察", "你站在这里观察周围的人和景，不主动搭话；有人接近时可以回应。");

    /** 中文标签（对外展示 / prompt 用）。 */
    private final String label;
    /** 行为允许的自由度描述（注入 LLM prompt 的行为窗口段）。 */
    private final String freedomText;

    ScheduleBehavior(String label, String freedomText) {
        this.label = label;
        this.freedomText = freedomText;
    }

    public String getLabel() { return label; }
    public String getFreedomText() { return freedomText; }
}
