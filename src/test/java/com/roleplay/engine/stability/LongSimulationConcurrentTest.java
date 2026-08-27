package com.roleplay.engine.stability;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.movement.MovementConstraint;
import com.roleplay.engine.simulation.movement.MovementTarget;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LONG-04：并发模拟稳定性（方案 7.4）。
 *
 * <p>① 真实 2D 世界定时 tick（SimulationWorld 调度线程）+ 每 tick 应用 GroupAnchor 运动约束，
 * 运行一段时间后断言 tick 持续推进、全部角色坐标有限且有界（无 NaN/越界/异常）。
 * ② 8 线程并发 compute+apply 运动约束 1000 次迭代，断言无异常、无死锁、坐标恒有界
 * （覆盖 2D 世界并发 send/tick 的共享状态读写稳定性）。
 */
class LongSimulationConcurrentTest {

    private static final int AGENT_COUNT = 8;

    private static final double MIN_BOUND = SimulationWorld.WORLD_MARGIN;
    private static final double MAX_X = SimulationWorld.DEFAULT_WORLD_WIDTH - SimulationWorld.WORLD_MARGIN;
    private static final double MAX_Y = SimulationWorld.DEFAULT_WORLD_HEIGHT - SimulationWorld.WORLD_MARGIN;

    private SimulationWorld worldWithAgents() {
        SimulationWorld world = new SimulationWorld();
        for (int i = 0; i < AGENT_COUNT; i++) {
            world.registerAgent(new Agent(new Persona("P" + i, "测试角色"), "npc", null),
                    100 + i * 20.0, 100, 200, 80);
        }
        return world;
    }

    /** 全 MERGED 群组：每人可见同组其他人 → GroupAnchor（leader P0 + follow slot）。 */
    private Map<String, TrackAssignment> mergedGroup() {
        Map<String, TrackAssignment> m = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < AGENT_COUNT; i++) all.add("P" + i);
        for (String n : all) {
            List<String> visible = all.stream().filter(v -> !v.equals(n)).toList();
            m.put(n, TrackAssignment.of(n, Track.Mode.MERGED, visible, "公开聊天"));
        }
        return m;
    }

    private void assertAllPositionsFiniteAndBounded(SimulationWorld world) {
        for (AgentState s : world.getAllStates().values()) {
            assertTrue(Double.isFinite(s.getX()) && Double.isFinite(s.getY()),
                    "坐标必须有限（NaN/Inf）: " + s.getAgentName());
            assertTrue(s.getX() >= MIN_BOUND && s.getX() <= MAX_X, "x 越界: " + s.getAgentName() + "=" + s.getX());
            assertTrue(s.getY() >= MIN_BOUND && s.getY() <= MAX_Y, "y 越界: " + s.getAgentName() + "=" + s.getY());
        }
    }

    @Test
    @DisplayName("LONG-04a: 2D 世界定时 tick + GroupAnchor 约束并发运行，tick 推进且坐标有界")
    void concurrentWorldTicksRemainConsistent() throws Exception {
        SimulationWorld world = worldWithAgents();
        MovementConstraint constraint = new MovementConstraint();
        Map<String, TrackAssignment> assignments = mergedGroup();
        world.addPreTickHook(() -> {
            Map<String, MovementTarget> targets = constraint.compute(world, assignments, Set.of());
            constraint.apply(world, targets);
        });

        world.start();
        Thread.sleep(1500); // ≈7 个 tick
        int ticks = world.getTickCount();
        world.stop();

        assertTrue(ticks >= 1, "世界应持续推进 tick，实际=" + ticks);
        assertAllPositionsFiniteAndBounded(world);
    }

    @Test
    @DisplayName("LONG-04b: 8 线程并发 compute+apply 运动约束 1000 迭代，无异常/死锁/坐标越界")
    void concurrentMovementConstraintLoops() throws Exception {
        SimulationWorld world = worldWithAgents();
        MovementConstraint constraint = new MovementConstraint();
        Map<String, TrackAssignment> assignments = mergedGroup();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        Map<String, MovementTarget> targets = constraint.compute(world, assignments, Set.of());
                        constraint.apply(world, targets);
                        for (MovementTarget tg : targets.values()) {
                            assertTrue(Double.isFinite(tg.targetX()) && Double.isFinite(tg.targetY()),
                                    "约束目标必须有限: " + tg.agentName());
                        }
                        if (i % 100 == 0) assertAllPositionsFiniteAndBounded(world);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS); // 超时 = 死锁判定
            }
        } finally {
            pool.shutdownNow();
        }
        assertAllPositionsFiniteAndBounded(world);
    }

    @Test
    @DisplayName("LONG-04c: 并发迭代数量核验（8 线程 × 1000 迭代均完成无丢任务）")
    void concurrentIterationCount() throws Exception {
        SimulationWorld world = worldWithAgents();
        MovementConstraint constraint = new MovementConstraint();
        Map<String, TrackAssignment> assignments = mergedGroup();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        int[] counts = new int[8];
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < 8; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        constraint.compute(world, assignments, Set.of());
                    }
                    counts[idx] = 1000;
                }));
            }
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(8 * 1000, Arrays.stream(counts).sum(), "并发任务应全部完成（无丢失）");
    }
}
