package com.roleplay.engine.simulation.schedule;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * P-0813-I：日程表生成器（规则 + 随机，零 LLM）。
 *
 * <p>对齐 D-002「WorldDirector 纯规则零 LLM」的成本纪律：开局/场景启动时为主世界每个
 * 角色生成日程表（时段 → 区域 → 行为），行为按角色 persona 权重（关键词命中提权），
 * 区域 = 角色偏好地点（关键词映射）或世界随机区。LLM 版生成器留接口：
 * {@link #generate} 签名即未来 LLM 版的替换点（同一产出结构，替换实现即可）。
 *
 * <p>确定性：以 {@code agentName.hashCode()} 为随机种子——同名角色日程表跨调用稳定，
 * 便于测试与跨 tick 一致（对齐 GroupAnchor「字典序最小名=确定性锚点」的确定性哲学）。
 */
public final class ScheduleGenerator {

    private ScheduleGenerator() {}

    // ── persona 关键词 → 行为权重（规则近似，缺省 WANDER 基准最高） ──────

    private static final String[][] SOCIAL_KW = {
            {"开朗", "外向", "活泼", "热情", "爱聊", "健谈", "社牛", "自来熟", "话多", "好动"}};
    private static final String[][] SOLO_KW = {
            {"害羞", "文静", "内向", "安静", "独处", "孤僻", "腼腆", "寡言", "沉默"}};
    private static final String[][] WORK_KW = {
            {"认真", "严谨", "工作", "负责", "勤劳", "敬业", "专注", "事业", "忙碌", "教师", "工程师", "职员"}};
    private static final String[][] OBSERVE_KW = {
            {"文艺", "观察", "思考", "沉思", "写诗", "摄影", "好奇", "敏感", "细腻", "画家"}};

    /** 关键词 → 区域偏好（persona 描述命中 → 倾向该区域）。 */
    private static final Map<String, String> REGION_KW = Map.of(
            "广场", "热闹",
            "花", "花园",
            "水", "湖边",
            "鱼", "湖边",
            "书", "长椅区",
            "画", "长椅区",
            "树", "树林",
            "自然", "树林",
            "安静", "树林",
            "独处", "树林"
    );

    /** 行为提权幅度（命中关键词 +3；WANDER 基准 3，其余基准 1）。 */
    private static final int KEYWORD_BONUS = 3;
    /** 行为→区域偏好命中概率（其余走随机区，制造多样性）。 */
    private static final double REGION_PREFERRED_PROB = 0.75;

    /**
     * 生成单个角色的日程表。
     *
     * @param agentName   角色名（同时作随机种子与表主键）
     * @param personaDesc 角色 persona 描述（五层 layer0 / 旧 personaDesc，可为 null/空）
     * @param slots       时段数量（每个时段一个窗口）
     * @param regions     可选区域表；null → 默认世界分区
     * @return 该角色完整日程表（恒 slots 个窗口，区域/行为恒非 null）
     */
    public static ScheduleTable generate(String agentName, String personaDesc, int slots,
                                         List<ScheduleRegion> regions) {
        List<ScheduleRegion> regionPool = (regions == null || regions.isEmpty())
                ? ScheduleRegion.DEFAULT_REGIONS : regions;
        Random rnd = new Random(agentName == null ? 0L : (long) agentName.hashCode());
        String desc = personaDesc == null ? "" : personaDesc;

        List<ScheduleWindow> windows = new ArrayList<>(Math.max(1, slots));
        for (int i = 0; i < Math.max(1, slots); i++) {
            ScheduleBehavior b = pickBehavior(desc, rnd);
            ScheduleRegion r = pickRegion(b, desc, rnd, regionPool);
            windows.add(new ScheduleWindow(i, r, b));
        }
        return new ScheduleTable(agentName, windows);
    }

    /** 行为加权随机：关键词命中提权，无命中按基准（WANDER 最高）。 */
    static ScheduleBehavior pickBehavior(String desc, Random rnd) {
        Map<ScheduleBehavior, Integer> w = new EnumMap<>(ScheduleBehavior.class);
        w.put(ScheduleBehavior.WANDER, 3);
        w.put(ScheduleBehavior.SOCIAL, 1);
        w.put(ScheduleBehavior.SOLO, 1);
        w.put(ScheduleBehavior.WORK, 1);
        w.put(ScheduleBehavior.OBSERVE, 1);
        if (matchesAny(desc, SOCIAL_KW[0])) w.merge(ScheduleBehavior.SOCIAL, KEYWORD_BONUS, Integer::sum);
        if (matchesAny(desc, SOLO_KW[0])) w.merge(ScheduleBehavior.SOLO, KEYWORD_BONUS, Integer::sum);
        if (matchesAny(desc, WORK_KW[0])) w.merge(ScheduleBehavior.WORK, KEYWORD_BONUS, Integer::sum);
        if (matchesAny(desc, OBSERVE_KW[0])) w.merge(ScheduleBehavior.OBSERVE, KEYWORD_BONUS, Integer::sum);

        int total = w.values().stream().mapToInt(Integer::intValue).sum();
        int roll = rnd.nextInt(Math.max(1, total));
        int acc = 0;
        for (Map.Entry<ScheduleBehavior, Integer> e : w.entrySet()) {
            acc += e.getValue();
            if (roll < acc) return e.getKey();
        }
        return ScheduleBehavior.WANDER;
    }

    /**
     * 区域选择：行为语义优先（社交→广场 / 独处·观察→安静区 / 工作→长椅或广场），
     * 再叠 persona 关键词偏好；以上未命中 → 随机区（制造多样性）。
     */
    static ScheduleRegion pickRegion(ScheduleBehavior b, String desc, Random rnd,
                                     List<ScheduleRegion> regions) {
        if (regions == null || regions.isEmpty()) return null;

        // 1) 行为语义偏好
        String preferred = switch (b) {
            case SOCIAL -> "广场";
            case SOLO, OBSERVE -> quietRegionName(rnd);
            case WORK -> rnd.nextBoolean() ? "长椅区" : "广场";
            case WANDER -> null;
        };
        // 2) persona 关键词偏好（覆盖行为的 WANDER 随机分支与同区重复）
        for (Map.Entry<String, String> e : REGION_KW.entrySet()) {
            if (desc.contains(e.getKey())) { preferred = e.getValue(); break; }
        }

        ScheduleRegion byName = preferred == null ? null
                : ScheduleRegion.findByName(regions, preferred);
        if (byName != null && rnd.nextDouble() < REGION_PREFERRED_PROB) return byName;

        return regions.get(rnd.nextInt(regions.size()));
    }

    /** 独处/观察的安静区域池（循环取用，避免同角色全部窗口挤一个区）。 */
    private static String quietRegionName(Random rnd) {
        String[] quiet = {"树林", "湖边", "长椅区", "花园"};
        return quiet[rnd.nextInt(quiet.length)];
    }

    private static boolean matchesAny(String desc, String[] kws) {
        if (desc == null || desc.isEmpty()) return false;
        for (String kw : kws) {
            if (desc.contains(kw)) return true;
        }
        return false;
    }
}
