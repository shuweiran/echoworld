package com.roleplay.engine.service;

import com.roleplay.engine.core.Persona;
import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RouterWorldOwnershipTest {

    @Test
    @DisplayName("旧角色接口不能覆盖或删除世界生命周期角色")
    void legacyAgentMutationCannotBypassWorldOwnership() {
        RouterService router = new RouterService(
                mock(ArbiterService.class), mock(AgentExecutor.class), new MemoryStore(),
                mock(Compressor.class), mock(Monitor.class), mock(GeneratorService.class),
                mock(TrackRequestService.class), mock(LLMClient.class), null,
                mock(LorebookService.class), mock(InterruptManager.class),
                mock(WorldEventBus.class), mock(SSEController.class), mock(PlayerIdentityService.class));
        Persona worldPersona = new Persona("街角路人", "记得刚才的谈话");
        router.addWorldAgent("街角路人", worldPersona);

        assertTrue(router.isWorldOwnedAgent("街角路人"));
        assertThrows(IllegalStateException.class,
                () -> router.addAgent("街角路人", new Persona("街角路人", "覆盖")));
        assertThrows(IllegalStateException.class, () -> router.removeAgent("街角路人"));
        assertTrue(router.suspendWorldAgent("街角路人"));
        assertThrows(IllegalStateException.class, () -> router.removeAgent("街角路人"));

        assertTrue(router.removeWorldAgent("街角路人"));
        assertFalse(router.isWorldOwnedAgent("街角路人"));
        assertDoesNotThrow(() -> router.addAgent("街角路人", worldPersona));
    }
}
