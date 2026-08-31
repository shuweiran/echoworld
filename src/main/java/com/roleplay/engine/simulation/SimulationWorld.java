package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.simulation.navigation.AiNavigationSystem;
import com.roleplay.engine.simulation.navigation.GridNavigationService;
import com.roleplay.engine.simulation.navigation.MultiFloorNavigationService;
import com.roleplay.engine.simulation.navigation.NavigationService;
import com.roleplay.engine.simulation.action.ActionDispatcher;
import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.action.ActionMutationPort;
import com.roleplay.engine.simulation.action.ActionResult;
import com.roleplay.engine.simulation.action.ActionType;
import com.roleplay.engine.simulation.action.BasicActionExecutors;
import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.Transform3D;
import com.roleplay.engine.simulation.observability.ActionCommitJfrEvent;
import com.roleplay.engine.simulation.observability.WorldRuntimeMetrics;
import com.roleplay.engine.simulation.observability.WorldTickJfrEvent;
import com.roleplay.engine.simulation.persistence.DurableWorldEvent;
import com.roleplay.engine.simulation.persistence.WorldCheckpoint;
import com.roleplay.engine.simulation.persistence.WorldCheckpointStore;
import com.roleplay.engine.simulation.navigation.NavProfile;
import com.roleplay.engine.simulation.navigation.portal.PortalEndpoint;
import com.roleplay.engine.simulation.navigation.portal.PortalRoute;
import com.roleplay.engine.simulation.navigation.portal.PortalRouteRequest;
import com.roleplay.engine.simulation.navigation.portal.PortalRouter;
import com.roleplay.engine.simulation.navigation.portal.PortalRuntimeState;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinitionValidator;
import com.roleplay.engine.simulation.agentruntime.AgentRuntime;
import com.roleplay.engine.simulation.agentruntime.AgentRuntimeSystem;
import com.roleplay.engine.simulation.worldobject.AffordanceResolver;
import com.roleplay.engine.simulation.worldobject.AffordanceDefinition;
import com.roleplay.engine.simulation.worldobject.WorldObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.time.Instant;

@Service
public class SimulationWorld implements ActionMutationPort {

    private static final Logger log = LoggerFactory.getLogger(SimulationWorld.class);

    /** 无 MapContract 时的兼容兜底，不作为运行中世界的权威边界。 */
    public static final double DEFAULT_WORLD_WIDTH = 1000.0;
    public static final double DEFAULT_WORLD_HEIGHT = 600.0;
    public static final long TICK_INTERVAL_MS = 200;
    public static final long CONVERSATION_COOLDOWN_MS = 5000;
    public static final double CONVERSATION_DISTANCE_FACTOR = 0.7;
    public static final double GRID_CELL_SIZE = 100.0;
    public static final double WORLD_MARGIN = 20.0;

    private SpatialGrid spatialGrid;
    private MovementSystem movementSystem;
    private AiNavigationSystem aiNavigationSystem;
    private final HearingSystem hearingSystem;
    private final ActionDispatcher actionDispatcher = new ActionDispatcher();
    private final AffordanceResolver affordanceResolver = new AffordanceResolver();
    private final WorldRuntimeMetrics runtimeMetrics = new WorldRuntimeMetrics();
    private final PortalRouter portalRouter = new PortalRouter();
    private final ConcurrentHashMap<String, PortalRuntimeState> portalStates = new ConcurrentHashMap<>();
    private volatile WorldDefinition worldDefinition;
    private final AgentRuntimeSystem agentRuntimeSystem = new AgentRuntimeSystem();

