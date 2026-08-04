package com.roleplay.engine.controller;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptMapService;
import com.roleplay.engine.simulation.map.BspMapGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** P-0803-O：LLM 全量生成统一路径（仅依赖 LLMClient，无循环依赖；4 参旧构造为 null → 防御回落 BSP）。 */
    private final ScriptMapService mapService;

    /** P-0803-O：4 参旧构造委托（mapService=null，LLM 主题请求防御回落 BSP 确定性；既有测试/调用点零破坏）。 */
    public SceneController(GeneratorService generator, RouterService router,
                           CharacterController characterController,
                           DatabaseService databaseService) {
        this(generator, router, characterController, databaseService, null);
    }

    /** P-0803-O：5 参 @Autowired 构造 —— 注入 ScriptMapService（Spring bean，仅依赖 LLMClient，无环）。 */
    @Autowired
    public SceneController(GeneratorService generator, RouterService router,
                           CharacterController characterController,
                           DatabaseService databaseService,
                           ScriptMapService mapService) {
        this.generator = generator;
        this.router = router;
        this.characterController = characterController;
        this.databaseService = databaseService;
        this.mapService = mapService;
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
        // P-0803-H：剧本绑定三字段（category 分类 / default_roles 默认角色组 / default_map 默认地图）
        scene.put("category", str(body.get("category"), "general"));
        scene.put("default_roles", body.getOrDefault("default_roles", List.of()));
        Object dm = body.get("default_map");
        // 空串 = 清除（不落库不暴露）；null = 无地图
        scene.put("default_map", (dm instanceof String s && s.isBlank()) ? null : dm);
        // P-0804-G：default_map 统一为对象（对齐 update 端点与 DB 加载行为，前端直接消费）
        if (scene.get("default_map") instanceof String s2 && !s2.isBlank()) {
            try {
                scene.put("default_map", MAPPER.readValue(s2, Map.class));
            } catch (Exception ex) {
                System.err.println("SceneController: create default_map parse failed: " + ex.getMessage());
            }
        }
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
                // P-0804-G：default_map 运行时统一为对象（对齐 DB 加载 parseJsonMap 行为）——
                // 前端 ScenePage 直接把 default_map 传给 PhaserScriptMapView，字符串会导致 map.zones
                // undefined → render 读 .length 崩溃 → React 白屏（仅在 PUT 后未重启时暴露）。
                if (updated.get("default_map") instanceof String s && !s.isBlank()) {
                    try {
                        updated.put("default_map", MAPPER.readValue(s, Map.class));
                    } catch (Exception ex) {
                        System.err.println("SceneController: default_map parse failed: " + ex.getMessage());
                    }
                }
                scenes.set(i, updated);
                String newId = str(updated.get("scene_id"));
                if (!id.equals(newId)) {
                    // Rename: scene_id is the primary key in H2 → drop old row first
                    databaseService.deleteScene(id);
                }
                persistScene(updated);
                // P-0803-H：default_map 空串 = 清除信号（已落库为 null）；响应归一不暴露空串
                Object dm = updated.get("default_map");
                if (dm instanceof String s && s.isBlank()) {
                    updated.remove("default_map");
                }
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
                str(scene.get("description")), agents, str(scene.get("keywords")),
                str(scene.get("category"), "general"),
                toJson(scene.get("default_roles")),
                toJson(scene.get("default_map")));
    }

    /** P-0803-H：default_roles/default_map 序列化 —— 已是字符串直用；对象转 JSON；null 传 null（不覆盖旧值） */
    private static String toJson(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    /**
     * P-0803-H：剧本默认地图生成（BSP 确定性生成，契约 v1）——供前端剧本编辑弹窗「生成默认地图」
     * 绑定到剧本卡 default_map；零 LLM 零成本（BspMapGenerator 纯规则，同 seed 同输出）。
     * P-0803-O：升级为双模式 ——
     * <ul>
     *   <li>body 无 theme（空/缺省）→ BSP 确定性模式（P-0803-H 既有行为逐字节零回归）</li>
     *   <li>body 带非空 theme → LLM 全量生成模式：ScriptMapService 统一路径（LLM 完整输出
     *       ground+collision 双层数组 + rooms/zones/spawns 全量元素 → 契约 v1 校验 → 校验失败/LLM
     *       失败/超预算自动 BSP 降级兜底，防御性保留），响应附加 mode/generator/validation/fallback 溯源键</li>
     * </ul>
     */
    @PostMapping("/map")
    public ResponseEntity<Map<String, Object>> generateDefaultMap(@RequestBody(required = false) Map<String, Object> body) {
        long seed = 0;
        String theme = "";
        int width = 0, height = 0;
        if (body != null) {
            if (body.get("seed") instanceof Number n) {
                seed = n.longValue();
            }
            Object t = body.get("theme");
            if (t != null) {
                theme = String.valueOf(t).trim();
            }
            // P-0804-G：显式尺寸透传（单张大地图生成；≤0 = 默认 24×16；超 LLM 上限自动走 BSP）
            if (body.get("width") instanceof Number nw) {
                width = nw.intValue();
            }
            if (body.get("height") instanceof Number nh) {
                height = nh.intValue();
            }
        }
        // 4 参构造（无 ScriptMapService）防御：主题请求回落 BSP 确定性
        if (mapService == null) {
            Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.of(seed, 0, 0, -1));
            return ResponseEntity.ok(Map.of("map", bsp));
        }
        // BSP 确定性模式（无主题）：P-0803-H 既有行为，零 LLM 零回归
        if (theme.isEmpty()) {
            Map<String, Object> bsp = BspMapGenerator.generate(BspMapGenerator.Options.of(seed, 0, 0, -1));
            return ResponseEntity.ok(Map.of("map", bsp));
        }
        // LLM 全量生成模式（带主题）：统一路径 → 契约 v1 校验 → 失败/超预算 BSP 兜底
        ScriptMapService.MapResult result = mapService.generateMap(theme, List.of(), List.of(), seed, width, height);
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("ok", result.validation().ok());
        validation.put("errors", result.validation().errors());
        validation.put("warnings", result.validation().warnings());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("map", result.map());
        resp.put("mode", result.usedBsp() ? "bsp-fallback" : "llm");
        resp.put("generator", result.map().get("generator"));
        resp.put("validation", validation);
        resp.put("fallback", result.fallbackReasons());
        return ResponseEntity.ok(resp);
    }
}
