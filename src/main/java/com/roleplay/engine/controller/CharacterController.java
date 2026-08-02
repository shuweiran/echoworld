package com.roleplay.engine.controller;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.GeneratorService;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Character CRUD endpoints.
 * Maps from Python api/routes_characters.py.
 *
 * <p>Storage: in-memory list mirrors H2 (DatabaseService). Startup loads persisted
 * characters from H2; every write (create/update/delete/batch) is persisted
 * synchronously so data survives restarts.
 */
@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final List<Map<String, Object>> characters = new CopyOnWriteArrayList<>();
    private final GeneratorService generator;
    private final DatabaseService databaseService;

    public CharacterController(GeneratorService generator, DatabaseService databaseService) {
        this.generator = generator;
        this.databaseService = databaseService;
    }

    @PostConstruct
    public void init() {
        // Load persisted characters from H2 (survives restarts)
        characters.addAll(databaseService.getAllCharacters());
        // Seed default only when DB is empty — avoids duplicates on restart
        if (characters.isEmpty()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", "助手");
            def.put("persona", "温柔体贴的助手");
            def.put("voice", "温和");
            def.put("background", "一直在你身边");
            characters.add(def);
            databaseService.saveCharacter("助手", "温柔体贴的助手", "温和", "一直在你身边");
        }
    }

    public List<Map<String, Object>> getAll() { return new ArrayList<>(characters); }

    /**
     * P-0802-P3（改造方案 §4.2 角色库改名）：内存列表改名 + DB 删旧建新（playerId 绑定随新行保留）。
     * 由 PlayerIdentityService.renamePlayerCharacter 编排调用（调用方已完成撞名校验②）。
     * 返回更新后的角色 map；旧名不存在返回 null（调用方按 404 处理）。
     * 撞名兜底：DB unique 冲突（并发窗口）→ 回滚内存列表并抛 DataIntegrityViolationException（调用方回滚）。
     */
    public synchronized Map<String, Object> renameCharacterInMemory(String oldName, String newName) {
        for (int i = 0; i < characters.size(); i++) {
            if (oldName.equals(characters.get(i).get("name"))) {
                Map<String, Object> original = new LinkedHashMap<>(characters.get(i));
                Map<String, Object> updated = new LinkedHashMap<>(original);
                updated.put("name", newName);
                characters.set(i, updated);
                // 改名：H2 name 是唯一键 → 先删旧行再存新行（playerId 绑定随新行保留）
                databaseService.deleteCharacter(oldName);
                try {
                    databaseService.saveCharacter(newName, str(updated.get("persona"), ""),
                            str(updated.get("voice"), ""), str(updated.get("background"), ""),
                            pid(updated.get("player_id")));
                } catch (DataIntegrityViolationException e) {
                    // 并发撞名兜底：回滚内存列表，交给调用方统一回滚
                    characters.set(i, original);
                    throw e;
                }
                return updated;
            }
        }
        return null;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(new ArrayList<>(characters));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String nm = str(body.get("name"), "未命名");
        // 撞名校验 ①（改造方案 §5 层 ①）：内存列表已有同名 → 409，不落库不覆盖
        if (hasName(nm)) {
            return conflict("角色名已存在: " + nm);
        }
        String pid = pid(body.get("player_id"));
        if (pid != null && hasPlayerId(pid)) {
            return conflict("该玩家已绑定角色");
        }
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", nm);
        ch.put("persona", str(body.get("persona"), ""));
        ch.put("voice", str(body.get("voice"), ""));
        ch.put("background", str(body.get("background"), ""));
        ch.put("player_id", pid);
        characters.add(ch);
        try {
            databaseService.saveCharacter(nm, (String) ch.get("persona"),
                    (String) ch.get("voice"), (String) ch.get("background"), pid);
        } catch (DataIntegrityViolationException e) {
            // DB unique 兜底（③层，并发窗口）：player_id/name 被并发占用 → 回滚内存列表
            characters.removeIf(c -> nm.equals(c.get("name")));
            return conflict("该玩家已绑定角色");
        }
        return ResponseEntity.ok(ch);
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name, @RequestBody Map<String, Object> body) {
        for (int i = 0; i < characters.size(); i++) {
            if (name.equals(characters.get(i).get("name"))) {
                Map<String, Object> original = new LinkedHashMap<>(characters.get(i));
                Map<String, Object> updated = new LinkedHashMap<>(original);
                body.forEach((k, v) -> { if (v != null) updated.put(k, v); });
                String newName = str(updated.get("name"), "未命名");
                // 撞名校验 ①：改名撞名（排除自身）→ 409，不覆盖同名角色 persona
                if (!newName.equals(name) && hasName(newName)) {
                    return conflict("角色名已存在: " + newName);
                }
                // playerId 绑定校验：换绑到已被其他角色占用的玩家 → 409（update 保留既有绑定）
                String pid = pid(updated.get("player_id"));
                if (pid != null && !pid.equals(original.get("player_id")) && hasPlayerId(pid)) {
                    return conflict("该玩家已绑定角色");
                }
                characters.set(i, updated);
                if (!name.equals(newName)) {
                    // Rename: name is the unique key in H2 → drop old row first
                    databaseService.deleteCharacter(name);
                }
                try {
                    databaseService.saveCharacter(newName, str(updated.get("persona"), ""),
                            str(updated.get("voice"), ""), str(updated.get("background"), ""), pid);
                } catch (DataIntegrityViolationException e) {
                    // DB unique 兜底：回滚内存列表（旧行已删则按 original 恢复）
                    characters.set(i, original);
                    if (!name.equals(newName)) {
                        characters.removeIf(c -> newName.equals(c.get("name")));
                    }
                    return conflict("该玩家已绑定角色");
                }
                return ResponseEntity.ok(updated);
            }
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        characters.removeIf(c -> name.equals(c.get("name")));
        databaseService.deleteCharacter(name);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(generator.generateCharacter(body.getOrDefault("keywords", "")));
    }

    @PostMapping("/batch")
    public ResponseEntity<?> batch(@RequestBody List<Map<String, Object>> batch) {
        // 撞名校验 ①：整批预校验——任一撞名（库内已有 或 批内重复）→ 409，整批不落库
        Set<String> seenNames = new HashSet<>();
        Set<String> seenPlayerIds = new HashSet<>();
        for (Map<String, Object> raw : batch) {
            String nm = str(raw.get("name"), "未命名");
            if (hasName(nm) || seenNames.contains(nm)) {
                return conflict("角色名已存在: " + nm);
            }
            seenNames.add(nm);
            String pid = pid(raw.get("player_id"));
            if (pid != null && (hasPlayerId(pid) || seenPlayerIds.contains(pid))) {
                return conflict("该玩家已绑定角色");
            }
            if (pid != null) seenPlayerIds.add(pid);
        }
        for (Map<String, Object> ch : batch) {
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("name", str(ch.get("name"), "未命名"));
            clean.put("persona", str(ch.get("persona"), ""));
            clean.put("voice", str(ch.get("voice"), ""));
            clean.put("background", str(ch.get("background"), ""));
            clean.put("player_id", pid(ch.get("player_id")));
            characters.add(clean);
            try {
                databaseService.saveCharacter((String) clean.get("name"), (String) clean.get("persona"),
                        (String) clean.get("voice"), (String) clean.get("background"),
                        (String) clean.get("player_id"));
            } catch (DataIntegrityViolationException e) {
                // DB unique 兜底（并发窗口）：回滚本批已加项
                characters.removeIf(c -> seenNames.contains(c.get("name")));
                return conflict("该玩家已绑定角色");
            }
        }
        return ResponseEntity.ok(new ArrayList<>(characters));
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isEmpty() ? def : s;
    }

    /** player_id 规范化：null/空串 → null（未绑定） */
    private static String pid(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isEmpty() ? null : s;
    }

    private boolean hasName(String name) {
        return characters.stream().anyMatch(c -> name.equals(c.get("name")));
    }

    private boolean hasPlayerId(String playerId) {
        return characters.stream().anyMatch(c -> playerId.equals(c.get("player_id")));
    }

    /** 撞名 409 响应：{error, detail}（detail 供前端 request() 直接展示） */
    private ResponseEntity<Map<String, Object>> conflict(String msg) {
        return ResponseEntity.status(409).body(Map.of("error", msg, "detail", msg));
    }
}
