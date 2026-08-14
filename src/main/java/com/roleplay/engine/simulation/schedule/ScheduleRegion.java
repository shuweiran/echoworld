package com.roleplay.engine.simulation.schedule;

import java.util.List;

/**
 * P-0813-I：日程区域（世界分区）。
 *
 * <p>区域 = 世界内一个圆形地带（圆心 + 半径），对应星露谷「商店/广场/住宅区」式分区
 * （调研 §7 借鉴点 7：地图分区 + 日程把人口分散）。当前世界为 1000×600 px 固定尺寸，
 * 区域为固定分区；后续若世界尺寸/地图契约变化，可改为从地图 zones 派生（留接口）。
 *
 * @param name   区域名（如 广场/湖边/树林，进 LLM prompt 与日志）
 * @param cx     圆心 x
 * @param cy     圆心 y
 * @param radius 半径（px，行为窗口内的活动范围）
 */
public record ScheduleRegion(String name, double cx, double cy, double radius) {

    /** 默认世界分区（1000×600 世界）——广场居中（高人流），四角为安静/特色区。 */
    public static final List<ScheduleRegion> DEFAULT_REGIONS = List.of(
            new ScheduleRegion("广场", 500, 300, 120),
            new ScheduleRegion("花园", 180, 150, 110),
            new ScheduleRegion("湖边", 820, 460, 120),
            new ScheduleRegion("长椅区", 820, 150, 100),
            new ScheduleRegion("树林", 180, 460, 110)
    );

    /** 按名查找区域（生成器选偏好区域用）；未命中返回 null。 */
    public static ScheduleRegion findByName(List<ScheduleRegion> regions, String name) {
        if (regions == null || name == null) return null;
        for (ScheduleRegion r : regions) {
            if (name.equals(r.name())) return r;
        }
        return null;
    }
}
