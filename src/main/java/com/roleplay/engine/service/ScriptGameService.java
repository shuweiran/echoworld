package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ⭐ Script murder mystery — full game lifecycle.
 *
 * <p>Phases:
 * <ol>
 *   <li>SETUP — generate script, assign roles</li>
 *   <li>INVESTIGATION — players search locations for clues</li>
 *   <li>DISCUSSION — players share/synth clues, accuse</li>
 *   <li>VOTE — players vote for suspect</li>
 *   <li>REVEAL — show truth + score</li>
 * </ol>
 *
 * <p>Maps from Python core/script_runtime.py (which was empty — this is new).
 */
@Service
public class ScriptGameService {
    private static final Logger log = LoggerFactory.getLogger(ScriptGameService.class);

    private final LLMClient llmClient;

    public enum Phase { SETUP, INVESTIGATION, DISCUSSION, VOTE, REVEAL, ENDED }

    public static class ScriptGame {
        String sessionId;
        Phase phase = Phase.SETUP;

        // Script data
        String name = "未命名剧本";
        String background = "";
        String truth = "";
        final List<String> roles = new ArrayList<>();
        final List<String> players = new ArrayList<>();
        final Map<String, String> assignments = new LinkedHashMap<>(); // player → role
        final Map<String, String> secrets = new LinkedHashMap<>();     // role → secret（D5：每个角色只看到自己的）

        // Game state
        int round = 1;
        final List<Map<String, Object>> clues = new ArrayList<>(); // all discovered clues
        final Map<String, List<String>> playerClues = new LinkedHashMap<>(); // player → clueIds
        final Map<String, String> votes = new LinkedHashMap<>(); // voter → suspect
        final List<String> locations = new ArrayList<>();
        String winner = "";

