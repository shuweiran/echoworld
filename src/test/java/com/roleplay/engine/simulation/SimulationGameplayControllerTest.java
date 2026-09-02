package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.core.Persona;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SimulationGameplayControllerTest {
    @Test
    void masterCapabilityIsDisabledByDefaultAndRequiresConfiguredHeader() {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("alice"), "", null), 0, 0, 200, 80);
        world.getState("alice").setPlayerControlled(true);
        SimulationController controller = new SimulationController(mock(SimulationService.class), world,
                mock(CharacterController.class));
        Map<String, Object> request = Map.of("actor_id", "alice", "action", "WAIT", "capability", "MASTER");

        assertEquals(403, controller.action(request, "anything").getStatusCode().value());
        ReflectionTestUtils.setField(controller, "gameplayMasterKey", "server-secret");
        assertEquals(403, controller.action(request, "wrong-secret").getStatusCode().value());
        assertEquals(400, controller.action(Map.of("actor_id", "alice", "action", "NOT_AN_ACTION",
                "capability", "MASTER"), "server-secret").getStatusCode().value());
    }
}
