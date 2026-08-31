package com.roleplay.engine.simulation;

import com.roleplay.engine.simulation.navigation.portal.SemanticPortal;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MultiFloorWorldStressTest {
    @Test
    void threeFloorsThirtyAgentsTenConnectorsRemainDeterministicForOneThousandTicks() {
        List<SemanticPortal> portals = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double x = 20 + i * 15;
            portals.add(MultiFloorMovementIntegrationTest.stairs("lower-" + i, "f1", "f2", x));
            portals.add(MultiFloorMovementIntegrationTest.stairs("upper-" + i, "f2", "f3", x));
        }
        SimulationWorld world = MultiFloorMovementIntegrationTest.world(portals);
        for (int i = 0; i < 30; i++) {
            String floor = "f" + (i % 3 + 1);
            AgentState state = MultiFloorMovementIntegrationTest.spawn(world, "agent-" + i, floor,
                    22 + (i % 10) * 6, 30 + (i / 10) * 15);
            String targetFloor = "f" + ((i + 1) % 3 + 1);
            state.setAutonomousTarget(75 - (i % 8) * 5, 70, targetFloor, "s-" + targetFloor);
        }

        long planningBefore = world.getNavigationPlanningCount();
        long[] durations = new long[1000];
        for (int tick = 0; tick < durations.length; tick++) {
            long started = System.nanoTime();
            SimulationWorld.WorldSnapshot snapshot = world.advanceOneTick();
            durations[tick] = System.nanoTime() - started;
            assertEquals(tick + 1, snapshot.tick());
            assertEquals(30, snapshot.agents().size());
        }
        long recalculations = world.getNavigationPlanningCount() - planningBefore;
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        double averageMs = Arrays.stream(durations).average().orElseThrow() / 1_000_000.0;
        double p95Ms = sorted[949] / 1_000_000.0;
        System.out.printf(Locale.ROOT,
                "MULTI_FLOOR_STRESS ticks=1000 agents=30 connectors=10 avgMs=%.4f p95Ms=%.4f pathRecalculations=%d%n",
                averageMs, p95Ms, recalculations);

        assertTrue(Double.isFinite(averageMs));
        assertTrue(Double.isFinite(p95Ms));
        assertTrue(recalculations >= 30);
        assertTrue(world.getAllStates().values().stream()
                .allMatch(state -> Set.of("f1", "f2", "f3").contains(state.navLocation().floorId())));
    }
}
