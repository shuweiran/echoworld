package com.roleplay.engine.service;

import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.llm.LLMClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 剧本杀“玩家可感知流程”的编排层。
 *
 * <p>{@link ScriptGameService} 继续作为唯一领域执行器：搜证/AP/线索归属/快照/讨论均由它负责；
 * 本服务只负责两个此前缺失的编排能力：
 * <ol>
 *   <li>完整剧本生成完成后不立即让玩家开始搜证，而是回到可交互的 SETUP/opening 子状态；</li>
 *   <li>玩家确认进入搜证后，为非真人角色规划地点，并逐一复用 {@link ScriptGameService#search} 执行。</li>
 * </ol>
 *
 * <p>为什么暂不新增 OPENING 枚举：SETUP 已经是前端准备页的稳定协议。这里用
 * {@code generating/scriptSchema} 区分“生成中 SETUP”和“开场 SETUP”，避免一次大范围协议迁移：
 * <pre>
 * SETUP(generating/schema=null) -> SETUP(opening, schema ready)
 * -> INVESTIGATION -> DISCUSSION -> VOTE -> REVEAL
 * </pre>
 *
 * <p>知识隔离：所有 LLM prompt 都由 {@link #knowledgeView} 构造，只包含当前角色自己的秘密、
 * 自己持有的私有线索、全局公开线索以及公共聊天记录；不会把 truth 或其他角色私有线索给模型。
 */
@Service
public class ScriptGameFlowService {

    private static final Logger log = LoggerFactory.getLogger(ScriptGameFlowService.class);
    private static final String FLOW_STATE_KEY = "__script_flow_state";
    private static final String FLOW_OPENING = "opening";
    private static final String FLOW_INVESTIGATION = "investigation";
    private static final String OPENING_INTRO_KEY = "__script_opening_intro_done";
    private static final int MAX_CHAT_CONTEXT = 12;

    private final ScriptGameService games;
    private final LLMClient llm;
    private final SSEController sse;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Future<?>> generationWatchers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> investigationTasks = new ConcurrentHashMap<>();

    public ScriptGameFlowService(ScriptGameService games, LLMClient llm, SSEController sse) {
        this.games = games;
        this.llm = llm;
        this.sse = sse;
    }

    /**
     * 两阶段生成的编排入口。底层仍调用既有 generateFull；生成线程完全结束后，把 full 模式
     * 从旧的 INVESTIGATION 落点校正到“开场就绪 SETUP”，并自动让 AI 做一轮开场自我介绍。
     */
    public Map<String, Object> generateFullToOpening(String sessionId) {
        Map<String, Object> result = games.generateFull(sessionId);
        if (result == null || result.containsKey("error")) return result;

        generationWatchers.compute(sessionId, (sid, old) -> {
            if (old != null && !old.isDone()) return old;
            return executor.submit(() -> awaitGenerationThenOpen(sid));
        });

        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("flow_target", FLOW_OPENING);
        out.put("message", "完整剧本生成完成后将进入开场交流，玩家确认后再开始搜证");
        return out;
    }

    /**
     * 兼容已经通过旧 generate_full 生成完成的对局：若尚未发生搜证/讨论，可显式归一到开场态。
     * 这也是部署升级后旧前端/断线恢复的兜底入口。
     */
    public Map<String, Object> ensureOpening(String sessionId, String player) {
        ScriptGameService.ScriptGame game = games.getGame(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (game.generating || game.scriptSchema == null) {
            return Map.of("ok", false, "generating", game.generating, "phase", phase(game),
                    "message", "完整剧本尚未就绪");
        }
        if ("chat".equalsIgnoreCase(game.mode)) {
            return Map.of("ok", true, "phase", phase(game), "mode", "chat", "skipped", true);
        }
        if (game.phase == ScriptGameService.Phase.SETUP && isOpening(game)) {
            return openingState(game, player);
        }
        if (game.phase != ScriptGameService.Phase.INVESTIGATION || hasInvestigationProgress(game)) {
            return Map.of("error", "当前对局已经离开可进入开场交流的状态", "phase", phase(game));
        }
        enterOpening(game);
        scheduleOpeningIntroductions(game.sessionId);
        return openingState(game, player);
    }

    /**
     * 开场阶段玩家发言。先进入公共聊天权威记录，再异步让 AI 依据各自知识视图回应。
     * 与旧 public /chat 的区别是：这里明确触发 NPC opening responder。
     */
    public Map<String, Object> openingSay(String sessionId, String player, String message) {
        ScriptGameService.ScriptGame game = games.getGame(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (!isOpeningReady(game)) return Map.of("error", "当前不是可交互的开场阶段", "phase", phase(game));
        if (player == null || !game.players.contains(player)) return Map.of("error", "玩家不在本局中");
        if (message == null || message.isBlank()) return Map.of("error", "发言内容不能为空");

        Map<String, Object> recorded = games.publicChat(sessionId, player, message.trim());
        if (recorded.containsKey("error")) return recorded;

        List<String> responders = selectOpeningResponders(game, player, message);
        if (!responders.isEmpty()) {
            executor.submit(() -> {
                for (String ai : responders) {
                    ScriptGameService.ScriptGame latest = games.getGame(sessionId);
                    if (latest == null || !isOpeningReady(latest)) return;
                    String reply = renderOpeningReply(latest, ai, player, message);
                    if (!reply.isBlank()) games.publicChat(sessionId, ai, reply);
                }
            });
        }

        Map<String, Object> out = new LinkedHashMap<>(recorded);
        out.put("npc_triggered", !responders.isEmpty());
        out.put("responders", responders);
        out.put("flow_state", FLOW_OPENING);
        return out;
    }

    /**
     * 玩家确认结束开场。服务端先原子切到 INVESTIGATION 并落快照，再启动 AI 搜证任务。
     * AI 任务只规划地点；每次实际搜查都调用 ScriptGameService.search，绝不旁路修改 AP/线索。
     */
    public Map<String, Object> startInvestigation(String sessionId, String player) {
        ScriptGameService.ScriptGame game = games.getGame(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        if (!isOpeningReady(game)) {
            if (game.phase == ScriptGameService.Phase.INVESTIGATION
                    && FLOW_INVESTIGATION.equals(game.discussionContexts.get(FLOW_STATE_KEY))) {
                return investigationState(game, player, false);
            }
            return Map.of("error", "只有开场交流完成后才能进入搜证", "phase", phase(game));
        }
        if (player == null || !game.players.contains(player)) return Map.of("error", "玩家不在本局中");

        synchronized (game) {
            if (!isOpeningReady(game)) return Map.of("error", "阶段已变化，请刷新状态");
            game.phase = ScriptGameService.Phase.INVESTIGATION;
            game.round = Math.max(1, game.round);
            game.phaseStartedAt = System.currentTimeMillis();
            game.discussionContexts.put(FLOW_STATE_KEY, FLOW_INVESTIGATION);
        }

        // publishNarration 内部会 saveSnapshot；因此 phase + flow marker 与旁白同一快照落库。
        games.publishNarration(sessionId, "开场交流结束，进入搜证阶段。玩家与 AI 将分别调查；私人线索只进入发现者自己的知识范围。");
        broadcastState(game);
        boolean scheduled = scheduleAiInvestigation(sessionId);
        return investigationState(game, player, scheduled);
    }

    /** 当前编排层状态；不会暴露任一 AI 的私有知识。 */
    public Map<String, Object> status(String sessionId, String player) {
        ScriptGameService.ScriptGame game = games.getGame(sessionId);
        if (game == null) return Map.of("error", "游戏不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("session_id", sessionId);
        out.put("phase", phase(game));
        out.put("flow_state", flowState(game));
        out.put("ready", game.scriptSchema != null && !game.generating);
        out.put("generating", game.generating);
        out.put("ai_players", aiPlayers(game));
        out.put("ai_investigation_running", isTaskRunning(investigationTasks.get(sessionId)));
        if (player != null && game.players.contains(player)) {
            out.put("your_ap", game.playerAp.getOrDefault(player, 0));
            out.put("your_known_clue_ids", knowledgeView(game, player).knownClueIds());
        }
        return out;
    }

    private void awaitGenerationThenOpen(String sessionId) {
        try {
            long deadline = System.currentTimeMillis() + 10 * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                ScriptGameService.ScriptGame game = games.getGame(sessionId);
                if (game == null) return;
                if (!game.generating && game.scriptSchema != null) {
                    if (!"chat".equalsIgnoreCase(game.mode)
                            && game.phase == ScriptGameService.Phase.INVESTIGATION
                            && !hasInvestigationProgress(game)) {
                        enterOpening(game);
                        scheduleOpeningIntroductions(sessionId);
                    }
                    return;
                }
                Thread.sleep(25L);
            }
            log.warn("Script flow {} timed out waiting for full generation", sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Script flow {} opening reconciliation failed: {}", sessionId, e.getMessage(), e);
        } finally {
            generationWatchers.remove(sessionId);
        }
    }

    private void enterOpening(ScriptGameService.ScriptGame game) {
        synchronized (game) {
            if (game.scriptSchema == null || game.generating) return;
            game.phase = ScriptGameService.Phase.SETUP;
            game.round = 0;
            game.phaseStartedAt = System.currentTimeMillis();
            game.discussionContexts.put(FLOW_STATE_KEY, FLOW_OPENING);
        }
        // 既是玩家可见的阶段说明，也是对 phase/marker 的持久化提交点。
        games.publishNarration(game.sessionId, "完整剧本已就绪。现在先进行开场交流；确认后再进入搜证阶段。");
        broadcastState(game);
    }

    private void scheduleOpeningIntroductions(String sessionId) {
        executor.submit(() -> {
            ScriptGameService.ScriptGame game = games.getGame(sessionId);
            if (game == null || !isOpeningReady(game)) return;
            synchronized (game) {
                if ("true".equals(game.discussionContexts.get(OPENING_INTRO_KEY))) return;
                game.discussionContexts.put(OPENING_INTRO_KEY, "true");
            }
            List<String> ais = aiPlayers(game);
            for (String ai : ais) {
                ScriptGameService.ScriptGame latest = games.getGame(sessionId);
                if (latest == null || !isOpeningReady(latest)) return;
                String text = renderOpeningIntroduction(latest, ai);
                if (!text.isBlank()) games.publicChat(sessionId, ai, text);
            }
            // 没有 AI 时也要让 intro marker 随快照落下；旁白是无隐私的可见说明。
            if (ais.isEmpty()) games.publishNarration(sessionId, "开场交流已就绪。你可以与其他玩家交流，随后确认进入搜证。");
        });
    }

    private boolean scheduleAiInvestigation(String sessionId) {
        if (aiPlayers(games.getGame(sessionId)).isEmpty()) return false;
        Future<?> task = investigationTasks.compute(sessionId, (sid, old) -> {
            if (old != null && !old.isDone()) return old;
            return executor.submit(() -> runAiInvestigation(sid));
        });
        return task != null;
    }

    private void runAiInvestigation(String sessionId) {
        try {
            ScriptGameService.ScriptGame game = games.getGame(sessionId);
            if (game == null || game.phase != ScriptGameService.Phase.INVESTIGATION) return;
            for (String ai : aiPlayers(game)) {
                investigateAs(sessionId, ai);
            }
        } catch (Exception e) {
            log.warn("Script flow {} AI investigation failed: {}", sessionId, e.getMessage(), e);
        } finally {
            investigationTasks.remove(sessionId);
        }
    }

    private void investigateAs(String sessionId, String ai) {
        Set<String> attempted = new LinkedHashSet<>();
        while (true) {
            ScriptGameService.ScriptGame game = games.getGame(sessionId);
            if (game == null || game.phase != ScriptGameService.Phase.INVESTIGATION) return;
            int ap = game.playerAp.getOrDefault(ai, 0);
            if (ap <= 0) return;

            List<String> candidates = new ArrayList<>();
            for (String location : game.locations) {
                if (location != null && !location.isBlank() && !attempted.contains(location)) candidates.add(location);
            }
            if (candidates.isEmpty()) return;

            String location = planLocation(game, ai, candidates, attempted.size());
            if (location == null || location.isBlank() || !candidates.contains(location)) location = candidates.get(0);
            attempted.add(location);

            // 唯一权威执行路径：这里不碰 playerAp/playerClues。
            Map<String, Object> result = games.search(sessionId, ai, location);
            if (result == null || result.containsKey("error")) {
                String err = String.valueOf(result == null ? "null result" : result.get("error"));
                if (err.contains("阶段") || err.contains("游戏不存在")) return;
                log.debug("AI {} search {} skipped: {}", ai, location, err);
            }

            ScriptGameService.ScriptGame latest = games.getGame(sessionId);
            if (latest == null || latest.phase != ScriptGameService.Phase.INVESTIGATION) return;
            int after = latest.playerAp.getOrDefault(ai, 0);
            // 搜到有效私有线索会扣 AP；无可搜线索则继续试下一个地点。即使 AP 仍有剩余，
            // 也不会重复同一地点，避免“空地点无限循环”。
            if (after <= 0 || attempted.size() >= candidates.size() + attempted.size() - candidates.size()) {
                // 上式最终等价于 attempted 覆盖全部原始地点；下一轮 candidates 为空会自然 return。
                if (after <= 0) return;
            }
        }
    }

    private String planLocation(ScriptGameService.ScriptGame game, String ai, List<String> candidates, int step) {
        KnowledgeView view = knowledgeView(game, ai);
        String prompt = "你正在剧本杀搜证阶段。请只从候选地点中选择一个下一步调查地点。\n"
                + "角色：" + view.role() + "\n"
                + "角色自己的秘密（仅用于动机判断，不得公开）：" + blank(view.secret(), "无") + "\n"
                + "公开背景：" + blank(game.background, "无") + "\n"
                + "你当前合法掌握的线索：" + renderClues(view.clues()) + "\n"
                + "候选地点：" + String.join("、", candidates) + "\n"
                + "只输出一个候选地点的原文名称，不解释。";
        try {
            String raw = llm.callSync(List.of(
                    new Message(Message.Role.SYSTEM, "system", "你是角色的搜证规划器。你看不到未发现的私有线索，也不能根据服务器隐藏答案选点。"),
                    new Message(Message.Role.USER, ai, prompt)));
            if (raw != null) {
                String normalized = raw.trim();
                for (String candidate : candidates) if (normalized.equals(candidate)) return candidate;
                for (String candidate : candidates) if (normalized.contains(candidate)) return candidate;
            }
        } catch (Exception e) {
            log.debug("AI {} location planner degraded: {}", ai, e.getMessage());
        }
        // 无 LLM/输出不合法时确定性降级；只依赖公开候选，不窥探线索表。
        int index = Math.floorMod((ai + "#" + step).hashCode(), candidates.size());
        return candidates.get(index);
    }

    private String renderOpeningIntroduction(ScriptGameService.ScriptGame game, String ai) {
        KnowledgeView view = knowledgeView(game, ai);
        String prompt = "这是剧本杀正式搜证前的开场交流。请以「" + ai + "」身份说 1~2 句自然的开场话。\n"
                + "你的公开身份/角色：" + view.role() + "\n"
                + "故事公开背景：" + blank(game.background, "无") + "\n"
                + "你的秘密只用于决定态度，绝不能直接泄露：" + blank(view.secret(), "无") + "\n"
                + "你现在合法知道的线索：" + renderClues(view.clues()) + "\n"
                + "规则：不要声称发现了尚未搜证的东西；不要编造证据；不要说出幕后真相；像真人开场交流，不要解释规则。";
        return guardedDialogue(game, ai, view, prompt, "我是" + ai + "。先把眼前能确认的情况说清楚，之后各自搜证再交换判断。");
    }

    private String renderOpeningReply(ScriptGameService.ScriptGame game, String ai, String human, String message) {
        KnowledgeView view = knowledgeView(game, ai);
        String prompt = "现在是剧本杀正式搜证前的开场交流。玩家「" + human + "」刚说：\n「" + message + "」\n\n"
                + "请以「" + ai + "」身份自然回应 1~3 句。\n"
                + "你的公开身份/角色：" + view.role() + "\n"
                + "故事公开背景：" + blank(game.background, "无") + "\n"
                + "你的秘密只用于态度，绝不能直接泄露：" + blank(view.secret(), "无") + "\n"
                + "你现在合法知道的线索：" + renderClues(view.clues()) + "\n"
                + "最近公共交流：" + renderRecentChat(game) + "\n"
                + "规则：只能依据上面信息回应；不要编造证据、不要假装已经搜过地点、不要泄露幕后真相。";
        return guardedDialogue(game, ai, view, prompt, "我听到了。现在还没有完成搜证，先别把猜测当成证据；等各自调查后再对照。");
    }

    private String guardedDialogue(ScriptGameService.ScriptGame game, String ai, KnowledgeView view,
                                   String prompt, String fallback) {
        try {
            String raw = llm.callSync(List.of(
                    new Message(Message.Role.SYSTEM, "system", "你是剧本杀角色，而不是主持人。严格遵守角色知识边界；未知就说未知，禁止补全服务器隐藏事实。"),
                    new Message(Message.Role.USER, ai, prompt)));
            String reply = normalizeReply(raw);
            if (!reply.isBlank() && !mentionsHiddenClue(game, view, reply)) return reply;
            if (!reply.isBlank()) log.warn("Blocked hidden-clue leakage from opening reply of {}", ai);
        } catch (Exception e) {
            log.debug("Opening dialogue degraded for {}: {}", ai, e.getMessage());
        }
        return fallback;
    }

    /** 统一角色知识视图：公开线索 + 本人持有私有线索；不含 truth/其他玩家私有线索。 */
    KnowledgeView knowledgeView(ScriptGameService.ScriptGame game, String player) {
        String role = game.assignments.getOrDefault(player, player == null ? "" : player);
        String secret = game.secrets.getOrDefault(role, "");
        Set<String> heldIds = new LinkedHashSet<>(game.playerClues.getOrDefault(player, List.of()));
        List<Map<String, Object>> visible = new ArrayList<>();
        Set<String> knownIds = new LinkedHashSet<>();
        for (Map<String, Object> clue : game.clues) {
            String id = String.valueOf(clue.getOrDefault("id", ""));
            boolean pub = Boolean.TRUE.equals(clue.get("public"));
            if (!pub && !heldIds.contains(id)) continue;
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", id);
            safe.put("title", String.valueOf(clue.getOrDefault("title", id)));
            safe.put("location", String.valueOf(clue.getOrDefault("location", "")));
            safe.put("content", String.valueOf(clue.getOrDefault("content", "")));
            safe.put("public", pub);
            visible.add(safe);
            knownIds.add(id);
        }
        return new KnowledgeView(player, role, secret, List.copyOf(visible), Set.copyOf(knownIds));
    }

    private boolean mentionsHiddenClue(ScriptGameService.ScriptGame game, KnowledgeView view, String reply) {
        for (Map<String, Object> clue : game.clues) {
            String id = String.valueOf(clue.getOrDefault("id", ""));
            if (view.knownClueIds().contains(id) || Boolean.TRUE.equals(clue.get("public"))) continue;
            String title = String.valueOf(clue.getOrDefault("title", "")).trim();
            String content = String.valueOf(clue.getOrDefault("content", "")).trim();
            if (title.length() >= 2 && reply.contains(title)) return true;
            if (content.length() >= 6) {
                String fragment = content.substring(0, Math.min(12, content.length()));
                if (reply.contains(fragment)) return true;
            }
        }
        return false;
    }

    private List<String> selectOpeningResponders(ScriptGameService.ScriptGame game, String human, String message) {
        List<String> ais = aiPlayers(game);
        if (ais.isEmpty()) return List.of();
        List<String> mentioned = ais.stream().filter(message::contains).toList();
        if (!mentioned.isEmpty()) return mentioned.subList(0, Math.min(2, mentioned.size()));
        // 未点名时选一个，避免玩家每句话所有 NPC 同时抢话；开场自我介绍已保证每个 AI 至少有一次声音。
        int index = Math.floorMod((human + "|" + message + "|" + game.publicChatTranscript.size()).hashCode(), ais.size());
        return List.of(ais.get(index));
    }

    private List<String> aiPlayers(ScriptGameService.ScriptGame game) {
        if (game == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String player : game.players) {
            if (!Boolean.TRUE.equals(game.playerIsHuman.getOrDefault(player, true))) out.add(player);
        }
        return List.copyOf(out);
    }

    private boolean hasInvestigationProgress(ScriptGameService.ScriptGame game) {
        for (List<String> ids : game.playerClues.values()) if (ids != null && !ids.isEmpty()) return true;
        for (String p : game.players) {
            int max = game.playerApMax.getOrDefault(p, game.playerAp.getOrDefault(p, 0));
            int now = game.playerAp.getOrDefault(p, max);
            if (now < max) return true;
        }
        return false;
    }

    private boolean isOpeningReady(ScriptGameService.ScriptGame game) {
        return game != null
                && game.phase == ScriptGameService.Phase.SETUP
                && !game.generating
                && game.scriptSchema != null
                && FLOW_OPENING.equals(game.discussionContexts.get(FLOW_STATE_KEY));
    }

    private boolean isOpening(ScriptGameService.ScriptGame game) {
        return FLOW_OPENING.equals(game.discussionContexts.get(FLOW_STATE_KEY));
    }

    private String flowState(ScriptGameService.ScriptGame game) {
        String explicit = game.discussionContexts.get(FLOW_STATE_KEY);
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (game.phase == ScriptGameService.Phase.SETUP && game.scriptSchema == null) return "generating";
        return phase(game);
    }

    private Map<String, Object> openingState(ScriptGameService.ScriptGame game, String player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("session_id", game.sessionId);
        out.put("phase", "setup");
        out.put("flow_state", FLOW_OPENING);
        out.put("opening_ready", true);
        out.put("ai_players", aiPlayers(game));
        if (player != null && game.players.contains(player)) out.put("state", game.toMap(player));
        return out;
    }

    private Map<String, Object> investigationState(ScriptGameService.ScriptGame game, String player, boolean scheduled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("session_id", game.sessionId);
        out.put("phase", "investigation");
        out.put("flow_state", FLOW_INVESTIGATION);
        out.put("ai_investigation_started", scheduled);
        out.put("ai_players", aiPlayers(game));
        if (player != null && game.players.contains(player)) out.put("state", game.toMap(player));
        return out;
    }

    private void broadcastState(ScriptGameService.ScriptGame game) {
        if (sse == null || game == null) return;
        sse.broadcastScriptPhase(game.sessionId, phase(game));
        // 广播用匿名视图，避免 your_secret/role_key 等个人字段泄露。
        sse.broadcastScriptStatus(game.sessionId, game.toMap(null));
    }

    private static String renderClues(List<Map<String, Object>> clues) {
        if (clues == null || clues.isEmpty()) return "无";
        List<String> rendered = new ArrayList<>();
        for (Map<String, Object> clue : clues) {
            rendered.add("[" + clue.get("title") + " @ " + clue.get("location") + "] " + clue.get("content"));
        }
        return String.join("；", rendered);
    }

    private static String renderRecentChat(ScriptGameService.ScriptGame game) {
        int from = Math.max(0, game.publicChatTranscript.size() - MAX_CHAT_CONTEXT);
        List<String> lines = new ArrayList<>();
        for (int i = from; i < game.publicChatTranscript.size(); i++) {
            Map<String, String> turn = game.publicChatTranscript.get(i);
            lines.add(turn.getOrDefault("speaker", "?") + "：" + turn.getOrDefault("message", ""));
        }
        return lines.isEmpty() ? "无" : String.join(" / ", lines);
    }

    private static String normalizeReply(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("“") && s.endsWith("”"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.length() > 500) s = s.substring(0, 500);
        return s;
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String phase(ScriptGameService.ScriptGame game) {
        return game == null || game.phase == null ? "" : game.phase.name().toLowerCase();
    }

    private static boolean isTaskRunning(Future<?> f) {
        return f != null && !f.isDone();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    record KnowledgeView(String player, String role, String secret,
                         List<Map<String, Object>> clues, Set<String> knownClueIds) {}
}
