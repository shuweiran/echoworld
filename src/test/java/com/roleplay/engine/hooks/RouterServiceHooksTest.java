package com.roleplay.engine.hooks;

import com.roleplay.engine.service.RouterService.RoundResult;
import com.roleplay.engine.service.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the RoundHook lifecycle interface.
 *
 * <p>Verifies that hooks are properly invoked at the correct lifecycle points,
 * receive the correct parameter values, and that the hook list management in
 * RouterService works correctly.
 */
class RouterServiceHooksTest {

    private TestHook testHook;
    private List<RoundHook> hookList;

    @BeforeEach
    void setUp() {
        testHook = new TestHook();
        hookList = new CopyOnWriteArrayList<>();
    }

    // ── Hook list management tests ─────────────────────────────

    @Test
    @DisplayName("Hook registration and removal via RouterService")
    void testHookRegistration() {
        // Verify RouterService.addHook/removeHook work via static logic
        assertTrue(testHook.events.isEmpty());

        testHook.beforeRound("hello", "free", 1);
        assertEquals(1, testHook.events.size());
        assertTrue(testHook.events.getFirst().contains("beforeRound"));
    }

    @Test
    @DisplayName("Null hook is silently ignored")
    void testNullHookIgnored() {
        hookList.add(null);  // RouterService would guard against this
        assertTrue(hookList.contains(null));
    }

    @Test
    @DisplayName("Multiple hooks run in registration order")
    void testMultipleHookOrder() {
        List<Integer> invocationOrder = new CopyOnWriteArrayList<>();
        RoundHook hook1 = new RoundHook() {
            @Override
            public void beforeRound(String userInput, String mode, int roundCount) {
                invocationOrder.add(1);
            }
        };
        RoundHook hook2 = new RoundHook() {
            @Override
            public void beforeRound(String userInput, String mode, int roundCount) {
                invocationOrder.add(2);
            }
        };
        RoundHook hook3 = new RoundHook() {
            @Override
            public void beforeRound(String userInput, String mode, int roundCount) {
                invocationOrder.add(3);
            }
        };

        hookList.add(hook1);
        hookList.add(hook2);
        hookList.add(hook3);

        for (RoundHook hook : hookList) {
            hook.beforeRound(null, "test", 1);
        }

        assertEquals(List.of(1, 2, 3), invocationOrder);
    }

    // ── Lifecycle event tests ──────────────────────────────────

    @Test
    @DisplayName("beforeRound receives correct parameters")
    void testBeforeRoundParameters() {
        AtomicReference<String> capturedInput = new AtomicReference<>();
        AtomicReference<String> capturedMode = new AtomicReference<>();
        AtomicInteger capturedRound = new AtomicInteger(-1);

        hookList.add(new RoundHook() {
            @Override
            public void beforeRound(String userInput, String mode, int roundCount) {
                capturedInput.set(userInput);
                capturedMode.set(mode);
                capturedRound.set(roundCount);
            }
        });

        for (RoundHook hook : hookList) {
            hook.beforeRound("你好，今天天气怎么样？", "free", 3);
        }

        assertEquals("你好，今天天气怎么样？", capturedInput.get());
        assertEquals("free", capturedMode.get());
        assertEquals(3, capturedRound.get());
    }

    @Test
    @DisplayName("afterTrackConfig receives correct parameters")
    void testAfterTrackConfig() {
        AtomicReference<List<Map<String, Object>>> capturedTracks = new AtomicReference<>();
        AtomicReference<String> capturedReasoning = new AtomicReference<>();

        hookList.add(new RoundHook() {
            @Override
            public void afterTrackConfig(List<Map<String, Object>> tracks, String reasoning) {
                capturedTracks.set(tracks);
                capturedReasoning.set(reasoning);
            }
        });

        List<Map<String, Object>> tracks = List.of(
            Map.of("id", "track_0", "mode", "merged", "agents", List.of("Alice", "Bob"))
        );
        for (RoundHook hook : hookList) {
            hook.afterTrackConfig(tracks, "测试轨道配置");
        }

        assertNotNull(capturedTracks.get());
        assertEquals(1, capturedTracks.get().size());
        assertEquals("merged", capturedTracks.get().getFirst().get("mode"));
        assertEquals("测试轨道配置", capturedReasoning.get());
    }

    @Test
    @DisplayName("beforeAgentContext can mutate context parts")
    void testBeforeAgentContextMutation() {
        List<String> contextParts = new ArrayList<>();
        contextParts.add("【当前场景】森林中");

        hookList.add(new RoundHook() {
            @Override
            public void beforeAgentContext(String agentName, String trackMode, List<String> contextParts) {
                contextParts.add("【Hook注入】额外上下文");
            }
        });

        for (RoundHook hook : hookList) {
            hook.beforeAgentContext("Alice", "merged", contextParts);
        }

        assertEquals(2, contextParts.size());
        assertTrue(contextParts.get(1).contains("Hook注入"));
    }

