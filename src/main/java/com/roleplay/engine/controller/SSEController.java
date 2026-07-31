package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
public class SSEController {

    private static final Logger log = LoggerFactory.getLogger(SSEController.class);
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final long SSE_TIMEOUT_MS = 300_000; // 5 min

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
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
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        return emitter;
    }

    private void sendHeartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Broadcast an event to all connected SSE clients.
     * {@code data} is serialized to a JSON string (Jackson handles escaping).
     */
    public void broadcast(String eventType, Object data) {
        String json;
        try {
            json = mapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("SSE serialization failed for event {}: {}", eventType, e.getMessage());
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(json, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
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

    /** agent_output → {agent_name, content, track_id, track_label, track_mode, visible_to} */
    public void broadcastAgentOutput(String agentName, String content, String trackId,
                                     String trackLabel, String trackMode, List<String> visibleTo) {
        broadcast("agent_output", Map.of(
            "agent_name", agentName,
            "content", content == null ? "" : content,
            "track_id", trackId == null ? "main" : trackId,
            "track_label", trackLabel,
            "track_mode", trackMode,
            "visible_to", visibleTo == null ? List.of() : visibleTo
        ));
    }

    /** agent_silent → {agent_name} */
    public void broadcastAgentSilent(String agentName) {
        broadcast("agent_silent", Map.of("agent_name", agentName));
    }

    /** arbiter_integrate → {round, narration} */
    public void broadcastArbiterIntegrate(int round, String narration) {
        broadcast("arbiter_integrate", Map.of("round", round, "narration", narration == null ? "" : narration));
    }

    /** round_complete → {round} */
    public void broadcastRoundComplete(int round) {
        broadcast("round_complete", Map.of("round", round));
    }

    /** compression → {summary} */
    public void broadcastCompression(String summary) {
        broadcast("compression", Map.of("summary", summary == null ? "" : summary));
    }

    /** user_input → {content, category, character, round} */
    public void broadcastUserInput(String content, String category, String character, int round) {
        broadcast("user_input", Map.of(
            "content", content == null ? "" : content,
            "category", category == null ? "" : category,
            "character", character == null ? "" : character,
            "round", round
        ));
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

    public int getConnectionCount() {
        return emitters.size();
    }
}
