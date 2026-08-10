package com.roleplay.engine.aiimage;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL 角色表情集预生成）：AI 生图 REST 端点。
 *
 * <ul>
 *   <li>POST /api/ai-image/character —— 注册/更新角色（id/name/appearance 外貌描述/style 风格描述）</li>
 *   <li>POST /api/ai-image/generate —— 触发某角色生成（头像 1 + 表情 6，异步；已有运行中任务直接返回）</li>
 *   <li>GET  /api/ai-image/character/{id}/images —— 该角色已生成图（avatar + 各表情 URL）</li>
 *   <li>GET  /api/ai-image/status —— 全量状态（注册表 + 任务 + 图片，前端头像映射数据源）</li>
 *   <li>POST /api/ai-image/scene-background —— P-0810-14 场景背景图（{scene} → 像素风非 NSFW 背景，
 *       存 static/ai-images/backgrounds/{hash}.png → 返回 {url, scene}；同 scene 键缓存）</li>
 * </ul>
 *
 * <p>独立新包（aiimage/），零侵入既有主链路（RouterService/ArbiterService/审批/狼人杀/剧本杀/SSE 不动）。
 */
@RestController
@RequestMapping("/api/ai-image")
public class ImageGenController {

    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private final ImageGenService service;

    public ImageGenController(ImageGenService service) {
        this.service = service;
    }

    /** 注册/更新角色。body: {id, name, appearance, style}。 */
    @PostMapping("/character")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
        String id = str(body.get("id"));
        String name = str(body.get("name"));
        String appearance = str(body.get("appearance"));
        String style = str(body.get("style"));
        if (id == null || id.isBlank()) return badRequest("缺少 id（角色标识，字母/数字/下划线/连字符）");
        if (!id.trim().matches(ID_PATTERN)) return badRequest("id 仅允许 1-64 位字母/数字/下划线/连字符");
        if (name == null || name.isBlank()) return badRequest("缺少 name（角色名）");
        if (appearance == null || appearance.isBlank()) return badRequest("缺少 appearance（角色外貌描述）");
        if (style == null || style.isBlank()) return badRequest("缺少 style（风格描述，同角色必须固定）");
        ImageGenService.CharacterProfile p =
                service.registerCharacter(id.trim(), name.trim(), appearance.trim(), style.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("character", Map.of("id", p.id(), "name", p.name(), "appearance", p.appearance(), "style", p.style()));
        return ResponseEntity.ok(out);
    }

    /** 触发角色生成。body: {characterId}。 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("characterId"));
        if (id == null || id.isBlank()) return badRequest("缺少 characterId");
        ImageGenService.GenTask task = service.triggerGenerate(id.trim());
        if (task == null) return badRequest("角色不存在: " + id.trim() + "（请先 POST /api/ai-image/character 注册）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("taskId", task.taskId());
        out.put("characterId", task.characterId());
        out.put("status", task.status().name().toLowerCase(java.util.Locale.ROOT));
        out.put("progress", task.progress());
        return ResponseEntity.ok(out);
    }

    /** 该角色已生成图（avatar + 各表情 URL）。 */
    @GetMapping("/character/{id}/images")
    public ResponseEntity<?> images(@PathVariable String id) {
        if (!id.matches(ID_PATTERN)) return badRequest("非法角色 id");
        Map<String, Object> out = service.imagesResponse(id);
        if (out.isEmpty()) return badRequest("角色不存在: " + id);
        return ResponseEntity.ok(out);
    }

    /** 全量状态（注册表 + 任务 + 图片；前端头像映射数据源）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("lora", service.loraName());
        List<Map<String, Object>> list = new ArrayList<>();
        for (ImageGenService.CharacterProfile p : service.allCharacters()) {
            list.add(service.characterStatus(p.id()));
        }
        out.put("characters", list);
        return out;
    }

    /**
     * P-0810-14：场景背景图生成（一般模式 AI 自动出对应背景）。
     * <p>body: {scene: 场景名/描述} → 像素风非 NSFW 场景背景图（Pony 文生图，复用 SCORE_TAGS+负面词）→
     * 存 static/ai-images/backgrounds/{hash}.png → 返回 {url, scene}。
     * 同 scene 键缓存（内存+磁盘双重）：相同键不重复生成，直接返回已有 url。
     * 同步语义：首调阻塞等待生成（单任务约 50s，上限 yml roleplay.ai-image.timeout-seconds），
     * 缓存命中/并发同键立即返回。
     */
    @PostMapping("/scene-background")
    public ResponseEntity<?> sceneBackground(@RequestBody Map<String, Object> body) {
        String scene = str(body.get("scene"));
        if (scene == null || scene.isBlank()) return badRequest("缺少 scene（场景名/描述）");
        try {
            String url = service.sceneBackground(scene.trim());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("url", url);
            out.put("scene", scene.trim());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            // 生成失败（ComfyUI 不可用/超时等）→ 502 上游失败，与 400（入参）区分
            return ResponseEntity.status(502).body(Map.of("error", "场景背景图生成失败: " + e.getMessage()));
        }
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static ResponseEntity<?> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
