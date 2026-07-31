package com.roleplay.engine.controller;

import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptGameService;
import com.roleplay.engine.simulation.SimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Script murder mystery game endpoints.
 */
@RestController
@RequestMapping("/api/script")
public class ScriptController {

    private static final Logger log = LoggerFactory.getLogger(ScriptController.class);

    private final ScriptGameService scriptGameService;
    private final RouterService router;
    private final SimulationService simulationService;
    private final Map<String, String> playerSessions = new ConcurrentHashMap<>();
    private String currentSessionId = "";

    public ScriptController(ScriptGameService scriptGameService, RouterService router,
                            SimulationService simulationService) {
        this.scriptGameService = scriptGameService;
        this.router = router;
        this.simulationService = simulationService;
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> init(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> players = (List<String>) body.getOrDefault("players", List.of());
        String theme = (String) body.getOrDefault("theme", "默认主题");
        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        currentSessionId = sessionId;
        players.forEach(p -> playerSessions.put(p, sessionId));
        Map<String, Object> state = scriptGameService.initGame(sessionId, theme, players);
        // D5: 将剧本局注册到 RouterService，secrets 随对话注入对应角色上下文
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game != null) {
            router.setScriptGame(game);
            log.info("Script game {} registered to router, {} secrets issued", sessionId, game.getSecrets().size());
        }
        return ResponseEntity.ok(state);
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String location = body.getOrDefault("location", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        return ResponseEntity.ok(scriptGameService.search(sessionId, player, location));
    }

    @PostMapping("/start_discussion")
    public ResponseEntity<Map<String, Object>> startDiscussion(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        boolean transitioned = scriptGameService.startDiscussion(sessionId);
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game == null) {
            return ResponseEntity.ok(Map.of("phase", "not_found", "simulation_started", false));
        }

        boolean simulationStarted = game.isSimulationStarted();
        if (transitioned && !simulationStarted) {
            simulationService.initWithPersonas(scriptGameService.buildSimulationPersonas(sessionId), "cafe");
            simulationService.setSecretAgents(scriptGameService.getSecretPlayers(sessionId));
            scriptGameService.buildDiscussionGoals(sessionId)
                .forEach(simulationService::setTrackGoal);
            simulationService.start();
            scriptGameService.markSimulationStarted(sessionId);
            simulationStarted = true;
            log.info("Script game {} bridged into 2D simulation: {} players, secretAgents={}",
                sessionId, game.getPlayers().size(), scriptGameService.getSecretPlayers(sessionId));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", "discussion");
        result.put("simulation_started", simulationStarted);
        result.put("simulation_url", "/simulation.html");
        result.put("simulation_state_url", "/api/simulation/state");
        result.put("track_state_url", "/api/simulation/track/state");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start_voting")
    public ResponseEntity<Map<String, Object>> startVoting(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        scriptGameService.startVoting(sessionId);
        return ResponseEntity.ok(Map.of("phase", "vote"));
    }

    @PostMapping("/vote")
    public ResponseEntity<Map<String, Object>> vote(@RequestBody Map<String, String> body) {
        String player = body.getOrDefault("player", "");
        String suspect = body.getOrDefault("suspect", "");
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        String result = scriptGameService.castVote(sessionId, player, suspect);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("session_id", currentSessionId);
        return ResponseEntity.ok(scriptGameService.resolveVote(sessionId));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(defaultValue = "") String player) {
        String sessionId = playerSessions.getOrDefault(player, currentSessionId);
        if (sessionId.isEmpty()) {
            return ResponseEntity.ok(Map.of("phase", "idle"));
        }
        ScriptGameService.ScriptGame game = scriptGameService.getGame(sessionId);
        if (game == null) return ResponseEntity.ok(Map.of("phase", "not_found"));
        return ResponseEntity.ok(game.toMap(player));
    }
}
