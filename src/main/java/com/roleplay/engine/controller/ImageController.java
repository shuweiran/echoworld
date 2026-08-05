package com.roleplay.engine.controller;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.ImageSpecService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P-0805-A + P-0805-C（生图接入，后端）：
 *  POST /api/image/spec —— 由剧本 schema v1 合成 image_spec 契约 v1（结构化生图描述）
 *  POST /api/image/generate —— 单图生成（provider 可配：OpenAI 兼容 /images/generations 或离线 SVG 占位），
 *     生成结果写入运行时目录 ./data/generated/images/ 并登记 assets 表（ROLE_PORTRAIT/SCENE_BACKGROUND/CLUE_IMAGE）
 *  GET  /api/image/file/{name} —— 读取运行时生成图片（显式 content-type，避免 SPA 兜底 / classpath 限制）
 *     供前端/Phaser 消费。
 *
 * <p>纯文本合成与图片生成解耦（spec 不阻塞；generate 单张同步，图片不进入主循环），
 * 对齐 demo 的三层降级思想（真实 API → 离线占位，风格统一 > 单图质量）。
 *
 * <p>P-0805-C 真机修复：图片写 classpath（src/main/resources/static/）运行时 jar 读不到
 * （SPA 兜底返回 index.html）→ 改写 ./data/generated/images/（运行时目录，jar 外持久化）+ 专用文件端点。
 */
@RestController
@RequestMapping("/api/image")
public class ImageController {

    private static final String IMAGES_SUBDIR = "images";
    private static final String GENERATED_ROOT = "data" + java.io.File.separator + "generated";

    private final ImageSpecService imageSpecService;
    private final DatabaseService databaseService;

    public ImageController(ImageSpecService imageSpecService, DatabaseService databaseService) {
        this.imageSpecService = imageSpecService;
        this.databaseService = databaseService;
    }

    /**
     * body: { theme?: string, script?: object } —— 两者至少其一；
     * script 缺省（或为空）时按 theme 产出场景+瓦片风格（纯主题驱动）。
     */
    @PostMapping("/spec")
    public Map<String, Object> spec(@RequestBody Map<String, Object> body) {
        String theme = body.get("theme") instanceof String s ? s : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> script = body.get("script") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        return imageSpecService.synthesize(script, theme);
    }

    /**
     * P-0805-C：单图生成。body: { unit: 生图单元(契约 v1), name?: 素材名, asset_type?: ROLE_PORTRAIT/SCENE_BACKGROUND/CLUE_IMAGE }
     * → { ok, file_path, url, asset, fallback, mime }；图片写入运行时目录 ./data/generated/images/ 并登记 assets。
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> unit = body.get("unit") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        if (unit == null) return ResponseEntity.badRequest().body(Map.of("error", "缺少 unit（生图单元）"));
        String name = body.get("name") instanceof String s && !s.isBlank() ? s : String.valueOf(unit.get("name"));
        String assetType = body.get("asset_type") instanceof String st
                ? st.toUpperCase(java.util.Locale.ROOT) : "ROLE_PORTRAIT";
        if (!AssetController.ASSET_TYPES.contains(assetType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "asset_type 非法: " + assetType));
        }

        Map<String, Object> gen = imageSpecService.generateImage(unit);
        if (!Boolean.TRUE.equals(gen.get("ok"))) {
            return ResponseEntity.internalServerError().body(Map.of("error", "生图失败"));
        }
        String mime = (String) gen.get("mime");
        String ext = mime.contains("png") ? "png" : "svg";
        String b64 = (String) gen.get("b64");
        String fileName = UUID.randomUUID().toString().substring(0, 12) + "." + ext;

        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            Path target = generatedImagesDir().resolve(fileName);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException | IllegalArgumentException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "图片落盘失败: " + e.getMessage()));
        }

        String filePath = IMAGES_SUBDIR + "/" + fileName;
        Map<String, Object> saved = databaseService.saveAsset(name, assetType, null, null, filePath, null);
        Map<String, Object> out = new LinkedHashMap<>(gen);
        out.put("name", name);
        out.put("asset_type", assetType);
        out.put("file_path", filePath);
        out.put("url", "/api/image/file/" + fileName);
        out.put("asset", saved);
        out.put("fallback", Boolean.TRUE.equals(gen.get("fallback")));
        return ResponseEntity.ok(out);
    }

    /** P-0805-C：读取运行时生成图片（显式 content-type；不存在 → 404）。 */
    @GetMapping("/file/{fileName}")
    public ResponseEntity<?> file(@PathVariable String fileName) {
        // 防路径穿越：仅允许纯文件名（无斜杠/点段）
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "非法文件名"));
        }
        Path p = generatedImagesDir().resolve(fileName);
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            return ResponseEntity.notFound().build();
        }
        String mime = fileName.endsWith(".png") ? "image/png" : "image/svg+xml";
        try {
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.parseMediaType(mime))
                    .cacheControl(org.springframework.http.CacheControl.noCache())
                    .body(Files.readAllBytes(p));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "读取失败"));
        }
    }

    /** 运行时生成图片目录：./data/generated/images/（jar 外持久化，重启不丢）。 */
    static Path generatedImagesDir() {
        return Paths.get(System.getProperty("user.dir"), GENERATED_ROOT, IMAGES_SUBDIR);
    }
}
