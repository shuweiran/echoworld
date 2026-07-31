package com.roleplay.engine.service;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.core.TrackConfig;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TrackChangeEvent;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.model.CompressedChunk;
import com.roleplay.engine.model.Session;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import com.roleplay.engine.service.ArbiterService.UserInputCategory;
import com.roleplay.engine.service.TrackRequestService.TrackChangeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ⭐ Round orchestrator — the heart of the roleplay system.
 *
 * <p>Coordinates Arbiter (track config) → AgentExecutor (parallel agent execution)
 * → Arbiter (output integration) → MemoryStore (persistence).
 *
 * <p>Maps from Python core/router.py (2200+ lines, the biggest mess).
 * In Java this is CLEAN — no mixin, no dead code, no serial agent execution.
 */
@Service
public class RouterService {
    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final ArbiterService arbiter;
    private final AgentExecutor executor;
    private final MemoryStore memory;
    private final Compressor compressor;
    private final Monitor monitor;
    private final GeneratorService generator;
    private final TrackRequestService trackRequestService;
    private final LLMClient llmClient;
    private final com.roleplay.engine.controller.HistoryController historyController;
    private final LorebookService lorebookService;
    /** D1: 中断管理器 —— stop() 时取消进行中的生成任务（需求文档第八条）。 */
    private final InterruptManager interruptManager;
    /** D1: 世界事件总线 —— 轨道变化时发布 TrackChangeEvent（§七）。 */
    private final WorldEventBus eventBus;

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private String mode = "free";        // free | protagonist | multi_track | director | werewolf | script
    private String protagonist = "";
    private String directorCharacter = "";
    private List<String> goals = new ArrayList<>();
    private Set<String> restrictedAgents = new HashSet<>();
    private String sceneDescription = "";
    private int roundCount = 0;
    private List<Map<String, Object>> previousTracks = new ArrayList<>();
    private String sessionId = "";
    // D5: 剧本杀当前对局 —— 用于把 secrets 发放到对应角色上下文（仅 script 模式生效）
    private ScriptGameService.ScriptGame scriptGame = null;

    public RouterService(ArbiterService arbiter, AgentExecutor executor,
                         MemoryStore memory, Compressor compressor,
                         Monitor monitor, GeneratorService generator,
                         TrackRequestService trackRequestService,
                         LLMClient llmClient,
                         com.roleplay.engine.controller.HistoryController historyController,
                         LorebookService lorebookService,
                         InterruptManager interruptManager,
                         WorldEventBus eventBus) {
        this.arbiter = arbiter;
        this.executor = executor;
        this.memory = memory;
        this.compressor = compressor;
        this.monitor = monitor;
        this.generator = generator;
        this.trackRequestService = trackRequestService;
        this.llmClient = llmClient;
        this.historyController = historyController;
        this.lorebookService = lorebookService;
        this.interruptManager = interruptManager;
        this.eventBus = eventBus;
        memory.setCompressor(compressor);
    }

    // ═══════════════════════════════════════════════════════════
    //  Session lifecycle
    // ═══════════════════════════════════════════════════════════

    public void initSession(String sessionId, List<Persona> personas,
                             String sceneDescription, String mode,
                             String protagonist, String directorCharacter) {
        this.sessionId = sessionId;
        this.sceneDescription = sceneDescription;
        this.mode = mode;
        this.protagonist = protagonist != null ? protagonist : "";
        this.directorCharacter = directorCharacter != null ? directorCharacter : "";
        this.roundCount = 0;
        this.previousTracks = new ArrayList<>();
        this.goals = new ArrayList<>();
        this.restrictedAgents = new HashSet<>();
        this.running = true;

        agents.clear();
        List<String> agentNames = new ArrayList<>();
        for (Persona p : personas) {
            Agent agent = new Agent(p, "agent", llmClient);
            agents.put(p.getName(), agent);
            agentNames.add(p.getName());
        }

        // Create memory session
        memory.createSession(sessionId, agentNames, Map.of(
            "mode", mode, "scene", sceneDescription));
        state.put("status", "initialized");
        log.info("Session {} initialized with {} agents, mode={}", sessionId, agentNames.size(), mode);
    }

