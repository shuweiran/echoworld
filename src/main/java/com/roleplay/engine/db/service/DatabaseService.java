package com.roleplay.engine.db.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.db.entity.*;
import com.roleplay.engine.db.repository.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Database persistence layer — bridges in-memory data to H2.
 * Used by controllers and services to persist data across restarts.
 */
@Service
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

    private final CharacterRepository characterRepo;
    private final SceneRepository sceneRepo;
    private final ConversationLogRepository conversationLogRepo;
    private final WorldSnapshotRepository worldSnapshotRepo;
    private final ScriptRepository scriptRepo;
    private final GameSessionRepository gameSessionRepo;
    private final AssetRepository assetRepo;
    private final ObjectMapper mapper;

    /**
     * P-0804-C：旧六参构造保留（委托新七参，assetRepo=null）——既有测试直构调用点（WerewolfRoleKeyTest/
     * WerewolfStage1Test 等）零改动；素材方法在 assetRepo==null 时防御性返回空结果（生产走 @Autowired 七参）。
     */
    public DatabaseService(CharacterRepository characterRepo,
                           SceneRepository sceneRepo,
                           ConversationLogRepository conversationLogRepo,
                           WorldSnapshotRepository worldSnapshotRepo,
                           ScriptRepository scriptRepo,
                           GameSessionRepository gameSessionRepo) {
        this(characterRepo, sceneRepo, conversationLogRepo, worldSnapshotRepo, scriptRepo, gameSessionRepo, null);
    }

    @Autowired
    public DatabaseService(CharacterRepository characterRepo,
                           SceneRepository sceneRepo,
                           ConversationLogRepository conversationLogRepo,
                           WorldSnapshotRepository worldSnapshotRepo,
                           ScriptRepository scriptRepo,
                           GameSessionRepository gameSessionRepo,
                           AssetRepository assetRepo) {
        this.characterRepo = characterRepo;
        this.sceneRepo = sceneRepo;
        this.conversationLogRepo = conversationLogRepo;
        this.worldSnapshotRepo = worldSnapshotRepo;
        this.scriptRepo = scriptRepo;
        this.gameSessionRepo = gameSessionRepo;
        this.assetRepo = assetRepo;
        this.mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
    }

    @PostConstruct
    public void init() {
        log.info("DatabaseService initialized. Data dir: ./data/roleplay");
        long charCount = characterRepo.count();
        long sceneCount = sceneRepo.count();
        if (charCount > 0) {
            log.info("Loaded {} characters from DB", charCount);
        }
        if (sceneCount > 0) {
            log.info("Loaded {} scenes from DB", sceneCount);
        }
    }

    // ── Characters ─────────────────────────────────────────────

    @Transactional
    public Map<String, Object> saveCharacter(String name, String persona,
                                              String voice, String background) {
        return saveCharacter(name, persona, voice, background, null);
    }

    /**
     * Phase 1（改造方案 §3.2）：saveCharacter 带 player_id 重载。
     * 旧签名（无 playerId）委托本方法 null——既有调用点/测试零改动。
     * 语义：playerId 非空时显式写入（建角色落绑定 / update 换绑）；
     * playerId 为空时保留既有绑定（改名迁移绑定，不误解绑）。
     */
    @Transactional
    public Map<String, Object> saveCharacter(String name, String persona,
                                              String voice, String background, String playerId) {
        // P-0803-H：角色名截断（用户 API 可传超长名 → characters.name(255) 溢出 500；200 上限留余量）
        name = truncateName(name, 200);
        CharacterEntity entity = characterRepo.findByName(name)
                .orElse(new CharacterEntity(name, persona, voice, background, playerId));
        entity.setPersona(persona != null ? persona : "");
        entity.setVoice(voice != null ? voice : "");
        entity.setBackground(background != null ? background : "");
        if (playerId != null) {
            entity.setPlayerId(playerId);
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.preUpdate();
        characterRepo.save(entity);
        return entityToMap(entity);
    }

    public List<Map<String, Object>> getAllCharacters() {
        return characterRepo.findAll().stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());
    }

    public Optional<Map<String, Object>> getCharacter(String name) {
        return characterRepo.findByName(name).map(this::entityToMap);
    }

    @Transactional
    public void deleteCharacter(String name) {
        characterRepo.findByName(name).ifPresent(characterRepo::delete);
    }

    @Transactional
    public void saveAllCharacters(List<Map<String, Object>> characters) {
        for (Map<String, Object> ch : characters) {
            saveCharacter(
                (String) ch.getOrDefault("name", "未命名"),
                (String) ch.getOrDefault("persona", ""),
                (String) ch.getOrDefault("voice", ""),
                (String) ch.getOrDefault("background", ""),
                nullableString(ch.get("player_id"))
            );
        }
    }

    // ── Scenes ─────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> saveScene(String sceneId, String name,
                                          String description, List<String> agents,
                                          String keywords) {
        // P-0803-H：旧五参调用方（剧本杀链路 enterScene 建临时场景等）委托新重载，默认值保持旧行为
        return saveScene(sceneId, name, description, agents, keywords, "general", "[]", null);
    }

    /**
     * P-0803-H：带剧本绑定的场景落库重载 —— category（一般/狼人杀）+ defaultRoles（JSON 数组串）+
     * defaultMap（地图 JSON 串，可空）。旧五参重载委托本方法（默认 general / 空角色组 / 无地图）。
     */
    @Transactional
    public Map<String, Object> saveScene(String sceneId, String name,
                                          String description, List<String> agents,
                                          String keywords, String category,
                                          String defaultRolesJson, String defaultMapJson) {
        String agentStr = agents != null ? String.join(",", agents) : "";
        SceneEntity entity = sceneRepo.findById(sceneId)
                .orElse(new SceneEntity(sceneId, name, description, agentStr));
        // P-0803-G（HTTP 500 修复）：LLM 生成的剧本 metadata.title 可能远超 255（实测 268 字符），
        // scenes.name VARCHAR(255) 超长 → H2 DataIntegrityViolation → 500。双保险：① 列宽扩到 2000；
        // ② 落库前截断到 500（覆盖所有调用方，防 LLM 极端超长输出；内存态 game.name 不受影响）。
        entity.setName(truncateName(name, 500));
        entity.setDescription(description != null ? description : "");
        entity.setInitialAgentNames(agentStr);
        entity.setKeywords(keywords != null ? keywords : "");
        // P-0803-H：category 非法值归一 general；defaultRoles/defaultMap 语义：
        // null=不修改保留旧值；defaultRoles 空串→清空为 []；defaultMap 空串→清空为 null（前端「清除地图」用）
        String cat = category;
        if (cat == null || cat.isBlank()) cat = "general";
        entity.setCategory(cat);
        if (defaultRolesJson != null) {
            entity.setDefaultRoles(defaultRolesJson.isBlank() ? "[]" : defaultRolesJson);
        }
        if (defaultMapJson != null) {
            entity.setDefaultMap(defaultMapJson.isBlank() ? null : defaultMapJson);
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        sceneRepo.save(entity);
        return entityToMap(entity);
    }

    public List<Map<String, Object>> getAllScenes() {
        return sceneRepo.findAll().stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());
    }

    public Optional<Map<String, Object>> getScene(String id) {
        return sceneRepo.findById(id).map(this::entityToMap);
    }

    @Transactional
    public void deleteScene(String id) {
        sceneRepo.findById(id).ifPresent(sceneRepo::delete);
    }

    // ── Conversation Logs ──────────────────────────────────────

    @Transactional
    public Map<String, Object> logConversation(String groupId, String mode,
                                                List<String> participants,
                                                List<Map<String, Object>> messages,
                                                int tick) {
        String participantsStr = participants != null ? String.join(",", participants) : "";
        String messagesJson;
        try {
            messagesJson = messages != null ? mapper.writeValueAsString(messages) : "[]";
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize messages: {}", e.getMessage());
            messagesJson = "[]";
        }
        ConversationLogEntity entity = new ConversationLogEntity(
                groupId, mode, participantsStr, messagesJson, tick);
        conversationLogRepo.save(entity);
        return entityToMap(entity);
    }

    public List<Map<String, Object>> getConversationLogs(String groupId) {
        return conversationLogRepo.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());
    }

    // ── World Snapshots ────────────────────────────────────────

    @Transactional
    public Map<String, Object> saveWorldSnapshot(String name,
                                                   List<Map<String, Object>> agents,
                                                   String scene, int tick) {
        String agentsJson;
        try {
            agentsJson = agents != null ? mapper.writeValueAsString(agents) : "[]";
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize agents: {}", e.getMessage());
            agentsJson = "[]";
        }
        WorldSnapshotEntity entity = new WorldSnapshotEntity(name, scene, agentsJson, tick);
        worldSnapshotRepo.save(entity);
        return entityToMap(entity);
    }

    public List<Map<String, Object>> getRecentSnapshots(int limit) {
        return worldSnapshotRepo.findTop10ByOrderByCreatedAtDesc().stream()
                .limit(limit)
                .map(this::entityToMap)
                .collect(Collectors.toList());
    }

    // ── Scripts ────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> saveScript(String name, Map<String, Object> content) {
        // P-0803-H：落库前截断（调用点拼 "剧本："+game.name / "对局结果："+game.name，title 已源头规约 100，
        // 但快照/其它来源仍可能超长；500 兜底与 saveScene 的 truncateName 同策略）
        name = truncateName(name, 500);
        String contentJson;
        try {
            contentJson = content != null ? mapper.writeValueAsString(content) : "{}";
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize script content: {}", e.getMessage());
            contentJson = "{}";
        }
        ScriptEntity entity = new ScriptEntity(name, contentJson);
        scriptRepo.save(entity);
        return entityToMap(entity);
    }

    public List<Map<String, Object>> getAllScripts() {
        return scriptRepo.findAll().stream()
                .map(this::entityToMap)
                .collect(Collectors.toList());
    }

    /**
     * C3: 读取指定对局的最新快照（type=snapshot 行，name 前缀「对局快照:<sessionId>」）。
     * 断线重连/重启恢复用：内存对局丢失后，从最近一次快照重建完整状态。
     *
     * @return 快照内容 map（含 type/session_id/phase/players/assignments/player_keys 等全量状态）；无快照返回 empty
     */
    public Optional<Map<String, Object>> getLatestScriptSnapshot(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        List<ScriptEntity> list = scriptRepo.findByNameStartingWithOrderByIdDesc("对局快照:" + sessionId);
        if (list.isEmpty()) return Optional.empty();
        Object content = entityToMap(list.get(0)).get("content");
        if (content instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cm = (Map<String, Object>) m;
            return Optional.of(cm);
        }
        return Optional.empty();
    }

    // ── Game Sessions ──────────────────────────────────────────

    @Transactional
    public Map<String, Object> createGameSession(String sessionType, String name,
                                                   Map<String, Object> state,
                                                   List<String> agents,
                                                   String currentScene) {
        String id = UUID.randomUUID().toString();
        String stateJson;
        try {
            stateJson = state != null ? mapper.writeValueAsString(state) : "{}";
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize state: {}", e.getMessage());
            stateJson = "{}";
        }
        String agentsStr = agents != null ? String.join(",", agents) : "";
        GameSessionEntity entity = new GameSessionEntity(
                id, sessionType, name, stateJson, agentsStr, currentScene);
        gameSessionRepo.save(entity);
        return entityToMap(entity);
    }

    @Transactional
    public Map<String, Object> updateGameSession(String id, Map<String, Object> state,
                                                   String currentScene) {
        return gameSessionRepo.findById(id).map(entity -> {
            if (state != null) {
                try {
                    entity.setStateJson(mapper.writeValueAsString(state));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize state: {}", e.getMessage());
                }
            }
            if (currentScene != null) {
                entity.setCurrentScene(currentScene);
            }
            entity.preUpdate();
            gameSessionRepo.save(entity);
            return entityToMap(entity);
        }).orElseGet(() -> {
            log.warn("Game session {} not found for update", id);
            return Map.of("error", "not_found");
        });
    }

    public List<Map<String, Object>> getActiveSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String type : List.of("free", "werewolf", "2d", "script")) {
            gameSessionRepo.findBySessionTypeOrderByUpdatedAtDesc(type).stream()
                    .map(this::entityToMap)
                    .forEach(result::add);
        }
        return result;
    }

    public Optional<Map<String, Object>> getGameSession(String id) {
        return gameSessionRepo.findById(id).map(this::entityToMap);
    }

    // ── Assets（P-0804-C 素材库）────────────────────────────────

    /**
     * P-0804-C：素材登记（导入 = 登记元数据 + 文件路径引用；文件实体由调用方预先放置到 static/assets/ 下）。
     * 重名（name 唯一键）→ 更新既有行（幂等导入）；否则新建。
     */
    @Transactional
    public Map<String, Object> saveAsset(String name, String assetType, String characterName,
                                          String sceneId, String filePath, String metaJson) {
        if (assetRepo == null) return Map.of("error", "asset_repo_unavailable");
        name = truncateName(name, 200);
        AssetEntity entity = assetRepo.findByName(name)
                .orElse(new AssetEntity(name, assetType, characterName, sceneId, filePath, metaJson));
        entity.setName(name);
        entity.setAssetType(assetType);
        entity.setCharacterName(characterName);
        entity.setSceneId(sceneId);
        entity.setFilePath(filePath != null ? filePath : "");
        entity.setMetaJson(metaJson);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.preUpdate();
        assetRepo.save(entity);
        return entityToMap(entity);
    }

    /**
     * P-0804-C：素材列表（按 assetType/characterName/sceneId 组合过滤；全空 = 全量）。
     * 过滤值非空才参与查询（JPA 派生查询不传 null，避免 null 匹配语义错误）。
     */
    public List<Map<String, Object>> findAssets(String assetType, String characterName, String sceneId) {
        if (assetRepo == null) return List.of();
        List<AssetEntity> list;
        if (isBlank(assetType) && isBlank(characterName) && isBlank(sceneId)) {
            list = assetRepo.findAll();
        } else if (isBlank(characterName) && isBlank(sceneId)) {
            list = assetRepo.findByAssetType(assetType);
        } else if (isBlank(assetType) && isBlank(sceneId)) {
            list = assetRepo.findByCharacterName(characterName);
        } else if (isBlank(assetType) && isBlank(characterName)) {
            list = assetRepo.findBySceneId(sceneId);
        } else if (isBlank(sceneId)) {
            list = assetRepo.findByAssetTypeAndCharacterName(assetType, characterName);
        } else if (isBlank(characterName)) {
            list = assetRepo.findByAssetTypeAndSceneId(assetType, sceneId);
        } else if (isBlank(assetType)) {
            list = assetRepo.findByCharacterNameAndSceneId(characterName, sceneId);
        } else {
            list = assetRepo.findByAssetTypeAndCharacterNameAndSceneId(assetType, characterName, sceneId);
        }
        return list.stream().map(this::entityToMap).collect(Collectors.toList());
    }

    public Optional<Map<String, Object>> findAssetById(Long id) {
        if (assetRepo == null || id == null) return Optional.empty();
        return assetRepo.findById(id).map(this::entityToMap);
    }

    @Transactional
    public void deleteAsset(Long id) {
        if (assetRepo == null || id == null) return;
        assetRepo.findById(id).ifPresent(assetRepo::delete);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ── Helpers ────────────────────────────────────────────────

    private Map<String, Object> entityToMap(CharacterEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", e.getName());
        map.put("persona", e.getPersona());
        map.put("voice", e.getVoice());
        map.put("background", e.getBackground());
        map.put("player_id", e.getPlayerId());
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        return map;
    }

    /** 空字符串视为 null（解绑语义：Phase 1 不解绑，仅规范化入参） */
    private static String nullableString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isEmpty() ? null : s;
    }

    /** P-0803-G：剧本名截断（LLM metadata.title 可能超长，防 scenes.name 列溢出） */
    private static String truncateName(String name, int maxLen) {
        if (name == null || name.isEmpty()) return "未命名场景";
        return name.length() <= maxLen ? name : name.substring(0, maxLen);
    }

    private Map<String, Object> entityToMap(SceneEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scene_id", e.getId());
        map.put("name", e.getName());
        map.put("description", e.getDescription());
        map.put("keywords", e.getKeywords() != null ? e.getKeywords() : "");
        // P-0803-H：category / default_roles / default_map 三个绑定键（宽容解析：旧行/空值给默认，不崩）
        map.put("category", e.getCategory() != null && !e.getCategory().isBlank() ? e.getCategory() : "general");
        map.put("default_roles", parseRoleList(e.getDefaultRoles()));
        map.put("default_map", parseJsonMap(e.getDefaultMap()));
        String agents = e.getInitialAgentNames();
        if (agents != null && !agents.isEmpty()) {
            map.put("initial_agent_names", List.of(agents.split(",")));
        } else {
            map.put("initial_agent_names", List.of());
        }
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        return map;
    }

    /** P-0803-H：defaultRoles JSON 数组串 → List（宽容解析：空/非法 → 空列表） */
    private static List<String> parseRoleList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            Object v = new ObjectMapper().readValue(raw, Object.class);
            if (v instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o));
                return out;
            }
        } catch (Exception ignored) {
            // 非 JSON（旧逗号分隔等）→ 按逗号拆分兜底
            List<String> out = new ArrayList<>();
            for (String s : raw.split(",")) if (!s.isBlank()) out.add(s.trim());
            return out;
        }
        return List.of();
    }

    /** P-0803-H：defaultMap JSON 串 → Map/List（宽容解析：空/非法 → null） */
    private static Object parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new ObjectMapper().readValue(raw, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> entityToMap(ConversationLogEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("groupId", e.getGroupId());
        map.put("mode", e.getMode());
        map.put("participants", e.getParticipants());
        map.put("tick", e.getTick());
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        // Try to parse messagesJson
        try {
            if (e.getMessagesJson() != null && !e.getMessagesJson().isEmpty()) {
                map.put("messages", mapper.readValue(e.getMessagesJson(), List.class));
            } else {
                map.put("messages", List.of());
            }
        } catch (JsonProcessingException ex) {
            map.put("messages", e.getMessagesJson());
        }
        return map;
    }

    private Map<String, Object> entityToMap(WorldSnapshotEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("name", e.getName());
        map.put("scene", e.getScene());
        map.put("tick", e.getTick());
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        try {
            if (e.getAgentsJson() != null && !e.getAgentsJson().isEmpty()) {
                map.put("agents", mapper.readValue(e.getAgentsJson(), List.class));
            } else {
                map.put("agents", List.of());
            }
        } catch (JsonProcessingException ex) {
            map.put("agents", e.getAgentsJson());
        }
        return map;
    }

    private Map<String, Object> entityToMap(ScriptEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("name", e.getName());
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        try {
            if (e.getContentJson() != null && !e.getContentJson().isEmpty()) {
                map.put("content", mapper.readValue(e.getContentJson(), Map.class));
            } else {
                map.put("content", Map.of());
            }
        } catch (JsonProcessingException ex) {
            map.put("content", e.getContentJson());
        }
        return map;
    }

    private Map<String, Object> entityToMap(AssetEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("name", e.getName());
        map.put("asset_type", e.getAssetType());
        map.put("character_name", e.getCharacterName());
        map.put("scene_id", e.getSceneId());
        map.put("file_path", e.getFilePath());
        map.put("meta_json", e.getMetaJson());
        map.put("created_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        map.put("updated_at", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> entityToMap(GameSessionEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("sessionType", e.getSessionType());
        map.put("name", e.getName());
        map.put("agents", e.getAgents());
        map.put("currentScene", e.getCurrentScene());
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        map.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
        try {
            if (e.getStateJson() != null && !e.getStateJson().isEmpty()) {
                map.put("state", mapper.readValue(e.getStateJson(), Map.class));
            } else {
                map.put("state", Map.of());
            }
        } catch (JsonProcessingException ex) {
            map.put("state", e.getStateJson());
        }
        return map;
    }
}
