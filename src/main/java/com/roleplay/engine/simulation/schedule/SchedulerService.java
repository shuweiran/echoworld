package com.roleplay.engine.simulation.schedule;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * P-0813-I：日程调度服务（混合架构主控侧核心）。
 *
 * <p>主控给每个角色下发轻量**日程窗口**（时段→区域→行为类型），角色 LLM 不再每轮
 * 重决策「去哪/干什么」，只负责窗口内的行为细节 + 全部台词。职责：
 * <ol>
 *   <li><b>日程表持有</b>——{@code agent → ScheduleTable}（开局/场景启动时由
 *       {@link #generateFor} 生成；{@link #clear} 清场）；</li>
 *   <li><b>窗口下发</b>——每 tick 由 SimulationService 的 pre-tick hook 调
 *       {@link #applyToStates}，按当前时刻查表给每个角色落地移动目标
 *       （窗口 {region, behavior} → MovementSystem 可执行行为），并把窗口文案写入
 *       AgentState.scheduleText（供 SSE 可观测 + Agent 系统提示注入）；</li>
 *   <li><b>对话占用</b>——{@link #occupiedSupplier}（SimulationService 注册为只读
 *       {@code conversationManager.getActiveGroups()} 查询）+ 角色 {@code isInConversation}
 *       双判定：进入对话群的角色不再下发移动窗口（原地冻结），对话结束自动恢复；
 *       群建立/解散处无需挂钩——每 tick 只读查询即天然「挂起/恢复」；</li>
 *   <li><b>开关</b>——{@link #enabled}（roleplay.director.schedule-enabled）：false
 *       时全部方法 no-op，回退原行为（导演 LLM 每轮重决策 + MovementConstraint）。</li>
 * </ol>
 *
 * <p>优先级：玩家控制（playerControlled）&gt; 玩家手动目标（manualTarget）&gt; 对话占用
 * &gt; 日程窗口 —— 前三者都不下发窗口（原行为保留），其余角色由窗口接管移动。
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    /** 总开关（roleplay.director.schedule-enabled，默认 true）。 */
    private final boolean enabled;
    /** 窗口时长 ms（roleplay.director.schedule-window-duration-ms，默认 300000=5 分钟）。
     *  时段索引 = floorDiv(now, windowDurationMs) % slots。 */
    private final long windowDurationMs;
    /** 时段数量（roleplay.director.schedule-slots，默认 6）。 */
    private final int slots;
    /** 区域表（默认世界分区；可注入自定义）。 */
    private final List<ScheduleRegion> regions;

    /** agent → 日程表（开局生成，clearAll 清空）。 */
    private final Map<String, ScheduleTable> tables = new ConcurrentHashMap<>();

    /** 对话占用角色提供者（SimulationService 注册：只读 ConversationManager.getActiveGroups()）。 */
    private volatile Supplier<Set<String>> occupiedSupplier = Set::of;
    /** applyToWorld 每 tick 刷新；纯 applyToStates 仍以兼容默认值运行。 */
    private volatile double activeWorldWidth = SimulationWorld.DEFAULT_WORLD_WIDTH;
    private volatile double activeWorldHeight = SimulationWorld.DEFAULT_WORLD_HEIGHT;

    /** 到点判定距离（px）：SOLO/WORK 到达该距离内即站立不再重选点；WANDER/SOCIAL 无此限制持续游荡。 */
    private static final double ARRIVE_EPSILON = 40.0;

    @Autowired
    public SchedulerService(com.roleplay.engine.config.AppConfig appConfig) {
        this(appConfig.getDirector().isScheduleEnabled(),
                appConfig.getDirector().getScheduleWindowDurationMs(),
                appConfig.getDirector().getScheduleSlots(),
                ScheduleRegion.DEFAULT_REGIONS);
    }

    /** 直构（测试/非 Spring 路径）：显式参数。 */
    public SchedulerService(boolean enabled, long windowDurationMs, int slots,
                            List<ScheduleRegion> regions) {
        this.enabled = enabled;
        this.windowDurationMs = Math.max(1, windowDurationMs);
        this.slots = Math.max(1, slots);
        this.regions = (regions == null || regions.isEmpty())
                ? ScheduleRegion.DEFAULT_REGIONS : new ArrayList<>(regions);
    }

    public boolean isEnabled() { return enabled; }
    public long getWindowDurationMs() { return windowDurationMs; }
    public int getSlots() { return slots; }
    public List<ScheduleRegion> getRegions() { return regions; }

    /** 注册对话占用角色提供者（SimulationService 接线；null 安全回退空集）。 */
    public void setOccupiedSupplier(Supplier<Set<String>> supplier) {
        this.occupiedSupplier = supplier == null ? Set::of : supplier;
    }

    /** 当前已占用（对话中）角色集合 —— 只读查询，不修改 ConversationManager 内部。 */
    public Set<String> occupiedAgents() {
        Supplier<Set<String>> s = occupiedSupplier;
        return s == null ? Set.of() : s.get();
    }

    // ── 日程表生命周期 ────────────────────────────────────────

    /** 开局/场景启动：为主世界每个角色生成日程表（规则+随机，零 LLM）。 */
    public void generateFor(Collection<AgentState> agents) {
        generateFor(agents, null);
    }

    /**
     * 开局/场景启动：为主世界每个角色生成日程表（规则+随机，零 LLM）。
     *
     * @param agents        角色集合
     * @param personaDescOf 角色名 → persona 描述（行为权重关键词判定用；null 回退缺省权重）
     */
    public void generateFor(Collection<AgentState> agents,
                            java.util.function.Function<String, String> personaDescOf) {
        if (!enabled) return;
        if (agents == null) return;
        int generated = 0;
        for (AgentState s : agents) {
            if (s == null || s.getAgentName() == null) continue;
            String desc = personaDescOf == null ? null : personaDescOf.apply(s.getAgentName());
            tables.put(s.getAgentName(), ScheduleGenerator.generate(
                    s.getAgentName(), desc, slots, regions));
            generated++;
        }
        log.info("Schedules generated for {} agents ({} slots, {}ms/window)", generated, slots, windowDurationMs);
    }

    /** 清场：清空日程表（clearAll 时调用）。 */
    public void clear() {
        tables.clear();
    }

    public Map<String, ScheduleTable> getTables() { return new ConcurrentHashMap<>(tables); }

    /** 角色是否有日程表且当前时刻存在窗口（导演轮/测试判定用）。 */
    public boolean hasTable(String agentName) {
        return agentName != null && tables.containsKey(agentName);
    }

    public boolean hasWindow(String agentName, long now) {
        ScheduleTable t = tables.get(agentName);
        return t != null && t.windowFor(now, windowDurationMs) != null;
    }

    /** 当前窗口（无表/无窗口 → null）。 */
    public ScheduleWindow currentWindow(String agentName, long now) {
        ScheduleTable t = tables.get(agentName);
        return t == null ? null : t.windowFor(now, windowDurationMs);
    }

    /** 当前窗口 prompt 文案（注入 Agent 系统提示；无窗口 → 空串，原 prompt 零变化）。 */
    public String currentWindowText(String agentName, long now) {
        ScheduleWindow w = currentWindow(agentName, now);
        return w == null ? "" : w.promptText();
    }

    // ── 窗口下发（每 tick pre-tick hook 调用） ─────────────────

    /** 对世界全部角色落地当前窗口（SimulationService 注册为 pre-tick hook，先于移动）。 */
    public void applyToWorld(SimulationWorld world, long now) {
        if (!enabled || world == null) return;
        activeWorldWidth = world.getWorldWidth();
        activeWorldHeight = world.getWorldHeight();
        applyToStates(world.getAllStates().values(), now);
    }

    /**
     * 对角色集合落地当前窗口（可单测的纯逻辑入口）。
     *
     * <p>跳过优先级：关闭 / 玩家控制 / 玩家手动目标 / 对话占用（isInConversation 或
     * activeGroups 命中）→ 不下发移动窗口（角色保持原行为）；有窗口 → 写 scheduleText
     * + 按行为落地 MovementSystem 可执行目标：
     * <ul>
     *   <li>WANDER / SOCIAL / SOLO / WORK —— 区域内选点 setTarget（到达自动清目标，
     *       下 tick 重选新点 = 区域内游荡 / 到点停留）；</li>
     *   <li>OBSERVE —— clearTarget 原地站立观察。</li>
     * </ul>
     */
    public void applyToStates(Collection<AgentState> states, long now) {
        if (!enabled || states == null) return;
        Set<String> occupied = occupiedAgents();
        for (AgentState s : states) {
            if (s == null || s.getAgentName() == null) continue;
            // 玩家控制 / 手动目标 / 对话占用 → 不接管（原行为保留）
            if (s.isPlayerControlled() || s.isManualTarget()
                    || s.isInConversation() || occupied.contains(s.getAgentName())) {
                continue;
            }
            ScheduleWindow w = currentWindow(s.getAgentName(), now);
            if (w == null) {
                s.setScheduleText("");
                continue;
            }
            s.setScheduleText(w.promptText());
            applyBehavior(s, w, now);
        }
    }

    /** 行为 → MovementSystem 可执行目标（见类注释行为表）。
     *  接管规则：已有目标但不在当前窗口区域内（导演/约束遗留）→ 一律重选到区域内，
     *  保证日程窗口对移动的裁决权（主控不再每轮重决策「去哪」）。 */
    private void applyBehavior(AgentState s, ScheduleWindow w, long now) {
        switch (w.behavior()) {
            case WANDER, SOCIAL -> {
                // 区域内游荡：无目标或目标已离开区域 → 选新随机点（到达后 MovementSystem 清目标 → 下 tick 重选）
                if (!s.isHasTarget() || !targetInRegion(s, w.region())) {
                    double[] p = randomPointInRegion(w.region(), s.getAgentName(), now);
                    s.setTarget(p[0], p[1]);
                }
            }
            case SOLO, WORK -> {
                // 到点停留：窗口内固定点（确定性，按 agent+slot 派生）；到达即站立不再重选
                double[] p = fixedPointInRegion(w.region(), s.getAgentName(), w.slot());
                double d = distance(s, p);
                if (d > ARRIVE_EPSILON) {
                    if (!s.isHasTarget() || !targetEquals(s, p)) s.setTarget(p[0], p[1]);
                } else if (s.isHasTarget()) {
                    s.clearTarget(); // 已到点：清目标站立（独处/工作静置）
                }
            }
            case OBSERVE -> {
                // 原地站立观察：清掉旧目标（含上一窗口遗留/导演/约束目标）
                if (s.isHasTarget()) s.clearTarget();
            }
        }
    }

    /** 当前目标是否落在区域内（半径 +20% 余量，防边界抖动误判）。 */
    private static boolean targetInRegion(AgentState s, ScheduleRegion r) {
        if (!s.isHasTarget() || r == null) return false;
        double dx = s.getTargetX() - r.cx();
        double dy = s.getTargetY() - r.cy();
        return Math.sqrt(dx * dx + dy * dy) <= r.radius() * 1.2;
    }

    /** 当前目标是否就是期望停留点（SOLO/WORK 防导演目标先行走偏后拉回）。 */
    private static boolean targetEquals(AgentState s, double[] p) {
        return Math.abs(s.getTargetX() - p[0]) < 1e-6 && Math.abs(s.getTargetY() - p[1]) < 1e-6;
    }

    /** 区域内随机游荡点（WANDER/SOCIAL）。种子 = now + 角色名 hash → 同刻同点（确定性）。 */
    private double[] randomPointInRegion(ScheduleRegion r, String agentName, long now) {
        if (r == null) return new double[]{activeWorldWidth / 2, activeWorldHeight / 2};
        Random rnd = new Random(now ^ (agentName == null ? 0L : agentName.hashCode()));
        double cx = r.cx() + (rnd.nextDouble() * 2 - 1) * r.radius() * 0.8;
        double cy = r.cy() + (rnd.nextDouble() * 2 - 1) * r.radius() * 0.8;
        return new double[]{clamp(cx, 30, activeWorldWidth - 30), clamp(cy, 30, activeWorldHeight - 30)};
    }

    /** 窗口内固定停留点（SOLO/WORK）：区域中心 + 按 agent+slot 派生的确定性偏移（到点即站）。 */
    private double[] fixedPointInRegion(ScheduleRegion r, String agentName, int slot) {
        if (r == null) return new double[]{activeWorldWidth / 2, activeWorldHeight / 2};
        Random rnd = new Random((agentName == null ? 0L : agentName.hashCode()) * 31L + slot * 7L);
        double ox = (rnd.nextDouble() * 2 - 1) * r.radius() * 0.5;
        double oy = (rnd.nextDouble() * 2 - 1) * r.radius() * 0.5;
        return new double[]{clamp(r.cx() + ox, 30, activeWorldWidth - 30), clamp(r.cy() + oy, 30, activeWorldHeight - 30)};
    }

    private static double distance(AgentState s, double[] p) {
        double dx = s.getX() - p[0];
        double dy = s.getY() - p[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 测试支持：注入一张日程表（单测构造确定性窗口用）。 */
    void putTable(ScheduleTable table) {
        if (table != null && table.getAgentName() != null) {
            tables.put(table.getAgentName(), table);
        }
    }

    /** 测试支持：当前已占用集合（对话占用判定可观测）。 */
    Set<String> occupiedSnapshot() {
        return new HashSet<>(occupiedAgents());
    }
}