    public void loadSession(Session session, List<Agent> agentList) {
        this.sessionId = session.getSessionId();
        this.sceneDescription = session.getCurrentScene();
        this.roundCount = session.getRoundCount();
        // D12: restore the routing mode saved at init time (config = {mode, scene}),
        // so a loaded werewolf/protagonist session keeps its original behavior.
        if (session.getConfig() != null && session.getConfig().get("mode") != null
                && !String.valueOf(session.getConfig().get("mode")).isBlank()) {
            this.mode = String.valueOf(session.getConfig().get("mode"));
        }
        memory.setSession(session);
        agents.clear();
        for (Agent a : agentList) {
            agents.put(a.getName(), a);
        }
        running = true;
        log.info("Loaded session {}", sessionId);
    }

    public Map<String, Object> getState() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", running ? "running" : "idle");
        s.put("mode", mode);
        s.put("session_id", sessionId);
        s.put("round", roundCount);
        s.put("scene", sceneDescription);
        s.put("agent_count", agents.size());
        s.put("agent_names", agents.keySet().stream().toList());
        s.put("agents", agents.keySet().stream().toList());
        s.put("protagonist", protagonist);
        s.put("director_character", directorCharacter);
        s.put("goals", goals);
        s.put("message_count", memory.hasSession() ? memory.getSession().getMessages().size() : 0);
        if (monitor != null) s.put("cost_report", monitor.getCostReport());
        return s;
    }

    public boolean isRunning() { return running; }

    /**
     * 停止会话。D1 增强：除置位停止标志外，立即硬停止所有进行中的生成任务
     * （取消令牌 + 中断 LLM 调用线程），使 /api/stop 真正能中断进行中的生成。
     */
    public void stop() {
        running = false;
        if (interruptManager != null) {
            interruptManager.cancelAll(StopType.HARD, "用户停止 /api/stop");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Core round execution
    // ═══════════════════════════════════════════════════════════

    /**
     * Execute one full conversation round:
     * 1. Arbiter configures tracks
     * 2. AgentExecutor runs all agents in parallel (Virtual Threads!)
     * 3. Arbiter integrates outputs
     * 4. Memory saves, compressor checks
     */
    public RoundResult runRound(String userInput, String userInterjection) {
        if (!running || agents.isEmpty()) {
            return RoundResult.error("No active session");
        }

        roundCount++;
        // Snapshot current round before starting
        if (memory.hasSession()) {
            snapshotRound(sessionId);
        }
        List<String> agentNames = new ArrayList<>(agents.keySet());
        String historySummary = memory.getSummaryContext();
        // Phase 1 Track isolation (leak bypass fix): if the previous round ran
        // multiple tracks and any of them was non-public (WEAK/ISOLATED), the raw
        // history summary would leak isolated-track conversation content to the
        // Arbiter. Replace it with one-line per-track descriptors only.
        historySummary = sanitizeSummaryForArbiter(historySummary, previousTracks);

        // Step 1b: Lorebook injection
        String loreContext = "";
        if (lorebookService != null) {
            if (userInput != null) {
                loreContext = lorebookService.buildLoreContext(sessionId, userInput);
            }
            // Also scan existing messages
            if (memory.hasSession()) {
                List<Message> msgs = memory.getSession().getMessages();
                if (!msgs.isEmpty()) {
                    String lastMsg = msgs.get(msgs.size() - 1).getContent();
                    loreContext += lorebookService.buildLoreContext(sessionId, lastMsg);
                }
            }
        }

        // Step 2: Silent process pending track requests
        trackRequestService.silentProcessPending(sessionId, goals);

        // Step 2: Configure tracks via Arbiter
        String enrichedScene = loreContext.isEmpty() ? sceneDescription
            : sceneDescription + loreContext;
        List<Map<String, Object>> prevTrackLayout = previousTracks;
        TrackConfigResult trackResult = arbiter.configureTracks(
            enrichedScene, agentNames, historySummary,
            mode, protagonist, previousTracks, goals, restrictedAgents);
        // D1: 轨道变化 → 发布 TrackChangeEvent（事件驱动中断：取消不属于新轨道的生成任务）
        boolean layoutChanged = prevTrackLayout != null
                && tracksLayoutChanged(prevTrackLayout, trackResult.tracks);
        previousTracks = trackResult.tracks;
        if (layoutChanged && eventBus != null) {
            publishTrackChange(trackResult.tracks);
        }

        // Build TrackConfig for executor
        TrackConfig config = buildTrackConfig(trackResult.tracks, roundCount);

        // Step 2: Handle user input (convert to narration)
        String narration = null;
        if (userInput != null && !userInput.isBlank()) {
            if (userInput.startsWith("/")) {
                // Handle commands
                narration = handleCommand(userInput);
            } else {
                UserInputCategory cat = arbiter.classifyUserInput(
                    userInput, "always", memory.getShortTermContextRaw(2));
                narration = arbiter.processUserInput(
                    userInput, cat, sceneDescription, agentNames, goals);
            }
            if (narration != null) {
                Message userMsg = new Message(Message.Role.USER, "me", narration);
                userMsg.setRoundNumber(roundCount);
                memory.addMessage(userMsg);
            }
        }

        // Step 3: Execute all agents in parallel
        Map<String, Agent> agentMap = new HashMap<>(agents);
        AgentExecutor.ContextBuilder ctxBuilder = (agentName, trackMode, trackId, cfg) ->
            buildAgentContext(agentName, trackMode, trackId);

        AgentExecutor.ExecutionResult execResult = executor.executeRound(config, agentMap, ctxBuilder);

        // D1: 回合被取消（如 /api/stop）→ 立即返回，不再做 Arbiter 整合 / 落库
        if (execResult.cancelled()) {
            log.info("Round {} aborted by interrupt request", roundCount);
            return RoundResult.error("生成已中断");
        }

        // Step 4: Collect agent outputs
        List<Map<String, Object>> agentOutputs = new ArrayList<>();
        for (AgentExecutor.AgentOutput output : execResult.outputs()) {
            if (output.isSuccess() && output.content() != null && !output.content().isBlank()) {
                Message agentMsg = new Message(Message.Role.AGENT, output.agentName(), output.content());
                agentMsg.setRoundNumber(roundCount);
                agentMsg.setTrackId(output.trackId());
                memory.addMessage(agentMsg);

                Map<String, Object> outMap = new LinkedHashMap<>();
                outMap.put("agent_name", output.agentName());
                outMap.put("content", output.content());
                outMap.put("track_id", output.trackId());
                agentOutputs.add(outMap);
            }
        }

        // Step 5: Integrate outputs via Arbiter
        Map<String, Object> integration = arbiter.integrateOutputs(
            sceneDescription, trackResult.tracks, agentOutputs, "werewolf".equals(mode));

        String narrationText = (String) integration.getOrDefault("narration", "");
        if (narrationText != null && !narrationText.isBlank()) {
            Message arbiterMsg = new Message(Message.Role.ARBITER, "主控", narrationText);
            arbiterMsg.setRoundNumber(roundCount);
            memory.addMessage(arbiterMsg);
        }

        // Step 6: Check compression
        if (compressor.shouldCompress(roundCount)) {
            List<Map<String, String>> recentRaw = memory.getShortTermContextRaw(compressor.getCompressionInterval());
            CompressedChunk chunk = compressor.compress(recentRaw,
                Math.max(0, roundCount - compressor.getCompressionInterval()), roundCount);
            if (memory.hasSession()) {
                memory.getSession().getCompressedChunks().add(chunk);
            }
        }

        memory.incrementRound();
        String status = execResult.metrics() != null
            ? String.format("%d agents in %.0fms", agentOutputs.size(), execResult.metrics().totalRoundTimeMs())
            : agentOutputs.size() + " agents done";

        // Auto-save to history
        if (historyController != null && memory.hasSession()) {
            historyController.saveSession(sessionId, memory.getSession());
        }

        return new RoundResult(status, agentOutputs, integration, trackResult.reasoning,
            execResult.metrics() != null ? execResult.metrics().toMap() : Map.of());
    }

    /** Run multiple automatic rounds. */
    public List<RoundResult> runAutoRounds(int count) {
        List<RoundResult> results = new ArrayList<>();
        for (int i = 0; i < count && running; i++) {
            results.add(runRound(null, null));
        }
        return results;
    }

    // ═══════════════════════════════════════════════════════════
    //  Agent context building
    // ═══════════════════════════════════════════════════════════

    private String buildAgentContext(String agentName, String trackMode, String trackId) {
        Agent agent = agents.get(agentName);
        if (agent == null) return "";

        List<String> contextParts = new ArrayList<>();

        // Identity lock — keep the agent strictly in character
        Persona persona = agent.getPersona();
        String personaDesc = persona != null ? persona.getPersonaDesc() : "";
        StringBuilder identity = new StringBuilder("【你的身份】\n你是 ").append(agentName).append("。");
        if (personaDesc != null && !personaDesc.isEmpty()) {
            identity.append("\n").append(personaDesc).append("。");
        }
        identity.append("\n你只能以该角色的身份、语气、性格说话，不得跳出角色。");
        contextParts.add(identity.toString());

        // Scene context
        if (sceneDescription != null && !sceneDescription.isEmpty()) {
            contextParts.add("【当前场景】\n" + sceneDescription);
        }

        // D5: 剧本杀角色卡 —— 每个角色只看到自己的 secret（仿狼人杀"身份只在自家 prompt"）
        if ("script".equals(mode) && scriptGame != null) {
            StringBuilder scriptCard = new StringBuilder("【剧本杀·角色卡】\n");
            String role = scriptGame.getRoleOf(agentName);
            if (role != null && !role.isEmpty()) {
                scriptCard.append("你扮演的角色：").append(role).append("\n");
            }
            String secret = scriptGame.getSecretFor(agentName);
            if (secret != null && !secret.isEmpty()) {
                scriptCard.append("【你的秘密】只有你自己知道：").append(secret)
                          .append("。严守秘密——除非剧情需要，绝不向任何人透露，也不要主动提及这是剧本设定。");
            }
            if (scriptGame.phase != null) {
                scriptCard.append("\n当前阶段：").append(scriptPhaseLabel(scriptGame.phase));
            }
            contextParts.add(scriptCard.toString());
        }

        // Track info
        contextParts.add("【轨道】\n" + trackId + " (" + trackMode + "模式)");

        // Summary context
        String summary = memory.getSummaryContext();
        if (!summary.isEmpty()) contextParts.add(summary);

        // Recent messages visible to this agent
        List<Message> visible = memory.getAgentContext(agentName, 30);
        if (!visible.isEmpty()) {
            StringBuilder history = new StringBuilder("【对话历史】\n");
            for (Message m : visible) {
                history.append("[").append(m.getName()).append("]: ")
                       .append(m.getContent()).append("\n");
            }
            contextParts.add(history.toString());
        }

        return String.join("\n\n", contextParts);
    }

    // ═══════════════════════════════════════════════════════════
    //  Commands
    // ═══════════════════════════════════════════════════════════

    private String handleCommand(String cmd) {
        String stripped = cmd.strip().toLowerCase();
        if (stripped.startsWith("/mode")) {
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                mode = parts[1].trim();
                return "【系统】模式切换为: " + mode;
            }
            return "【系统】当前模式: " + mode;
        }
        if (stripped.startsWith("/goal") || stripped.startsWith("/goals")) {
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                goals = Arrays.asList(parts[1].trim().split("[,，]"));
                return "【系统】剧情目标已更新: " + String.join(", ", goals);
            }
            return "【系统】当前目标: " + (goals.isEmpty() ? "无" : String.join(", ", goals));
        }
        if (stripped.startsWith("/protagonist")) {
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                protagonist = parts[1].trim();
                mode = "protagonist";
                return "【系统】主角模式，主角: " + protagonist;
            }
            return "【系统】当前主角: " + (protagonist.isEmpty() ? "未设置" : protagonist);
        }
        if (stripped.startsWith("/restrict")) {
            restrictedAgents.clear();
            String[] parts = cmd.split("\\s+", 2);
            if (parts.length > 1) {
                restrictedAgents.addAll(Arrays.asList(parts[1].trim().split("[,，]")));
                return "【系统】禁止出场: " + String.join(", ", restrictedAgents);
            }
            return "【系统】禁止列表已清空";
        }
        if (stripped.equals("/stop") || stripped.equals("/end")) {
            running = false;
            return "【系统】对话已停止";
        }
        if (stripped.equals("/status")) {
            return "【系统状态】模式=" + mode + " 轮次=" + roundCount
                + " Agent数=" + agents.size() + " 运行中=" + running;
        }
        return "【系统】未知命令: " + cmd;
    }

    // ═══════════════════════════════════════════════════════════
    //  Config mutations
    // ═══════════════════════════════════════════════════════════

    public void setMode(String mode) { this.mode = mode; }
    public String getMode() { return mode; }
    public void setProtagonist(String name) { this.protagonist = name; }
    public void setDirectorCharacter(String name) { this.directorCharacter = name; }
    public void setGoals(List<String> goals) { this.goals = goals; }
    public List<String> getGoals() { return goals; }
    public int getRoundCount() { return roundCount; }
    public void setSceneDescription(String desc) { this.sceneDescription = desc; }

    /** D5: 剧本杀模式 —— 绑定当前剧本局，使 secrets 能在 buildAgentContext 注入对应角色。 */
    public void setScriptGame(ScriptGameService.ScriptGame game) {
        this.scriptGame = game;
        log.info("Script game registered to router ({} secrets)", game != null ? game.getSecrets().size() : 0);
    }

    /** D5: 解绑剧本局（新剧本 init 时会自动覆盖，通常无需手动调用）。 */
    public void clearScriptGame() {
        this.scriptGame = null;
    }

    /** 剧本杀阶段中文标签（供角色卡上下文注入）。 */
    private String scriptPhaseLabel(ScriptGameService.Phase phase) {
        return switch (phase) {
            case SETUP -> "准备阶段";
            case INVESTIGATION -> "搜证阶段（可搜索地点收集线索）";
            case DISCUSSION -> "讨论阶段（交流线索、指认嫌疑人）";
            case VOTE -> "投票阶段（选出你怀疑的真凶）";
            case REVEAL -> "揭晓阶段（真相即将公布）";
            case ENDED -> "游戏已结束";
        };
    }

    public void addAgent(String name, Persona persona) {
        agents.put(name, new Agent(persona, "agent", llmClient));
        // Update state with current agent list
        List<String> agentNames = new ArrayList<>(agents.keySet());
        state.put("agents", agentNames);
        state.put("agent_count", agentNames.size());
    }

    public void removeAgent(String name) {
        agents.remove(name);
        // Update state
        List<String> agentNames = new ArrayList<>(agents.keySet());
        state.put("agents", agentNames);
        state.put("agent_count", agentNames.size());
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //  Arbiter input sanitization (Phase 1 Track isolation)
    // ═══════════════════════════════════════════════════════════

    /**
     * If the previous round's track layout contains a non-public track (WEAK/ISOLATED)
     * alongside others, strip ALL conversation content from the Arbiter's history input
     * and keep only per-track structural one-liners (track id + mode + participants).
     * This prevents the Arbiter — and anything downstream — from reading isolated
     * tracks' full conversation. Single-track / all-public layouts pass through unchanged.
     */
    private String sanitizeSummaryForArbiter(String historySummary, List<Map<String, Object>> trackMaps) {
        if (historySummary == null || historySummary.isBlank()) return historySummary;
        if (trackMaps == null || trackMaps.size() < 2) return historySummary;

        boolean hasNonPublicTrack = false;
        for (Map<String, Object> t : trackMaps) {
            String mode = String.valueOf(t.getOrDefault("mode", "merged")).toLowerCase();
            if (!"merged".equals(mode)) { hasNonPublicTrack = true; break; }
        }
        if (!hasNonPublicTrack) return historySummary;

        StringBuilder sb = new StringBuilder("【轨道概况（多轨道隔离，仅保留结构摘要）】\n");
        for (int i = 0; i < trackMaps.size(); i++) {
            Map<String, Object> t = trackMaps.get(i);
            sb.append(i + 1).append(". 轨道「")
              .append(t.getOrDefault("label", "轨道" + (i + 1)))
              .append("」(模式=")
              .append(t.getOrDefault("mode", "merged"))
              .append("): 参与者=")
              .append(t.getOrDefault("agents", List.of()))
              .append("\n");
        }
        return sb.toString();
    }

    private TrackConfig buildTrackConfig(List<Map<String, Object>> trackMaps, int round) {
        TrackConfig config = new TrackConfig(round);
        for (Map<String, Object> m : trackMaps) {
            Track track = Track.fromMap(m);
            config.addTrack(track);
        }
        return config;
    }

    // ═══════════════════════════════════════════════════════════
    //  D1: TrackChangeEvent（需求文档第八条 §七：轨道变化 → 取消失效任务）
    // ═══════════════════════════════════════════════════════════

    /** 相邻两轮的轨道布局是否不同（轨道 id / 参与者集合任一变化）。 */
    private boolean tracksLayoutChanged(List<Map<String, Object>> oldTracks,
                                        List<Map<String, Object>> newTracks) {
        if (oldTracks.size() != newTracks.size()) return true;
        for (int i = 0; i < oldTracks.size(); i++) {
            Map<String, Object> a = oldTracks.get(i);
            Map<String, Object> b = newTracks.get(i);
            if (!Objects.equals(a.get("id"), b.get("id"))) return true;
            if (!Objects.equals(a.get("agents"), b.get("agents"))) return true;
        }
        return false;
    }

    /** 发布 TrackChangeEvent：新轨道 id 集合 + 轨道参与者映射。 */
    private void publishTrackChange(List<Map<String, Object>> trackMaps) {
        List<String> trackIds = new ArrayList<>();
        Map<String, List<String>> trackAgents = new LinkedHashMap<>();
        for (Map<String, Object> m : trackMaps) {
            String id = String.valueOf(m.getOrDefault("id", "track"));
            trackIds.add(id);
            Object agentsObj = m.get("agents");
            List<String> agentList = new ArrayList<>();
            if (agentsObj instanceof List<?> list) {
                for (Object o : list) agentList.add(String.valueOf(o));
            }
            trackAgents.put(id, agentList);
        }
        eventBus.publish(new TrackChangeEvent("router", trackIds, trackAgents,
                List.of(), trackAgents.values().stream()
                        .flatMap(List::stream).distinct().toList()));
        log.info("Track layout changed, published TrackChangeEvent: {}", trackIds);
    }

    // ═══════════════════════════════════════════════════════════
    //  Character Relations
    // ═══════════════════════════════════════════════════════════

    private final Map<String, Map<String, Map<String, String>>> characterRelations = new ConcurrentHashMap<>();

    /** Build relationship graph from script data. */
    public void buildCharacterRelations(Map<String, Object> scriptData) {
        characterRelations.clear();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) scriptData.getOrDefault("relationships", List.of());
        for (Map<String, Object> rel : relationships) {
            String from = (String) rel.getOrDefault("from", rel.getOrDefault("from_char", ""));
            String to = (String) rel.get("to");
            String relation = (String) rel.get("relation");
            String desc = (String) rel.get("description");
            if (from.isEmpty() || to == null) continue;
            characterRelations.computeIfAbsent(from, k -> new ConcurrentHashMap<>())
                .put(to, Map.of("relation", relation != null ? relation : "",
                                "description", desc != null ? desc : ""));
        }
        // Store in memory
        if (!characterRelations.isEmpty()) {
            StringBuilder sb = new StringBuilder("【角色关系图】\n");
            characterRelations.forEach((from, targets) ->
                targets.forEach((to, info) ->
                    sb.append(from).append("→").append(to).append(": ")
                      .append(info.get("relation")).append("（").append(info.get("description")).append("）\n")));
            String currentSession = this.state.getOrDefault("session_id", "").toString();
            if (!currentSession.isEmpty() && memory.hasSession()) {
                memory.addMessage(new com.roleplay.engine.core.Message(
                    com.roleplay.engine.core.Message.Role.SYSTEM, "主控", sb.toString()));
            }
        }
    }

    /** Get drift prevention prompt for an agent. */
    public String buildDriftPreventionPrompt(String agentName) {
        Map<String, Map<String, String>> rels = characterRelations.get(agentName);
        if (rels == null || rels.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【角色关系】\n");
        rels.forEach((target, info) ->
            sb.append("与").append(target).append("的关系：")
              .append(info.get("relation")).append("（").append(info.get("description")).append("）\n"));
        sb.append("请严格依据以上设定行动，不要偏离角色的背景、目标和人际关系。");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    //  Round Rollback
    // ═══════════════════════════════════════════════════════════

    private final List<List<Message>> roundHistory = new ArrayList<>();

    /** Save current round's messages before overwriting. */
    private void snapshotRound(String sessionId) {
        List<Message> snapshot = new ArrayList<>(memory.getSession().getMessages());
        roundHistory.add(snapshot);
        if (roundHistory.size() > 50) roundHistory.remove(0); // cap
    }

    /** Rollback to a previous round number. */
    public String rollbackToRound(String sessionId, int targetRound) {
        if (targetRound < 0 || targetRound >= roundHistory.size())
            return "无效回合: " + targetRound;
        memory.getSession().setMessages(new ArrayList<>(roundHistory.get(targetRound)));
        roundCount = targetRound;
        state.put("round", targetRound);
        return "已回滚到第 " + targetRound + " 轮";
    }

    // ═══════════════════════════════════════════════════════════
    //  Auto Rounds
    // ═══════════════════════════════════════════════════════════

    private volatile boolean autoRunning = false;

    /** Run multiple rounds automatically. */
    public List<RoundResult> runAutoRounds(String userInput, int turns) {
        List<RoundResult> results = new ArrayList<>();
        autoRunning = true;
        for (int i = 0; i < turns && autoRunning; i++) {
            RoundResult result = runRound(i == 0 ? userInput : null, null);
            results.add(result);
            if (result.status.contains("error")) break;
        }
        autoRunning = false;
        return results;
    }

    public void stopAutoRounds() { autoRunning = false; }
    public boolean isAutoRunning() { return autoRunning; }

    // ═══════════════════════════════════════════════════════════
    //  Message snapshot in runRound
    // ═══════════════════════════════════════════════════════════

    public void addSnapshotToRunRound(String sessionId) {
        snapshotRound(sessionId);
    }

    // ═══════════════════════════════════════════════════════════
    //  Value objects
    // ═══════════════════════════════════════════════════════════

    public static class RoundResult {
        public final String status;
        public final List<Map<String, Object>> agentOutputs;
        public final Map<String, Object> integration;
        public final String reasoning;
        public final Map<String, Object> metrics;

        public RoundResult(String status, List<Map<String, Object>> agentOutputs,
                           Map<String, Object> integration, String reasoning,
                           Map<String, Object> metrics) {
            this.status = status;
            this.agentOutputs = agentOutputs;
            this.integration = integration;
            this.reasoning = reasoning;
            this.metrics = metrics;
        }

        public static RoundResult error(String msg) {
            return new RoundResult("error: " + msg, List.of(), Map.of(), "", Map.of());
        }
    }
}
