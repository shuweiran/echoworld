package com.roleplay.engine.controller;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.GeneratorService;
import jakarta.annotation.PostConstruct;
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

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(new ArrayList<>(characters));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", str(body.get("name"), "未命名"));
        ch.put("persona", str(body.get("persona"), ""));
        ch.put("voice", str(body.get("voice"), ""));
        ch.put("background", str(body.get("background"), ""));
        characters.add(ch);
        databaseService.saveCharacter((String) ch.get("name"), (String) ch.get("persona"),
                (String) ch.get("voice"), (String) ch.get("background"));
        return ResponseEntity.ok(ch);
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name, @RequestBody Map<String, Object> body) {
        for (int i = 0; i < characters.size(); i++) {
            if (name.equals(characters.get(i).get("name"))) {
                Map<String, Object> updated = new LinkedHashMap<>(characters.get(i));
                body.forEach((k, v) -> { if (v != null) updated.put(k, v); });
                characters.set(i, updated);
                String newName = str(updated.get("name"), "未命名");
                if (!name.equals(newName)) {
                    // Rename: name is the unique key in H2 → drop old row first
                    databaseService.deleteCharacter(name);
                }
                databaseService.saveCharacter(newName, str(updated.get("persona"), ""),
                        str(updated.get("voice"), ""), str(updated.get("background"), ""));
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
    public ResponseEntity<List<Map<String, Object>>> batch(@RequestBody List<Map<String, Object>> batch) {
        for (Map<String, Object> ch : batch) {
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("name", str(ch.get("name"), "未命名"));
            clean.put("persona", str(ch.get("persona"), ""));
            clean.put("voice", str(ch.get("voice"), ""));
            clean.put("background", str(ch.get("background"), ""));
            characters.add(clean);
            databaseService.saveCharacter((String) clean.get("name"), (String) clean.get("persona"),
                    (String) clean.get("voice"), (String) clean.get("background"));
        }
        return ResponseEntity.ok(new ArrayList<>(characters));
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isEmpty() ? def : s;
    }
}
