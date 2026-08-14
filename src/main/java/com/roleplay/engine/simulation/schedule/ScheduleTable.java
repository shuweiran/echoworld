package com.roleplay.engine.simulation.schedule;

import java.util.List;

/**
 * P-0813-I：角色日程表 —— 时段（slot）→ 窗口的有序列表，每 slot 一个窗口。
 *
 * <p>对应星露谷「一天 20 小时只有 4–6 个日程点」的粒度哲学：角色一天只有
 * {@code slots} 个窗口（默认 6），窗口之间是「走到点 + 停留」，而非每 tick 重决策。
 *
 * @param agentName 角色名
 * @param windows   按 slot 索引排好的窗口（size == 时段数）
 */
public class ScheduleTable {

    private final String agentName;
    private final List<ScheduleWindow> windows;

    public ScheduleTable(String agentName, List<ScheduleWindow> windows) {
        this.agentName = agentName;
        this.windows = windows == null || windows.isEmpty()
                ? List.of() : List.copyOf(windows);
    }

    public String getAgentName() { return agentName; }

    public List<ScheduleWindow> getWindows() { return windows; }

    /** 按当前时刻取窗口：slot = floorDiv(now, windowDurationMs) % slots。 */
    public ScheduleWindow windowFor(long nowMs, long windowDurationMs) {
        if (windows.isEmpty()) return null;
        int idx = slotIndex(nowMs, windowDurationMs, windows.size());
        return windows.get(Math.max(0, Math.min(idx, windows.size() - 1)));
    }

    /**
     * 时刻 → 时段索引：{@code floorDiv(now, windowDurationMs) % slots}。
     * 纯毫秒计算（与真实时钟解耦，可单测确定性）；负 now 也取正余数。
     */
    public static int slotIndex(long nowMs, long windowDurationMs, int slots) {
        long dur = Math.max(1, windowDurationMs);
        long n = Math.max(1, slots);
        long idx = Math.floorDiv(nowMs, dur) % n;
        return (int) ((idx + n) % n);
    }
}
