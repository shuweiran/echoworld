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
        // P-0810-16：场景目标视图随 /api/state 下发（前端场景卡进入/刷新时拉取；无目标时 enabled=false 零影响）
        state.put("scene_goals", r.getSceneGoalsView());
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
            // P-0810-10：五层 persona 卡（导入卡优先，无则默认资源卡；已有 layer 不覆盖）
            characterController.attachPersonaCard(p);
            personas.add(p);
        }
        if (personas.isEmpty()) {
            // P-0810-21-C：空 characters 不再兜底「助手」——用户实测「没选助手但对局里出现助手」根因
            // （GalLivePanel「空角色」预设 characters:[] 触发旧兜底）；与 startScene 一致返回 400，
            // 前端须显式传至少一个角色。旧兜底行为移除，测试无依赖（已核查）。
            return ResponseEntity.badRequest().body(Map.of("error", "至少需要一个角色"));
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        String sceneDesc = (String) body.getOrDefault("scene", "默认场景");
        String mode = (String) body.getOrDefault("mode", "free");
        // D11: 按 session_id 创建/获取独立 router 实例（多会话隔离）
        RouterService sessionRouter = sessions.getOrCreate(sessionId);
        sessionRouter.initSession(sessionId, personas, sceneDesc, mode,
            (String) body.getOrDefault("protagonist", ""),
            (String) body.getOrDefault("director_character", ""));
        // 向后兼容：默认单例 router 同步初始化（未传 session_id 的旧客户端仍走默认会话）
        router.initSession(sessionId, personas, sceneDesc, mode,
            (String) body.getOrDefault("protagonist", ""),
            (String) body.getOrDefault("director_character", ""));
        // P-0810-09：一般模式 init（含 scene）时确保场景目标集 —— scene_id 用于 DB 目标装载/回写（可选），
        // player_goal 为玩家自定义目标（可选，缺省 LLM 生成）；生成失败规则兜底恒不抛。
        String sceneId = body.get("scene_id") != null ? String.valueOf(body.get("scene_id")).trim() : "";
        String playerGoal = body.get("player_goal") != null ? String.valueOf(body.get("player_goal")).trim() : "";
        sessionRouter.ensureSceneGoals(sceneId, sceneDesc, playerGoal);
        // P-0810-14：起局后自动触发第一轮（AI 开场白）—— 仅一般模式生效，异步不阻塞 init 响应
        sessionRouter.triggerAutoFirstRound();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("session_id", sessionId);
        resp.put("status", "initialized");
        resp.put("agents", personas.stream().map(Persona::getName).toList());
        // P-0810-09：目标列表随 init 返回（玩家目标明文，AI 目标 ?? 占位+数量；无目标时 goals.enabled=false）
        resp.put("goals", sessionRouter.getSceneGoalsView());
        // P-0810-21：玩家角色回传 —— 与 GET /api/state 的 protagonist 字段同源（保持一致性），
        // 前端据此确认玩家角色已注入。mode=protagonist 时额外回传 your_role/player_name（同值）；
        // 非主角模式仅回 protagonist（空串=未设置，附加键零破坏）。getState 可能为 null（测试 mock），null 守卫。
        Map<String, Object> routerState = sessionRouter.getState();
        String protagonist = "";
        if (routerState != null && routerState.get("protagonist") != null) {
            protagonist = String.valueOf(routerState.get("protagonist"));
        }
        resp.put("protagonist", protagonist);
        if ("protagonist".equals(mode)) {
            resp.put("your_role", protagonist);
            resp.put("player_name", protagonist);
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", body.getOrDefault("text", ""));
        // D11: 按 session_id 路由到对应会话实例；未传 → 默认单例（向后兼容）
        String sessionId = String.valueOf(body.getOrDefault("session_id", "")).trim();
        // P0-2：读取前端传来的 player_name —— 命中 agent 名单时该角色直接发言（角色说），
        // 否则主控旁白（原来完全忽略 player_name，导致 me 发消息恒变主控）
        String playerName = String.valueOf(body.getOrDefault("player_name", "")).trim();
        // P-0802-P2：读取前端 player_id（可选）—— 角色库改名后按 player_id 解析当前角色名
        // 豁免主控代声（无 player_id 或未绑定 → 零行为变化，走现状 player_name 逻辑）
        String playerId = String.valueOf(body.getOrDefault("player_id", "")).trim();
        RouterService r = sessions.get(sessionId);
        RouterService.RoundResult result = r.runRound(message, null, playerName, playerId);
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