    private final ConcurrentHashMap<String, AgentState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Agent> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorldObject> worldObjects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> worldObjectStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> carriedBy = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> recentActionEvents = new CopyOnWriteArrayList<>();
    private final List<Consumer<Map<String, Object>>> actionEventListeners = new CopyOnWriteArrayList<>();
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
    /** Monotonic runtime version used to reject asynchronous stale intents. */
    private volatile long worldVersion = 0;
    private final List<Map<String, Object>> recentConversations = new ArrayList<>();
    private final List<WorldEvent> recentWorldEvents = new CopyOnWriteArrayList<>();
    private static final int MAX_RECENT_WORLD_EVENTS = 20;
    private static final int MAX_RECENT_CONVERSATIONS = 50;
    private volatile String worldNarration = "";
    private volatile boolean directorActive = false;
    private volatile String userDirective = "";
    private volatile String currentScene = "park";
    private volatile List<Obstacle> obstacles = new CopyOnWriteArrayList<>();
    private volatile double worldWidth = DEFAULT_WORLD_WIDTH;
    private volatile double worldHeight = DEFAULT_WORLD_HEIGHT;

    public SimulationWorld() {
        this.spatialGrid = new SpatialGrid(worldWidth, worldHeight, GRID_CELL_SIZE);
        this.movementSystem = new MovementSystem(worldWidth, worldHeight, WORLD_MARGIN, spatialGrid);
        this.aiNavigationSystem = new AiNavigationSystem(new GridNavigationService(), worldWidth, worldHeight);
        this.hearingSystem = new HearingSystem(spatialGrid);
        this.obstacles = Obstacle.createScene(currentScene, worldWidth, worldHeight);
        this.movementSystem.setObstacles(this.obstacles);
        this.aiNavigationSystem.setObstacles(this.obstacles);
        this.hearingSystem.setObstacles(this.obstacles);
        BasicActionExecutors.register(actionDispatcher);
        actionDispatcher.addStateListener(state -> {
            if (state.phase().terminal()) runtimeMetrics.recordAction(state.phase() == com.roleplay.engine.simulation.action.ActionPhase.SUCCESS);
            ActionCommitJfrEvent event = new ActionCommitJfrEvent();
            event.intentId = state.intent().intentId();
            event.action = state.intent().action().name();
            event.phase = state.phase().name();
            event.code = state.code();
            event.worldVersion = worldVersion;
            if (event.isEnabled()) event.commit();
        });
    }

    public SpatialGrid getSpatialGrid() { return spatialGrid; }
    public MovementSystem getMovementSystem() { return movementSystem; }
    public AiNavigationSystem getAiNavigationSystem() { return aiNavigationSystem; }
    public long getNavigationPlanningCount() { return aiNavigationSystem.planningCount(); }
    public synchronized void installNavigationService(NavigationService navigationService) {
        aiNavigationSystem = new AiNavigationSystem(Objects.requireNonNull(navigationService, "navigationService"),
                worldWidth, worldHeight);
        aiNavigationSystem.setObstacles(obstacles);
    }
    public HearingSystem getHearingSystem() { return hearingSystem; }
    public ActionDispatcher getActionDispatcher() { return actionDispatcher; }
    public WorldRuntimeMetrics.Snapshot getRuntimeMetrics() { return runtimeMetrics.snapshot(); }
    public WorldDefinition getWorldDefinition() { return worldDefinition; }
    public AgentRuntimeSystem getAgentRuntimeSystem() { return agentRuntimeSystem; }
    @Override public long worldVersion() { return worldVersion; }
    @Override public long minimumAcceptedWorldVersion() { return Math.max(0, worldVersion - 25); }
    @Override public boolean entityExists(String entityId) {
        return agents.containsKey(entityId) || worldObjects.containsKey(entityId);
    }
    @Override public ControlAuthority authorityOf(String entityId) {
        AgentState state = states.get(entityId);
        return state == null ? ControlAuthority.AI_AUTONOMOUS : state.controlAuthority();
    }
    @Override public Transform3D transformOf(String entityId) {
        AgentState state = states.get(entityId);
        if (state != null) return state.transform();
        WorldObject object = worldObjects.get(entityId);
        return object == null ? null : object.transform();
    }
    @Override public boolean affordanceAvailable(String actorId, String targetId, ActionType action) {
        WorldObject object = worldObjects.get(targetId);
        if (object == null) return true; // Agent-to-agent actions have their own domain validators.
        AgentState actor = states.get(actorId);
        if (actor == null) return false;
        AffordanceResolver.Result resolved = affordanceResolver.resolve(
                new ActionIntent("validation", actorId, com.roleplay.engine.simulation.action.ActionSource.ENGINE,
                        action, targetId, worldVersion, 0, Map.of()), actor.transform(), object);
        if (!resolved.available()) return false;
        for (Map.Entry<String, String> required : resolved.definition().requiredState().entrySet()) {
            if (!Objects.equals(required.getValue(), objectState(targetId, required.getKey()))) return false;
        }
        if (action == ActionType.SIT) {
            String occupant = objectState(targetId, "occupiedBy");
            return occupant == null || occupant.isBlank() || occupant.equals(actorId);
        }
        return true;
    }
    /** Enqueue from HTTP/LLM threads; validation and execution happen in the next world tick. */
    public java.util.concurrent.CompletableFuture<ActionResult> enqueueAction(ActionIntent intent) {
        return actionDispatcher.enqueue(intent);
    }

