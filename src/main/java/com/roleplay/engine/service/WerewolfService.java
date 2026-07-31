package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ⭐ Complete werewolf game engine — rules, phase management, win detection.
 * Maps from Python core/werewolf_api.py + core/werewolf_game.py + games/werewolf_engine.py.
 */
@Service
public class WerewolfService {
    private static final Logger log = LoggerFactory.getLogger(WerewolfService.class);

    public enum Role { WEREWOLF, SEER, WITCH, VILLAGER, HUNTER }
    public enum Phase { NIGHT, DAY_DISCUSS, DAY_VOTE, JUDGMENT, ENDED }

    public static class GameState {
        final Map<String, Role> roles = new LinkedHashMap<>();
        final List<String> alive = new ArrayList<>();
        Phase phase = Phase.NIGHT;
        int round = 1;
        String winner = "";

        // Night actions
        String wolfTarget = "";
        String seerTarget = "";
        String seerResult = "";
        String witchSaveTarget = "";
        String witchPoisonTarget = "";
        boolean witchUsedAntidote = false;
        boolean witchUsedPoison = false;
        String lastNightVictim = "";
        String lastNightSaved = "";
        boolean hunterCanShoot = true;

        // Voting
        final Map<String, String> votes = new LinkedHashMap<>(); // voter → target
        final List<Map<String, Object>> eliminated = new ArrayList<>();

