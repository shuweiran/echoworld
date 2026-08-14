package com.roleplay.engine.simulation;

import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.schedule.ScheduleBehavior;
import com.roleplay.engine.simulation.schedule.ScheduleRegion;
import com.roleplay.engine.simulation.schedule.ScheduleWindow;
import com.roleplay.engine.simulation.schedule.SchedulerService;
import com.roleplay.engine.db.service.DatabaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P-0813-I：SimulationService 接线层测试（①⑤ 的集成视角）——
 * 开局 initWithPersonas 自动生成日程表 + Agent 注册窗口文案提供者；
 * 导演决定仍落地、日程在 tick 层接管移动（既有测试语义零破坏）。
 */
class SimulationServiceScheduleTest {

    private static Map<String, Object> decision(String agent, int x, int y) {
        return Map.of("agent", agent, "target_x", x, "target_y", y);
    }

    private static SimulationService buildSim(SimulationWorld world, SchedulerService scheduler) {
        InterruptManager im = new InterruptManager(new WorldEventBus());
        return new SimulationService(world, mock(LLMClient.class), mock(DatabaseService.class),
                im, new AgentTaskManager(im), new WorldEventBus(),
                mock(AnnouncementService.class), null, new AppConfig(), scheduler);
    }

    @Test
    @DisplayName("① 接线：initWithPersonas 自动生成日程表 + Agent 注册行为窗口提供者（SSE/prompt 数据源就绪）")
    void init_wiresSchedulesAndSuppliers() {
        SimulationWorld world = new SimulationWorld();
        SchedulerService scheduler = new SchedulerService(true, 1000, 6, ScheduleRegion.DEFAULT_REGIONS);
        SimulationService sim = buildSim(world, scheduler);

        sim.initWithPersonas(
                List.of(new Persona("甲", "性格直爽"), new Persona("乙", "性格温和")), "park");

        assertEquals(2, scheduler.getTables().size(), "开局应为每个角色生成日程表");
        assertNotNull(world.getAgent("甲"), "角色应在世界注册");
        assertNotNull(world.getAgent("甲").getScheduleContextSupplier(), "Agent 应注册窗口文案提供者");
        assertFalse(scheduler.currentWindowText("甲", System.currentTimeMillis()).isBlank(),
                "当前窗口文案应非空（prompt 注入源就绪）");

        // 窗口文案写入 AgentState.scheduleText（SSE 可观测）
        scheduler.applyToWorld(world, System.currentTimeMillis());
        assertFalse(world.getState("甲").getScheduleText().isBlank(), "applyToWorld 后 scheduleText 应写入");
        assertTrue(world.getState("甲").toMap().containsKey("schedule"), "toMap 应暴露 schedule 键（附加键零破坏）");
    }

    @Test
    @DisplayName("⑤ 导演决定仍落地（决定层零拦截）；日程在 tick 层接管——applyToWorld 后目标被拉到窗口区域内")
    void directorDecision_applies_thenScheduleOwnsMovement() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new com.roleplay.engine.agent.Agent(new Persona("甲", "性格直爽"), "npc", null),
                100, 100, 200, 60);
        SchedulerService scheduler = new SchedulerService(true, 1000, 6, ScheduleRegion.DEFAULT_REGIONS);
        scheduler.generateFor(List.of(new AgentState("甲", 100, 100)), n -> "开朗外向爱聊天");
        SimulationService sim = buildSim(world, scheduler);

        sim.applyDirectorDecisions(List.of(decision("甲", 900, 100)));
        AgentState jia = world.getState("甲");
        assertNotNull(jia);
        assertEquals(900, jia.getTargetX(), 1e-9, "导演决定在决定层仍落地（既有测试语义零破坏）");

        // 日程接管：applyToWorld 后（目标不在窗口区域 → 重选到区域内）。
        // 遍历时段找一个非 OBSERVE 窗口（闲逛/社交/独处/工作都会设目标）断言；
        // OBSERVE 语义=清目标站立（ScheduleSystemTest 已单测），不在此重复。
        ScheduleRegion plaza = ScheduleRegion.findByName(ScheduleRegion.DEFAULT_REGIONS, "广场");
        boolean asserted = false;
        for (long now = 0; now < 6000 && !asserted; now += 1000) {
            ScheduleWindow w = scheduler.currentWindow("甲", now);
            if (w == null || w.behavior() == ScheduleBehavior.OBSERVE) continue;
            scheduler.applyToWorld(world, now);
            assertTrue(jia.isHasTarget(), "slot" + w.slot() + "(" + w.behavior() + ") 日程接管后应有目标");
            double dx = jia.getTargetX() - w.region().cx();
            double dy = jia.getTargetY() - w.region().cy();
            assertTrue(Math.sqrt(dx * dx + dy * dy) <= w.region().radius() * 1.2,
                    "slot" + w.slot() + " 日程接管后目标应落在当前窗口区域内（实际距区域中心 "
                            + Math.sqrt(dx * dx + dy * dy) + "px）");
            asserted = true;
        }
        assertTrue(asserted, "6 个时段中应至少有一个非 OBSERVE 窗口可断言");
    }

    @Test
    @DisplayName("⑤b 开关关闭/未注入：日程零介入，导演决定原样落地（回退原行为）")
    void disabledOrAbsent_directorOwnsMovement() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new com.roleplay.engine.agent.Agent(new Persona("甲", "性格直爽"), "npc", null),
                100, 100, 200, 60);

        // 未注入 SchedulerService（8 参直构）→ 日程全链路禁用
        SimulationService sim = buildSim(world, null);
        sim.applyDirectorDecisions(List.of(decision("甲", 800, 500)));
        AgentState jia = world.getState("甲");
        assertEquals(800, jia.getTargetX(), 1e-9, "未注入日程时导演决定原样落地");
        assertEquals(500, jia.getTargetY(), 1e-9);

        // 注入但关闭（enabled=false）→ 同样零介入
        SchedulerService disabled = new SchedulerService(false, 1000, 6, ScheduleRegion.DEFAULT_REGIONS);
        SimulationWorld world2 = new SimulationWorld();
        world2.registerAgent(new com.roleplay.engine.agent.Agent(new Persona("乙", "性格温和"), "npc", null),
                100, 100, 200, 60);
        SimulationService sim2 = buildSim(world2, disabled);
        sim2.applyDirectorDecisions(List.of(decision("乙", 300, 400)));
        assertEquals(300, world2.getState("乙").getTargetX(), 1e-9, "关闭开关时导演决定原样落地");
        assertFalse(world2.getState("乙").getScheduleText().contains("行为窗口"),
                "关闭时不写窗口文案");
    }

    @Test
    @DisplayName("② 窗口→时段：currentWindow 按当前时刻查表（slot = now/时长%slots）")
    void currentWindow_matchesSlot() {
        SchedulerService scheduler = new SchedulerService(true, 1000, 6, ScheduleRegion.DEFAULT_REGIONS);
        scheduler.generateFor(List.of(new AgentState("丙", 0, 0)), n -> "文艺青年喜欢观察");
        ScheduleWindow w0 = scheduler.currentWindow("丙", 0L);
        ScheduleWindow w5 = scheduler.currentWindow("丙", 5500L);
        assertNotNull(w0);
        assertEquals(0, w0.slot());
        assertEquals(5, w5.slot(), "now=5500 应落 slot5");
        assertNotNull(w5.behavior(), "窗口行为非 null");
        assertEquals(ScheduleBehavior.class, w5.behavior().getDeclaringClass(), "行为枚举合法");
    }
}
