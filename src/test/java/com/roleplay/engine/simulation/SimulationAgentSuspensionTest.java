package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SimulationAgentSuspensionTest {

    @Test
    void suspendAndResumeRestoresExactAgentAndStateObjects() {
        SimulationWorld world = new SimulationWorld();
        WorldEventBus events = new WorldEventBus();
        InterruptManager interrupts = new InterruptManager(events);
        SimulationService simulation = new SimulationService(world, mock(LLMClient.class),
                mock(DatabaseService.class), interrupts, new AgentTaskManager(interrupts), events,
                mock(AnnouncementService.class), null, new AppConfig());
        assertEquals("ok", simulation.addSocialAgent("保留角色", "有长期记忆").get("status"));
        Agent originalAgent = world.getAgent("保留角色");
        AgentState originalState = world.getState("保留角色");
        originalState.setX(321.5);
        originalState.setY(178.25);
        originalState.setEmotion(Emotion.HAPPY);
        originalState.setTarget(600, 400);
        simulation.setSocialGoal("保留角色", "找到旧友", null);
        Map<String, Object> socialBefore = simulation.getSocialState("保留角色");

        simulation.setAgentPassive("保留角色", true);
        assertTrue(simulation.isAgentPassive("保留角色"));

        assertEquals("ok", simulation.suspendSocialAgent("保留角色").get("status"));
        assertNull(world.getAgent("保留角色"));
        assertTrue(simulation.isSuspendedAgent("保留角色"));
        assertEquals("ok", simulation.resumeSocialAgent("保留角色").get("status"));
        simulation.setAgentPassive("保留角色", false);

        assertSame(originalAgent, world.getAgent("保留角色"));
        assertSame(originalState, world.getState("保留角色"));
        assertEquals(321.5, world.getState("保留角色").getX());
        assertEquals(Emotion.HAPPY, world.getState("保留角色").getEmotion());
        assertTrue(world.getState("保留角色").isHasTarget());
        assertEquals(socialBefore, simulation.getSocialState("保留角色"));
        assertFalse(simulation.isAgentPassive("保留角色"));
    }
}
