package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.core.Persona;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ⭐ Script murder mystery — full game lifecycle.
 *
 * <p>Phases:
 * <ol>
 *   <li>SETUP — generate script, assign roles</li>
 *   <li>INVESTIGATION — players search locations for clues</li>
 *   <li>DISCUSSION — players share/synth clues, accuse</li>
 *   <li>VOTE — players vote for suspect（平票时清空票数复用 VOTE 重投，D6）</li>
 *   <li>REVEAL — show truth + score（揭晓前经审批门挂起等待 DM 审批，D7）</li>
 * </ol>
 *
 * <p>Maps from Python core/script_runtime.py (which was empty — this is new).
 */
@Service
public class ScriptGameService {
    private static final Logger log = LoggerFactory.getLogger(ScriptGameService.class);

    private final LLMClient llmClient;
    private final ApprovalService approvalService;

    /** D7: 审批门总开关 —— true=揭晓挂起等待 DM 审批（超时自动驳回回滚），false=自动通过。 */
    @Value("${roleplay.game.approval.enabled:true}")
    private boolean approvalEnabled = true;

    /** D7: 审批等待超时（秒），超时视为驳回。 */
    @Value("${roleplay.game.approval.timeout-seconds:60}")
    private long approvalTimeoutSeconds = 60;

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
        boolean simulationStarted = false;

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
            m.put("simulation_started", simulationStarted);
            if (simulationStarted) {
                m.put("simulation_url", "/simulation.html");
            }
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

        public List<String> getPlayers() {
            return new ArrayList<>(players);
        }

