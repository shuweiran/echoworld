package com.roleplay.engine.simulation.agentruntime;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** World-tick host for opt-in V2 AgentRuntime instances during strangler migration. */
public final class AgentRuntimeSystem {
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private volatile Map<String, RuntimeDecision> lastDecisions = Map.of();

    public void register(String agentId, AgentRuntime runtime, InputProvider inputProvider) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        Binding previous = bindings.putIfAbsent(agentId,
                new Binding(Objects.requireNonNull(runtime, "runtime"), Objects.requireNonNull(inputProvider, "inputProvider")));
        if (previous != null) throw new IllegalArgumentException("runtime already registered: " + agentId);
    }

    public void remove(String agentId) { bindings.remove(agentId); }
    public void clear() {
        bindings.clear();
        lastDecisions = Map.of();
    }
    public int size() { return bindings.size(); }
    public Map<String, RuntimeDecision> lastDecisions() { return lastDecisions; }

    public Map<String, RuntimeDecision> update(SimulationWorld world, long tick, long worldVersion, long nowMillis) {
        Map<String, RuntimeDecision> decisions = new LinkedHashMap<>();
        bindings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                AgentState state = world.getState(entry.getKey());
                if (state == null || state.isPlayerControlled()) return;
                RuntimeInput input = entry.getValue().inputProvider().sample(world, state, tick, worldVersion, nowMillis);
                if (input == null) return;
                decisions.put(entry.getKey(), entry.getValue().runtime().tick(input));
            } catch (RuntimeException failure) {
                decisions.put(entry.getKey(), new RuntimeDecision(RuntimeDecision.Status.RUNTIME_ERROR,
                        CognitiveLod.MACRO, "", "", failure.getClass().getSimpleName(), false, java.util.List.of()));
            }
        });
        lastDecisions = Map.copyOf(decisions);
        return lastDecisions;
    }

    @FunctionalInterface
    public interface InputProvider {
        RuntimeInput sample(SimulationWorld world, AgentState state, long tick, long worldVersion, long nowMillis);
    }

    private record Binding(AgentRuntime runtime, InputProvider inputProvider) { }
}
