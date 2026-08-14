package com.roleplay.engine.simulation.schedule;

/**
 * P-0813-I：单个日程窗口 —— 时段 → 区域 → 行为。
 *
 * <p>对应星露谷日程表一行（{@code <时刻> <地点> <瓦片X> <瓦片Y> <朝向>}）的轻量版：
 * 本窗口只约束「此刻在哪个区域、做什么类型的行为」，不指定精确坐标
 * （坐标由 {@link SchedulerService} 按行为在区域内生成，LLM 只填台词与行为细节）。
 *
 * @param slot     时段索引（0..slots-1，slot = now / windowDurationMs % slots）
 * @param region   本窗口所在区域
 * @param behavior 本窗口行为类型
 */
public record ScheduleWindow(int slot, ScheduleRegion region, ScheduleBehavior behavior) {

    /**
     * 行为窗口段（注入 Agent 系统提示）——「你在哪 / 正在做什么 / 允许的自由度」。
     * 显式声明「不要自行离开区域或更换行为」，弱化角色自行决定下一步行动的自由度
     * （P-0813-I 核心：节奏可控 + 个性保留——台词仍自由，行动骨架由主控日程决定）。
     */
    public String promptText() {
        return "【当前行为窗口】\n"
                + "你在「" + region.name() + "」，当前行为：" + behavior.getLabel() + "。\n"
                + behavior.getFreedomText() + "\n"
                + "行为窗口是主控对你本时段行动的安排：不要自行离开「" + region.name()
                + "」区域或更换行为类型，只在本窗口允许的自由度内行动与说话。";
    }
}
