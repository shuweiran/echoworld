package com.roleplay.engine.controller;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTask;
import com.roleplay.engine.interrupt.AgentTaskStatus;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TaskType;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptService;
import com.roleplay.engine.service.PrivateChatService;
import com.roleplay.engine.service.SessionRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Main session endpoints — the primary conversational interface.
 * Maps from Python api/routes_session.py (700+ lines, the largest route file).
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    private final RouterService router;
    private final ScriptService scriptService;
    private final PrivateChatService privateChatService;
    private final CharacterController characterController;
    private final SceneController sceneController;
    /** D1: 中断管理器 —— /api/interrupt 按 Task ID 取消 + 状态查询。 */
    private final InterruptManager interruptManager;
    /** D11: 按 session_id 的 router 实例管理 —— 多会话隔离（替代原只写不读的 sessions map）。 */
    private final SessionRegistry sessions;

    public SessionController(RouterService router, ScriptService scriptService,
                             PrivateChatService privateChatService,
                             CharacterController characterController,
                             SceneController sceneController,
                             InterruptManager interruptManager,
                             SessionRegistry sessions) {
        this.router = router;
        this.scriptService = scriptService;
        this.privateChatService = privateChatService;
        this.characterController = characterController;
        this.sceneController = sceneController;
        this.interruptManager = interruptManager;
        this.sessions = sessions;
    }

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getState(@RequestParam(required = false) String session_id) {
        RouterService r = sessions.get(session_id);
        Map<String, Object> state = new LinkedHashMap<>(r.getState());
        state.put("characters", characterController.getAll());
        state.put("scenes", sceneController.getAll());
        return ResponseEntity.ok(state);
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> initialize(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> characterList = (List<Map<String, String>>) body.getOrDefault("characters", List.of());
        List<Persona> personas = new ArrayList<>();
        for (Map<String, String> ch : characterList) {
            Persona p = new Persona(ch.getOrDefault("name", "未知"));
            p.setPersonaDesc(ch.getOrDefault("persona", ""));
            p.setVoice(ch.getOrDefault("voice", ""));
            p.setBackground(ch.getOrDefault("background", ""));
            personas.add(p);
        }
        if (personas.isEmpty()) {
            personas.add(new Persona("助手", "一个友好的助手"));
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        // D11: 按 session_id 创建/获取独立 router 实例（多会话隔离）
        RouterService sessionRouter = sessions.getOrCreate(sessionId);
        sessionRouter.initSession(sessionId, personas,
            (String) body.getOrDefault("scene", "默认场景"),
            (String) body.getOrDefault("mode", "free"),
            (String) body.getOrDefault("protagonist", ""),
            (String) body.getOrDefault("director_character", ""));
        // 向后兼容：默认单例 router 同步初始化（未传 session_id 的旧客户端仍走默认会话）
        router.initSession(sessionId, personas,
            (String) body.getOrDefault("scene", "默认场景"),
            (String) body.getOrDefault("mode", "free"),
            (String) body.getOrDefault("protagonist", ""),
            (String) body.getOrDefault("director_character", ""));

        return ResponseEntity.ok(Map.of(
            "session_id", sessionId,
            "status", "initialized",
            "agents", personas.stream().map(Persona::getName).toList()
        ));
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", body.getOrDefault("text", ""));
        // D11: 按 session_id 路由到对应会话实例；未传 → 默认单例（向后兼容）
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        RouterService r = sessions.get(sessionId);
        RouterService.RoundResult result = r.runRound(message, null);
        return ResponseEntity.ok(Map.of(
            "status", result.status,
            "agent_outputs", result.agentOutputs,
            "narration", result.integration.getOrDefault("narration", ""),
            "reasoning", result.reasoning
        ));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop(@RequestBody(required = false) Map<String, String> body) {
        // D11: 支持按 session_id 停止指定会话；未传 → 默认单例
        String sessionId = body != null ? String.valueOf(body.getOrDefault("session_id", "")).trim() : "";
        RouterService r = sessions.get(sessionId);
        // D1: router.stop() 除置位停止标志外，还会硬停止所有进行中的生成任务
        r.stop();
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    // ═══════════════════════════════════════════════════════════
    //  D1: 中断系统 API（需求文档第八条：按 Task ID 取消 / 查询任务状态）
    // ═══════════════════════════════════════════════════════════

    /**
     * 中断请求。取消优先级：task_id &gt; agent &gt; type &gt; 全部。
     * <pre>{@code
     * POST /api/interrupt
     * {"task_id":"小明_dialogue_3", "stop_type":"hard"|"soft"|"state", "reason":"玩家打断"}
     * {"agent":"小明", "stop_type":"soft"}
     * {"type":"dialogue", "stop_type":"state"}
     * {} → 取消全部
     * }</pre>
     */
    @PostMapping("/interrupt")
    public ResponseEntity<Map<String, Object>> interrupt(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        StopType stopType = StopType.fromString((String) body.getOrDefault("stop_type", "hard"));
        String reason = String.valueOf(body.getOrDefault("reason", "API 中断请求"));
        // D21: null 值（JSON 显式传 null）按缺省处理，避免 String.valueOf(null)="null" 误匹配
        String taskId = body.get("task_id") != null ? String.valueOf(body.get("task_id")) : "";
        String agent = body.get("agent") != null ? String.valueOf(body.get("agent")) : "";
        String typeStr = body.get("type") != null ? String.valueOf(body.get("type")) : "";

        List<AgentTask> cancelled = new ArrayList<>();
        if (!taskId.isBlank()) {
            AgentTask t = interruptManager.cancel(taskId, stopType, reason);
            if (t != null) cancelled.add(t);
        } else if (!agent.isBlank()) {
            cancelled.addAll(interruptManager.cancelAgent(agent, stopType, reason));
        } else if (!typeStr.isBlank()) {
            cancelled.addAll(interruptManager.cancelByType(TaskType.fromString(typeStr), stopType, reason));
        } else {
            cancelled.addAll(interruptManager.cancelAll(stopType, reason));
        }

        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "stop_type", stopType.name(),
            "cancelled_count", cancelled.size(),
            "cancelled", cancelled.stream().map(AgentTask::toMap).toList()
        ));
    }

    /** 任务状态列表（可按 agent / type / status 过滤；type 缺省不过滤，D21）。 */
    @GetMapping("/interrupt/tasks")
    public ResponseEntity<Map<String, Object>> listInterruptTasks(
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        AgentTaskStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = AgentTaskStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // 非法 status 过滤参数 → 不过滤
            }
        }
        // D21: 无 type 参数 → 不过滤（返回全部类型）；非法 type 同样视为不过滤，
        // 避免 TaskType.fromString 的 GENERATION 兜底导致 DIALOGUE 等任务不可见
        TaskType typeFilter = null;
        if (type != null && !type.isBlank()) {
            try {
                typeFilter = TaskType.valueOf(type.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // 非法 type 过滤参数 → 不过滤
            }
        }
        List<AgentTask> tasks = interruptManager.listTasks(agent, typeFilter, statusFilter);
        return ResponseEntity.ok(Map.of(
            "active", interruptManager.activeTaskCount(),
            "count", tasks.size(),
            "tasks", tasks.stream().map(AgentTask::toMap).toList()
        ));
    }

    /** 单个任务状态（含历史归档任务）。 */
    @GetMapping("/interrupt/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getInterruptTask(@PathVariable String taskId) {
        AgentTask t = interruptManager.getTask(taskId);
        if (t == null) {
            return ResponseEntity.status(404).body(Map.of("status", "not_found", "task_id", taskId));
        }
        return ResponseEntity.ok(t.toMap());
    }

    @PostMapping("/auto")
    public ResponseEntity<Map<String, Object>> startAuto(@RequestBody Map<String, Object> body) {
        int rounds = ((Number) body.getOrDefault("rounds", 3)).intValue();
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        RouterService r = sessions.get(sessionId);
        List<RouterService.RoundResult> results = r.runAutoRounds(rounds);
        return ResponseEntity.ok(Map.of(
            "rounds", results.size(),
            "last_status", results.isEmpty() ? "" : results.get(results.size() - 1).status
        ));
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> setMode(@RequestBody Map<String, String> body) {
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        RouterService r = sessions.get(sessionId);
        r.setMode(body.getOrDefault("mode", "free"));
        String protagonist = body.getOrDefault("protagonist",
            body.getOrDefault("protagonist", ""));
        if (!protagonist.isEmpty()) r.setProtagonist(protagonist);
        String director = body.getOrDefault("director_character",
            body.getOrDefault("director", ""));
        if (!director.isEmpty()) r.setDirectorCharacter(director);
        return ResponseEntity.ok(Map.of("mode", r.getMode()));
    }

    @GetMapping("/mode")
    public ResponseEntity<Map<String, String>> getMode(@RequestParam(required = false) String session_id) {
        return ResponseEntity.ok(Map.of("mode", sessions.get(session_id).getMode()));
    }

    @PostMapping("/goals")
    public ResponseEntity<Map<String, Object>> setGoals(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> goals = (List<String>) body.getOrDefault("goals", List.of());
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        sessions.get(sessionId).setGoals(goals);
        return ResponseEntity.ok(Map.of("goals", goals));
    }

    @GetMapping("/goals")
    public ResponseEntity<Map<String, Object>> getGoals(@RequestParam(required = false) String session_id) {
        return ResponseEntity.ok(Map.of("goals", sessions.get(session_id).getGoals()));
    }

    @PostMapping("/agents")
    public ResponseEntity<Map<String, Object>> addAgent(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "新角色");
        String persona = body.getOrDefault("persona", "");
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        RouterService r = sessions.get(sessionId);
        r.addAgent(name, new Persona(name, persona));
        return ResponseEntity.ok(Map.of("status", "added", "name", name));
    }

    @DeleteMapping("/agents/{name}")
    public ResponseEntity<Map<String, Object>> removeAgent(@PathVariable String name,
                                                           @RequestParam(required = false) String session_id) {
        sessions.get(session_id).removeAgent(name);
        return ResponseEntity.ok(Map.of("status", "removed", "name", name));
    }

    // ── Voice toggle (inline in routes_session.py in Python) ──

    @GetMapping("/voice/toggle")
    public ResponseEntity<Map<String, Boolean>> getVoiceToggle() {
        return ResponseEntity.ok(Map.of("enabled", true));
    }

    @PostMapping("/voice/toggle")
    public ResponseEntity<Map<String, Boolean>> setVoiceToggle(@RequestBody Map<String, Boolean> body) {
        return ResponseEntity.ok(Map.of("enabled", body.getOrDefault("enabled", true)));
    }

    @PostMapping("/script/generate")
    public ResponseEntity<Map<String, Object>> generateScript(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> characters = (List<String>) body.getOrDefault("characters", List.of());
        Map<String, Object> script = scriptService.generateScript(
            (String) body.getOrDefault("theme", "默认主题"),
            characters);
        return ResponseEntity.ok(script);
    }

    @PostMapping("/private_chat/request")
    public ResponseEntity<Map<String, Object>> requestPrivateChat(@RequestBody Map<String, String> body) {
        String from = body.getOrDefault("from", "");
        String to = body.getOrDefault("to", "");
        String message = body.getOrDefault("message", "");
        return ResponseEntity.ok(privateChatService.requestChat(from, to, message));
    }

    @PostMapping("/private_chat/reply")
    public ResponseEntity<Map<String, Object>> replyPrivateChat(@RequestBody Map<String, String> body) {
        String from = body.getOrDefault("from", "");
        String to = body.getOrDefault("to", "");
        boolean accept = Boolean.parseBoolean(body.getOrDefault("accept", "true"));
        return ResponseEntity.ok(privateChatService.reply(from, to, accept));
    }

    @PostMapping("/private_chat/send")
    public ResponseEntity<Map<String, Object>> sendPrivateChat(@RequestBody Map<String, String> body) {
        String from = body.getOrDefault("from", "");
        String to = body.getOrDefault("to", "");
        String content = body.getOrDefault("content", "");
        return ResponseEntity.ok(privateChatService.sendMessage(from, to, content));
    }
}
