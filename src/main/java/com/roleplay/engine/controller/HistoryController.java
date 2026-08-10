package com.roleplay.engine.controller;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.model.Session;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SessionRegistry;
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
    /** D11: 按 session_id 的 router 实例管理 —— 历史加载写回对应会话实例。 */
    private final SessionRegistry sessions;

    public HistoryController(@Lazy RouterService router, LLMClient llmClient,
                             CharacterController characterController,
                             SessionRegistry sessions) {
        this.router = router;
        this.llmClient = llmClient;
        this.characterController = characterController;
        this.sessions = sessions;
    }

    /**
     * Current-conversation history (Python routes_history.py GET /api/history).
     *
     * <p>N1 fix: previously returned {@code {sessions:[…]}} (saved-session metadata),
     * but the frontend {@code loadHistory()} reads {@code resp.messages || []} and maps
     * it into the main chat list — so the chat never re-displayed after a page refresh
     * or history-session load. Now returns {@code {messages, total, round_logs}} exactly
     * like the Python contract.
     *
     * <p>Query params: limit / offset / character / round / player_name (player_name =
     * visibility filter for werewolf/rules mode, mirrors Python).
     *
     * <p>P-0810-21：新增 session_id 参数 —— 有 session_id 时读取该会话专属 router 实例
     * （SessionRegistry）的真实消息（含 round_logs，结构与默认单例完全一致）；
     * 未传 / 未知 session_id 回退默认单例（向后兼容，旧客户端行为逐字节不变）。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "") String character,
            @RequestParam(defaultValue = "0") int round,
            @RequestParam(defaultValue = "") String player_name,
            @RequestParam(defaultValue = "") String session_id) {
        // P-0810-21：session_id → 该会话实例（无/未知 → 默认单例，向后兼容）
        RouterService target = sessions.get(session_id);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Message m : target.getConversationMessages()) {
            Map<String, Object> d = m.toMap();
            if (!character.isEmpty() && !character.equals(d.get("name"))) continue;
            if (round > 0 && ((Number) d.getOrDefault("round_number", 0)).intValue() != round) continue;
            if (!player_name.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> visibleTo = (List<String>) d.getOrDefault("visible_to", List.of());
                if (!visibleTo.isEmpty() && !visibleTo.contains(player_name)) continue;
            }
            filtered.add(d);
        }
        int from = Math.min(offset, filtered.size());
        int to = Math.min(offset + limit, filtered.size());
        return ResponseEntity.ok(Map.of(
            "messages", filtered.subList(from, to),
            "total", filtered.size(),
            "round_logs", lastN(target.getConversationRoundLogs(), 20)
        ));
    }

    /**
     * Saved-session list (Python GET /api/history/sessions).
     *
     * <p>N1 fix: items now carry the full field set the frontend reads — session_id,
     * round_count, message_count, agent_names, scene_title, created_at, updated_at
     * (previously only id/rounds/created_at, so the history panel showed "0 条" and
     * no title).
     */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> listSessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        savedSessions.forEach((id, s) -> list.add(Map.of(
            "session_id", id,
            "created_at", s.getCreatedAt(),
            "updated_at", s.getUpdatedAt(),
            "round_count", s.getRoundCount(),
            "message_count", s.getMessages().size(),
            "agent_names", s.getAgentNames(),
            "scene_title", deriveSceneTitle(s)
        )));
        list.sort((a, b) -> String.valueOf(b.get("updated_at")).compareTo(String.valueOf(a.get("updated_at"))));
        return ResponseEntity.ok(Map.of("sessions", list));
    }

    /**
     * Session detail (Python GET /api/history/sessions/{id}).
     *
     * <p>N1 fix: Session.toMap() doesn't include messages, so the history preview
     * panel (frontend reads {@code resp.messages.slice(-30)} + {@code resp.total})
     * was always empty. Now returns the Python contract
     * {@code {session_id, messages, total, round_logs}} on top of the session metadata.
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        Session s = savedSessions.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        Map<String, Object> result = new LinkedHashMap<>(s.toMap());
        List<Map<String, Object>> messages = s.getMessages().stream()
            .map(Message::toMap).collect(Collectors.toList());
        result.put("messages", messages);
        result.put("total", messages.size());
        result.put("round_logs", lastN(s.getRoundLog(), 20));
        return ResponseEntity.ok(result);
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
        // D11: 写回该会话自己的 router 实例（首次触达自动创建独立实例）
        sessions.getOrCreate(id).loadSession(s, agentList);
        // 向后兼容：默认单例 router 同步加载（未传 session_id 的旧客户端继续走默认会话）
        router.loadSession(s, agentList);

        List<String> agentNames = agentList.stream().map(Agent::getName).toList();
        return ResponseEntity.ok(Map.of(
            "status", "loaded",
            "session_id", id,
            "round", s.getRoundCount(),
            "agents", agentNames,
            "message_count", s.getMessages().size()
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
            // P-0810-10：五层 persona 卡（导入卡优先，无则默认资源卡；已有 layer 不覆盖）
            characterController.attachPersonaCard(p);
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

    // ═───────────────────────────────────────────────────────────
    //  N1 helpers
    // ═───────────────────────────────────────────────────────────

    /**
     * Scene title derived from the first messages (mirrors Python: scan first
     * 5 messages for 【场景】, take first line before '：'; fallback = agent names).
     */
    private String deriveSceneTitle(Session s) {
        List<Message> msgs = s.getMessages();
        int scan = Math.min(5, msgs.size());
        for (int i = 0; i < scan; i++) {
            String content = msgs.get(i).getContent();
            if (content != null && content.contains("【场景】")) {
                String after = content.split("【场景】", 2)[1].strip();
                String firstLine = after.split("\n")[0].strip();
                if (firstLine.contains("：")) {
                    firstLine = firstLine.split("：")[0].strip();
                }
                if (!firstLine.isEmpty()) {
                    return firstLine.length() > 40 ? firstLine.substring(0, 40) : firstLine;
                }
            }
        }
        List<String> names = s.getAgentNames();
        if (names != null && !names.isEmpty()) {
            String joined = String.join("、", names.subList(0, Math.min(3, names.size())));
            return names.size() > 3 ? joined + "…" : joined;
        }
        return "";
    }

    /** Last {@code n} entries of a log list (Python {@code round_logs[-20:]}). */
    private List<Map<String, Object>> lastN(List<Map<String, Object>> logs, int n) {
        if (logs == null || logs.isEmpty()) return List.of();
        return logs.subList(Math.max(0, logs.size() - n), logs.size());
    }
}
