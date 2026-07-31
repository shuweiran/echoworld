package com.roleplay.engine.controller;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.RouterService;
import jakarta.annotation.PostConstruct;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scene CRUD endpoints.
 *
 * <p>Storage: in-memory list mirrors H2 (DatabaseService). Startup loads persisted
 * scenes from H2; every write (create/update/delete) is persisted synchronously
 * so data survives restarts.
 */
@RestController
@RequestMapping("/api/scenes")
public class SceneController {

    private final List<Map<String, Object>> scenes = new CopyOnWriteArrayList<>();
    private final GeneratorService generator;
    private final RouterService router;
    private final CharacterController characterController;
    private final DatabaseService databaseService;

    public SceneController(GeneratorService generator, RouterService router,
                           CharacterController characterController,
                           DatabaseService databaseService) {
        this.generator = generator;
        this.router = router;
        this.characterController = characterController;
        this.databaseService = databaseService;
    }

    @PostConstruct
    public void init() {
        // Load persisted scenes from H2 (survives restarts)
        scenes.addAll(databaseService.getAllScenes());
        // Seed default only when DB is empty — avoids duplicates on restart
        if (scenes.isEmpty()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("scene_id", "default");
            def.put("name", "默认场景");
            def.put("description", "一个普通的房间");
            def.put("initial_agent_names", List.of("助手"));
            scenes.add(def);
            databaseService.saveScene("default", "默认场景", "一个普通的房间", List.of("助手"), "");
        }
    }

    public List<Map<String, Object>> getAll() { return new ArrayList<>(scenes); }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(new ArrayList<>(scenes));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> scene = new LinkedHashMap<>();
        Object sid = body.getOrDefault("scene_id", UUID.randomUUID().toString().substring(0, 8));
        // 前端剧本杀链路需要固定 scene_id（script_xxx），此处支持客户端指定
        scene.put("scene_id", sid == null ? UUID.randomUUID().toString().substring(0, 8) : String.valueOf(sid));
        scene.put("name", body.getOrDefault("name", "未命名场景"));
        scene.put("description", body.getOrDefault("description", ""));
        scene.put("keywords", body.getOrDefault("keywords", ""));
        scene.put("initial_agent_names", body.getOrDefault("initial_agent_names", List.of()));
        scenes.add(scene);
        persistScene(scene);
        return ResponseEntity.ok(scene);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        for (int i = 0; i < scenes.size(); i++) {
            if (id.equals(scenes.get(i).get("scene_id"))) {
                Map<String, Object> updated = new LinkedHashMap<>(scenes.get(i));
                body.forEach((k, v) -> { if (v != null) updated.put(k, v); });
                scenes.set(i, updated);
                String newId = str(updated.get("scene_id"));
                if (!id.equals(newId)) {
                    // Rename: scene_id is the primary key in H2 → drop old row first
                    databaseService.deleteScene(id);
                }
                persistScene(updated);
                return ResponseEntity.ok(updated);
            }
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        scenes.removeIf(s -> id.equals(s.get("scene_id")));
        databaseService.deleteScene(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> startScene(@PathVariable String id,
                                                           @RequestParam(required = false, defaultValue = "") String agents,
                                                           @RequestParam(defaultValue = "") String me,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        // Resolve agent names: prefer JSON body (characters list), fall back to query param (backward compatible)
        List<String> agentNames = new ArrayList<>();
        if (body != null && body.get("agents") instanceof List<?> bodyAgents) {
            for (Object o : bodyAgents) {
                if (o != null && !String.valueOf(o).trim().isEmpty()) {
                    agentNames.add(String.valueOf(o).trim());
                }
            }
        }
        if (agentNames.isEmpty() && agents != null && !agents.isBlank()) {
            for (String name : agents.split(",")) {
                if (name != null && !name.trim().isEmpty()) {
                    agentNames.add(name.trim());
                }
            }
        }

        // Character details from request body (frontend passes real personas/voices/backgrounds)
        Map<String, Map<String, String>> bodyChars = new LinkedHashMap<>();
        if (body != null && body.get("characters") instanceof List<?> charList) {
            for (Object o : charList) {
                if (o instanceof Map<?, ?> m) {
                    Object nameObj = m.get("name");
                    if (nameObj == null || String.valueOf(nameObj).isBlank()) continue;
                    Map<String, String> detail = new LinkedHashMap<>();
                    detail.put("persona", str(m.get("persona")));
                    detail.put("voice", str(m.get("voice")));
                    detail.put("background", str(m.get("background")));
                    bodyChars.put(String.valueOf(nameObj).trim(), detail);
                }
            }
        }

        // Build real personas: body details first, then character library, then placeholder
        List<com.roleplay.engine.core.Persona> personas = new ArrayList<>();
        for (String name : agentNames) {
            Map<String, String> detail = bodyChars.get(name);
            if (detail == null) {
                for (Map<String, Object> ch : characterController.getAll()) {
                    if (name.equals(ch.get("name"))) {
                        detail = new LinkedHashMap<>();
                        detail.put("persona", str(ch.get("persona")));
                        detail.put("voice", str(ch.get("voice")));
                        detail.put("background", str(ch.get("background")));
                        break;
                    }
                }
            }
            com.roleplay.engine.core.Persona p = new com.roleplay.engine.core.Persona(name);
            if (detail != null) {
                p.setPersonaDesc(detail.getOrDefault("persona", ""));
                p.setVoice(detail.getOrDefault("voice", ""));
                p.setBackground(detail.getOrDefault("background", ""));
            } else {
                p.setPersonaDesc(name + "，一个角色");
            }
            personas.add(p);
        }
        if (personas.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "至少需要一个角色"));
        }
        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        // 用场景描述（若存在）作为会话场景上下文；剧本杀场景描述即剧本背景
        String sceneDesc = id;
        for (Map<String, Object> s : scenes) {
            if (id.equals(s.get("scene_id"))) {
                Object d = s.get("description");
                if (d != null && !String.valueOf(d).isBlank()) sceneDesc = String.valueOf(d);
                break;
            }
        }
        router.initSession(sessionId, personas, sceneDesc, "free", "", "");
        Map<String, Object> result = new LinkedHashMap<>(router.getState());
        result.put("session_id", sessionId);
        result.put("mode", "free");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(generator.generateScene(body.getOrDefault("keywords", ""), ""));
    }

    /** Persist one scene map to H2 (create/update path). */
    private void persistScene(Map<String, Object> scene) {
        List<String> agents = new ArrayList<>();
        Object agentsObj = scene.get("initial_agent_names");
        if (agentsObj instanceof List<?> raw) {
            for (Object o : raw) {
                if (o != null) agents.add(String.valueOf(o));
            }
        }
        databaseService.saveScene(str(scene.get("scene_id")), str(scene.get("name")),
                str(scene.get("description")), agents, str(scene.get("keywords")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
