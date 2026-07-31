package com.roleplay.engine.db.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.db.entity.*;
import com.roleplay.engine.db.repository.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ObjectMapper mapper;

    public DatabaseService(CharacterRepository characterRepo,
                           SceneRepository sceneRepo,
                           ConversationLogRepository conversationLogRepo,
                           WorldSnapshotRepository worldSnapshotRepo,
                           ScriptRepository scriptRepo,
                           GameSessionRepository gameSessionRepo) {
        this.characterRepo = characterRepo;
        this.sceneRepo = sceneRepo;
        this.conversationLogRepo = conversationLogRepo;
        this.worldSnapshotRepo = worldSnapshotRepo;
        this.scriptRepo = scriptRepo;
        this.gameSessionRepo = gameSessionRepo;
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
        CharacterEntity entity = characterRepo.findByName(name)
                .orElse(new CharacterEntity(name, persona, voice, background));
        entity.setPersona(persona != null ? persona : "");
        entity.setVoice(voice != null ? voice : "");
        entity.setBackground(background != null ? background : "");
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
                (String) ch.getOrDefault("background", "")
            );
        }
    }

    // ── Scenes ─────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> saveScene(String sceneId, String name,
                                          String description, List<String> agents,
                                          String keywords) {
        String agentStr = agents != null ? String.join(",", agents) : "";
        SceneEntity entity = sceneRepo.findById(sceneId)
                .orElse(new SceneEntity(sceneId, name, description, agentStr));
        entity.setName(name != null ? name : "未命名场景");
        entity.setDescription(description != null ? description : "");
        entity.setInitialAgentNames(agentStr);
        entity.setKeywords(keywords != null ? keywords : "");
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

    // ── Helpers ────────────────────────────────────────────────

    private Map<String, Object> entityToMap(CharacterEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", e.getName());
        map.put("persona", e.getPersona());
        map.put("voice", e.getVoice());
        map.put("background", e.getBackground());
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> entityToMap(SceneEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scene_id", e.getId());
        map.put("name", e.getName());
        map.put("description", e.getDescription());
        map.put("keywords", e.getKeywords() != null ? e.getKeywords() : "");
        String agents = e.getInitialAgentNames();
        if (agents != null && !agents.isEmpty()) {
            map.put("initial_agent_names", List.of(agents.split(",")));
        } else {
            map.put("initial_agent_names", List.of());
        }
        map.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        return map;
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
