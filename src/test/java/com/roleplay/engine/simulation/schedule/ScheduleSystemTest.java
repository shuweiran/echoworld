package com.roleplay.engine.simulation.schedule;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0813-I：日程系统测试（任务要求 5 项：①日程表生成 ②行为窗口下发 ③对话占用
 * ④LLM prompt 含行为窗口段 ⑤关闭开关回退原行为）。
 *
 * <p>纯单元测试（不依赖 Spring）：SchedulerService 直构 + 确定性窗口构造。
 */
class ScheduleSystemTest {

    private static final long WINDOW_MS = 1000; // 1s/窗口，6 个时段 → 6s 一轮
    private static final int SLOTS = 6;

    private static SchedulerService enabledScheduler() {
        return new SchedulerService(true, WINDOW_MS, SLOTS, ScheduleRegion.DEFAULT_REGIONS);
    }

    private static AgentState state(String name, double x, double y) {
        return new AgentState(name, x, y);
    }

    private static List<ScheduleWindow> windows(ScheduleBehavior... behaviors) {
        List<ScheduleRegion> regions = ScheduleRegion.DEFAULT_REGIONS;
        List<ScheduleWindow> ws = new ArrayList<>();
        for (int i = 0; i < behaviors.length; i++) {
            ws.add(new ScheduleWindow(i, regions.get(i % regions.size()), behaviors[i]));
        }
        return ws;
    }

    // ── ① 日程表生成 ───────────────────────────────────────────

    @Test
    @DisplayName("① 日程表生成：每角色有表、窗口数=时段数、区域/行为合法、确定性")
    void generation_producesValidDeterministicTables() {
        List<AgentState> agents = List.of(
                state("小明", 100, 100), state("小红", 200, 200),
                state("小林", 300, 300), state("阿杰", 400, 400));
        Map<String, String> desc = Map.of(
                "小明", "开朗外向的年轻人，喜欢运动", "小红", "害羞文静的女孩，喜欢阅读",
                "小林", "文艺青年，喜欢观察和写诗", "阿杰", "严肃认真的工程师，工作勤奋");

        SchedulerService scheduler = enabledScheduler();
        scheduler.generateFor(agents, desc::get);

        Map<String, ScheduleTable> tables = scheduler.getTables();
        assertEquals(4, tables.size(), "每角色一张日程表");
        for (AgentState a : agents) {
            ScheduleTable t = tables.get(a.getAgentName());
            assertNotNull(t, a.getAgentName() + " 应有日程表");
            assertEquals(SLOTS, t.getWindows().size(), a.getAgentName() + " 窗口数=时段数");
            for (ScheduleWindow w : t.getWindows()) {
                assertNotNull(w, "窗口非 null");
                assertNotNull(w.behavior(), "行为非 null");
                assertNotNull(w.region(), "区域非 null");
                assertTrue(ScheduleRegion.DEFAULT_REGIONS.contains(w.region()),
                        "区域必须来自世界分区表");
            }
        }

        // 确定性：同名同 desc 再生成 → 逐窗口行为/区域一致（规则+种子随机，跨调用稳定）
        SchedulerService again = enabledScheduler();
        again.generateFor(agents, desc::get);
        for (AgentState a : agents) {
            ScheduleTable t1 = tables.get(a.getAgentName());
            ScheduleTable t2 = again.getTables().get(a.getAgentName());
            for (int i = 0; i < SLOTS; i++) {
                assertEquals(t1.getWindows().get(i).behavior(), t2.getWindows().get(i).behavior(),
                        a.getAgentName() + " slot" + i + " 行为应确定性一致");
                assertEquals(t1.getWindows().get(i).region().name(), t2.getWindows().get(i).region().name(),
                        a.getAgentName() + " slot" + i + " 区域应确定性一致");
            }
        }
    }

    @Test
    @DisplayName("①b persona 权重：外向角色 SOCIAL 占比显著高于内向角色（行为骨架带个性）")
    void generation_personaWeightsShapeBehavior() {
        SchedulerService s1 = enabledScheduler();
        s1.generateFor(List.of(state("外向哥", 0, 0)), n -> "开朗外向热情健谈爱聊天");
        SchedulerService s2 = enabledScheduler();
        s2.generateFor(List.of(state("内向妹", 0, 0)), n -> "害羞文静内向安静独处");

        long social1 = s1.getTables().get("外向哥").getWindows().stream()
                .filter(w -> w.behavior() == ScheduleBehavior.SOCIAL).count();
        long social2 = s2.getTables().get("内向妹").getWindows().stream()
                .filter(w -> w.behavior() == ScheduleBehavior.SOCIAL).count();
        long solo2 = s2.getTables().get("内向妹").getWindows().stream()
                .filter(w -> w.behavior() == ScheduleBehavior.SOLO).count();
        assertTrue(social1 >= social2, "外向角色 SOCIAL 应不少于内向角色（实际 " + social1 + " vs " + social2 + "）");
        assertTrue(solo2 > 0, "内向角色应有 SOLO 窗口（实际 " + solo2 + "）");
    }

