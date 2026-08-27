package com.roleplay.engine.simulation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldEventPerceptionTest {
    @Test
    void soundIsLocalAndWallBlockedWhileTargetAndGlobalFollowTheirScopes() {
        SimulationWorld world = new SimulationWorld();
        AgentState near = new AgentState("近处", 470, 300);
        AgentState blocked = new AgentState("隔墙", 530, 300);
        world.getAllStates(); // keep construction intent explicit; add through registered states is unnecessary for perception source
        // WorldEvent perception uses only the supplied state plus the world's HearingSystem/obstacles.
        WorldEvent sound = WorldEvent.from(Map.of("type", "SOUND", "scope", "AREA", "x", 470, "y", 300, "radius", 200, "text", "玻璃碎裂"));
        world.addWorldEvent(sound);
        assertEquals(1, world.getPerceivedWorldEvents(near).size());
        world.setCustomObstacles(List.of(new Obstacle(Obstacle.Type.WALL, 495, 240, 10, 120, true, "墙")), "wall");
        assertTrue(world.getPerceivedWorldEvents(blocked).isEmpty());

        world.addWorldEvent(WorldEvent.from(Map.of("type", "PRIVATE", "scope", "TARGET", "targets", List.of("隔墙"), "text", "只给你")));
        world.addWorldEvent(WorldEvent.from(Map.of("type", "SYSTEM", "scope", "GLOBAL", "text", "系统通知")));
        assertEquals(2, world.getPerceivedWorldEvents(blocked).size());
    }

    @Test
    void invalidAreaAndUntargetedPrivateEventsAreRejected() {
        assertNull(WorldEvent.from(Map.of("type", "SOUND", "scope", "AREA", "text", "缺坐标")));
        assertNull(WorldEvent.from(Map.of("type", "PRIVATE", "scope", "TARGET", "text", "缺目标")));
    }

    @Test
    void visualIsNotInjectedIntoAgentPerceptionBeforeVisionSystemExists() {
        SimulationWorld world = new SimulationWorld();
        AgentState observer = new AgentState("观察者", 10, 10);
        world.addWorldEvent(WorldEvent.from(Map.of("type", "VISUAL", "scope", "AREA", "x", 10, "y", 10,
                "radius", 100, "text", "一道闪光")));
        assertTrue(world.getPerceivedWorldEvents(observer).isEmpty());
    }
}
