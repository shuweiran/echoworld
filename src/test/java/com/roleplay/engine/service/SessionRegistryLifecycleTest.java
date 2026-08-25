package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.util.concurrent.atomic.AtomicInteger;

class SessionRegistryLifecycleTest {

    private record Harness(SessionRegistry registry, RouterService defaultRouter) {}

    private Harness harness() {
        RouterService defaultRouter = mock(RouterService.class);
        SessionRegistry registry = new SessionRegistry(
                defaultRouter,
                mock(ArbiterService.class),
                mock(AgentExecutor.class),
                mock(Compressor.class),
                mock(Monitor.class),
                mock(GeneratorService.class),
                mock(TrackRequestService.class),
                mock(LLMClient.class),
                null,
                mock(LorebookService.class),
                mock(InterruptManager.class),
                mock(WorldEventBus.class),
                mock(SSEController.class),
                mock(PlayerIdentityService.class),
                mock(SceneGoalService.class));
        return new Harness(registry, defaultRouter);
    }

    @Test
    @DisplayName("非空未知 session_id 明确返回 404；仅空 id 兼容默认会话")
    void unknownNonBlankSessionDoesNotFallbackToDefault() {
        Harness h = harness();
        assertSame(h.defaultRouter(), h.registry().get(""));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> h.registry().get("missing-session"));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    @DisplayName("显式 remove 释放会话，关闭后不能再次读取")
    void explicitRemoveClosesSession() {
        Harness h = harness();
        AtomicInteger removalNotifications = new AtomicInteger();
        h.registry().addRemovalListener((id, router) -> {
            if ("close-me".equals(id)) removalNotifications.incrementAndGet();
        });
        RouterService created = h.registry().getOrCreate("close-me");
        assertSame(created, h.registry().get("close-me"));

        assertTrue(h.registry().remove("close-me"));
        assertEquals(0, h.registry().sessionCount());
        assertThrows(ResponseStatusException.class, () -> h.registry().get("close-me"));
        assertFalse(h.registry().remove("close-me"));
        assertEquals(1, removalNotifications.get());
    }

    @Test
    @DisplayName("TTL 清扫移除空闲会话并保留近期会话")
    void ttlSweepEvictsOnlyIdleSessions() throws Exception {
        Harness h = harness();
        h.registry().setSessionTtlMs(40);
        h.registry().getOrCreate("idle");
        Thread.sleep(60);
        h.registry().getOrCreate("active");

        h.registry().sweepExpired();

        assertThrows(ResponseStatusException.class, () -> h.registry().get("idle"));
        assertNotNull(h.registry().get("active"));
        assertEquals(1, h.registry().sessionCount());
    }

    @Test
    @DisplayName("容量达到上限时淘汰最久未访问会话")
    void capacityEvictsLeastRecentlyAccessedSession() throws Exception {
        Harness h = harness();
        h.registry().setMaxSessions(2);
        h.registry().getOrCreate("oldest");
        Thread.sleep(25);
        h.registry().getOrCreate("kept");
        Thread.sleep(25);
        h.registry().getOrCreate("newest");

        assertEquals(2, h.registry().sessionCount());
        assertThrows(ResponseStatusException.class, () -> h.registry().get("oldest"));
        assertNotNull(h.registry().get("kept"));
        assertNotNull(h.registry().get("newest"));
    }
}