    // ── ② 行为窗口下发 ─────────────────────────────────────────

    @Test
    @DisplayName("② 窗口下发：按当前时刻查表，WANDER/SOCIAL/SOLO/WORK 设目标、OBSERVE 清目标站立")
    void dispatch_currentSlotBehaviorSetsTargets() {
        SchedulerService scheduler = enabledScheduler();
        // slot0=闲逛(广场) slot1=社交(花园) slot2=独处(湖边) slot3=工作(长椅区) slot4=观察(树林) slot5=闲逛(树林)
        List<ScheduleWindow> ws = List.of(
                new ScheduleWindow(0, ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "广场"), ScheduleBehavior.WANDER),
                new ScheduleWindow(1, ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "花园"), ScheduleBehavior.SOCIAL),
                new ScheduleWindow(2, ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "湖边"), ScheduleBehavior.SOLO),
                new ScheduleWindow(3, ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "长椅区"), ScheduleBehavior.WORK),
                new ScheduleWindow(4, ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "树林"), ScheduleBehavior.OBSERVE),
                new ScheduleWindow(5, ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "树林"), ScheduleBehavior.WANDER));
        ScheduleTable table = new ScheduleTable("甲", ws);
        scheduler.putTable(table);

        AgentState a = state("甲", 500, 300); // 广场中心出生
        List<AgentState> states = new ArrayList<>(List.of(a));

        // now=1500 → slot 1（社交·花园）：应有目标且落在花园区域内
        scheduler.applyToStates(states, 1500L);
        assertTrue(a.isHasTarget(), "slot1 社交应下发移动目标");
        double dx = a.getTargetX() - 180, dy = a.getTargetY() - 150;
        assertTrue(Math.sqrt(dx * dx + dy * dy) <= 110 * 1.2, "社交目标应落在花园区域内");
        assertEquals(ScheduleTable.slotIndex(1500L, WINDOW_MS, SLOTS), 1, "时刻→时段索引");

        // now=2500 → slot 2（独处·湖边）：远离湖边 → 设目标到湖边固定点
        a.clearTarget();
        scheduler.applyToStates(states, 2500L);
        assertTrue(a.isHasTarget(), "slot2 独处应下发到点目标");
        assertEquals(ScheduleTable.slotIndex(2500L, WINDOW_MS, SLOTS), 2);

        // now=4500 → slot 4（观察·树林）：清目标站立
        scheduler.applyToStates(states, 4500L);
        assertFalse(a.isHasTarget(), "slot4 观察应清目标站立");
        assertTrue(a.getScheduleText().contains("观察"), "scheduleText 应含当前行为");

        // now=500 → slot 0（闲逛·广场）：无目标 → 下发区域内点
        a.clearTarget();
        scheduler.applyToStates(states, 500L);
        assertTrue(a.isHasTarget(), "slot0 闲逛应下发区域内目标");
    }

    @Test
    @DisplayName("②b 到点即停：SOLO/WORK 到达固定点后不再重选（站立静置）")
    void dispatch_soloArrivesThenStands() {
        SchedulerService scheduler = enabledScheduler();
        ScheduleRegion lake = ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "湖边");
        scheduler.putTable(new ScheduleTable("甲", List.of(new ScheduleWindow(0, lake, ScheduleBehavior.SOLO))));

        AgentState a = state("甲", lake.cx(), lake.cy()); // 已站在湖边中心
        scheduler.applyToStates(List.of(a), 0L);
        assertFalse(a.isHasTarget(), "已到点（<40px）→ 清目标站立不重选");
    }

    // ── ③ 对话占用 ─────────────────────────────────────────────

    @Test
    @DisplayName("③ 对话占用：群建立后（activeGroups 命中）不再下发移动窗口；对话结束恢复")
    void conversationOccupied_pausesAndResumes() {
        SchedulerService scheduler = enabledScheduler();
        scheduler.putTable(new ScheduleTable("小红",
                List.of(new ScheduleWindow(0, ScheduleRegion.DEFAULT_REGIONS.get(0), ScheduleBehavior.WANDER))));

        AgentState hong = state("小红", 100, 100);
        // 群建立：occupiedSupplier 返回含小红（只读 activeGroups 查询模拟）
        scheduler.setOccupiedSupplier(() -> Set.of("小红"));
        scheduler.applyToStates(List.of(hong), 0L);
        assertFalse(hong.isHasTarget(), "对话占用中不得下发移动窗口");
        assertEquals("", hong.getScheduleText(), "对话占用中窗口文案不应写入");

        // 对话结束：占用集合清空 → 恢复下发
        scheduler.setOccupiedSupplier(Set::of);
        scheduler.applyToStates(List.of(hong), 0L);
        assertTrue(hong.isHasTarget(), "对话结束后应恢复下发移动窗口");
    }

    @Test
    @DisplayName("③b 对话占用：isInConversation 标记（群建立/解散内建标志）同样挂起窗口")
    void conversationOccupied_stateFlagPauses() {
        SchedulerService scheduler = enabledScheduler();
        scheduler.putTable(new ScheduleTable("小红",
                List.of(new ScheduleWindow(0, ScheduleRegion.DEFAULT_REGIONS.get(0), ScheduleBehavior.WANDER))));
        AgentState hong = state("小红", 100, 100);
        hong.setInConversation(true);
        scheduler.applyToStates(List.of(hong), 0L);
        assertFalse(hong.isHasTarget(), "inConversation=true 时不接管移动");
        hong.setInConversation(false);
        scheduler.applyToStates(List.of(hong), 0L);
        assertTrue(hong.isHasTarget(), "对话结束恢复接管");
    }

    // ── ④ LLM prompt 含行为窗口段 ──────────────────────────────

    @Test
    @DisplayName("④ Agent 系统提示注入【当前行为窗口】：含区域/行为/自由度，无窗口时零变化")
    void agentPrompt_containsScheduleWindow() {
        Persona persona = new Persona("小红", "温柔女孩");
        Agent agent = new Agent(persona, "npc", null);

        // 未接线：原 prompt 不含窗口段（零变化）
        List<Message> plain = agent.buildContext(null, null, "merged", List.of(), "", null, "");
        assertFalse(plain.get(0).getContent().contains("【当前行为窗口】"), "未接线不得注入窗口段");

        // 接线：窗口文案注入 system 提示
        agent.setScheduleContextSupplier(() -> "【当前行为窗口】\n你在「广场」，当前行为：社交。\n"
                + "你愿意和附近的人搭话，主动开启话题或自然回应他人。\n"
                + "行为窗口是主控对你本时段行动的安排：不要自行离开「广场」区域或更换行为类型，只在本窗口允许的自由度内行动与说话。");
        List<Message> wired = agent.buildContext(null, null, "merged", List.of(), "", null, "");
        String content = wired.get(0).getContent();
        assertTrue(content.contains("【当前行为窗口】"), "system 提示应含行为窗口段");
        assertTrue(content.contains("「广场」"), "应含当前区域");
        assertTrue(content.contains("社交"), "应含当前行为");
        assertTrue(content.contains("不要自行离开"), "应含窗口约束（弱化自行决定行动）");
    }

    @Test
    @DisplayName("④b ScheduleWindow.promptText 文案结构：你在哪/正在做什么/允许的自由度")
    void windowPromptText_structure() {
        ScheduleRegion plaza = ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "广场");
        ScheduleWindow w = new ScheduleWindow(0, plaza, ScheduleBehavior.SOCIAL);
        String text = w.promptText();
        assertTrue(text.contains("广场"), "含区域名");
        assertTrue(text.contains("社交"), "含行为标签");
        assertTrue(text.contains(ScheduleBehavior.SOCIAL.getFreedomText()), "含自由度描述");
    }

    // ── ⑤ 关闭开关回退原行为 ──────────────────────────────────

    @Test
    @DisplayName("⑤ 关闭开关：schedule-enabled=false 时不下发窗口/不写文案（回退原行为）")
    void disabled_noOp() {
        SchedulerService scheduler = new SchedulerService(false, WINDOW_MS, SLOTS, ScheduleRegion.DEFAULT_REGIONS);
        scheduler.putTable(new ScheduleTable("甲",
                List.of(new ScheduleWindow(0, ScheduleRegion.DEFAULT_REGIONS.get(0), ScheduleBehavior.WANDER))));
        AgentState a = state("甲", 100, 100);
        scheduler.applyToStates(List.of(a), 0L);
        assertFalse(a.isHasTarget(), "关闭时不得下发移动目标");
        assertEquals("", a.getScheduleText(), "关闭时不写窗口文案");
        assertFalse(scheduler.isEnabled());
    }
}
