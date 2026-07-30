package com.roleplay.engine.controller;

import com.roleplay.engine.service.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
    private final RouterService router;

    public SSEController(RouterService router) {
        this.router = router;
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
     */
    public void broadcast(String eventType, String data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    public void broadcastAgentOutput(String agentName, String content) {
        String escapedContent = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        broadcast("agent_output", "{\"agent_name\":\"" + agentName
            + "\",\"content\":\"" + escapedContent + "\"}");
    }

    public void broadcastRoundComplete(String status) {
        broadcast("round_complete", "{\"status\":\"" + status + "\"}");
    }

    public void broadcastError(String message) {
        broadcast("error", "{\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }

    public int getConnectionCount() {
        return emitters.size();
    }
}
