package com.roleplay.engine.simulation;

import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SessionRegistry;
import com.roleplay.engine.service.world.WorldRuntimeService;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.map.MapWorldDefinitionAdapter;
import com.roleplay.engine.simulation.map.SocialExperimentMap;
import com.roleplay.engine.simulation.worlddefinition.WorldDefinition;
import com.roleplay.engine.simulation.worldobject.WorldObject;
import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.action.ActionResult;
import com.roleplay.engine.simulation.action.ActionSource;
import com.roleplay.engine.simulation.action.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationService simulationService;
    private final SimulationWorld world;
    private final CharacterController characterController;
    /** P-0814-A：会话注册表（playback_done 无 group_id 时推进一般模式 RouterService 轮次）。 */
    private final SessionRegistry sessions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WorldRuntimeService worldRuntime;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SimulationWorld.WorldSnapshot> recentSnapshots = new CopyOnWriteArrayList<>();
    /** Disabled when blank; callers cannot self-assert the server-side MASTER capability. */
    @Value("${roleplay.gameplay.master-key:}")
    private String gameplayMasterKey = "";
    private static final int MAX_RECENT_SNAPSHOTS = 100;
    /** P-0820-R：SSE 全量快照每个世界 tick 广播一次（200ms/5Hz），保证玩家位置及时可见。 */
    private static final int SSE_BROADCAST_TICK_INTERVAL = 1;

    public SimulationController(SimulationService simulationService, SimulationWorld world,
                                CharacterController characterController) {
        this(simulationService, world, characterController, null);
    }

    /** Spring 使用 4 参注入版（SessionRegistry 供 playback_done 路由）；3 参旧构造保留委托（测试/直构零破坏）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SimulationController(SimulationService simulationService, SimulationWorld world,
                                CharacterController characterController, SessionRegistry sessions) {
        this.simulationService = simulationService;
        this.world = world;
        this.characterController = characterController;
        this.sessions = sessions;

        world.addTickListener(snapshot -> {
            Map<String, Object> event = snapshot.toMap();
            event.put("type", "world_snapshot");
            // P-0815-E 需求3：SSE 全量快照节流广播（每 2 tick 一次）+ 事件附 recentConversations——
            // ① 消息即时推送（前端不再等 3s 轮询，消「刷新太慢」）；② 全量快照推送频率减半（消卡顿）。
            // P-0815-F 深度调研修复（2026-08-15 #206）：节流判定改为无状态「tick % 2 == 0」——
            // 原实现 snapshot.tick() - lastBroadcastTick >= 2 依赖跨重启的 lastBroadcastTick 游标，
            // 而 load-characters/clearAgents 会把 tickCount 归零、lastBroadcastTick 却保留旧值 →
            // 新世界 tick 未超过旧世界最后广播 tick 前 SSE 静默（静默区随重启次数增长，实测整段 0 广播）→
            // 前端只能靠 3s 轮询更新 = 角色每 3s 跳变（「更卡了」根因）。tick % 2 == 0 无状态恒 2.5Hz。
            if (snapshot.tick() % SSE_BROADCAST_TICK_INTERVAL == 0) {
                event.put("recentConversations", world.getRecentConversations());
                broadcastToAll(event);
            }
            recentSnapshots.add(snapshot);
            if (recentSnapshots.size() > MAX_RECENT_SNAPSHOTS) {
                recentSnapshots.remove(0);
            }
        });
    }

    @PostMapping("/init")
    public Map<String, Object> initDemo(@RequestBody(required = false) Map<String, Object> body) {
        int count = 2;
        if (body != null && body.containsKey("count")) {
            count = ((Number) body.get("count")).intValue();
        }
        simulationService.initDemo(count);
        return Map.of("status", "ok", "message", "Initialized " + count + " agents");
    }

    /**
     * Load user-defined characters into the simulation world.
     * Bridges roleplay session characters into the 2D spatial system.
     *
     * <p>Request body:
     * <pre>{@code
     * {
     *   "characters": [
     *     {"name": "\u5c0f\u660e", "persona": "\u5f00\u6717\u5916\u5411\u7684\u5e74\u8f7b\u4eba", "voice": "\u8bf4\u8bdd\u8f7b\u677e\u6d3b\u6cca", "background": "\u7a0b\u5e8f\u5458"},
     *     {"name": "\u5c0f\u7ea2", "persona": "\u6e29\u67d4\u7ec6\u5fc3\u7684\u5973\u5b69", "voice": "\u8bf4\u8bdd\u8f7b\u58f0\u7ec6\u8bed", "background": "\u5b66\u751f"}
     *   ],
     *   "scene": "park",
     *   "player_name": "me"     // 可选（P0-1）：显式玩家名，同名 agent 标记为玩家控制；缺省按旧规则
     *   "map": { ... }          // 可选（P-0811-G）：契约 v1 地图 → collision 瓦片转障碍注入模拟世界（替代预置场景）
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/load-characters")
    public Map<String, Object> loadCharacters(@RequestBody Map<String, Object> body) {
        List<Map<String, String>> characterList = (List<Map<String, String>>) body.getOrDefault("characters", List.of());
        String sceneName = (String) body.getOrDefault("scene", "park");
        String playerName = body.get("player_name") != null ? String.valueOf(body.get("player_name")) : null;
        // P-0802-P2：可选 player_id —— 角色库改名后按 player_id 解析当前角色名标记 playerControlled
        //（无 player_id 或未绑定 → 零行为变化，走现状 playerName 逻辑）
        String playerId = body.get("player_id") != null ? String.valueOf(body.get("player_id")) : null;
        // P-0811-G：可选契约 v1 地图 → collision 瓦片转障碍注入模拟世界（动态模拟用 LLM 布局）
        List<Obstacle> customObstacles = null;
        String mapLabel = null;
        double mapWorldWidth = SimulationWorld.DEFAULT_WORLD_WIDTH;
        double mapWorldHeight = SimulationWorld.DEFAULT_WORLD_HEIGHT;
        WorldDefinition mapDefinition = null;
        List<WorldObject> customWorldObjects = List.of();
        if (body.get("map") instanceof Map<?, ?> m && !m.isEmpty()) {
            try {
                MapWorldDefinitionAdapter.AdaptedWorld adapted =
                        MapWorldDefinitionAdapter.adapt((Map<String, Object>) m);
                mapDefinition = adapted.definition();
                customWorldObjects = adapted.worldObjects();
                customObstacles = adapted.obstacles();
                mapWorldWidth = adapted.worldWidth();
                mapWorldHeight = adapted.worldHeight();
                mapLabel = adapted.definition().metadata().name();
            } catch (Exception e) {
                // 地图解析失败 → 回退预置场景（不阻塞加载）
                customObstacles = null;
                mapLabel = null;
                mapDefinition = null;
                customWorldObjects = List.of();
                log.warn("MapContract rejected, using legacy scene: {}", e.getMessage());
            }
        }

        List<Persona> personas = new ArrayList<>();
        for (Map<String, String> ch : characterList) {
            String name = ch.getOrDefault("name", "\u672a\u77e5");
            Persona p = new Persona(name);
            p.setPersonaDesc(ch.getOrDefault("persona", ""));
            p.setVoice(ch.getOrDefault("voice", ""));
            p.setBackground(ch.getOrDefault("background", ""));
            // P-0810-10：五层 persona 卡（导入卡优先，无则默认资源卡；已有 layer 不覆盖）
            characterController.attachPersonaCard(p);
            personas.add(p);
        }

        final List<Obstacle> selectedObstacles = customObstacles;
        final String selectedMapLabel = mapLabel;
        final double selectedWorldWidth = mapWorldWidth;
        final double selectedWorldHeight = mapWorldHeight;
        final WorldDefinition selectedDefinition = mapDefinition;
        final List<WorldObject> selectedWorldObjects = customWorldObjects;
        Runnable load = () -> {
            // 必须先清空旧角色，运行时边界才可安全替换；无地图则回退兼容默认尺寸。
            simulationService.clearAll();
            if (selectedDefinition != null) {
                world.loadWorldDefinition(selectedDefinition);
                selectedWorldObjects.forEach(world::registerWorldObject);
            } else {
                world.clearWorldDefinition();
                world.setWorldBounds(selectedWorldWidth, selectedWorldHeight);
            }
            simulationService.initWithPersonas(personas, sceneName, playerName, playerId, selectedObstacles, selectedMapLabel);
        };
        if (worldRuntime != null) worldRuntime.replaceSimulationWorld(load);
        else load.run();
        return Map.of("status", "ok", "message", "Loaded " + personas.size() + " characters into simulation");
    }

    /** P-0811-G：从契约 v1 地图 map 读取 collision 网格（int[height][width]）。解析失败返回 null。 */
    private int[][] mapCollisionGrid(Map<?, ?> m) {
        if (!(m.get("layers") instanceof Map<?, ?> layers)) return null;
        if (!(layers.get("collision") instanceof List<?> rows)) return null;
        int height = rows.size();
        int[][] grid = new int[height][];
        for (int i = 0; i < height; i++) {
            Object r = rows.get(i);
            if (!(r instanceof List<?> row)) return null;
            int[] g = new int[row.size()];
            for (int j = 0; j < row.size(); j++) {
                Object v = row.get(j);
                g[j] = (v instanceof Number num) ? num.intValue() : 0;
            }
            grid[i] = g;
        }
        return grid;
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        simulationService.start();
        return Map.of("status", "ok", "message", "Simulation started");
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        simulationService.stop();
        return Map.of("status", "ok", "message", "Simulation stopped");
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        if (worldRuntime != null) worldRuntime.replaceSimulationWorld(simulationService::clearAll);
        else simulationService.clearAll();
        return Map.of("status", "ok", "message", "Simulation reset");
    }

    @GetMapping("/state")
    public Map<String, Object> getState() {
        Map<String, Object> result = new LinkedHashMap<>(simulationService.getState());
        Map<String, Object> snapshot = world.snapshotNow().toMap();
        result.put("agents", snapshot.getOrDefault("agents", List.of()));
        result.put("worldObjects", snapshot.getOrDefault("worldObjects", List.of()));
        result.put("worldVersion", world.worldVersion());
        result.put("worldWidth", world.getWorldWidth());
        result.put("worldHeight", world.getWorldHeight());
        return result;
    }

    /** Shared 2D/3D gameplay projection for one character. */
    @GetMapping("/gameplay/{agentName}")
    public ResponseEntity<?> gameplay(@PathVariable String agentName) {
        try { return ResponseEntity.ok(world.gameplaySnapshot(agentName)); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(404).body(Map.of("error", e.getMessage())); }
    }

    /**
     * Narrow compatibility adapter into the tick-owned Action FSM.
     * SELF may only act for the player-controlled entity; MASTER is the local director capability.
     */
    @PostMapping("/actions")
    public ResponseEntity<?> action(@RequestBody(required = false) Map<String, Object> body,
                                    @RequestHeader(value = "X-Gameplay-Master-Key", defaultValue = "") String masterKey) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "body required"));
        String actorId = text(body.get("actor_id"));
        AgentState actor = world.getState(actorId);
        if (actor == null) return ResponseEntity.status(404).body(Map.of("error", "actor not found"));
        String capability = text(body.getOrDefault("capability", "SELF")).toUpperCase(Locale.ROOT);
        ActionSource source;
        if ("MASTER".equals(capability)) {
            if (gameplayMasterKey == null || gameplayMasterKey.isBlank()
                    || !java.security.MessageDigest.isEqual(gameplayMasterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    masterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return ResponseEntity.status(403).body(Map.of("error", "MASTER capability is disabled or key is invalid"));
            }
            source = ActionSource.ENGINE;
        }
        else {
            if (!actor.isPlayerControlled()) {
                return ResponseEntity.status(403).body(Map.of("error", "SELF may only control the player entity"));
            }
            source = ActionSource.PLAYER_INPUT;
        }
        ActionType action;
        try { action = ActionType.valueOf(text(body.get("action")).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", "unsupported action")); }
        String intentId = text(body.getOrDefault("intent_id", UUID.randomUUID().toString()));
        long basedOn = body.get("based_on_world_version") instanceof Number n ? n.longValue() : world.worldVersion();
        long expires = body.get("expires_at_millis") instanceof Number n ? n.longValue() : System.currentTimeMillis() + 5_000;
        Map<String, Object> parameters = objectMap(body.get("parameters"));
        if (action == ActionType.ADJUST_STAT && "define".equalsIgnoreCase(text(parameters.get("operation")))
                && source != ActionSource.ENGINE) {
            return ResponseEntity.status(403).body(Map.of("error", "only MASTER may define metrics"));
        }
        ActionIntent intent = new ActionIntent(intentId, actorId, source, action,
                text(body.get("target_id")), basedOn, expires, parameters);
        var future = world.enqueueAction(intent);
        try {
            ActionResult result = future.get(900, TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(result);
        } catch (TimeoutException e) {
            return ResponseEntity.accepted().body(Map.of("intentId", intentId, "status", "ACCEPTED",
                    "worldVersion", world.worldVersion()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage() == null ? "action failed" : e.getMessage()));
        }
    }

    /** Main-controller/self quantitative mutation, committed by the same Action FSM. */
    @PostMapping("/gameplay/{agentName}/metrics/{metricKey}")
    public ResponseEntity<?> mutateMetric(@PathVariable String agentName, @PathVariable String metricKey,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          @RequestHeader(value = "X-Gameplay-Master-Key", defaultValue = "") String masterKey) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("actor_id", agentName);
        request.put("action", ActionType.ADJUST_STAT.name());
        request.put("capability", body == null ? "SELF" : body.getOrDefault("capability", "SELF"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("key", metricKey);
        parameters.put("operation", body == null ? "adjust" : body.getOrDefault("operation", "adjust"));
        parameters.put("value", body == null ? 0 : body.getOrDefault("value", 0));
        parameters.put("reason", body == null ? "" : body.getOrDefault("reason", ""));
        if (body != null) {
            for (String key : List.of("label", "min", "max", "unit")) {
                if (body.get(key) != null) parameters.put(key, body.get(key));
            }
        }
        request.put("parameters", parameters);
        return action(request, masterKey);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /** 返回一般模式 AI 社会实验的确定性起始地图，供前端加载或作为 load-characters 的 map 参数。 */
    @GetMapping("/social-map")
    public Map<String, Object> getSocialExperimentMap() {
        return SocialExperimentMap.generate();
    }

    /** 动态加入一般模式 2D 社会实验。 */
    @PostMapping("/agent")
    public Map<String, Object> addAgent(@RequestBody Map<String, Object> body) {
        String name = body == null ? "" : String.valueOf(body.getOrDefault("name", ""));
        String persona = body == null ? "" : String.valueOf(body.getOrDefault("persona", ""));
        if (worldRuntime != null && worldRuntime.isManagedAgentName(name)) {
            return Map.of("status", "error", "message", "Agent name is owned by lifecycle manager");
        }
        return simulationService.addSocialAgent(name, persona);
    }

    /** 动态移除一般模式 2D 社会实验角色，并清理关系/记忆/目标。 */
    @DeleteMapping("/agent/{agentName}")
    public Map<String, Object> removeAgent(@PathVariable String agentName) {
        if (worldRuntime != null && worldRuntime.isManagedAgentName(agentName)) {
            return Map.of("status", "error", "message", "Managed agent must retire through world lifecycle");
        }
        return simulationService.removeSocialAgent(agentName);
    }

    @GetMapping("/social")
    public Map<String, Object> getSocialState() {
        return simulationService.getSocialState();
    }

    @GetMapping("/social/{agentName}")
    public Map<String, Object> getAgentSocialState(@PathVariable String agentName) {
        return simulationService.getSocialState(agentName);
    }

    /** 给角色注入可观测的社会目标，可带 targetAgent 触发寻找某人的目标语义。 */
    @PostMapping("/social/{agentName}/goal")
    public Map<String, Object> setSocialGoal(@PathVariable String agentName,
                                               @RequestBody Map<String, String> body) {
        if (!simulationService.hasAgent(agentName)) {
            return Map.of("status", "error", "message", "Agent not found");
        }
        simulationService.setSocialGoal(agentName,
                body == null ? "" : body.getOrDefault("goal", ""),
                body == null ? "" : body.getOrDefault("targetAgent", ""));
        return Map.of("status", "ok", "agent", agentName);
    }

    @DeleteMapping("/social/{agentName}/goal")
    public Map<String, Object> clearSocialGoal(@PathVariable String agentName) {
        if (!simulationService.hasAgent(agentName)) {
            return Map.of("status", "error", "message", "Agent not found");
        }
        simulationService.clearSocialGoal(agentName);
        return Map.of("status", "ok", "agent", agentName);
    }

    @PostMapping("/send/{agentName}")
    public Map<String, Object> sendMessage(
            @PathVariable String agentName,
            @RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        simulationService.sendUserMessage(agentName, message);
        String focusedRoleId = body.getOrDefault("focused_role_id", "").trim();
        String interactionId = body.getOrDefault("interaction_id", "").trim();
        if (worldRuntime != null && !message.isBlank() && !focusedRoleId.isBlank()
                && !interactionId.isBlank()) {
            try {
                worldRuntime.interactOnce(WorldRuntimeService.SIMULATION_SESSION, focusedRoleId,
                        com.roleplay.engine.service.world.RoleInteractionKind.DIALOGUE, interactionId);
            } catch (IllegalArgumentException ignored) {
                // 焦点群演可能已被其他命令晋升/退场；不回滚已接受的玩家发言。
            }
        }
        return Map.of("status", "ok");
    }

    @PostMapping("/move/{agentName}")
    public Map<String, Object> moveAgent(
            @PathVariable String agentName,
            @RequestBody Map<String, Double> body) {
        AgentState state = world.getState(agentName);
        if (state == null) return Map.of("status", "error", "message", "Agent not found");
        double x = body.getOrDefault("x", state.getX());
        double y = body.getOrDefault("y", state.getY());
        x = Math.max(10, Math.min(world.getWorldWidth() - 10, x));
        y = Math.max(10, Math.min(world.getWorldHeight() - 10, y));
        state.setX(x);
        state.setY(y);
        state.clearTarget();
        return Map.of("status", "ok");
    }

    @PostMapping("/target/{agentName}")
    public Map<String, Object> setTarget(
            @PathVariable String agentName,
            @RequestBody Map<String, Object> body) {
        AgentState state = world.getState(agentName);
        if (state == null) return Map.of("status", "error", "message", "Agent not found");
        String floorId = body.get("floorId") == null ? state.navLocation().floorId() : String.valueOf(body.get("floorId"));
        if (!floorId.equals(state.navLocation().floorId())) {
            return Map.of("status", "error", "message", "cross-floor click targets are forbidden; use a legal connector");
        }
        double x = body.get("x") instanceof Number n ? n.doubleValue() : state.getX();
        double y = body.get("y") instanceof Number n ? n.doubleValue() : state.getY();
        x = Math.max(10, Math.min(world.getWorldWidth() - 10, x));
        y = Math.max(10, Math.min(world.getWorldHeight() - 10, y));
        state.setPlayerIntentTarget(x, y);
        return Map.of("status", "ok");
    }

    /**
     * P-0814-I：持续方向移动（WASD/方向键）——玩家按住方向键时前端高频调用（约 120ms 一次）。
     * <pre>{@code POST /api/simulation/move-dir/{agentName}  {"dx":1,"dy":0,"step":90}}
     * 语义：以服务端权威坐标为准，目标点 = 当前坐标 + 归一化方向 × 步长（默认 90px，上限 200px），
     * 并置位 manualTarget（每次调用刷新时间戳 → 持续按住时不会被 MANUAL_TARGET_HOLD_MS 60s
     * 超时释放给导演接管）；松开方向键即停止调用 → 60s 后恢复导演接管（既有语义）。</pre>
     */
    @PostMapping("/move-dir/{agentName}")
    public Map<String, Object> moveDir(
            @PathVariable String agentName,
            @RequestBody Map<String, Double> body) {
        AgentState state = world.getState(agentName);
        if (state == null) return Map.of("status", "error", "message", "Agent not found");
        double dx = body.getOrDefault("dx", 0.0);
        double dy = body.getOrDefault("dy", 0.0);
        double step = body.getOrDefault("step", 0.0);
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01) {
            // P-0816-C：零方向 = 停止移动——清除目标（hasTarget=false + manualTarget=false），
            // 角色立即静止（原实现仅刷新时间戳，松开方向键后角色继续滑向最后目标点 = 主人反馈的
            // 「用户角色乱动」根因之一；现在松开 WASD 前端即发零方向停止信号）。
            state.clearTarget();
            return Map.of("status", "ok", "stopped", true);
        }
        if (step <= 0) step = 90.0;
        step = Math.min(step, 200.0);
        double nx = dx / len;
        double ny = dy / len;
        double tx = state.getX() + nx * step;
        double ty = state.getY() + ny * step;
        tx = Math.max(10, Math.min(world.getWorldWidth() - 10, tx));
        ty = Math.max(10, Math.min(world.getWorldHeight() - 10, ty));
        state.setTargetX(tx);
        state.setTargetY(ty);
        state.setHasTarget(true);
        // P-0820-R：方向键保存确定性方向，MovementSystem 直接执行，不经过惯性/斥力积分。
        state.setManualDirection(nx, ny);
        // 手动目标标记：MovementConstraint/导演/日程均跳过（D-006/P-0813-E），时间戳随每次调用刷新
        state.setManualTarget(true);
        return Map.of("status", "ok", "targetX", Math.round(tx * 100.0) / 100.0, "targetY", Math.round(ty * 100.0) / 100.0);
    }

    @PostMapping("/emotion/{agentName}")
    public Map<String, Object> setEmotion(
            @PathVariable String agentName,
            @RequestBody Map<String, String> body) {
        AgentState state = world.getState(agentName);
        if (state == null) return Map.of("status", "error", "message", "Agent not found");
        try {
            Emotion emotion = Emotion.valueOf(body.getOrDefault("emotion", "NEUTRAL").toUpperCase());
            state.setEmotion(emotion);
            return Map.of("status", "ok");
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "message", "Invalid emotion");
        }
    }

    @PostMapping("/config/{agentName}")
    public Map<String, Object> configAgent(
            @PathVariable String agentName,
            @RequestBody Map<String, Double> body) {
        AgentState state = world.getState(agentName);
        if (state == null) return Map.of("status", "error", "message", "Agent not found");
        if (body.containsKey("hearRange")) state.setHearRange(body.get("hearRange"));
        if (body.containsKey("moveSpeed")) state.setMoveSpeed(body.get("moveSpeed"));
        return Map.of("status", "ok", "hearRange", state.getHearRange(), "moveSpeed", state.getMoveSpeed());
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventStream() {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        // Send initial snapshot
        List<SimulationWorld.WorldSnapshot> recent = new ArrayList<>(recentSnapshots);
        if (!recent.isEmpty()) {
            try {
                emitter.send(SseEmitter.event()
                    .name("world_snapshot")
                    .data(recent.get(recent.size() - 1).toMap(), MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
        return emitter;
    }

    private void broadcastToAll(Map<String, Object> data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("world_snapshot")
                    .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    @PostMapping("/directive")
    public Map<String, Object> sendDirective(@RequestBody Map<String, String> body) {
        String directive = body.getOrDefault("directive", "");
        if (directive.isBlank()) return Map.of("status", "error", "message", "Empty directive");
        simulationService.sendUserDirective(directive);
        return Map.of("status", "ok", "message", "Directive sent to director");
    }

    /**
     * AI 自动演讲/广播触发（演讲与广播合并地基 demo 入口）。
     * <pre>{@code POST /api/simulation/speech  {"speaker":"小明","text":"...","mode":"auto"}}
     * speaker/text 省略时自动选第一个 NPC + 演示文案；
     * 形态由系统自动判定（ModeClassifier.wouldOthersListen：有听众→演讲 area，无听众→全局广播）。</pre>
     */
    @PostMapping("/speech")
    public Map<String, Object> aiSpeech(@RequestBody(required = false) Map<String, String> body) {
        String speaker = body != null ? body.getOrDefault("speaker", "") : "";
        String text = body != null ? body.getOrDefault("text", "") : "";
        return simulationService.publishAiSpeech(speaker, text);
    }

    @PostMapping("/scene/{sceneName}")
    public Map<String, Object> setScene(@PathVariable String sceneName) {
        if (!Obstacle.availableScenes().contains(sceneName.toLowerCase())) {
            return Map.of("status", "error", "message", "Unknown scene: " + sceneName,
                    "available", Obstacle.availableScenes());
        }
        world.setScene(sceneName.toLowerCase());
        return Map.of("status", "ok", "message", "Scene set to " + sceneName);
    }

    @GetMapping("/scenes")
    public Map<String, Object> getScenes() {
        return Map.of("scenes", Obstacle.availableScenes(), "current", world.getCurrentScene());
    }

    @GetMapping("/conversation-status")
    public Map<String, Object> getConversationStatus() {
        return simulationService.getConversationStatus();
    }

    /**
     * P-0814-A：点击驱动对话模式 —— 前端「播出完毕」信号（一轮展示完成 → 请求生成下一轮）。
     * <pre>{@code POST /api/simulation/playback_done
     * body: {"session_id":"...", "group_id":"..."}}  // 两者至少一者有效
     *  - group_id 非空 → 2D 世界对话组推进（ConversationManager 轮间门：该组下一轮）；
     *  - 否则 → 一般模式 RouterService 轮次推进（onPlaybackDone：下一轮）。
     * 200 → {"ok":true,"advanced":true|false}（advanced=false 时含 error：组不存在/未等待/重复信号等）</pre>
     * 幂等：非等待态重复信号被忽略（不产生多余轮次）。
     */
    @PostMapping("/playback_done")
    public Map<String, Object> playbackDone(@RequestBody(required = false) Map<String, Object> body) {
        String groupId = body != null && body.get("group_id") != null ? String.valueOf(body.get("group_id")) : null;
        String sessionId = body != null && body.get("session_id") != null ? String.valueOf(body.get("session_id")) : null;

        if (groupId != null && !groupId.isBlank()) {
            boolean ok = simulationService.notifyPlaybackDone(groupId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("group_id", groupId);
            m.put("advanced", ok);
            if (!ok) m.put("error", "group not found or not awaiting playback");
            return m;
        }

        if (sessions == null) {
            return Map.of("ok", false, "advanced", false, "error", "session registry unavailable");
        }
        RouterService router = sessions.get(sessionId);
        boolean ok = router != null && router.onPlaybackDone();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("advanced", ok);
        if (!ok) m.put("error", "not awaiting playback (already advanced / non-general mode / stopped)");
        return m;
    }

    /**
     * 方案A（轨道系统用户加入）：玩家加入现有对话组。
     * <pre>{@code POST /api/simulation/group/{groupId}/join  {"player_name":"me"}}
     * 200 → {"status":"ok","group":{"id":...,"mode":...,"participants":[...]}}
     * 4xx → {"status":"error","message":"..."}（组不存在 / 角色不存在或不在场 / 已在组 / 组已满）</pre>
     * 加入后 conversation-status（GET /api/simulation/conversation-status）的 participants 同步反映。
     */
    @PostMapping("/group/{groupId}/join")
    public Map<String, Object> joinConversationGroup(
            @PathVariable String groupId,
            @RequestBody(required = false) Map<String, String> body) {
        String playerName = body != null ? body.getOrDefault("player_name", "") : "";
        if (playerName.isBlank()) {
            return Map.of("status", "error", "message", "player_name required");
        }
        return joinResultMap(simulationService.joinGroup(groupId, playerName));
    }

    /**
     * 方案A（轨道系统用户加入）：玩家离开对话组（组内无人时自动解散）。
     * <pre>{@code POST /api/simulation/group/{groupId}/leave  {"player_name":"me"}}</pre>
     */
    @PostMapping("/group/{groupId}/leave")
    public Map<String, Object> leaveConversationGroup(
            @PathVariable String groupId,
            @RequestBody(required = false) Map<String, String> body) {
        String playerName = body != null ? body.getOrDefault("player_name", "") : "";
        if (playerName.isBlank()) {
            return Map.of("status", "error", "message", "player_name required");
        }
        return joinResultMap(simulationService.leaveGroup(groupId, playerName));
    }

    /** 方案A：join/leave 结果 → 既有端点风格响应（status ok/error + 组信息）。 */
    private Map<String, Object> joinResultMap(ConversationManager.JoinResult result) {
        if (!result.success()) {
            return Map.of("status", "error", "message", result.message());
        }
        Map<String, Object> groupInfo = new LinkedHashMap<>();
        groupInfo.put("id", result.group().getGroupId());
        groupInfo.put("mode", result.group().getMode().name());
        groupInfo.put("participants", result.group().getParticipantList().stream()
                .map(AgentState::getAgentName).toList());
        return Map.of("status", "ok", "message", result.message(), "group", groupInfo);
    }

    @GetMapping("/conversations")
    public Object getConversations() {
        return world.getRecentConversations();
    }

    // ── Phase 4: Track REST 暴露 ────────────────────────────────

    /**
     * World Director 手动目标注入。
     * <pre>{@code POST /api/simulation/track/goal  {"agent":"小明","goal":"调查"}}
     * goal 为空字符串 → 清除该角色的手动目标，恢复规则驱动。</pre>
     */
    @PostMapping("/track/goal")
    public Map<String, Object> setTrackGoal(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) return Map.of("status", "error", "message", "body required");
        String agent = body.getOrDefault("agent", "");
        String goal = body.getOrDefault("goal", "");
        if (agent.isBlank()) {
            return Map.of("status", "error", "message", "agent required");
        }
        if (goal.isBlank()) {
            simulationService.clearTrackGoal(agent);
            return Map.of("status", "ok", "message", "Goal cleared for " + agent);
        }
        simulationService.setTrackGoal(agent, goal);
        return Map.of("status", "ok", "message", "Goal set for " + agent, "agent", agent, "goal", goal);
    }

    /**
     * 秘密任务注入（成员强制 ISOLATED）。
     * <pre>{@code POST /api/simulation/track/secret  {"agents":["阿杰","小林"]}}</pre>
     * 传入空数组可清除全部秘密任务。
     */
    @PostMapping("/track/secret")
    public Map<String, Object> setSecretAgents(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Map.of("status", "error", "message", "body required");
        Object agents = body.getOrDefault("agents", List.of());
        List<String> names = new ArrayList<>();
        if (agents instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) names.add(String.valueOf(o));
            }
        }
        simulationService.setSecretAgents(new java.util.LinkedHashSet<>(names));
        return Map.of("status", "ok", "secret_agents", names);
    }

    /**
     * 轨道状态汇总：目标 / 秘密任务 / 最近 TrackScore / 轨道分配摘要。
     * <pre>{@code GET /api/simulation/track/state →
     * {"goals":{...},"secret_agents":[...],"last_score":{...},"assignments":{...}}}</pre>
     */
    @GetMapping("/track/state")
    public Map<String, Object> getTrackState() {
        return simulationService.getTrackState();
    }
}