    public void registerAgentRuntime(String agentId, AgentRuntime runtime, AgentRuntimeSystem.InputProvider inputProvider) {
        AgentState state = states.get(agentId);
        if (state == null) throw new IllegalArgumentException("unknown agent: " + agentId);
        if (state.isPlayerControlled()) throw new IllegalArgumentException("player-controlled entities cannot use AgentRuntime");
        agentRuntimeSystem.register(agentId, runtime, inputProvider);
    }

    public void registerWorldObject(WorldObject object) {
        worldObjects.put(Objects.requireNonNull(object, "object").id(), object);
        worldObjectStates.putIfAbsent(object.id(), new ConcurrentHashMap<>());
    }

    public void removeWorldObject(String id) {
        worldObjects.remove(id);
        worldObjectStates.remove(id);
        carriedBy.remove(id);
    }
    public Map<String, WorldObject> getWorldObjects() { return Map.copyOf(worldObjects); }
    public List<Map<String, Object>> getRecentActionEvents() { return List.copyOf(recentActionEvents); }
    public void addActionEventListener(Consumer<Map<String, Object>> listener) {
        if (listener != null) actionEventListeners.add(listener);
    }
    public void removeActionEventListener(Consumer<Map<String, Object>> listener) { actionEventListeners.remove(listener); }

    @Override public boolean setMovementTarget(String actorId, double worldX, double worldZ) {
        AgentState state = states.get(actorId);
        if (state == null) return false;
        if (state.isPlayerControlled()) state.setPlayerIntentTarget(worldX, worldZ);
        else if (!state.setAutonomousTarget(worldX, worldZ)) return false;
        return true;
    }

    @Override public boolean setObjectState(String objectId, String key, String value) {
        if (!worldObjects.containsKey(objectId) || key == null || key.isBlank()) return false;
        worldObjectStates.computeIfAbsent(objectId, ignored -> new ConcurrentHashMap<>())
                .put(key, value == null ? "" : value);
        if ("open".equals(key) && portalStates.containsKey(objectId)) {
            PortalRuntimeState previous = portalStates.get(objectId);
            PortalRuntimeState.Availability availability = "true".equalsIgnoreCase(value)
                    ? PortalRuntimeState.Availability.AVAILABLE : PortalRuntimeState.Availability.CLOSED;
            portalStates.put(objectId, new PortalRuntimeState(objectId, availability,
                    previous == null ? 1 : previous.revision() + 1, "action:" + key));
        }
        return true;
    }

    @Override public String objectState(String objectId, String key) {
        Map<String, String> state = worldObjectStates.get(objectId);
        return state == null ? null : state.get(key);
    }

    @Override public boolean setCarriedBy(String objectId, String actorId) {
        if (!worldObjects.containsKey(objectId)) return false;
        if (actorId == null || actorId.isBlank()) carriedBy.remove(objectId);
        else if (states.containsKey(actorId)) carriedBy.put(objectId, actorId);
        else return false;
        return true;
    }

