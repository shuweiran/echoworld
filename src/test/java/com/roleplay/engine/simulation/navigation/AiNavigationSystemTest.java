package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Obstacle;
import com.roleplay.engine.simulation.spatial.ControlAuthority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNavigationSystemTest {

    @Test
    void plansAiTargetButNeverPlansPlayerInput() {
        AtomicInteger calls = new AtomicInteger();
        NavigationService navigation = request -> {
            calls.incrementAndGet();
            assertEquals(ControlAuthority.AI_AUTONOMOUS, request.authority());
            return PathPlan.ready("test", List.of(PathStep.walk(request.to().worldPosition())));
        };
        AiNavigationSystem system = new AiNavigationSystem(navigation, 1000, 600);

        AgentState ai = new AgentState("ai", 100, 100);
        assertTrue(ai.setAutonomousTarget(500, 300));
        AgentState player = new AgentState("player", 100, 200);
        player.setPlayerControlled(true);
        player.setPlayerIntentTarget(500, 400);

        system.update(List.of(ai, player));

        assertEquals(1, calls.get(), "AI 导航系统只能为 AI 规划一次");
        assertTrue(ai.hasNavigationPlan());
        assertFalse(ai.getNavigationPath().isEmpty());
        assertFalse(player.hasNavigationPlan(), "玩家输入不得进入 AI 规划链路");
        assertTrue(player.isManualTarget());
    }

    @Test
    void autonomousTargetHardRejectsPlayerEvenIfCallerForgetsGuard() {
        AgentState player = new AgentState("player", 100, 100);
        player.setPlayerControlled(true);

        assertFalse(player.setAutonomousTarget(900, 500));
        assertFalse(player.isHasTarget());
    }

    @Test
    void gridBackendRejectsPlayerAuthority() {
        GridNavigationService service = new GridNavigationService();
        AgentState player = new AgentState("player", 10, 10);
        player.setPlayerControlled(true);
        PathRequest request = new PathRequest("player", ControlAuthority.PLAYER_INPUT,
                player.getSpatial().navLocation(), player.getSpatial().navLocation(), NavProfile.humanoid(),
                1000, 600, List.<Obstacle>of());

        assertEquals(PathPlan.Status.REJECTED, service.plan(request).status());
    }
}
