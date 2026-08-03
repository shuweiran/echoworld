package com.roleplay.engine.simulation;

import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.conversation.ConversationManager;
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

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationService simulationService;
    private final SimulationWorld world;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SimulationWorld.WorldSnapshot> recentSnapshots = new CopyOnWriteArrayList<>();
    private static final int MAX_RECENT_SNAPSHOTS = 100;

    public SimulationController(SimulationService simulationService, SimulationWorld world) {
        this.simulationService = simulationService;
        this.world = world;

        world.addTickListener(snapshot -> {
            Map<String, Object> event = snapshot.toMap();
            event.put("type", "world_snapshot");
            broadcastToAll(event);
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
     *     {"name": "\u5c0f\u660e", "persona": "\u5f00\u6717\u5916\u5411\u7684\u5e74\u8f7b\u4eba", "voice": "\u8bf4\u8bdd\u8f7b\u677e\u6d3b\u6cfc", "background": "\u7a0b\u5e8f\u5458"},
     *     {"name": "\u5c0f\u7ea2", "persona": "\u6e29\u67d4\u7ec6\u5fc3\u7684\u5973\u5b69", "voice": "\u8bf4\u8bdd\u8f7b\u58f0\u7ec6\u8bed", "background": "\u5b66\u751f"}
     *   ],
     *   "scene": "park",
     *   "player_name": "me"     // 可选（P0-1）：显式玩家名，同名 agent 标记为玩家控制；缺省按旧规则
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

        List<Persona> personas = new ArrayList<>();
        for (Map<String, String> ch : characterList) {
            String name = ch.getOrDefault("name", "\u672a\u77e5");
            Persona p = new Persona(name);
            p.setPersonaDesc(ch.getOrDefault("persona", ""));
            p.setVoice(ch.getOrDefault("voice", ""));
            p.setBackground(ch.getOrDefault("background", ""));
            personas.add(p);
        }

        simulationService.initWithPersonas(personas, sceneName, playerName, playerId);
        return Map.of("status", "ok", "message", "Loaded " + personas.size() + " characters into simulation");
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
        simulationService.clearAll();
        return Map.of("status", "ok", "message", "Simulation reset");
    }

    @GetMapping("/state")
    public Map<String, Object> getState() {
        return simulationService.getState();
    }

    @PostMapping("/send/{agentName}")
    public Map<String, Object> sendMessage(
            @PathVariable String agentName,
            @RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        simulationService.sendUserMessage(agentName, message);
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
        x = Math.max(10, Math.min(SimulationWorld.WORLD_WIDTH - 10, x));
        y = Math.max(10, Math.min(SimulationWorld.WORLD_HEIGHT - 10, y));
        state.setX(x);
        state.setY(y);
        state.clearTarget();
        return Map.of("status", "ok");
    }

    @PostMapping("/target/{agentName}")
    public Map<String, Object> setTarget(
            @PathVariable String agentName,
            @RequestBody Map<String, Double> body) {
        AgentState state = world.getState(agentName);
        if (state == null) return Map.of("status", "error", "message", "Agent not found");
        double x = body.getOrDefault("x", state.getX());
        double y = body.getOrDefault("y", state.getY());
        x = Math.max(10, Math.min(SimulationWorld.WORLD_WIDTH - 10, x));
        y = Math.max(10, Math.min(SimulationWorld.WORLD_HEIGHT - 10, y));
        state.setTargetX(x);
        state.setTargetY(y);
        state.setHasTarget(true);
        // Phase 4: 手动目标标记——MovementConstraint 不得覆盖玩家手动指定。
        state.setManualTarget(true);
        return Map.of("status", "ok");
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
