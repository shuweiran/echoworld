package com.roleplay.engine.controller;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.model.Session;
import com.roleplay.engine.service.RouterService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Conversation history endpoints.
 * Maps from Python api/routes_history.py.
 *
 * <p>D12 fix: POST /api/history/load/{id} previously only returned "loaded"
 * without touching the router — the saved conversation was never actually
 * restored. It now calls {@link RouterService#loadSession} to write the saved
 * session (messages / roster / scene / round counter) back into the running
 * singleton router, so the conversation continues where it left off.
 *
 * <p>The {@link Lazy} RouterService injection breaks the constructor cycle
 * RouterService → HistoryController → RouterService (Spring Boot 3.x rejects
 * eager circular references).
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final Map<String, Session> savedSessions = new ConcurrentHashMap<>();
    private final RouterService router;
    private final LLMClient llmClient;
    private final CharacterController characterController;

    public HistoryController(@Lazy RouterService router, LLMClient llmClient,
                             CharacterController characterController) {
        this.router = router;
        this.llmClient = llmClient;
        this.characterController = characterController;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHistory() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        savedSessions.forEach((id, s) -> sessions.add(Map.of(
            "session_id", id,
            "message_count", s.getMessages().size(),
            "created_at", s.getCreatedAt(),
            "round_count", s.getRoundCount()
        )));
        sessions.sort((a, b) -> String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at"))));
        return ResponseEntity.ok(Map.of("sessions", sessions));
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> listSessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        savedSessions.forEach((id, s) -> list.add(Map.of(
            "id", id, "created_at", s.getCreatedAt(),
            "rounds", s.getRoundCount()
        )));
        return ResponseEntity.ok(Map.of("sessions", list));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        Session s = savedSessions.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(s.toMap());
    }

    /**
     * Load a saved session back into the running router (D12 fix).
     *
     * <p>Restores the saved session's messages, roster, scene and round counter
     * into the current singleton RouterService, so the next round continues the
     * restored conversation instead of starting from a blank state. The response
     * stays frontend-compatible ({status, session_id}) and additionally carries
     * {@code round} + {@code agents}, which HistoryPanel reads for its
     * "已加载历史会话" toast (previously always showed 0 轮 / 0 个角色).
     */
    @PostMapping("/load/{id}")
    public ResponseEntity<Map<String, Object>> loadSession(@PathVariable String id) {
        Session s = savedSessions.get(id);
        if (s == null) return ResponseEntity.ok(Map.of("status", "not_found"));

        List<Agent> agentList = buildAgents(s);
        router.loadSession(s, agentList);

        List<String> agentNames = agentList.stream().map(Agent::getName).toList();
        return ResponseEntity.ok(Map.of(
            "status", "loaded",
            "session_id", id,
            "round", s.getRoundCount(),
            "agents", agentNames
        ));
    }

    /**
     * Rebuild the Agent roster of a saved session. Personas are enriched from the
     * character store when a matching character exists; otherwise a minimal
     * name-only persona is used (roster / messages / scene still fully restore).
     */
    private List<Agent> buildAgents(Session s) {
        List<String> names = s.getAgentNames();
        if (names == null || names.isEmpty()) {
            // Safety net: derive roster from the message authors.
            names = s.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.AGENT && m.getName() != null)
                .map(Message::getName)
                .distinct()
                .collect(Collectors.toList());
        }
        Map<String, Map<String, Object>> charactersByName = new HashMap<>();
        for (Map<String, Object> ch : characterController.getAll()) {
            Object name = ch.get("name");
            if (name != null) charactersByName.put(String.valueOf(name), ch);
        }
        List<Agent> agents = new ArrayList<>();
        for (String name : names) {
            Persona p = new Persona(name);
            Map<String, Object> ch = charactersByName.get(name);
            if (ch != null) {
                p.setPersonaDesc(String.valueOf(ch.getOrDefault("persona", "")));
                p.setVoice(String.valueOf(ch.getOrDefault("voice", "")));
                p.setBackground(String.valueOf(ch.getOrDefault("background", "")));
            }
            agents.add(new Agent(p, "agent", llmClient));
        }
        return agents;
    }

    /** Auto-save a session from MemoryStore. */
    public void saveSession(String id, Session session) {
        if (id != null && session != null) {
            savedSessions.put(id, session);
        }
    }
}