    @Override public String carriedBy(String objectId) { return carriedBy.getOrDefault(objectId, ""); }

    @Override public void emitActionEvent(String actorId, ActionType type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("worldVersion", worldVersion);
        event.put("actorId", actorId);
        event.put("action", type.name());
        event.put("payload", payload == null ? Map.of() : Map.copyOf(payload));
        event.put("timestamp", System.currentTimeMillis());
        Map<String, Object> immutable = Map.copyOf(event);
        recentActionEvents.add(immutable);
        while (recentActionEvents.size() > 100) recentActionEvents.remove(0);
        for (Consumer<Map<String, Object>> listener : actionEventListeners) {
            try { listener.accept(immutable); } catch (RuntimeException ignored) { /* adapter isolation */ }
        }
    }

    public WorldCheckpoint createCheckpoint(String worldId) {
        Map<String, Map<String, String>> objectStateCopy = new LinkedHashMap<>();
        worldObjectStates.forEach((id, state) -> objectStateCopy.put(id, Map.copyOf(state)));
        List<Map<String, Object>> entitySnapshots = states.values().stream()
                .map(AgentState::toMap).map(Map::copyOf).toList();
        return new WorldCheckpoint(worldId, worldVersion, tickCount, Instant.now(), entitySnapshots, objectStateCopy);
    }

    public void saveCheckpoint(String worldId, WorldCheckpointStore store) {
        Objects.requireNonNull(store, "store").save(createCheckpoint(worldId));
    }

    /** Restores mutable facts after agents/objects have been registered from static WorldDefinition. */
    public synchronized void restoreCheckpoint(WorldCheckpoint checkpoint) {
        if (running) throw new IllegalStateException("checkpoint restore requires a stopped world");
        Objects.requireNonNull(checkpoint, "checkpoint");
        for (Map<String, Object> raw : checkpoint.entities()) {
            String id = String.valueOf(raw.getOrDefault("agentName", ""));
            AgentState state = states.get(id);
            if (state == null) continue;
            Object x = raw.get("x"), y = raw.get("y");
            if (x instanceof Number nx && y instanceof Number ny) {
                state.setX(nx.doubleValue());
                state.setY(ny.doubleValue());
            }
        }
        checkpoint.objectStates().forEach((id, values) -> {
            if (worldObjects.containsKey(id)) values.forEach((key, value) -> setObjectState(id, key, value));
        });
        worldVersion = checkpoint.worldVersion();
        tickCount = Math.toIntExact(checkpoint.tick());
    }

    public DurableWorldEvent durableActionEvent(String worldId, Map<String, Object> actionEvent) {
        return new DurableWorldEvent(UUID.randomUUID().toString(), worldId, worldVersion, "ACTION_COMMITTED",
                Instant.now(), actionEvent == null ? Map.of() : actionEvent);
    }
    public double getWorldWidth() { return worldWidth; }
    public double getWorldHeight() { return worldHeight; }