        public Map<String, Object> toMap(String playerName) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase.name().toLowerCase());
            m.put("round", round);
            m.put("alive", new ArrayList<>(alive));
            m.put("your_role", roles.getOrDefault(playerName, Role.VILLAGER).name().toLowerCase());
            m.put("game_over", !winner.isEmpty());
            m.put("winner", winner);
            m.put("eliminated", new ArrayList<>(eliminated));
            if (roles.containsKey(playerName)) {
                Map<String, String> visible = new LinkedHashMap<>();
                roles.forEach((name, role) -> {
                    if (role == Role.WEREWOLF && roles.get(playerName) == Role.WEREWOLF)
                        visible.put(name, role.name().toLowerCase());
                    else if (name.equals(playerName))
                        visible.put(name, role.name().toLowerCase());
                });
                if (roles.get(playerName) == Role.SEER && !seerResult.isEmpty()) {
                    m.put("seer_result", seerResult);
                    m.put("seer_target", seerTarget);
                }
            }
            return m;
        }

        public boolean isGameOver() { return !winner.isEmpty(); }
    }

    private final Map<String, GameState> games = new ConcurrentHashMap<>();
    private final ApprovalService approvalService;

    /** D7: 狼人杀审批门总开关 —— true=投票结算挂起等待 DM 审批（超时自动驳回回滚），false=自动通过。 */
    @Value("${roleplay.game.approval.enabled:true}")
    private boolean approvalEnabled = true;

    /** D7: 审批等待超时（秒），超时视为驳回。 */
    @Value("${roleplay.game.approval.timeout-seconds:60}")
    private long approvalTimeoutSeconds = 60;

    public WerewolfService(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    public GameState getGame(String sessionId) {
        return games.computeIfAbsent(sessionId, k -> new GameState());
    }

    // ═══════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> initGame(String sessionId, List<String> players,
                                          Map<String, String> customRoles) {
        GameState g = new GameState();
        g.alive.addAll(players);

        if (customRoles != null && !customRoles.isEmpty()) {
            customRoles.forEach((name, roleStr) ->
                g.roles.put(name, Role.valueOf(roleStr.toUpperCase())));
            // Assign remaining players as villagers
            for (String p : players) {
                if (!g.roles.containsKey(p)) g.roles.put(p, Role.VILLAGER);
            }
        } else {
            assignDefaultRoles(g, players);
        }

        g.phase = Phase.NIGHT;
        g.round = 1;
        g.winner = "";
        resetNight(g);
        games.put(sessionId, g);

        log.info("Werewolf game {}: {} players, roles={}", sessionId, players.size(), g.roles);
        return g.toMap(players.get(0));
    }

    private void assignDefaultRoles(GameState g, List<String> players) {
        List<String> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        int n = shuffled.size();
        if (n >= 6) {
            g.roles.put(shuffled.get(0), Role.WEREWOLF);
            g.roles.put(shuffled.get(1), Role.WEREWOLF);
            g.roles.put(shuffled.get(2), Role.SEER);
            g.roles.put(shuffled.get(3), Role.WITCH);
            g.roles.put(shuffled.get(4), Role.HUNTER);
            for (int i = 5; i < n; i++) g.roles.put(shuffled.get(i), Role.VILLAGER);
        } else if (n >= 4) {
            g.roles.put(shuffled.get(0), Role.WEREWOLF);
            g.roles.put(shuffled.get(1), Role.WEREWOLF);
            g.roles.put(shuffled.get(2), Role.SEER);
            if (n > 3) g.roles.put(shuffled.get(3), Role.WITCH);
            for (int i = 4; i < n; i++) g.roles.put(shuffled.get(i), Role.VILLAGER);
        } else {
            players.forEach(p -> g.roles.put(p, Role.VILLAGER));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Night phase
    // ═══════════════════════════════════════════════════════════

    /** Record a night action. Returns result message for the player. */
    public String recordNightAction(String sessionId, String player, String action, String target) {
        GameState g = games.get(sessionId);
        if (g == null || g.phase != Phase.NIGHT) return "当前不是夜晚阶段";

        Role role = g.roles.get(player);
        String result = switch (action) {
            case "kill" -> {
                if (role == Role.WEREWOLF && g.alive.contains(target) && !target.equals(player)) {
                    g.wolfTarget = target;
                    yield "狼人已选择目标：" + target;
                }
                yield "你不能执行此行动";
            }
            case "check" -> {
                if (role == Role.SEER && g.alive.contains(target)) {
                    g.seerTarget = target;
                    g.seerResult = g.roles.get(target).name().toLowerCase();
                    yield "预言家查验：" + target + " 的身份是 " + g.seerResult;
                }
                yield "你不能执行此行动";
            }
            case "save" -> {
                if (role == Role.WITCH && !g.witchUsedAntidote && g.alive.contains(target)) {
                    g.witchSaveTarget = target;
                    g.witchUsedAntidote = true;
                    yield "女巫使用了解药，目标：" + target;
                }
                yield "女巫无法使用解药（已使用或无此目标）";
            }
            case "poison" -> {
                if (role == Role.WITCH && !g.witchUsedPoison && g.alive.contains(target)) {
                    g.witchPoisonTarget = target;
                    g.witchUsedPoison = true;
                    yield "女巫使用了毒药，目标：" + target;
                }
                yield "女巫无法使用毒药（已使用或无此目标）";
            }
            default -> "未知行动";
        };
        return result;
    }

    /** End night phase, resolve actions, transition to day. Returns night results narration. */
    public Map<String, Object> resolveNight(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return Map.of("error", "游戏不存在");

        List<String> died = new ArrayList<>();

        // Resolve wolf kill
        if (!g.wolfTarget.isEmpty() && g.alive.contains(g.wolfTarget)) {
            if (g.witchSaveTarget.equals(g.wolfTarget)) {
                g.lastNightSaved = g.wolfTarget;
            } else {
                died.add(g.wolfTarget);
                g.lastNightVictim = g.wolfTarget;
            }
        }

        // Resolve witch poison
        if (!g.witchPoisonTarget.isEmpty() && g.alive.contains(g.witchPoisonTarget)) {
            if (!died.contains(g.witchPoisonTarget)) {
                died.add(g.witchPoisonTarget);
            }
        }

        // Apply deaths
        for (String d : died) {
            g.alive.remove(d);
            g.eliminated.add(Map.of("name", d, "reason", killedBy(g, d), "round", g.round));
        }

        // Hunter retaliates
        if (died.stream().anyMatch(d -> g.roles.get(d) == Role.HUNTER) && g.hunterCanShoot) {
            g.hunterCanShoot = false;
        }

        // Check win condition
        String winner = checkWinCondition(g);
        if (!winner.isEmpty()) {
            g.winner = winner;
            g.phase = Phase.ENDED;
        } else {
            g.phase = Phase.DAY_DISCUSS;
        }

        resetNight(g);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("died", died);
        result.put("saved", g.lastNightSaved.isEmpty() ? "" : g.lastNightSaved);
        result.put("phase", g.phase.name().toLowerCase());
        result.put("round", g.round);
        result.put("game_over", g.isGameOver());
        result.put("winner", g.winner);
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    //  Voting
    // ═══════════════════════════════════════════════════════════

    /** Record a vote from one player to another. */
    public String castVote(String sessionId, String voter, String target) {
        GameState g = games.get(sessionId);
        if (g == null) return "游戏不存在";
        if (g.phase != Phase.DAY_VOTE) return "当前不是投票阶段";
        if (!g.alive.contains(voter)) return "已死亡玩家不能投票";
        if (voter.equals(target)) return "不能投自己";
        g.votes.put(voter, target);
        return voter + " 投票给了 " + target;
    }

    /** Resolve votes, eliminate the most-voted player.（D7：投票结算接入审批门） */
    public Map<String, Object> resolveVote(String sessionId) {
        GameState g = games.get(sessionId);
        if (g == null) return Map.of("error", "游戏不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("votes", new LinkedHashMap<>(g.votes));

        // 先统计得票（先计算、后应用，便于审批回滚）
        String topTarget = "";
        int topCount = 0;
        boolean tie = false;
        if (!g.votes.isEmpty()) {
            Map<String, Integer> count = new LinkedHashMap<>();
            g.votes.values().forEach(t -> count.merge(t, 1, Integer::sum));
            topTarget = Collections.max(count.entrySet(), Map.Entry.comparingByValue()).getKey();
            topCount = count.get(topTarget);
            final int topCountFinal = topCount; // effectively-final 副本（lambda 引用要求）
            tie = count.values().stream().filter(c -> c == topCountFinal).count() > 1;
        }

        // D7: 投票结算为关键决策点 —— 挂起等待 DM 审批，批准后才应用放逐；驳回/超时回滚且保留票数
        if (approvalEnabled && g.phase == Phase.DAY_VOTE && !g.isGameOver() && !g.votes.isEmpty()) {
            Map<String, Object> decision = awaitVoteSettlementApproval(sessionId, g, topTarget, topCount, tie);
            if (decision != null) return decision;
            result.put("approval", "approved");
        }

        // 应用结算（原逻辑）
        if (g.votes.isEmpty()) {
            result.put("exiled", "");
            result.put("reason", "无人投票，无人被放逐");
        } else if (tie) {
            result.put("exiled", "");
            result.put("reason", "平票，无人被放逐");
        } else {
            g.alive.remove(topTarget);
            g.eliminated.add(Map.of("name", topTarget, "reason", "被投票放逐", "round", g.round));
            result.put("exiled", topTarget);
            result.put("reason", topTarget + " 被放逐");
        }

        // Clear votes for next round
        g.votes.clear();

        // Check win condition
        String winner = checkWinCondition(g);
        if (!winner.isEmpty()) {
            g.winner = winner;
            g.phase = Phase.ENDED;
        } else {
            g.round++;
            g.phase = Phase.NIGHT;
        }

        result.put("phase", g.phase.name().toLowerCase());
        result.put("round", g.round);
        result.put("game_over", g.isGameOver());
        result.put("winner", g.winner);
        return result;
    }

    /**
     * D7: 投票结算审批门 —— 拟放逐结果提交 ApprovalService 挂起等待 DM 审批。
     * 返回 null 表示批准（调用方继续结算）；返回 Map 表示已回滚（驳回/超时/中断，票数保留）。
     */
    private Map<String, Object> awaitVoteSettlementApproval(String sessionId, GameState g,
                                                            String topTarget, int topCount, boolean tie) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gate", "werewolf_vote_settlement");
        payload.put("session_id", sessionId);
        payload.put("round", g.round);
        payload.put("votes", new LinkedHashMap<>(g.votes));
        payload.put("proposed_exile", tie ? "" : topTarget);
        payload.put("vote_count", topCount);
        payload.put("tie", tie);
        payload.put("phase", "day_vote_settlement");

        List<Map<String, Object>> outputs = new ArrayList<>();
        for (Map.Entry<String, String> v : g.votes.entrySet()) {
            outputs.add(Map.of("voter", v.getKey(), "target", v.getValue()));
        }

        RouterService.RoundResult round = new RouterService.RoundResult(
            "werewolf_vote_settlement",
            outputs,
            payload,
            "狼人杀投票结算：拟放逐=" + (tie ? "平票" : topTarget) + "，得票=" + topCount,
            Map.of("gate", "werewolf_vote_settlement"));

        try {
            RouterService.RoundResult approved = approvalService.submitForApproval(round, sessionId, approvalTimeoutSeconds);
            if (approved == null) {
                log.warn("Werewolf game {} vote settlement rejected/timeout, rollback (votes kept)", sessionId);
                Map<String, Object> rollback = new LinkedHashMap<>();
                rollback.put("votes", new LinkedHashMap<>(g.votes));
                rollback.put("exiled", "");
                rollback.put("reason", "投票结算被驳回或超时，已回滚，可重新投票");
                rollback.put("phase", g.phase.name().toLowerCase());
                rollback.put("round", g.round);
                rollback.put("game_over", g.isGameOver());
                rollback.put("winner", g.winner);
                rollback.put("approval", "rejected");
                rollback.put("rollback", true);
                return rollback;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Werewolf game {} vote settlement approval interrupted", sessionId);
            Map<String, Object> rollback = new LinkedHashMap<>();
            rollback.put("votes", new LinkedHashMap<>(g.votes));
            rollback.put("exiled", "");
            rollback.put("reason", "审批流程被中断，已回滚");
            rollback.put("phase", g.phase.name().toLowerCase());
            rollback.put("round", g.round);
            rollback.put("game_over", g.isGameOver());
            rollback.put("winner", g.winner);
            return rollback;
        }
        return null;
    }

    /** Start voting phase (transition from discussion). */
    public void startVoting(String sessionId) {
        GameState g = games.get(sessionId);
        if (g != null && g.phase == Phase.DAY_DISCUSS) {
            g.phase = Phase.DAY_VOTE;
            g.votes.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Win condition
    // ═══════════════════════════════════════════════════════════

    private String checkWinCondition(GameState g) {
        long wolves = g.alive.stream().filter(p -> g.roles.get(p) == Role.WEREWOLF).count();
        long villagers = g.alive.stream().filter(p -> g.roles.get(p) != Role.WEREWOLF).count();

        if (wolves == 0) return "villager";
        if (wolves >= villagers) return "werewolf";
        return "";
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void resetNight(GameState g) {
        g.wolfTarget = "";
        g.seerTarget = "";
        g.seerResult = "";
        g.witchSaveTarget = "";
        g.witchPoisonTarget = "";
        g.lastNightVictim = "";
        g.lastNightSaved = "";
    }

    /** Hunter retaliates — picks a target to shoot after death. */
    public String hunterShoot(String sessionId, String player, String target) {
        GameState g = games.get(sessionId);
        if (g == null) return "游戏不存在";
        if (!g.eliminated.stream().anyMatch(e -> player.equals(e.get("name"))))
            return "只有被淘汰的猎人才能开枪";
        if (!g.hunterCanShoot) return "猎人已经开过枪了";
        if (!g.alive.contains(target)) return target + " 已死亡";
        g.hunterCanShoot = false;
        g.alive.remove(target);
        g.eliminated.add(Map.of("name", target, "reason", "被猎人反击击杀", "round", g.round));

        // Re-check win condition after hunter shot
        String winner = checkWinCondition(g);
        if (!winner.isEmpty()) {
            g.winner = winner;
            g.phase = Phase.ENDED;
        }
        return "猎人 " + player + " 开枪击杀了 " + target;
    }

    private String killedBy(GameState g, String player) {
        if (player.equals(g.wolfTarget) && !player.equals(g.witchSaveTarget)) return "被狼人杀害";
        if (player.equals(g.witchPoisonTarget)) return "被女巫毒杀";
        return "死亡";
    }

    public void endGame(String sessionId) {
        GameState g = games.get(sessionId);
        if (g != null) g.phase = Phase.ENDED;
    }

    public boolean isPlayerAlive(String sessionId, String player) {
        GameState g = games.get(sessionId);
        return g != null && g.alive.contains(player);
    }
}
