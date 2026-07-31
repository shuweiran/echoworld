package com.roleplay.engine.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multiplayer room management.
 * Maps from Python api/routes_room.py.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final Map<String, Map<String, Object>> rooms = new ConcurrentHashMap<>();

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRoom(@RequestBody Map<String, Object> body) {
        String code = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String host = (String) body.getOrDefault("host",
            body.getOrDefault("player_name", "unknown"));
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("code", code);
        room.put("mode", body.getOrDefault("mode", "free"));
        room.put("host", host);
        room.put("players", new ArrayList<>(List.of(host)));
        room.put("assignments", new HashMap<>());
        rooms.put(code, room);
        // F-CTR-02: 前端 createRoom 读 (await w.createRoom(a)).room.code/.players/.assignments，
        // 顶层直接返回房间对象取不到，必须包一层 room
        return ResponseEntity.ok(Map.of("room", room));
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> getRoom(@PathVariable String code) {
        Map<String, Object> room = rooms.get(code);
        if (room == null) return ResponseEntity.notFound().build();
        // F-CTR-02 同一契约: 前端 refreshRoom 读 (await w.getRoom(l)).room.players/.assignments
        return ResponseEntity.ok(Map.of("room", room));
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<?> joinRoom(@PathVariable String code, @RequestBody Map<String, String> body) {
        Map<String, Object> room = rooms.get(code);
        if (room == null) return ResponseEntity.ok(Map.of("error", "房间不存在"));
        @SuppressWarnings("unchecked")
        List<String> players = (List<String>) room.get("players");
        // 前端 joinRoom 发送 {player_name, mode}（client.ts），优先取 player_name
        String player = body.getOrDefault("player_name",
            body.getOrDefault("player", "unknown"));
        if (!players.contains(player)) players.add(player);
        // F-CTR-02 同一契约: 前端 joinRoom 读 .room.code/.players/.assignments，需 room 包装
        return ResponseEntity.ok(Map.of("room", room));
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<?> leaveRoom(@PathVariable String code, @RequestBody Map<String, String> body) {
        Map<String, Object> room = rooms.get(code);
        if (room == null) return ResponseEntity.ok(Map.of("error", "房间不存在"));
        @SuppressWarnings("unchecked")
        List<String> players = (List<String>) room.get("players");
        // 前端 leaveRoom 发送 {player_name}（client.ts），原代码只读 player 字段导致移不掉人
        players.remove(body.getOrDefault("player_name",
            body.getOrDefault("player", "")));
        return ResponseEntity.ok(Map.of("status", "left"));
    }

    @PostMapping("/{code}/assign")
    public ResponseEntity<?> assignRoles(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Map<String, Object> room = rooms.get(code);
        if (room == null) return ResponseEntity.ok(Map.of("error", "房间不存在"));
        // F-CTR-03: 前端 assignRoomCharacters 发送 {characters: <分配>}（client.ts），
        // 而非旧契约的 {assignments: ...}；后端统一存为 room.assignments 并整体回显
        Object assignments = body.get("characters");
        if (assignments == null) {
            // 向后兼容: 旧契约字段 assignments
            assignments = body.get("assignments");
        }
        if (assignments != null) {
            room.put("assignments", assignments);
        }
        // 前端读 .room.assignments / .room.players（store assignRoomCharacters），需 room 包装
        return ResponseEntity.ok(Map.of("room", room));
    }
}