    /** Loads validated static content before runtime entities are registered. */
    public synchronized void loadWorldDefinition(WorldDefinition definition) {
        if (!states.isEmpty() || running) throw new IllegalStateException("world definition requires an empty stopped world");
        WorldDefinitionValidator.Result result = new WorldDefinitionValidator().validate(definition);
        if (!result.valid()) throw new IllegalArgumentException("invalid world definition: " + result.errors());
        double maxX = definition.floors().stream().map(WorldDefinition.Floor::bounds)
                .mapToDouble(bounds -> bounds.max().x()).max().orElse(DEFAULT_WORLD_WIDTH);
        double maxZ = definition.floors().stream().map(WorldDefinition.Floor::bounds)
                .mapToDouble(bounds -> bounds.max().z()).max().orElse(DEFAULT_WORLD_HEIGHT);
        double minX = definition.floors().stream().map(WorldDefinition.Floor::bounds)
                .mapToDouble(bounds -> bounds.min().x()).min().orElse(0);
        double minZ = definition.floors().stream().map(WorldDefinition.Floor::bounds)
                .mapToDouble(bounds -> bounds.min().z()).min().orElse(0);
        if (minX < 0 || minZ < 0) throw new IllegalArgumentException("negative world origins require a coordinate adapter");
        setWorldBounds(Math.max(maxX, WORLD_MARGIN * 2 + 1), Math.max(maxZ, WORLD_MARGIN * 2 + 1));
        worldObjects.clear();
        worldObjectStates.clear();
        for (WorldDefinition.EntityDefinition entity : definition.entityDefinitions()) {
            registerWorldObject(new WorldObject(entity.id(), entity.type(), entity.transform(), Map.of(), entity.tags()));
        }
        portalStates.clear();
        definition.portals().forEach(portal -> {
            portalStates.put(portal.id(), PortalRuntimeState.available(portal.id()));
            if (portal.kind() == com.roleplay.engine.simulation.navigation.portal.SemanticPortal.Kind.DOOR) {
                Map<ActionType, AffordanceDefinition> affordances = Map.of(
                        ActionType.OPEN, new AffordanceDefinition(ActionType.OPEN, 40, 0, 1, Map.of("open", "false")),
                        ActionType.CLOSE, new AffordanceDefinition(ActionType.CLOSE, 40, 0, 1, Map.of("open", "true")));
                registerWorldObject(new WorldObject(portal.id(), "PORTAL_DOOR",
                        new Transform3D(portal.endpointA().worldPosition(), com.roleplay.engine.simulation.spatial.Quaternion.identity()),
                        affordances, portal.tags()));
                setObjectState(portal.id(), "open", "true");
            }
        });
        this.worldDefinition = definition;
        aiNavigationSystem = new AiNavigationSystem(new MultiFloorNavigationService(definition, portalRouter, portalStates), worldWidth, worldHeight);
        aiNavigationSystem.setObstacles(obstacles);
        hearingSystem.setSemanticPortals(definition.portals(), portalStates);
    }

    /** Restores the legacy single-floor runtime before loading a map without WorldDefinition metadata. */
    public synchronized void clearWorldDefinition() {
        if (!states.isEmpty() || running) throw new IllegalStateException("world definition reset requires an empty stopped world");
        worldDefinition = null;
        portalStates.clear();
        worldObjects.clear();
        worldObjectStates.clear();
        hearingSystem.setSemanticPortals(List.of(), Map.of());
    }

