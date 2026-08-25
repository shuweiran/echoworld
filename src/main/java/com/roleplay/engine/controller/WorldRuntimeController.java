package com.roleplay.engine.controller;

import com.roleplay.engine.service.world.InputMailbox;
import com.roleplay.engine.service.world.MapGenerationRequest;
import com.roleplay.engine.service.world.MapJob;
import com.roleplay.engine.service.world.RoleLifecycleSnapshot;
import com.roleplay.engine.service.world.WorldCommand;
import com.roleplay.engine.service.world.WorldCommandType;
import com.roleplay.engine.service.world.WorldPrecondition;
import com.roleplay.engine.service.world.WorldRuntimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 一般模式异步输入、受控世界命令、轻量群演和后台地图任务 API。 */
@RestController
@RequestMapping("/api/world")
public class WorldRuntimeController {

    private final WorldRuntimeService runtime;

    public WorldRuntimeController(WorldRuntimeService runtime) {
        this.runtime = runtime;
    }

    /** 接收后立即返回；同一 session 的主控在后台逐条消费，input_id 可安全重试。 */
    @PostMapping("/input")
    public ResponseEntity<Map<String, Object>> enqueueInput(
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return bad("body required");
        String sessionId = string(body, "session_id", "");
        String content = string(body, "content", string(body, "message", ""));
        if (sessionId.isBlank() || content.isBlank()) return bad("session_id and content required");
        String inputId = string(body, "input_id", UUID.randomUUID().toString());
        InputMailbox.Priority priority = enumValue(InputMailbox.Priority.class,
                string(body, "priority", "NORMAL"), InputMailbox.Priority.NORMAL);
        Map<String, Object> attributes = new LinkedHashMap<>();
        copyIfPresent(body, attributes, "speaker");
        copyIfPresent(body, attributes, "player_id");
        copyIfPresent(body, attributes, "focused_role_id");
        copyIfPresent(body, attributes, "focused_role_ids");
        copyIfPresent(body, attributes, "conversation_members");
        InputMailbox.OfferResult result = runtime.enqueueInput(new InputMailbox.MailboxInput(
                sessionId, inputId, content, priority, Instant.now(), attributes));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", result.accepted());
        response.put("status", result.status().name());
        response.put("input_id", inputId);
        if (result.evictedInput() != null) response.put("evicted_input_id", result.evictedInput().inputId());
        return ResponseEntity.status(result.accepted() ? 202 : 409).body(response);
    }

    /** 主控提交结构化意图；只入有界总线，不在请求线程直接改世界。 */
    @PostMapping("/commands")
    public ResponseEntity<Map<String, Object>> propose(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return bad("body required");
        String sessionId = string(body, "session_id", "");
        String typeText = string(body, "type", "");
        if (sessionId.isBlank() || typeText.isBlank()) return bad("session_id and type required");
        WorldCommandType type;
        try { type = WorldCommandType.valueOf(typeText.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return bad("unsupported command type"); }
        Map<String, Object> payload = objectMap(body.get("payload"));
        List<WorldPrecondition> preconditions = parsePreconditions(body.get("preconditions"));
        WorldCommand command = new WorldCommand(string(body, "id", null), type, sessionId, payload,
                preconditions, string(body, "reason", ""), Instant.now());
        boolean accepted = runtime.propose(command);
        return ResponseEntity.status(accepted ? 202 : 409).body(Map.of(
                "accepted", accepted, "command_id", command.id(), "type", type.name()));
    }

    /** 直接创建零 LLM 成本的可见群演投影。 */
    @PostMapping("/extras")
    public ResponseEntity<?> spawnExtra(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return bad("body required");
        try {
            RoleLifecycleSnapshot role = runtime.spawnExtra(
                    string(body, "session_id", WorldRuntimeService.SIMULATION_SESSION),
                    string(body, "role_id", ""), string(body, "name", ""),
                    string(body, "line", ""), string(body, "persona", ""),
                    number(body.get("x")), number(body.get("y")));
            return ResponseEntity.status(201).body(role);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", safeError(e)));
        }
    }

    /** 普通点击只刷新注意时间；对话/剧情/关系行为才增加有效互动分。 */
    @PostMapping("/extras/{roleId}/interact")
    public ResponseEntity<?> interact(@PathVariable String roleId,
                                      @RequestParam(name = "session_id", defaultValue = "simulation") String sessionId) {
        try { return ResponseEntity.ok(runtime.interact(sessionId, roleId, com.roleplay.engine.service.world.RoleInteractionKind.ATTENTION)); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(404).body(Map.of("error", safeError(e))); }
    }

    /** 真实角色（含 PASSIVE）的正常点击唤醒入口；名称须已归生命周期所有。 */
    @PostMapping("/roles/by-name/{name}/interact")
    public ResponseEntity<?> interactByName(@PathVariable String name) {
        try { return ResponseEntity.ok(runtime.interactByName(name)); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(404).body(Map.of("error", safeError(e))); }
    }

    /** 后台生成地图；默认不自动发布，避免半成品热切换当前世界。 */
    @PostMapping("/maps")
    public ResponseEntity<?> submitMap(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return bad("body required");
        String sessionId = string(body, "session_id", WorldRuntimeService.SIMULATION_SESSION);
        String key = string(body, "idempotency_key", UUID.randomUUID().toString());
        boolean autoPublish = Boolean.parseBoolean(string(body, "auto_publish", "false"));
        Map<String, Object> attributes = new LinkedHashMap<>(body);
        attributes.remove("session_id");
        attributes.remove("idempotency_key");
        attributes.remove("auto_publish");
        try {
            MapJob job = runtime.submitMap(sessionId, new MapGenerationRequest(key, attributes), autoPublish);
            return ResponseEntity.accepted().body(job);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", safeError(e)));
        }
    }

    @PostMapping("/maps/{jobId}/publish")
    public ResponseEntity<?> publishMap(@PathVariable String jobId,
                                        @RequestParam(name = "session_id", defaultValue = "simulation") String sessionId) {
        try { return ResponseEntity.ok(runtime.publishMap(sessionId, jobId)); }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", safeError(e)));
        }
    }

    @GetMapping("/state")
    public ResponseEntity<?> state(
            @RequestParam(name = "session_id", defaultValue = "simulation") String sessionId) {
        try { return ResponseEntity.ok(runtime.state(sessionId)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", safeError(e))); }
    }

    private static List<WorldPrecondition> parsePreconditions(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<WorldPrecondition> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> p = objectMap(item);
            String field = string(p, "field", "");
            if (field.isBlank()) continue;
            result.add(new WorldPrecondition(field, string(p, "operator", "EQ"), p.get("expected")));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.get(key) != null) to.put(key, from.get(key));
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? null : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value).trim();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return fallback; }
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static String safeError(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
