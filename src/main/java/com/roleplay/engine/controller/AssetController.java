package com.roleplay.engine.controller;

import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.db.repository.SceneRepository;
import com.roleplay.engine.db.service.DatabaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * P-0804-C：素材库 REST 端点 —— 角色像素动画（Aseprite PNG+JSON）/ 场景瓦片图集（tileset）素材登记与查询。
 *
 * <p>导入语义（优先简单方案）：导入 = 登记元数据 + 文件路径引用，不做 multipart 上传——
 * 素材文件由调用方/前端预先放置到 {@code src/main/resources/static/assets/} 下（filePath 为相对路径），
 * 本端点仅负责持久化登记 + 关联校验 + 供前端/Phaser 消费查询。
 * 取舍说明：multipart 上传需处理二进制存储与静态资源落盘的安全边界（路径穿越/大小限制），
 * 本阶段文件实体由人工/脚本预置更可控；Aseprite JSON 全文可经 meta_json 字段登记，
 * 前端直接消费（Phaser load.aseprite 的 JSON 经 Blob URL 提供，无需二次落盘）。
 *
 * <p>关联校验（二选一，本实现选「严格拒绝」）：character_name / scene_id 提供时必须存在
 * （CharacterRepository / SceneRepository 校验），不匹配 → 400；两者皆空 = 未关联素材，允许。
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    /** 合法素材类型（注册即规范化大写） */
    public static final Set<String> ASSET_TYPES = Set.of("CHARACTER_ANIMATION", "SCENE_TILESET");

    private final DatabaseService databaseService;
    private final CharacterRepository characterRepo;
    private final SceneRepository sceneRepo;

    public AssetController(DatabaseService databaseService,
                           CharacterRepository characterRepo,
                           SceneRepository sceneRepo) {
        this.databaseService = databaseService;
        this.characterRepo = characterRepo;
        this.sceneRepo = sceneRepo;
    }

    /** POST /api/assets/import —— 素材登记（body: name/asset_type/character_name?/scene_id?/file_path/meta_json?） */
    @PostMapping("/import")
    public ResponseEntity<?> importAsset(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        String name = str(body.get("name"), "");
        String assetType = str(body.get("asset_type"), "").toUpperCase(Locale.ROOT);
        String characterName = nullable(body.get("character_name"));
        String sceneId = nullable(body.get("scene_id"));
        String filePath = str(body.get("file_path"), "");
        String metaJson = nullable(body.get("meta_json"));

        if (name.isBlank()) return badRequest("素材名 name 必填");
        if (!ASSET_TYPES.contains(assetType)) {
            return badRequest("asset_type 非法（应为 CHARACTER_ANIMATION 或 SCENE_TILESET）：" + assetType);
        }
        if (filePath.isBlank()) return badRequest("file_path 必填（素材文件相对 static/assets/ 的路径）");
        // 关联校验：提供即必须存在（严格拒绝）；两者皆空 = 未关联素材允许
        if (characterName != null && !characterRepo.existsByName(characterName)) {
            return badRequest("关联角色不存在: " + characterName);
        }
        if (sceneId != null && !sceneId.isBlank() && !sceneRepo.existsById(sceneId)) {
            return badRequest("关联场景不存在: " + sceneId);
        }

        Map<String, Object> saved = databaseService.saveAsset(
                name, assetType, characterName, sceneId, filePath, metaJson);
        if (saved.containsKey("error")) {
            return ResponseEntity.internalServerError().body(saved);
        }
        return ResponseEntity.ok(withLinkedNames(saved));
    }

    /** GET /api/assets —— 素材列表（可选 ?type=&character=&scene= 过滤） */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) String type,
                                                          @RequestParam(required = false) String character,
                                                          @RequestParam(required = false) String scene) {
        List<Map<String, Object>> list = databaseService.findAssets(
                nullable(type), nullable(character), nullable(scene));
        return ResponseEntity.ok(list.stream().map(this::withLinkedNames).collect(Collectors.toList()));
    }

    /** GET /api/assets/{id} —— 单个素材 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return databaseService.findAssetById(id)
                .map(a -> ResponseEntity.ok((Object) withLinkedNames(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** DELETE /api/assets/{id} —— 删除素材登记（文件实体不删除，仅移除登记） */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Optional<Map<String, Object>> existing = databaseService.findAssetById(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        databaseService.deleteAsset(id);
        return ResponseEntity.ok().build();
    }

    /** 响应附加关联对象名（角色名/场景名，供前端列表直接展示；素材登记本身存的是 name/id 引用） */
    private Map<String, Object> withLinkedNames(Map<String, Object> asset) {
        Map<String, Object> out = new LinkedHashMap<>(asset);
        String cn = (String) asset.get("character_name");
        out.put("linked_character_name", cn != null && !cn.isBlank() && characterRepo.existsByName(cn) ? cn : null);
        String sid = (String) asset.get("scene_id");
        out.put("linked_scene_name", sid != null && !sid.isBlank()
                ? sceneRepo.findById(sid).map(s -> (Object) s.getName()).orElse(null)
                : null);
        return out;
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isEmpty() ? def : s;
    }

    /** 空串归一 null（未关联语义） */
    private static String nullable(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg, "detail", msg));
    }
}