        public Map<String, Object> toMap(String playerName) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase.name().toLowerCase());
            m.put("session_id", sessionId);
            m.put("name", name);
            m.put("background", background);
            m.put("roles", new ArrayList<>(roles));
            m.put("players", new ArrayList<>(players));
            String role = assignments.getOrDefault(playerName, "");
            m.put("your_role", role);
            // D5: secrets 发放 —— 每个玩家只能看到自己扮演角色的秘密
            m.put("your_secret", role.isEmpty() ? "" : secrets.getOrDefault(role, ""));
            m.put("round", round);
            m.put("game_over", !winner.isEmpty());
            m.put("winner", winner);
            m.put("clues", clues.stream()
                .filter(c -> c.getOrDefault("public", false).equals(true)
                    || (playerName != null && playerClues.getOrDefault(playerName, List.of())
                        .contains(c.get("id"))))
                .collect(Collectors.toList()));
            m.put("locations", new ArrayList<>(locations));
            return m;
        }

        /** D5: 该玩家分配到的角色名（未分配返回空串）。 */
        public String getRoleOf(String playerName) {
            if (playerName == null) return "";
            return assignments.getOrDefault(playerName, "");
        }

        /** D5: 发放给对应角色的秘密 —— 每个角色只能看到自己的 secret。 */
        public String getSecretFor(String playerName) {
            if (playerName == null) return "";
            String role = assignments.getOrDefault(playerName, "");
            return role.isEmpty() ? "" : secrets.getOrDefault(role, "");
        }

        public Map<String, String> getSecrets() {
            return secrets;
        }
    }

    private final Map<String, ScriptGame> games = new ConcurrentHashMap<>();

    public ScriptGameService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /** Phase 1: Generate script and assign roles. */
    public Map<String, Object> initGame(String sessionId, String theme, List<String> playerNames) {
        ScriptGame game = new ScriptGame();
        game.sessionId = sessionId;
        game.players.addAll(playerNames);

        // Generate script via LLM
        String prompt = String.format("""
            你是一个剧本杀创作者。请根据以下信息生成一个完整的谋杀之谜剧本。

            主题：%s
            角色数：%d

            剧本要求：
            - 每个角色都有作案动机和秘密
            - 有3-5个可搜证的地点
            - 至少3条线索
            - 真相合理

            返回JSON格式（不要任何markdown标记，纯JSON）：
            {"name": "剧本名称", "background": "背景故事（100-150字）", "roles": ["角色1", "角色2", ...],
             "locations": ["地点1", "地点2", ...],
             "clues": [{"id": "clue_1", "location": "地点1", "content": "线索内容", "public": false, "related_role": "角色名"},
                       {"id": "clue_2", ...}],
             "secrets": {"角色1": "秘密内容", ...},
             "truth": "真相（50-80字）"}
            """, theme, playerNames.size());

        Map<String, Object> script = llmClient.callJson(prompt, 600);
        if (script == null || script.isEmpty()) {
            script = defaultScript(theme, playerNames);
        }

        game.name = (String) script.getOrDefault("name", "默认剧本");
        game.background = (String) script.getOrDefault("background", "一个普通的谋杀案");
        game.truth = (String) script.getOrDefault("truth", "真相待揭晓");

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) script.getOrDefault("roles",
            playerNames.stream().map(p -> "角色_" + p).collect(Collectors.toList()));
        game.roles.addAll(roles);

        @SuppressWarnings("unchecked")
        List<String> locations = (List<String>) script.getOrDefault("locations",
            List.of("客厅", "书房", "花园", "厨房", "地下室"));
        game.locations.addAll(locations);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clues = (List<Map<String, Object>>) script.getOrDefault("clues", List.of());
        if (clues.isEmpty()) {
            clues = List.of(
                Map.of("id", "clue_1", "location", "客厅", "content", "地上有碎玻璃", "public", false, "related_role", roles.size() > 0 ? roles.get(0) : ""),
                Map.of("id", "clue_2", "location", "书房", "content", "桌上有一封匿名信", "public", false, "related_role", roles.size() > 1 ? roles.get(1) : ""),
                Map.of("id", "clue_3", "location", "花园", "content", "泥土中有脚印", "public", true, "related_role", "")
            );
        }
        game.clues.addAll(clues);

        // D5: secrets 发放 —— 解析 LLM 生成的角色秘密（role → secret），按角色存储
        @SuppressWarnings("unchecked")
        Map<String, Object> secretsMap = (Map<String, Object>) script.getOrDefault("secrets", Map.of());
        for (Map.Entry<String, Object> e : secretsMap.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            game.secrets.put(String.valueOf(e.getKey()).trim(), String.valueOf(e.getValue()));
        }
        if (game.secrets.isEmpty()) {
            // 兜底：为每个角色生成通用秘密，保证每位玩家都能收到自己的 secret
            for (String roleName : game.roles) {
                game.secrets.put(roleName, "你有一段不愿为人所知的往事，它与这起案件有着隐秘的联系。");
            }
        }

        // Assign roles to players (shuffle)
        List<String> shuffledRoles = new ArrayList<>(roles);
        Collections.shuffle(shuffledRoles);
        for (int i = 0; i < playerNames.size() && i < shuffledRoles.size(); i++) {
            game.assignments.put(playerNames.get(i), shuffledRoles.get(i));
        }
        // Leftover players get generic roles
        for (int i = shuffledRoles.size(); i < playerNames.size(); i++) {
            game.assignments.put(playerNames.get(i), "嫌疑人_" + (i - shuffledRoles.size() + 1));
        }

        game.phase = Phase.INVESTIGATION;
        game.round = 1;
        games.put(sessionId, game);

        log.info("Script game {}: {} players, {} locations, {} clues, {} secrets",
            sessionId, playerNames.size(), game.locations.size(), game.clues.size(), game.secrets.size());

        return game.toMap(playerNames.isEmpty() ? "" : playerNames.get(0));
    }

    /** Phase 2: Search a location for clues. */
    public Map<String, Object> search(String sessionId, String player, String location) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase != Phase.INVESTIGATION) return Map.of("error", "当前不是搜证阶段");

        // Find clues at this location
        List<Map<String, Object>> found = game.clues.stream()
            .filter(c -> location.equals(c.get("location")))
            .filter(c -> c.get("public").equals(false))
            .collect(Collectors.toList());

        List<String> foundIds = new ArrayList<>();
        for (Map<String, Object> clue : found) {
            String clueId = (String) clue.get("id");
            if (!game.playerClues.getOrDefault(player, List.of()).contains(clueId)) {
                game.playerClues.computeIfAbsent(player, k -> new ArrayList<>()).add(clueId);
                foundIds.add(clueId);
            }
        }

        // Also reveal public clues at this location
        List<Map<String, Object>> publicClues = game.clues.stream()
            .filter(c -> location.equals(c.get("location")))
            .filter(c -> c.get("public").equals(true))
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", foundIds);
        result.put("clues", found.stream()
            .map(c -> Map.of("id", c.get("id"), "content", c.get("content")))
            .collect(Collectors.toList()));
        result.put("public_clues", publicClues.stream()
            .map(c -> Map.of("id", c.get("id"), "content", c.get("content")))
            .collect(Collectors.toList()));
        result.put("location", location);
        return result;
    }

    /** Phase 3-4: Cast vote for suspect. */
    public String castVote(String sessionId, String voter, String suspect) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return "游戏不存在";
        if (game.phase != Phase.VOTE) return "当前不是投票阶段";
        if (voter.equals(suspect)) return "不能投自己";
        game.votes.put(voter, suspect);
        return voter + " 投票给了 " + suspect;
    }

    /** Resolve votes and reveal truth. */
    public Map<String, Object> resolveVote(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");

        // Count votes
        Map<String, Integer> voteCount = new LinkedHashMap<>();
        game.votes.values().forEach(s -> voteCount.merge(s, 1, Integer::sum));

        String mostVoted = "";
        int maxVotes = 0;
        for (Map.Entry<String, Integer> e : voteCount.entrySet()) {
            if (e.getValue() > maxVotes) {
                maxVotes = e.getValue();
                mostVoted = e.getKey();
            }
        }

        // Check for tie
        final int finalMaxVotes = maxVotes;
        long ties = voteCount.values().stream().filter(c -> c == finalMaxVotes).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("votes", new LinkedHashMap<>(game.votes));
        result.put("most_voted", mostVoted);
        result.put("vote_count", maxVotes);

        if (ties > 1) {
            result.put("result", "平票，无人被定罪");
        } else {
            // Check if most voted is the murderer (matching truth)
            boolean correct = game.truth.contains(mostVoted);
            result.put("result", correct ? "剧本杀成功！真凶被找到" : "冤枉了好人...");
            result.put("correct", correct);
        }

        result.put("truth", game.truth);
        game.phase = Phase.REVEAL;
        game.winner = mostVoted;
        return result;
    }

    /** Start voting phase. */
    public void startVoting(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null && (game.phase == Phase.INVESTIGATION || game.phase == Phase.DISCUSSION)) {
            game.phase = Phase.VOTE;
        }
    }

    /** Transition to discussion phase. */
    public void startDiscussion(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null && game.phase == Phase.INVESTIGATION) {
            game.phase = Phase.DISCUSSION;
            game.round++;
        }
    }

    public ScriptGame getGame(String sessionId) {
        return games.get(sessionId);
    }

    // ═══════════════════════════════════════════════════════════
    //  Default fallback
    // ═══════════════════════════════════════════════════════════

    private Map<String, Object> defaultScript(String theme, List<String> players) {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", theme + "谋杀案");
        script.put("background", "在一个风雨交加的夜晚，" + String.join("、", players) + "聚集在一座古老的庄园中。突然，灯光熄灭，当灯光再次亮起时，庄园主人倒在血泊中...");
        script.put("roles", players.stream().map(p -> "嫌疑人_" + p).collect(Collectors.toList()));
        script.put("locations", List.of("客厅", "书房", "花园", "厨房", "地下室"));
        script.put("truth", "凶手是" + (players.size() > 0 ? players.get(0) : "未知"));
        script.put("secrets", players.stream().collect(Collectors.toMap(
            p -> "嫌疑人_" + p, p -> p + " 有一个隐藏的秘密，与这起案件有关。")));
        script.put("clues", List.of(
            Map.of("id", "clue_1", "location", "客厅", "content", "地上有碎玻璃和血迹", "public", false, "related_role", players.size() > 0 ? players.get(0) : ""),
            Map.of("id", "clue_2", "location", "书房", "content", "桌上有一封威胁信", "public", false, "related_role", players.size() > 1 ? players.get(1) : ""),
            Map.of("id", "clue_3", "location", "花园", "content", "泥土中的脚印通向围墙", "public", true, "related_role", "")
        ));
        return script;
    }
}