        public boolean isSimulationStarted() {
            return simulationStarted;
        }
    }

    private final Map<String, ScriptGame> games = new ConcurrentHashMap<>();

    public ScriptGameService(LLMClient llmClient, ApprovalService approvalService) {
        this.llmClient = llmClient;
        this.approvalService = approvalService;
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
        // D6: 只接受本局玩家名或角色名，杜绝无效/残缺票面导致揭晓误判
        if (suspect == null || suspect.isBlank()) return "投票对象不能为空";
        if (!game.players.contains(suspect) && !game.roles.contains(suspect)) {
            return "投票对象无效（必须是本局玩家或角色名）：" + suspect;
        }
        game.votes.put(voter, suspect);
        return voter + " 投票给了 " + suspect;
    }

    /**
     * Resolve votes and reveal truth（D6 + D7）。
     *
     * <p>D6 判定重做：① 票数按玩家名/角色名精确归一统计（非法票忽略）；② 真凶从真相文本中
     * 精确识别（凶手指向词 + 玩家/角色全名匹配，排除 contains 子串误判）；③ 平票 → 清空投票
     * 复用 VOTE 阶段重投，不再误入 REVEAL、不再误设 winner。
     *
     * <p>D7 审批门：揭晓为剧本杀关键决策点 —— 判定结果先提交 ApprovalService 挂起等待 DM 审批；
     * 批准 → 进入 REVEAL 正式揭晓；驳回/超时 → 回滚至 VOTE 重新投票。
     */
    public Map<String, Object> resolveVote(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.phase != Phase.VOTE) return Map.of("error", "当前不是投票阶段");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("votes", new LinkedHashMap<>(game.votes));

        // 1) 精确统计：只计合法票（票面嫌疑人归一为规范玩家名，非法票忽略）
        Map<String, Integer> voteCount = countValidVotes(game);
        String mostVoted = "";
        int maxVotes = 0;
        for (Map.Entry<String, Integer> e : voteCount.entrySet()) {
            if (e.getValue() > maxVotes) {
                maxVotes = e.getValue();
                mostVoted = e.getKey();
            }
        }
        result.put("most_voted", mostVoted);
        result.put("vote_count", maxVotes);

        // 2) 无人投票 → 不揭晓，留在投票阶段
        if (game.votes.isEmpty()) {
            result.put("result", "无人投票，无人被定罪，请先投票再揭晓");
            result.put("revote", true);
            return result;
        }
        // 票面全为无效值 → 同样不揭晓
        if (voteCount.isEmpty()) {
            result.put("result", "无有效投票（票面必须为本局玩家或角色名），请重新投票");
            result.put("revote", true);
            return result;
        }

        // 3) 平票 → 清空投票，复用 VOTE 阶段重投（D6：平票不再进入 REVEAL / 误设 winner）
        final int maxVotesFinal = maxVotes; // effectively-final 副本（lambda 引用要求）
        long ties = voteCount.values().stream().filter(c -> c == maxVotesFinal).count();
        if (ties > 1) {
            game.votes.clear();
            result.put("votes", new LinkedHashMap<>());
            result.put("result", "平票，无人被定罪，已清空投票，请重新投票");
            result.put("tie", true);
            result.put("revote", true);
            return result;
        }

        // 4) 唯一得票最高 → 从真相精确解析真凶（玩家/角色全名，非 contains 子串）
        String murderer = resolveMurderer(game);
        boolean correct = !murderer.isEmpty() && murderer.equals(mostVoted);
        String verdict = correct ? "剧本杀成功！真凶被找到" : "冤枉了好人...";

        // 5) D7 审批门：揭晓为关键决策点，挂起等待 DM 审批
        if (approvalEnabled) {
            Map<String, Object> decision = awaitRevealApproval(game, mostVoted, maxVotes, murderer, correct, verdict);
            if (decision != null) return decision; // 驳回/超时/中断 → 已回滚至投票阶段
            result.put("approval", "approved");
        }

        // 6) 批准 → 正式进入揭晓阶段
        result.put("result", verdict);
        result.put("correct", correct);
        result.put("murderer", murderer.isEmpty() ? "未识别" : murderer);
        result.put("truth", game.truth);
        game.phase = Phase.REVEAL;
        game.winner = mostVoted; // 保持原语义：winner=被定罪者，game_over=true 表示已揭晓
        return result;
    }

    /**
     * D7: 揭晓审批门 —— 将判定结果提交 ApprovalService 挂起等待 DM 审批。
     * 返回 null 表示批准（调用方继续揭晓）；返回 Map 表示已回滚（驳回/超时/中断）。
     */
    private Map<String, Object> awaitRevealApproval(ScriptGame game, String mostVoted, int maxVotes,
                                                    String murderer, boolean correct, String verdict) {
        Map<String, Object> revealPayload = new LinkedHashMap<>();
        revealPayload.put("gate", "script_reveal");
        revealPayload.put("session_id", game.sessionId);
        revealPayload.put("votes", new LinkedHashMap<>(game.votes));
        revealPayload.put("most_voted", mostVoted);
        revealPayload.put("vote_count", maxVotes);
        revealPayload.put("murderer", murderer.isEmpty() ? "未识别" : murderer);
        revealPayload.put("correct", correct);
        revealPayload.put("verdict", verdict);
        revealPayload.put("truth", game.truth);
        revealPayload.put("phase", "reveal");

        RouterService.RoundResult round = new RouterService.RoundResult(
            "script_reveal_approval",
            votesToAgentOutputs(game.votes),
            revealPayload,
            "剧本杀揭晓判定：得票最高=" + mostVoted + "，真凶=" + (murderer.isEmpty() ? "未识别" : murderer)
                + "，判定=" + (correct ? "命中" : "冤枉"),
            Map.of("gate", "script_reveal"));

        try {
            RouterService.RoundResult approved = approvalService.submitForApproval(round, game.sessionId, approvalTimeoutSeconds);
            if (approved == null) {
                log.warn("Script game {} reveal rejected/timeout, rollback to VOTE", game.sessionId);
                game.votes.clear();
                Map<String, Object> rollback = new LinkedHashMap<>();
                rollback.put("votes", new LinkedHashMap<>());
                rollback.put("most_voted", mostVoted);
                rollback.put("vote_count", maxVotes);
                rollback.put("result", "揭晓被驳回或超时，已回滚至投票阶段，请重新投票");
                rollback.put("revote", true);
                rollback.put("approval", "rejected");
                rollback.put("approval_hint", "DM 可通过 POST /api/approval/approve 批准，或 /reject 驳回");
                return rollback;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Script game {} reveal approval interrupted", game.sessionId);
            game.votes.clear();
            Map<String, Object> rollback = new LinkedHashMap<>();
            rollback.put("votes", new LinkedHashMap<>());
            rollback.put("most_voted", mostVoted);
            rollback.put("vote_count", maxVotes);
            rollback.put("result", "审批流程被中断，已回滚至投票阶段");
            rollback.put("revote", true);
            return rollback;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    //  D6: 揭晓判定辅助
    // ═══════════════════════════════════════════════════════════

    /** D6: 合法票精确统计 —— 嫌疑人归一为规范玩家名（角色名经 assignments 反查），非法票忽略。 */
    private Map<String, Integer> countValidVotes(ScriptGame game) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Map.Entry<String, String> v : game.votes.entrySet()) {
            String player = normalizeSuspect(game, v.getValue());
            if (player == null) continue;
            count.merge(player, 1, Integer::sum);
        }
        return count;
    }

    /** D6: 票面嫌疑人 → 规范玩家名；无法识别返回 null。 */
    private String normalizeSuspect(ScriptGame game, String suspect) {
        if (suspect == null) return null;
        String s = suspect.trim();
        if (game.players.contains(s)) return s;
        for (Map.Entry<String, String> e : game.assignments.entrySet()) {
            if (s.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /**
     * D6: 从真相文本精确解析真凶（返回玩家名；无法识别返回空串）。
     * 三级策略：① 凶手指向词后紧跟的名字（“凶手是X / 真凶就是X…”）→ 精确映射玩家；
     * ② 玩家全名出现在真相中（最长名优先，避免“张”命中“张伟”的子串误判）；
     * ③ 角色全名出现在真相中 → 经 assignments 反查玩家。
     */
    private String resolveMurderer(ScriptGame game) {
        String truth = game.truth == null ? "" : game.truth;
        if (truth.isEmpty()) return "";

        String marker = extractNameAfterMurderMarker(truth);
        if (!marker.isEmpty()) {
            String p = mapNameToPlayer(game, marker);
            if (!p.isEmpty()) return p;
        }

        List<String> candidates = new ArrayList<>();
        for (String player : game.players) {
            if (player != null && !player.isEmpty() && truth.contains(player)) candidates.add(player);
        }
        if (candidates.isEmpty()) {
            for (String role : game.roles) {
                if (role == null || role.isEmpty() || !truth.contains(role)) continue;
                for (Map.Entry<String, String> e : game.assignments.entrySet()) {
                    if (role.equals(e.getValue())) {
                        candidates.add(e.getKey());
                        break;
                    }
                }
            }
        }
        if (candidates.size() > 1) {
            candidates.sort((a, b) -> Integer.compare(b.length(), a.length()));
        }
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    /** D6: 提取凶手指向词后紧跟的名字片段（如“凶手是管家”→“管家”）。 */
    private String extractNameAfterMurderMarker(String truth) {
        Matcher m = Pattern
            .compile("(?:凶手|真凶|犯人|幕后真凶)(?:就是|便是|是|为|：|:)?\\s*([^，。；,!！?？、\\s]{1,20})")
            .matcher(truth);
        return m.find() ? m.group(1).trim() : "";
    }

    /** D6: 名字 → 玩家名（先玩家全名，再角色名反查 assignments）。 */
    private String mapNameToPlayer(ScriptGame game, String name) {
        if (name == null) return "";
        String n = name.trim();
        if (game.players.contains(n)) return n;
        for (Map.Entry<String, String> e : game.assignments.entrySet()) {
            if (n.equals(e.getValue())) return e.getKey();
        }
        return "";
    }

    /** D7: 投票明细 → RoundResult.agentOutputs（供 DM 审批时查看）。 */
    private List<Map<String, Object>> votesToAgentOutputs(Map<String, String> votes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> v : votes.entrySet()) {
            out.add(Map.of("voter", v.getKey(), "suspect", v.getValue()));
        }
        return out;
    }

    /** Start voting phase. */
    public void startVoting(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null && (game.phase == Phase.INVESTIGATION || game.phase == Phase.DISCUSSION)) {
            game.phase = Phase.VOTE;
        }
    }

    /** Transition to discussion phase. */
    public boolean startDiscussion(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null && game.phase == Phase.INVESTIGATION) {
            game.phase = Phase.DISCUSSION;
            game.round++;
            return true;
        }
        return false;
    }

    public void markSimulationStarted(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game != null) game.simulationStarted = true;
    }

    public List<Persona> buildSimulationPersonas(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return List.of();
        List<Persona> personas = new ArrayList<>();
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, player);
            String secret = game.secrets.getOrDefault(role, "");
            Persona persona = new Persona(player);
            persona.setPersonaDesc(buildPersonaDescription(game, player, role, secret));
            persona.setBackground(game.background);
            persona.setVoice("贴合剧情杀角色身份，发言谨慎，避免直接泄露私密信息");
            personas.add(persona);
        }
        return personas;
    }

    public Set<String> getSecretPlayers(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, "");
            if (!role.isBlank() && game.secrets.containsKey(role)) {
                names.add(player);
            }
        }
        return names;
    }

    public Map<String, String> buildDiscussionGoals(String sessionId) {
        ScriptGame game = games.get(sessionId);
        if (game == null) return Map.of();
        Map<String, String> goals = new LinkedHashMap<>();
        for (String player : game.players) {
            String role = game.assignments.getOrDefault(player, player);
            String secret = game.secrets.getOrDefault(role, "");
            String goal = secret.isBlank()
                ? "参与剧情杀讨论，结合公开线索推理真凶"
                : "参与剧情杀讨论，保护自己的秘密，同时根据线索推理真凶";
            goals.put(player, goal);
        }
        return goals;
    }

    private String buildPersonaDescription(ScriptGame game, String player, String role, String secret) {
        StringBuilder desc = new StringBuilder();
        desc.append("你是剧情杀《").append(game.name).append("》中的").append(role)
            .append("，玩家名为").append(player).append("。");
        if (game.background != null && !game.background.isBlank()) {
            desc.append("案件背景：").append(game.background).append("。");
        }
        if (secret != null && !secret.isBlank()) {
            desc.append("你的秘密：").append(secret)
                .append("。除非剧情推进到必要时刻，否则不要主动公开这段秘密。");
        }
        desc.append("讨论时应根据已知线索发言、试探他人、隐藏不利信息。");
        return desc.toString();
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
