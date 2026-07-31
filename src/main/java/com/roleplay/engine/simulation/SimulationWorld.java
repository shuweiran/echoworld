package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Service
public class SimulationWorld {

    private static final Logger log = LoggerFactory.getLogger(SimulationWorld.class);

    public static final double WORLD_WIDTH = 1000.0;
    public static final double WORLD_HEIGHT = 600.0;
    public static final long TICK_INTERVAL_MS = 200;
    public static final long CONVERSATION_COOLDOWN_MS = 5000;
    public static final double CONVERSATION_DISTANCE_FACTOR = 0.7;
    public static final double GRID_CELL_SIZE = 100.0;
    public static final double WORLD_MARGIN = 20.0;

    private final SpatialGrid spatialGrid;
    private final MovementSystem movementSystem;
    private final HearingSystem hearingSystem;

    private final ConcurrentHashMap<String, AgentState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Agent> agents = new ConcurrentHashMap<>();
    private final List<Consumer<WorldSnapshot>> tickListeners = new CopyOnWriteArrayList<>();
    /** Phase 4: 移动 tick 之前运行的钩子（MovementConstraint 等，先于 MovementSystem.update）。 */
    private final List<Runnable> preTickHooks = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-world-tick");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private ScheduledFuture<?> tickFuture;
    private int tickCount = 0;
    private final List<Map<String, Object>> recentConversations = new ArrayList<>();
    private static final int MAX_RECENT_CONVERSATIONS = 50;
    private volatile String worldNarration = "";
    private volatile boolean directorActive = false;
    private volatile String userDirective = "";
    private volatile String currentScene = "park";
    private volatile List<Obstacle> obstacles = new CopyOnWriteArrayList<>();

    public SimulationWorld() {
        this.spatialGrid = new SpatialGrid(WORLD_WIDTH, WORLD_HEIGHT, GRID_CELL_SIZE);
        this.movementSystem = new MovementSystem(WORLD_WIDTH, WORLD_HEIGHT, WORLD_MARGIN, spatialGrid);
        this.hearingSystem = new HearingSystem(spatialGrid);
        this.obstacles = Obstacle.createScene(currentScene, WORLD_WIDTH, WORLD_HEIGHT);
    }

    public SpatialGrid getSpatialGrid() { return spatialGrid; }
    public MovementSystem getMovementSystem() { return movementSystem; }
    public HearingSystem getHearingSystem() { return hearingSystem; }

    // ── Agent management ───────────────────────────────────────

    public void registerAgent(Agent agent, double startX, double startY,
                               double hearRange, double moveSpeed) {
        String name = agent.getName();
        AgentState state = new AgentState(name, startX, startY);
        state.setHearRange(hearRange);
        state.setMoveSpeed(moveSpeed);
        states.put(name, state);
        agents.put(name, agent);
        log.info("Registered agent: {} at ({},{}) hear={} speed={}", name, startX, startY, hearRange, moveSpeed);
    }

    public void removeAgent(String name) {
        states.remove(name);
        agents.remove(name);
    }

    public AgentState getState(String name) { return states.get(name); }
    public Agent getAgent(String name) { return agents.get(name); }
    public Map<String, AgentState> getAllStates() { return new HashMap<>(states); }
    public Map<String, Agent> getAllAgents() { return new HashMap<>(agents); }
    public List<String> getAgentNames() { return new ArrayList<>(states.keySet()); }
    public int getAgentCount() { return states.size(); }

    public void clearAgents() {
        stop();
        states.clear();
        agents.clear();
        recentConversations.clear();
        tickCount = 0;
        worldNarration = "";
        directorActive = false;
        userDirective = "";
    }

    // ── Tick engine ────────────────────────────────────────────

    public void addTickListener(Consumer<WorldSnapshot> listener) { tickListeners.add(listener); }
    public void removeTickListener(Consumer<WorldSnapshot> listener) { tickListeners.remove(listener); }

    /** Phase 4: 注册移动 tick 前的钩子（在 MovementSystem.update 之前执行）。 */
    public void addPreTickHook(Runnable hook) {
        if (hook != null) preTickHooks.add(hook);
    }

    public void addConversationEntry(Map<String, Object> entry) {
        recentConversations.add(entry);
        if (recentConversations.size() > MAX_RECENT_CONVERSATIONS) recentConversations.remove(0);
    }
    public List<Map<String, Object>> getRecentConversations() { return new ArrayList<>(recentConversations); }

    public synchronized void start() {
        if (running) return;
        running = true;
        tickCount = 0;
        tickFuture = tickExecutor.scheduleAtFixedRate(this::tick, 0, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("World started ({}ms tick)", TICK_INTERVAL_MS);
    }

    public synchronized void stop() {
        running = false;
        if (tickFuture != null) { tickFuture.cancel(false); tickFuture = null; }
        log.info("World stopped at tick {}", tickCount);
    }

    public boolean isRunning() { return running; }
    public int getTickCount() { return tickCount; }

    public String getWorldNarration() { return worldNarration; }
    public void setWorldNarration(String s) { this.worldNarration = s; }
    public boolean isDirectorActive() { return directorActive; }
    public void setDirectorActive(boolean v) { this.directorActive = v; }
    public String getUserDirective() { return userDirective; }
    public void setUserDirective(String s) { this.userDirective = s; }
    public String getCurrentScene() { return currentScene; }
    public List<Obstacle> getObstacles() { return obstacles; }

    public void setScene(String sceneName) {
        this.currentScene = sceneName;
        this.obstacles = Obstacle.createScene(sceneName, WORLD_WIDTH, WORLD_HEIGHT);
        this.movementSystem.setObstacles(this.obstacles);
        log.info("Scene changed to: {} ({} obstacles)", sceneName, obstacles.size());
    }

    private void tick() {
        if (!running) return;
        tickCount++;

        // Phase 4: 移动前先应用轨道运动约束（MovementConstraint 产物），再寻路。
        for (Runnable hook : preTickHooks) {
            try { hook.run(); } catch (Exception e) {
                log.warn("Pre-tick hook error: {}", e.getMessage());
            }
        }

        Collection<AgentState> allStates = states.values();
        movementSystem.update(allStates, TICK_INTERVAL_MS / 1000.0);

        WorldSnapshot snapshot = buildSnapshot();
        for (Consumer<WorldSnapshot> listener : tickListeners) {
            try { listener.accept(snapshot); } catch (Exception e) {
                log.warn("Tick listener error: {}", e.getMessage());
            }
        }
    }

    private WorldSnapshot buildSnapshot() {
        List<Map<String, Object>> agentStates = new ArrayList<>();
        for (AgentState s : states.values()) {
            agentStates.add(s.toMap());
        }
        List<Map<String, Object>> obsList = new ArrayList<>();
        for (Obstacle o : obstacles) {
            obsList.add(o.toMap());
        }
        return new WorldSnapshot(tickCount, agentStates, obsList, System.currentTimeMillis(),
                worldNarration, directorActive, currentScene);
    }

    public record WorldSnapshot(int tick, List<Map<String, Object>> agents,
                                List<Map<String, Object>> obstacles, long timestamp,
                                String worldNarration, boolean directorActive, String scene) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tick", tick);
            map.put("agents", agents);
            map.put("obstacles", obstacles);
            map.put("timestamp", timestamp);
            map.put("worldNarration", worldNarration);
            map.put("directorActive", directorActive);
            map.put("scene", scene);
            return map;
        }
    }
}
