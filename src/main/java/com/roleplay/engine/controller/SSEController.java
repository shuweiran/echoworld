package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.debug.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE (Server-Sent Events) streaming endpoint.
 * Uses Spring MVC SseEmitter — no WebFlux dependency needed.
 *
 * <p>Clients connect to /api/events and receive real-time updates
 * (agent output, round completions, system messages, etc.).
 *
 * <p>D8 fix: this controller used to be dead code — its {@code broadcast*}
 * methods had zero callers, so the frontend's 28 registered SSE listeners
 * (agent_output / round_complete / user_input / …) never fired. The round
 * pipeline ({@code RouterService}) now calls these broadcast methods at every
 * key node (round start, agent output, arbiter integration, round complete,
 * stop, …). The HTTP response body is unchanged — SSE is an additional
 * push channel, the two are dual-path compatible.
 *
 * <p>Event names + payload shapes follow the frontend (assets/index-*.js,
 * {@code new EventSource("/api/events")} listener table) and the Python
 * reference implementation (roleplay-v4/backend/api/routes_sse.py +
 * core/router.py). Payloads are rendered as JSON via Jackson (already on the
 * classpath), so content escaping is handled — no hand-rolled string JSON.
 */
@RestController
@RequestMapping("/api/events")
public class SSEController implements SseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SSEController.class);
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final long SSE_TIMEOUT_MS = 300_000; // 5 min

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    /** P-0802-I：emitter → 会话过滤（stream 带 ?session_id= 时注册；null = 不过滤，收全部全局广播）。 */
    private final ConcurrentHashMap<SseEmitter, String> emitterSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper mapper = new ObjectMapper();

    public SSEController() {
        // Start heartbeat
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * SSE event stream — the frontend's long-lived connection for real-time updates.
     *
     * <p>P-0802-I：可选 {@code session_id} 查询参数 —— 带会话标识的连接只接收该对局的
     * {@link #broadcastToSession} 定向事件（同时仍接收全部全局广播，如 agent_output/announcement）；
     * 不带时与旧版一致（全局广播全覆盖）。多客户端/多对局并发互不串扰。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "session_id", required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);
        if (sessionId != null && !sessionId.isBlank()) {
            emitterSessions.put(emitter, sessionId.trim());
        }

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            emitterSessions.remove(emitter);
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitterSessions.remove(emitter);
        });
        emitter.onError(e -> {
            emitters.remove(emitter);
            emitterSessions.remove(emitter);
        });

        return emitter;
    }

    private void sendHeartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                // 已超时/完成的 emitter send 抛 IllegalStateException（非 IOException），
                // 宽捕获防止死连接阻塞整个心跳循环
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Broadcast an event to all connected SSE clients.
     * {@code data} is serialized to a JSON string (Jackson handles escaping).
     */
    public void broadcast(String eventType, Object data) {
        // P-0809-B（API 逻辑链追踪）：SSE 事件打点——关联到当前请求链路（同一请求线程内
        // 触发的广播可关联；后台线程触发时 ThreadLocal 为空，自动跳过，属已知限制）
        TraceContext.recordSse(eventType, null);
        String json = serialize(eventType, data);
        if (json == null) return;
        for (SseEmitter emitter : emitters) {
            send(emitter, eventType, json);
        }
    }

    /**
     * P-0802-I：按会话定向推送 —— 仅投递给注册了该 {@code sessionId} 的连接（带 ?session_id= 建立的流）。
     * 无匹配连接时静默丢弃（事件本就是该对局专属，无人收听不广播）；sessionId 为空回退全局广播。
     */
    public void broadcastToSession(String sessionId, String eventType, Object data) {
        if (sessionId == null || sessionId.isBlank()) {
            broadcast(eventType, data);
            return;
        }
        // P-0809-B（API 逻辑链追踪）：定向广播同样打点（会话定向分支不经 broadcast()）
        TraceContext.recordSse(eventType, sessionId);
        String json = serialize(eventType, data);
        if (json == null) return;
        boolean delivered = false;
        for (Map.Entry<SseEmitter, String> e : emitterSessions.entrySet()) {
            if (sessionId.equals(e.getValue())) {
                if (send(e.getKey(), eventType, json)) delivered = true;
            }
        }
        if (!delivered) {
            log.debug("SSE targeted event {} for session {} dropped: no matching emitter", eventType, sessionId);
        }
    }

    /** 序列化载荷；失败返回 null（调用方跳过发送）。 */
    private String serialize(String eventType, Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("SSE serialization failed for event {}: {}", eventType, e.getMessage());
            return null;
        }
    }

    /** 发送单个事件到单个 emitter；死连接移除并返回 false。 */
    private boolean send(SseEmitter emitter, String eventType, String json) {
        try {
            emitter.send(SseEmitter.event()
                .name(eventType)
                .data(json, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            // 已超时/完成的 emitter send 抛 IllegalStateException（ResponseBodyEmitter has already
            // completed），宽捕获：移除死连接，避免中断整个广播循环（否则后续 emitter 收不到事件）
            emitters.remove(emitter);
            emitterSessions.remove(emitter);
            return false;
        }
    }

    // ── Typed broadcast helpers (payload shapes = frontend listeners) ──

    /** round_start → {round} */
    public void broadcastRoundStart(int round) {
        broadcast("round_start", Map.of("round", round));
    }

    /** arbiter_task → {round, tasks:[{agent_name, task}]} */
    public void broadcastArbiterTask(int round, List<Map<String, Object>> tasks) {
        broadcast("arbiter_task", Map.of("round", round, "tasks", tasks));
    }

    /** agent_output → {session_id?, agent_name, content, track_id, track_label, track_mode, visible_to} */
    public void broadcastAgentOutput(String agentName, String content, String trackId,
                                     String trackLabel, String trackMode, List<String> visibleTo) {
        broadcastAgentOutput(null, agentName, content, trackId, trackLabel, trackMode, visibleTo);
    }

    /**
     * P-0811-G(B-2)：带会话标识的 agent_output —— 载荷含 session_id（前端可按当前会话过滤，
     * 多会话并存时不串扰）。全局广播语义不变（仍广播给所有连接）；仅当 sessionId 非空时写入载荷。
     */
    public void broadcastAgentOutput(String sessionId, String agentName, String content, String trackId,
                                     String trackLabel, String trackMode, List<String> visibleTo) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) payload.put("session_id", sessionId);
        payload.put("agent_name", agentName);
        payload.put("content", content == null ? "" : content);
        payload.put("track_id", trackId == null ? "main" : trackId);
        payload.put("track_label", trackLabel);
        payload.put("track_mode", trackMode);
        payload.put("visible_to", visibleTo == null ? List.of() : visibleTo);
        broadcast("agent_output", payload);
    }

    /** agent_silent → {agent_name} */
    public void broadcastAgentSilent(String agentName) {
        broadcast("agent_silent", Map.of("agent_name", agentName));
    }

    /**
     * P-0802-M：agent_token → {session_id?, agent_name, delta, track_id, track_label, track_mode} ——
     * LLM 流式生成增量片（SSE 推送，前端逐字实时渲染）；完整内容仍由 agent_output 结算
     * （前端收到 agent_output 后以完整文本替换增量草稿）。
     * P-0811-G(B-2)：带会话标识的重载（多会话并存时前端按 session_id 过滤，防串扰）。
     */
    public void broadcastAgentToken(String agentName, String delta, String trackId,
                                    String trackLabel, String trackMode) {
        broadcastAgentToken(null, agentName, delta, trackId, trackLabel, trackMode);
    }

    public void broadcastAgentToken(String sessionId, String agentName, String delta, String trackId,
                                    String trackLabel, String trackMode) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) payload.put("session_id", sessionId);
        payload.put("agent_name", agentName == null ? "" : agentName);
        payload.put("delta", delta == null ? "" : delta);
        payload.put("track_id", trackId == null ? "main" : trackId);
        payload.put("track_label", trackLabel == null ? "" : trackLabel);
        payload.put("track_mode", trackMode == null ? "merged" : trackMode);
        broadcast("agent_token", payload);
    }

    /** arbiter_integrate → {round, narration} */
    public void broadcastArbiterIntegrate(int round, String narration) {
        broadcast("arbiter_integrate", Map.of("round", round, "narration", narration == null ? "" : narration));
    }

    /** round_complete → {session_id?, round} */
    public void broadcastRoundComplete(int round) {
        broadcastRoundComplete(null, round);
    }

    /** P-0811-G(B-2)：带会话标识的 round_complete（多会话并存时前端按 session_id 过滤）。 */
    public void broadcastRoundComplete(String sessionId, int round) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) payload.put("session_id", sessionId);
        payload.put("round", round);
        broadcast("round_complete", payload);
    }

    /** compression → {summary} */
    public void broadcastCompression(String summary) {
        broadcast("compression", Map.of("summary", summary == null ? "" : summary));
    }

    /** user_input → {session_id?, content, category, character, round} */
    public void broadcastUserInput(String content, String category, String character, int round) {
        broadcastUserInput(null, content, category, character, round);
    }

    /** P-0811-G(B-2)：带会话标识的 user_input（多会话并存时前端按 session_id 过滤）。 */
    public void broadcastUserInput(String sessionId, String content, String category, String character, int round) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) payload.put("session_id", sessionId);
        payload.put("content", content == null ? "" : content);
        payload.put("category", category == null ? "" : category);
        payload.put("character", character == null ? "" : character);
        payload.put("round", round);
        broadcast("user_input", payload);
    }

    /** auto_complete → {rounds} */
    public void broadcastAutoComplete(int rounds) {
        broadcast("auto_complete", Map.of("rounds", rounds));
    }

    /** stopped → {} */
    public void broadcastStopped() {
        broadcast("stopped", Map.of());
    }

    /** error → {error} (frontend reads u.error) */
    public void broadcastError(String error) {
        broadcast("error", Map.of("error", error == null ? "未知错误" : error));
    }

    /** saved → {} */
    public void broadcastSaved() {
        broadcast("saved", Map.of());
    }

    /** agent_added → {name, char_status} */
    public void broadcastAgentAdded(String name, String charStatus) {
        broadcast("agent_added", Map.of("name", name, "char_status", charStatus == null ? "active" : charStatus));
    }

    /** agent_removed → {name} */
    public void broadcastAgentRemoved(String name) {
        broadcast("agent_removed", Map.of("name", name));
    }

    /** track_created → {id, label} */
    public void broadcastTrackCreated(String id, String label) {
        broadcast("track_created", Map.of("id", id, "label", label));
    }

    /** track_closed → {id, label} */
    public void broadcastTrackClosed(String id, String label) {
        broadcast("track_closed", Map.of("id", id, "label", label));
    }

    // ── Script (剧本杀) typed broadcast helpers (GAP-8) ──
    // P-0802-J：三个 helper 全部改走 broadcastToSession 会话定向（对齐 werewolf_* P-0802-I）——
    // script_* 事件只送达注册了该对局 session_id 的 SSE 连接，多局并发互不串扰（D-013 已知限制修复）；
    // sessionId 为空时 broadcastToSession 回退全局广播（向后兼容），全局广播（announcement/agent_output）不受影响。

    /** script_phase → {session_id, phase} — 阶段机每次流转推送（SETUP/INVESTIGATION/DISCUSSION/VOTE/REVEAL/ENDED） */
    public void broadcastScriptPhase(String sessionId, String phase) {
        broadcastToSession(sessionId, "script_phase", Map.of(
            "session_id", sessionId == null ? "" : sessionId,
            "phase", phase == null ? "" : phase));
    }

    /** script_status → {session_id, phase, name, background, roles, players, round, locations, …}（脱敏：不带 your_secret） */
    public void broadcastScriptStatus(String sessionId, Map<String, Object> status) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (status != null) payload.putAll(status);
        payload.put("session_id", sessionId == null ? "" : sessionId);
        broadcastToSession(sessionId, "script_status", payload);
    }

    /** script_reveal → {session_id, votes, most_voted, vote_count, murderer, correct, result, truth, approval} */
    public void broadcastScriptReveal(String sessionId, Map<String, Object> reveal) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (reveal != null) payload.putAll(reveal);
        payload.put("session_id", sessionId == null ? "" : sessionId);
        broadcastToSession(sessionId, "script_reveal", payload);
    }

    /** P-0805-C（私聊 SSE）：script_private → {session_id, from, to, message, reply, guarded, ts}
     *  会话定向推送（该对局所有连接收到，前端按本人 player 过滤——私聊双方才展示，对齐 script_status 通道）。 */
    public void broadcastScriptPrivate(String sessionId, Map<String, Object> data) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (data != null) payload.putAll(data);
        payload.put("session_id", sessionId == null ? "" : sessionId);
        broadcastToSession(sessionId, "script_private", payload);
    }

    /**
     * P-0810-17（B1）：script_speech → {session_id, speaker, message, round, human?}
     *  剧本杀讨论发言逐轮实时回显（会话定向；与 werewolf_speech 同形态，前端可直接复用消费逻辑）。
     */
    public void broadcastScriptSpeech(String sessionId, Map<String, Object> data) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (data != null) payload.putAll(data);
        payload.put("session_id", sessionId == null ? "" : sessionId);
        broadcastToSession(sessionId, "script_speech", payload);
    }

    /**
     * P-0810-17（阶段 1）：script_ready → {session_id, ready, phase, name, map_ready, generated?}
     *  完整剧本（+地图）后台异步生成完成通知（决策点 6：新增结构化事件承载「剧本就绪」，
     *  与 script_phase/script_status 并存——script_phase 仍推阶段切换、script_status 仍推全量状态）。
     */
    public void broadcastScriptReady(String sessionId, Map<String, Object> data) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (data != null) payload.putAll(data);
        payload.put("session_id", sessionId == null ? "" : sessionId);
        broadcastToSession(sessionId, "script_ready", payload);
    }

    public int getConnectionCount() {
        return emitters.size();
    }

    /** P-0802-I：指定会话的连接数（测试/观测用）。 */
    public int getConnectionCount(String sessionId) {
        if (sessionId == null) return 0;
        int n = 0;
        for (String sid : emitterSessions.values()) {
            if (sessionId.equals(sid)) n++;
        }
        return n;
    }
}