    public void registerAgentAtSpawn(Agent agent, String spawnId, double hearRange, double moveSpeed) {
        WorldDefinition definition = Objects.requireNonNull(worldDefinition, "world definition not loaded");
        WorldDefinition.SpawnPoint spawn = definition.spawnPoints().stream()
                .filter(candidate -> candidate.id().equals(spawnId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown spawn point: " + spawnId));
        registerAgent(agent, spawn.position().x(), spawn.position().z(), hearRange, moveSpeed);
        AgentState state = getState(agent.getName());
        state.getSpatial().setNavLocation(new NavLocation(spawn.surfaceId(), spawn.floorId(), spawn.position(), -1L));
    }

    public PortalRuntimeState getPortalState(String portalId) { return portalStates.get(portalId); }

    public void setPortalState(PortalRuntimeState state) {
        Objects.requireNonNull(state, "state");
        if (worldDefinition == null || worldDefinition.portals().stream().noneMatch(p -> p.id().equals(state.portalId()))) {
            throw new IllegalArgumentException("unknown portal: " + state.portalId());
        }
        portalStates.put(state.portalId(), state);
        if (state.availability() != PortalRuntimeState.Availability.AVAILABLE) {
            states.values().stream()
                    .filter(agent -> agent.getNavigationSteps().stream().anyMatch(step -> state.portalId().equals(step.worldObjectId())))
                    .forEach(AgentState::invalidateNavigationRoute);
        }
        if (worldObjects.containsKey(state.portalId())) {
            worldObjectStates.computeIfAbsent(state.portalId(), ignored -> new ConcurrentHashMap<>())
                    .put("open", state.availability() == PortalRuntimeState.Availability.AVAILABLE ? "true" : "false");
        }
    }

    public PortalRoute routeAcrossFloors(PortalEndpoint from, PortalEndpoint to, NavProfile profile) {
        WorldDefinition definition = Objects.requireNonNull(worldDefinition, "world definition not loaded");
        return portalRouter.route(new PortalRouteRequest(from, to, profile, definition.portals(), Map.copyOf(portalStates)));
    }

    /** 在加载角色前由 MapContract 设置物理边界；网格、移动与预置障碍随之重建。 */
    public synchronized void setWorldBounds(double width, double height) {
        if (width <= WORLD_MARGIN * 2 || height <= WORLD_MARGIN * 2) throw new IllegalArgumentException("invalid world bounds");
        if (!states.isEmpty()) throw new IllegalStateException("world bounds may only change before registering agents");
        worldWidth = width;
        worldHeight = height;
        spatialGrid = new SpatialGrid(width, height, GRID_CELL_SIZE);
        hearingSystem.setSpatialGrid(spatialGrid);
        movementSystem = new MovementSystem(width, height, WORLD_MARGIN, spatialGrid);
        aiNavigationSystem = new AiNavigationSystem(new GridNavigationService(), width, height);
        obstacles = Obstacle.createScene(currentScene, width, height);
        movementSystem.setObstacles(obstacles);
        aiNavigationSystem.setObstacles(obstacles);
        hearingSystem.setObstacles(obstacles);
    }

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
        actionDispatcher.interruptActor(name, "actor removed from world", System.currentTimeMillis());
        states.remove(name);
        agents.remove(name);
        agentRuntimeSystem.remove(name);
    }

    /** 非破坏性休眠恢复：把原 Agent 与原 AgentState 引用放回世界，完整保留内存与运行字段。 */
    public void restoreAgent(Agent agent, AgentState state) {
        if (agent == null || state == null) throw new IllegalArgumentException("agent/state required");
        agents.put(agent.getName(), agent);
        states.put(state.getAgentName(), state);
        log.info("Restored suspended agent: {} at ({},{})", agent.getName(), state.getX(), state.getY());
    }

    /**
     * P-0802-P3（改造方案 §4.2.2）：局中改名 —— agents/states 两个 map 换键 + persona/state 改名。
     * Agent 经 persona.setName 改名（Agent.getName 委托 persona，零 Agent 类改动）；
     * AgentState 经 {@link AgentState#rename} 原地改名（去 final 后引用一致性保留，toMap 自动用新名）。
     * 方法锁：与 tick/对话并发时防半同步状态被读取（单会话世界，方法级锁即可）。
     */
    public synchronized void renameAgent(String oldName, String newName) {
        // AgentRuntime owns an immutable agent id. Drop the opt-in V2 binding so it
        // cannot keep issuing intents under the stale authoritative entity id.
        agentRuntimeSystem.remove(oldName);
        actionDispatcher.interruptActor(oldName, "actor identity changed", System.currentTimeMillis());
        Agent a = agents.remove(oldName);
        if (a != null) {
            a.getPersona().setName(newName);
            agents.put(newName, a);
        }
        AgentState st = states.remove(oldName);
        if (st != null) {
            st.rename(newName);
            states.put(newName, st);
        }
        log.info("Agent renamed: {} → {}", oldName, newName);
    }

    public AgentState getState(String name) { return states.get(name); }
    public Agent getAgent(String name) { return agents.get(name); }
    public Map<String, AgentState> getAllStates() { return new HashMap<>(states); }
    public Map<String, Agent> getAllAgents() { return new HashMap<>(agents); }
    public List<String> getAgentNames() { return new ArrayList<>(states.keySet()); }
    public int getAgentCount() { return states.size(); }

    public void clearAgents() {
        stop();
        actionDispatcher.reset("world reset", System.currentTimeMillis());
        states.clear();
        agents.clear();
        worldObjects.clear();
        worldObjectStates.clear();
        carriedBy.clear();
        portalStates.clear();
        worldDefinition = null;
        agentRuntimeSystem.clear();
        recentActionEvents.clear();
        recentConversations.clear();
        recentWorldEvents.clear();
        tickCount = 0;
        worldVersion = 0;
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
    public void addWorldEvent(WorldEvent event) {
        if (event == null) return;
        recentWorldEvents.add(event);
        while (recentWorldEvents.size() > MAX_RECENT_WORLD_EVENTS) recentWorldEvents.remove(0);
    }
    /** 只返回此角色可感知的近期世界事件；非 2D 路径不调用本方法，继续走 Track 隔离。 */
    public List<WorldEvent> getPerceivedWorldEvents(AgentState self) {
        if (self == null) return List.of();
        List<WorldEvent> out = new ArrayList<>();
        for (WorldEvent event : recentWorldEvents) {
            // 尚无 VisionSystem/遮挡视线契约：VISUAL 只作为前端世界效果，不进入 Agent 私有认知。
            if (event.type() == WorldEvent.Type.VISUAL) continue;
            boolean perceived = switch (event.scope()) {
                case GLOBAL -> true;
                case TARGET -> event.targets().contains(self.getAgentName());
                case AREA -> event.type() == WorldEvent.Type.SOUND
                        ? hearingSystem.canHearEvent(event.x(), event.y(), event.radius(), self)
                        : Math.hypot(self.getX() - event.x(), self.getY() - event.y()) <= event.radius();
            };
            if (perceived) out.add(event);
        }
        return out;
    }

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
        this.obstacles = Obstacle.createScene(sceneName, worldWidth, worldHeight);
        this.movementSystem.setObstacles(this.obstacles);
        this.aiNavigationSystem.setObstacles(this.obstacles);
        this.hearingSystem.setObstacles(this.obstacles);
        log.info("Scene changed to: {} ({} obstacles)", sceneName, obstacles.size());
    }

    /**
     * P-0811-G：注入自定义障碍（LLM 地图 collision 瓦片转矩形 Obstacle）。
     * 覆盖预置场景；scene 名保留为"custom:<label>"便于识别。传空列表 = 清空障碍（无墙世界）。
     */
    public void setCustomObstacles(List<Obstacle> custom, String label) {
        this.obstacles = custom == null || custom.isEmpty()
                ? new CopyOnWriteArrayList<>()
                : new CopyOnWriteArrayList<>(custom);
        this.currentScene = label == null || label.isBlank() ? "custom" : "custom:" + label;
        this.movementSystem.setObstacles(this.obstacles);
        this.aiNavigationSystem.setObstacles(this.obstacles);
        this.hearingSystem.setObstacles(this.obstacles);
        log.info("Custom obstacles set: {} (label={})", obstacles.size(), currentScene);
    }

    private void tick() {
        if (!running) return;
        advanceOneTick();
    }

    /** Deterministic host entry used by the scheduler, tests, and future multi-world runners. */
    public synchronized WorldSnapshot advanceOneTick() {
        long startedNanos = System.nanoTime();
        WorldTickJfrEvent tickEvent = new WorldTickJfrEvent();
        if (tickEvent.isEnabled()) tickEvent.begin();
        tickCount++;
        worldVersion++;

        // Phase 4: 移动前先应用轨道运动约束（MovementConstraint 产物），再寻路。
        for (Runnable hook : preTickHooks) {
            try { hook.run(); } catch (Exception e) {
                log.warn("Pre-tick hook error: {}", e.getMessage());
            }
        }

        agentRuntimeSystem.update(this, tickCount, worldVersion, System.currentTimeMillis());

        // Single-writer rule: all queued action consequences commit on this thread.
        actionDispatcher.drain(this, System.currentTimeMillis());

        Collection<AgentState> allStates = states.values();
        aiNavigationSystem.update(allStates);
        movementSystem.update(allStates, TICK_INTERVAL_MS / 1000.0);

        WorldSnapshot snapshot = buildSnapshot();
        for (Consumer<WorldSnapshot> listener : tickListeners) {
            try { listener.accept(snapshot); } catch (Exception e) {
                log.warn("Tick listener error: {}", e.getMessage());
            }
        }
        runtimeMetrics.pendingActions(actionDispatcher.pendingCount() + actionDispatcher.activeCount());
        runtimeMetrics.recordTick(System.nanoTime() - startedNanos);
        if (tickEvent.isEnabled()) {
            tickEvent.worldVersion = worldVersion;
            tickEvent.tick = tickCount;
            tickEvent.agentCount = states.size();
            tickEvent.pendingActions = actionDispatcher.pendingCount() + actionDispatcher.activeCount();
            tickEvent.commit();
        }
        return snapshot;
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
        List<Map<String, Object>> floors = worldDefinition == null ? List.of() : worldDefinition.floors().stream()
                .map(f -> Map.<String, Object>of("id", f.id(), "name", f.name(), "elevation", f.elevation(),
                        "width", f.bounds().max().x() - f.bounds().min().x(), "height", f.bounds().max().z() - f.bounds().min().z())).toList();
        List<Map<String, Object>> connectors = worldDefinition == null ? List.of() : worldDefinition.portals().stream()
                .map(p -> {
                    Map<String, Object> connector = new LinkedHashMap<>();
                    connector.put("id", p.id()); connector.put("kind", p.kind().name());
                    connector.put("sourceFloor", p.endpointA().floorId()); connector.put("sourceSurface", p.endpointA().surfaceId());
                    connector.put("sourceX", p.endpointA().worldPosition().x()); connector.put("sourceY", p.endpointA().worldPosition().z());
                    connector.put("targetFloor", p.endpointB().floorId()); connector.put("targetSurface", p.endpointB().surfaceId());
                    connector.put("targetX", p.endpointB().worldPosition().x()); connector.put("targetY", p.endpointB().worldPosition().z());
                    connector.put("bidirectional", p.bidirectional());
                    PortalRuntimeState state = portalStates.get(p.id());
                    connector.put("availability", state == null ? PortalRuntimeState.Availability.AVAILABLE.name() : state.availability().name());
                    return connector;
                }).toList();
        return new WorldSnapshot(tickCount, agentStates, obsList, System.currentTimeMillis(),
                worldNarration, directorActive, currentScene, worldWidth, worldHeight, floors, connectors);
    }

    public record WorldSnapshot(int tick, List<Map<String, Object>> agents,
                                List<Map<String, Object>> obstacles, long timestamp,
                                String worldNarration, boolean directorActive, String scene,
                                double worldWidth, double worldHeight,
                                List<Map<String, Object>> floors, List<Map<String, Object>> connectors) {
        public WorldSnapshot(int tick, List<Map<String, Object>> agents, List<Map<String, Object>> obstacles,
                             long timestamp, String worldNarration, boolean directorActive, String scene,
                             double worldWidth, double worldHeight) {
            this(tick, agents, obstacles, timestamp, worldNarration, directorActive, scene,
                    worldWidth, worldHeight, List.of(), List.of());
        }
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tick", tick);
            map.put("agents", agents);
            map.put("obstacles", obstacles);
            map.put("timestamp", timestamp);
            map.put("worldNarration", worldNarration);
            map.put("directorActive", directorActive);
            map.put("scene", scene);
            map.put("worldWidth", worldWidth);
            map.put("worldHeight", worldHeight);
            if (!floors.isEmpty()) map.put("floors", floors);
            if (!connectors.isEmpty()) map.put("connectors", connectors);
            return map;
        }
    }
}