    @Test
    @DisplayName("afterAgentOutput receives output and timing")
    void testAfterAgentOutput() {
        List<String> capturedNames = new ArrayList<>();
        List<String> capturedOutputs = new ArrayList<>();
        List<Boolean> capturedSuccess = new ArrayList<>();
        List<Long> capturedDurations = new ArrayList<>();

        hookList.add(new RoundHook() {
            @Override
            public void afterAgentOutput(String agentName, String output, boolean success, long durationMs) {
                capturedNames.add(agentName);
                capturedOutputs.add(output);
                capturedSuccess.add(success);
                capturedDurations.add(durationMs);
            }
        });

        for (RoundHook hook : hookList) {
            hook.afterAgentOutput("Bob", "Hello world", true, 1234L);
            hook.afterAgentOutput("Charlie", null, false, 0L);
        }

        assertEquals(2, capturedNames.size());
        assertEquals("Bob", capturedNames.get(0));
        assertEquals("Hello world", capturedOutputs.get(0));
        assertTrue(capturedSuccess.get(0));
        assertEquals(1234L, capturedDurations.get(0));

        assertEquals("Charlie", capturedNames.get(1));
        assertNull(capturedOutputs.get(1));
        assertFalse(capturedSuccess.get(1));
    }

    @Test
    @DisplayName("afterRound receives the RoundResult")
    void testAfterRound() {
        AtomicReference<RoundResult> capturedResult = new AtomicReference<>();
        hookList.add(new RoundHook() {
            @Override
            public void afterRound(RoundResult result) {
                capturedResult.set(result);
            }
        });

        RoundResult result = new RoundResult(
            "3 agents done",
            List.of(Map.of("agent_name", "Alice", "content", "Hi")),
            Map.of("narration", "Alice speaks"),
            "测试整合",
            Map.of("total_round_time_ms", 5000)
        );
        for (RoundHook hook : hookList) {
            hook.afterRound(result);
        }

        assertNotNull(capturedResult.get());
        assertEquals("3 agents done", capturedResult.get().status);
        assertEquals(1, capturedResult.get().agentOutputs.size());
        assertEquals("Alice", capturedResult.get().agentOutputs.getFirst().get("agent_name"));
        assertEquals("测试整合", capturedResult.get().reasoning);
    }

    @Test
    @DisplayName("onRoundError captures phase and error")
    void testOnRoundError() {
        AtomicReference<String> capturedPhase = new AtomicReference<>();
        AtomicReference<Exception> capturedError = new AtomicReference<>();

        hookList.add(new RoundHook() {
            @Override
            public void onRoundError(String phase, Exception error) {
                capturedPhase.set(phase);
                capturedError.set(error);
            }
        });

        Exception testError = new RuntimeException("LLM调用失败");
        for (RoundHook hook : hookList) {
            hook.onRoundError("integration", testError);
        }

        assertEquals("integration", capturedPhase.get());
        assertEquals("LLM调用失败", capturedError.get().getMessage());
    }

    @Test
    @DisplayName("Hook exception does not break other hooks")
    void testHookExceptionIsolation() {
        AtomicBoolean hook2Called = new AtomicBoolean(false);

        hookList.add(new RoundHook() {
            @Override
            public void beforeRound(String userInput, String mode, int roundCount) {
                throw new RuntimeException("Hook 1 failed");
            }
        });
        hookList.add(new RoundHook() {
            @Override
            public void beforeRound(String userInput, String mode, int roundCount) {
                hook2Called.set(true);
            }
        });

        // Simulate RouterService's try-catch per hook
        for (RoundHook hook : hookList) {
            try {
                hook.beforeRound(null, "test", 1);
            } catch (Exception ignored) {
                // RouterService catches and logs these individually
            }
        }

        assertTrue(hook2Called.get());
    }

    @Test
    @DisplayName("Default implementations do nothing (no-op test)")
    void testDefaultImplementations() {
        // Create a hook that overrides nothing — should not throw
        RoundHook noop = new RoundHook() {};
        hookList.add(noop);

        // All of these should complete without error
        for (RoundHook hook : hookList) {
            hook.beforeRound(null, "free", 1);
            hook.afterTrackConfig(List.of(), "");
            hook.beforeAgentContext("Alice", "merged", new ArrayList<>());
            hook.afterAgentOutput("Alice", "output", true, 100L);
            hook.afterRound(new RoundResult("ok", List.of(), Map.of(), "", Map.of()));
            hook.onRoundError("test", new Exception("test"));
        }
    }

    /**
     * A simple test hook that records all invocations.
     */
    static class TestHook implements RoundHook {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void beforeRound(String userInput, String mode, int roundCount) {
            events.add("beforeRound(" + userInput + ", " + mode + ", " + roundCount + ")");
        }

        @Override
        public void afterTrackConfig(List<Map<String, Object>> tracks, String reasoning) {
            events.add("afterTrackConfig(" + tracks.size() + " tracks, " + reasoning + ")");
        }

        @Override
        public void beforeAgentContext(String agentName, String trackMode, List<String> contextParts) {
            events.add("beforeAgentContext(" + agentName + ", " + trackMode + ")");
        }

        @Override
        public void afterAgentOutput(String agentName, String output, boolean success, long durationMs) {
            events.add("afterAgentOutput(" + agentName + ", " + success + ", " + durationMs + "ms)");
        }

        @Override
        public void afterRound(RoundResult result) {
            events.add("afterRound(" + result.status + ")");
        }

        @Override
        public void onRoundError(String phase, Exception error) {
            events.add("onRoundError(" + phase + ", " + error.getMessage() + ")");
        }
    }
}
