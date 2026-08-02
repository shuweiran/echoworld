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
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TaskCancelledException;
import com.roleplay.engine.interrupt.TaskType;
import com.roleplay.engine.interrupt.TrackChangeEvent;
import com.roleplay.engine.interrupt.WorldEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    /** D8: SSE 广播器 —— 回合管线关键节点推送事件（agent_output / round_complete 等），前端实时回显。 */
    private final SSEController sse;
    /** D1: 中断管理器 —— stop() 时取消进行中的生成任务（需求文档第八条）。 */
    private final InterruptManager interruptManager;
    /** D1: 世界事件总线 —— 轨道变化时发布 TrackChangeEvent（§七）。 */
    private final WorldEventBus eventBus;
    /** P-0802-P2（改造方案 Phase 2）：玩家身份解析器 —— player_id → 当前绑定角色名（纯 DB 查询零缓存）。 */
    private final PlayerIdentityService identityService;

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    /** C-2: 一般模式串行调度开关（roleplay.round.serial，默认 false=保持并行行为；true=同轮按序生成、每完成一个即时入史）。 */
    @Value("${roleplay.round.serial:false}")
    private boolean serialRound;

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
    // P-0802-F: 狼人杀当前对局 —— 用于把身份（含狼人互认）发放到对应角色上下文（仅 werewolf 模式生效）
    private WerewolfService.GameState werewolfGame = null;

    public RouterService(ArbiterService arbiter, AgentExecutor executor,
                         MemoryStore memory, Compressor compressor,
                         Monitor monitor, GeneratorService generator,
                         TrackRequestService trackRequestService,
                         LLMClient llmClient,
                         com.roleplay.engine.controller.HistoryController historyController,
                         LorebookService lorebookService,
                         InterruptManager interruptManager,
                         WorldEventBus eventBus,
                         SSEController sse,
                         PlayerIdentityService playerIdentityService) {
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
        this.sse = sse;
        this.identityService = playerIdentityService;
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
     * P-0802-P3（改造方案 §4.2.1，主人授权 2026-08-02 沿 P-0802 授权链）：局中改名 ——
     * agents map 换键（:68）+ persona 改名（Persona.setName :64，Agent.getName 委托 persona :35-36）
     * + protagonist/directorCharacter（initSession :126-127）/restrictedAgents（:80）引用同步替换。
     * 历史消息保留旧名（不可篡改，正确行为）；后续轮次经 Agent.getName 自然用新名。
     * 方法锁：单会话 Router，方法级锁防与 runRound 并发（改名与对话轮/自动轮互斥）。
     */
    public synchronized void renameAgent(String oldName, String newName) {
        Agent a = agents.remove(oldName);
        if (a == null) return;
        a.getPersona().setName(newName);
        agents.put(newName, a);
        // 引用名同步替换：主角 / 导演角色 / 受限角色集合
        if (protagonist != null && protagonist.equals(oldName)) protagonist = newName;
        if (directorCharacter != null && directorCharacter.equals(oldName)) directorCharacter = newName;
        if (restrictedAgents != null && restrictedAgents.remove(oldName)) restrictedAgents.add(newName);
        log.info("Router agent renamed: {} → {}", oldName, newName);
    }

    /** P-0802-P3：当前会话 agents 名单是否含指定名字（局中改名会话收集用）。 */
    public boolean hasAgent(String name) {
        return name != null && agents.containsKey(name);
    }

    /** P-0802-P3：主角引用是否为指定角色（局中改名后断言引用同步用）。 */
    public boolean isProtagonist(String name) {
        return name != null && name.equals(protagonist);
    }

    /** P-0802-P3：受限角色集合副本（局中改名后断言引用同步用）。 */
    public Set<String> getRestrictedAgents() {
        return new HashSet<>(restrictedAgents);
    }

    /** P-0802-P3：设置受限角色集合（测试/运行时注入用；renameAgent 同步替换引用）。 */
    public void setRestrictedAgents(Set<String> names) {
        this.restrictedAgents = names != null ? new HashSet<>(names) : new HashSet<>();
    }

    /**
     * 停止会话。D1 增强：除置位停止标志外，立即硬停止所有进行中的生成任务
     * （取消令牌 + 中断 LLM 调用线程），使 /api/stop 真正能中断进行中的生成。
     */
    public void stop() {
        running = false;
        if (interruptManager != null) {
            interruptManager.cancelAll(StopType.HARD, "用户停止 /api/stop");
        }
        // D8: 停止推送（前端 "已停止" + 解除运行锁）
        if (sse != null) sse.broadcastStopped();
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
        return runRound(userInput, userInterjection, null);
    }

    /**
     * P0-2（2026-08-02）修复：
     * ① 会话已停止（running=false，如 /api/stop）但角色仍在时，发消息/自动轮视为恢复会话自动置 running=true——
     *    消除「send 前 stop 后永久停摆」；仅 agents 为空才报 No active session。
     * ② 支持显式发言人 speaker（前端 player_name）：命中 agent 名单时，该角色原文直接入史（角色说），
     *    跳过主控旁白化，本轮该角色不参与 LLM 生成（避免自问自答双声）；未命中走原主控旁白链路。
     */
    public RoundResult runRound(String userInput, String userInterjection, String speaker) {
        return runRound(userInput, userInterjection, speaker, null);
    }

    /**
     * P-0802-P2（改造方案《玩家角色改名与 AI 识别》Phase 2，主人授权 2026-08-02）：
     * runRound 四参重载 —— playerId 解析式豁免。判定优先级（方案 §3.3）：player_id 存在且能解析
     * → 用解析出的当前角色名；否则用 player_name 字符串（现状逻辑，零行为变化）。
     */
    public RoundResult runRound(String userInput, String userInterjection, String speaker, String playerId) {
        if (agents.isEmpty()) {
            if (sse != null) sse.broadcastError("No active session");
            return RoundResult.error("No active session");
        }
        if (!running) {
            // P0-2：恢复会话（用户再次发消息/点三轮 = 恢复运行）
            running = true;
        }
        // P0-2：发言人命中 agent → 该角色直接发言（跳过 arbiter 主控旁白化）
        // P-0802-P2：加 playerId 解析式豁免 —— 角色库改名后前端仍传旧名 speaker 时，
        // 按 player_id 解析当前角色名命中 agent 名单同样豁免（防主控代声 = 防被识别为 AI）；
        // 解析未命中（player_id 缺省/未绑定/identityService 缺失）→ 回退现状 speakers 字符串逻辑。
        boolean speakerIsAgent = speaker != null && !speaker.isBlank()
                && (agents.containsKey(speaker)
                    || (playerId != null && !playerId.isBlank() && identityService != null
                        && agents.containsKey(identityService.resolveCharacterName(playerId).orElse(""))));

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
        // D8: 轨道增删 → 推送 track_created / track_closed（前端轨道提示）
        if (layoutChanged) {
            publishTrackLifecycle(prevTrackLayout, trackResult.tracks);
        }

        // Build TrackConfig for executor
        TrackConfig config = buildTrackConfig(trackResult.tracks, roundCount);

        // D8: SSE 广播 —— 回合开始 + 轨道任务分配 + 旁听角色
        if (sse != null) {
            sse.broadcastRoundStart(roundCount);
            sse.broadcastArbiterTask(roundCount, buildTaskList(config));
            for (Track track : config.getTracks()) {
                for (String silentAgent : track.getSilentAgents()) {
                    sse.broadcastAgentSilent(silentAgent);
                }
            }
        }

        // Step 2: Handle user input (convert to narration)
        String narration = null;
        String userCategory = "";
        if (userInput != null && !userInput.isBlank()) {
            if (userInput.startsWith("/")) {
                // Handle commands
                narration = handleCommand(userInput);
            } else if (speakerIsAgent) {
                // P0-2：玩家以自己角色身份发言 → 原文直接入史（角色说），不再主控旁白化；
                // 该角色本轮不参与 LLM 生成（避免同一句被角色再生成一遍的双声）
                Message agentSpoke = new Message(Message.Role.AGENT, speaker, userInput);
                agentSpoke.setRoundNumber(roundCount);
                memory.addMessage(agentSpoke);
                if (sse != null) {
                    sse.broadcastUserInput(userInput, "human_discussion", speaker, roundCount);
                }
            } else {
                UserInputCategory cat = arbiter.classifyUserInput(
                    userInput, "always", memory.getShortTermContextRaw(2));
                userCategory = cat.name().toLowerCase();
                narration = arbiter.processUserInput(
                    userInput, cat, sceneDescription, agentNames, goals);
            }
            if (narration != null) {
                // P0-1：历史发言人不再硬编码 "me"——有显式发言人时用其名字，避免与同名角色混淆
                String speakerName = (speaker != null && !speaker.isBlank()) ? speaker : "me";
                Message userMsg = new Message(Message.Role.USER, speakerName, narration);
                userMsg.setRoundNumber(roundCount);
                memory.addMessage(userMsg);
                // D8: 非命令输入 → 推送 user_input 事件（前端回显主控输入）
                if (sse != null && !userInput.startsWith("/")) {
                    sse.broadcastUserInput(narration, userCategory, speakerName, roundCount);
                }
            }
        }

        // Step 3: Execute all agents (parallel default; serial when roleplay.round.serial=true)
        Map<String, Agent> agentMap = new HashMap<>(agents);
        if (speakerIsAgent) {
            // P0-2：发言人角色本轮已“说过话”，从生成任务中排除（buildTasks 对缺失 agent 自动跳过）
            // P-0802-P2：排除键优先 speaker 命中名；speaker 为旧名时改用 playerId 解析出的当前名
            //（否则改名场景下解析名 agent 仍会参与 LLM 生成 → 同一句双声）
            String excludeName = agents.containsKey(speaker) ? speaker : null;
            if (excludeName == null && playerId != null && !playerId.isBlank() && identityService != null) {
                excludeName = identityService.resolveCharacterName(playerId).orElse(null);
            }
            if (excludeName != null) agentMap.remove(excludeName);
        }

        AgentExecutor.ExecutionResult execResult;
        List<Map<String, Object>> agentOutputs = new ArrayList<>();
        // D8: trackId → 轨道信息映射（agent_output 事件的 track_label / track_mode）
        Map<String, Map<String, Object>> trackById = new HashMap<>();
        for (Map<String, Object> t : trackResult.tracks) {
            trackById.put(String.valueOf(t.getOrDefault("id", "main")), t);
        }

        if (serialRound) {
            // C-2 串行调度：按轨道顺序 × 轨道内 agent 顺序逐个生成，每个 agent 输出完成
            // 立即 memory.addMessage + SSE 推送 —— 后发言者 buildAgentContext 读到
            // 的对话历史即包含前面角色本轮已完成的发言（解决「同轮上下文不共享」）。
            execResult = executeRoundSerial(config, agentMap, trackById, agentOutputs);
        } else {
            AgentExecutor.ContextBuilder ctxBuilder = (agentName, trackMode, trackId, cfg) ->
                buildAgentContext(agentName, trackMode, trackId);
            execResult = executor.executeRound(config, agentMap, ctxBuilder);
        }

        // D1: 回合被取消（如 /api/stop）→ 立即返回，不再做 Arbiter 整合 / 落库
        if (execResult.cancelled()) {
            log.info("Round {} aborted by interrupt request", roundCount);
            if (sse != null) sse.broadcastStopped();
            return RoundResult.error("生成已中断");
        }

        // Step 4: Collect agent outputs（并行路径；串行路径已在 executeRoundSerial 内即时入史+收集）
        if (!serialRound) {
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

                    // D8: 每个 Agent 输出即时推送（前端 addAgentMsg 实时上屏）
                    if (sse != null) {
                        Map<String, Object> trackMap = trackById.getOrDefault(output.trackId(), Map.of());
                        sse.broadcastAgentOutput(
                            output.agentName(), output.content(), output.trackId(),
                            String.valueOf(trackMap.getOrDefault("label", "")),
                            String.valueOf(trackMap.getOrDefault("mode", "merged")),
                            output.visibleTo());
                    }
                }
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
            // D8: 主控整合旁白推送（前端 addIntegration 上屏）
            if (sse != null) sse.broadcastArbiterIntegrate(roundCount, narrationText);
        }

        // Step 6: Check compression
        if (compressor.shouldCompress(roundCount)) {
            List<Map<String, String>> recentRaw = memory.getShortTermContextRaw(compressor.getCompressionInterval());
            CompressedChunk chunk = compressor.compress(recentRaw,
                Math.max(0, roundCount - compressor.getCompressionInterval()), roundCount);
            if (memory.hasSession()) {
                memory.getSession().getCompressedChunks().add(chunk);
            }
            // D8: 记忆压缩完成推送（前端系统提示）
            if (sse != null) sse.broadcastCompression(chunk.getSummary());
        }

        memory.incrementRound();
        String status = execResult.metrics() != null
            ? String.format("%d agents in %.0fms", agentOutputs.size(), execResult.metrics().totalRoundTimeMs())
            : agentOutputs.size() + " agents done";

        // Auto-save to history
        if (historyController != null && memory.hasSession()) {
            historyController.saveSession(sessionId, memory.getSession());
            // D8: 自动保存完成推送
            if (sse != null) sse.broadcastSaved();
        }

        // D8: 回合完成推送（前端 setRunning(false) + "第N轮完成"）
        if (sse != null) sse.broadcastRoundComplete(roundCount);

        return new RoundResult(status, agentOutputs, integration, trackResult.reasoning,
            execResult.metrics() != null ? execResult.metrics().toMap() : Map.of());
    }

    /** Run multiple automatic rounds. */
    public List<RoundResult> runAutoRounds(int count) {
        List<RoundResult> results = new ArrayList<>();
        // P0-2：会话已停止但角色仍在 → 自动恢复（用户点「三轮/自动」= 恢复运行，不再 0 轮静默）
        if (!running && !agents.isEmpty()) running = true;
        for (int i = 0; i < count && running; i++) {
            results.add(runRound(null, null));
        }
        // D8: 自动对话结束推送（前端 "自动对话结束，共 N 轮"）
        if (sse != null) sse.broadcastAutoComplete(results.size());
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

        // P-0802-F: 狼人杀角色卡 —— 每个角色只见自己身份 + 狼人互认（对齐"身份只在自家 prompt"纪律）
        if ("werewolf".equals(mode) && werewolfGame != null) {
            StringBuilder wwCard = new StringBuilder("【狼人杀·角色卡】\n");
            WerewolfService.Role role = werewolfGame.roles.get(agentName);
            if (role != null) {
                wwCard.append("你的身份：").append(werewolfRoleLabel(role)).append("。");
                if (role == WerewolfService.Role.WEREWOLF) {
                    List<String> mates = werewolfGame.alive.stream()
                            .filter(p -> !p.equals(agentName) && werewolfGame.roles.get(p) == WerewolfService.Role.WEREWOLF)
                            .toList();
                    if (!mates.isEmpty()) {
                        wwCard.append("你的狼人同伴：").append(String.join("、", mates)).append("。");
                    }
                    wwCard.append("隐藏身份，白天伪装成普通玩家参与讨论，误导他人、保护狼队。");
                } else if (role == WerewolfService.Role.SEER) {
                    wwCard.append("你每晚可以查验一名玩家的身份。白天利用查验结果引导好人找出狼人，注意隐藏身份避免被狼刀。");
                } else if (role == WerewolfService.Role.WITCH) {
                    wwCard.append("你有一瓶解药和一瓶毒药。白天隐藏身份，暗中协助好人阵营。");
                } else if (role == WerewolfService.Role.HUNTER) {
                    wwCard.append("你被淘汰时可以开枪带走一名玩家。白天隐藏身份，谨慎发言。");
                } else {
                    wwCard.append("你是普通村民，白天通过发言、试探与推理找出狼人。");
                }
                wwCard.append("\n当前阶段：").append(werewolfPhaseLabel(werewolfGame.phase));
            }
            contextParts.add(wwCard.toString());
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
    //  C-2 串行调度（roleplay.round.serial=true）
    // ═══════════════════════════════════════════════════════════

    /**
     * C-2 串行调度（roleplay.round.serial=true）：
     * 按轨道顺序 × 轨道内 agent 顺序逐个生成（PLAYER > DM > NPC 优先级，与
     * AgentExecutor.buildTasks 的排序逻辑一致），每个 agent 输出完成**立即**
     * memory.addMessage + SSE 推送 —— 后发言者 buildAgentContext 读
     * memory.getAgentContext 即包含前面角色本轮已完成的发言（解决「同轮上下文不共享」断点）。
     *
     * <p>D1 中断语义保留：每个 agent 注册 AgentTask + CancellationToken
     * （register/toRunning/toDone/unregister），/api/stop → cancelAll → 令牌置位
     * → 生成前检查点抛 TaskCancelledException → 中断循环返回 cancelled
     * （与并行路径共用调用方处理）。
     *
     * <p>性能代价：n agents 从 1× LLM delay → n× LLM delay（主人方案明确「不限速」，
     * 开关默认 false 保持并行，仅按需开启）。
     */
    private AgentExecutor.ExecutionResult executeRoundSerial(
            TrackConfig config,
            Map<String, Agent> agentMap,
            Map<String, Map<String, Object>> trackById,
            List<Map<String, Object>> agentOutputs) {

        Instant roundStart = Instant.now();
        List<AgentExecutor.AgentOutput> outputs = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        boolean cancelled = false;
        long taskSeq = 0;

        // 顺序：轨道顺序 × 轨道内 agent 顺序，按优先级 PLAYER > DM > NPC 稳定排序
        List<AgentExecutor.AgentTask> ordered = new ArrayList<>();
        for (Track track : config.getTracks()) {
            String trackMode = track.getMode().name().toLowerCase();
            for (String agentName : track.getActiveAgents()) {
                if (!agentMap.containsKey(agentName)) continue; // P0-2 speaker 排除
                AgentExecutor.Priority priority = computeSerialPriority(agentName);
                ordered.add(new AgentExecutor.AgentTask(
                        agentName, track.getId(), trackMode, priority, null));
            }
        }
        ordered.sort(Comparator.comparingInt(t -> t.priority().ordinal()));

        for (AgentExecutor.AgentTask task : ordered) {
            // D1: 每步前检查 —— stop() 置 running=false 则中断循环
            if (!running) { cancelled = true; break; }
            Agent agent = agentMap.get(task.agentName());
            if (agent == null) continue;

            // D1: 注册中断任务（/api/stop → cancelAll 可取消生成中令牌）
            com.roleplay.engine.interrupt.AgentTask it = new com.roleplay.engine.interrupt.AgentTask(
                    task.agentName() + "_dialogue_" + (++taskSeq),
                    task.agentName(), TaskType.DIALOGUE,
                    Map.of("trackId", task.trackId(), "trackMode", task.trackMode()));
            interruptManager.register(it);
            it.toRunning();
            Instant taskStart = Instant.now();
            try {
                CancellationToken token = it.getCancelToken();
                token.checkpoint(); // 生成前检查点
                // 上下文在生成时构建：此时 memory 已含本轮前面角色已完成的发言
                String context = buildAgentContext(task.agentName(), task.trackMode(), task.trackId());
                token.checkpoint(); // 上下文构建后检查点
                // P-0802-M：后端真·流式 —— 增量经 SSE agent_token 逐片推送（前端逐字渲染）；
                // 完整内容仍由下方 broadcastAgentOutput 结算（流式失败自动降级非流式，内容不丢）
                String content = agent.generateWithContextStream(context, token, delta -> {
                    if (sse != null && delta != null && !delta.isEmpty()) {
                        Map<String, Object> trackMap = trackById.getOrDefault(task.trackId(), Map.of());
                        sse.broadcastAgentToken(
                            task.agentName(), delta, task.trackId(),
                            String.valueOf(trackMap.getOrDefault("label", "")),
                            String.valueOf(trackMap.getOrDefault("mode", "merged")));
                    }
                });
                long elapsed = Duration.between(taskStart, Instant.now()).toMillis();
                it.toDone();
                interruptManager.unregister(it.getId());

                if (content != null && !content.isBlank()) {
                    // 即时入史：后发言者 buildAgentContext 立即可见
                    Message agentMsg = new Message(Message.Role.AGENT, task.agentName(), content);
                    agentMsg.setRoundNumber(roundCount);
                    agentMsg.setTrackId(task.trackId());
                    memory.addMessage(agentMsg);

                    Map<String, Object> outMap = new LinkedHashMap<>();
                    outMap.put("agent_name", task.agentName());
                    outMap.put("content", content);
                    outMap.put("track_id", task.trackId());
                    agentOutputs.add(outMap);

                    // D8: 每个 Agent 输出即时推送（前端 addAgentMsg 实时上屏）
                    if (sse != null) {
                        Map<String, Object> trackMap = trackById.getOrDefault(task.trackId(), Map.of());
                        sse.broadcastAgentOutput(
                            task.agentName(), content, task.trackId(),
                            String.valueOf(trackMap.getOrDefault("label", "")),
                            String.valueOf(trackMap.getOrDefault("mode", "merged")),
                            List.of());
                    }
                    outputs.add(new AgentExecutor.AgentOutput(
                            task.agentName(), content, task.trackId(), List.of(), elapsed, null));
                    latencies.add(elapsed);
                }
            } catch (TaskCancelledException e) {
                // D1: 任务被取消 → 中断循环（软停止的未完成内容保存到任务上）
                cancelled = true;
                it.saveUnfinished(e.getPartial());
                interruptManager.unregister(it.getId());
                log.info("Agent {} serial task cancelled: {}", task.agentName(), e.getReason());
                break;
            } catch (Exception e) {
                // 与并行路径一致：失败不中断整轮，输出占位（isSuccess=false，不入史）
                long elapsed = Duration.between(taskStart, Instant.now()).toMillis();
                interruptManager.markFailed(it.getId(), e.getMessage());
                interruptManager.unregister(it.getId());
                outputs.add(new AgentExecutor.AgentOutput(
                        task.agentName(),
                        "[" + task.agentName() + " 走神了: " + e.getMessage() + "]",
                        task.trackId(), List.of(), elapsed, e.getMessage()));
                log.warn("Agent {} serial failed: {}", task.agentName(), e.getMessage());
            }
        }

        double totalTimeMs = Duration.between(roundStart, Instant.now()).toMillis();
        double avgLatency = latencies.isEmpty() ? 0
                : latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        AgentExecutor.ExecutorMetrics metrics = new AgentExecutor.ExecutorMetrics(
                ordered.size(), 1, avgLatency, maxLatency, totalTimeMs);

        log.info("Agent round complete (serial): {} agents in {}ms (avg {}ms/agent){}",
                outputs.size(), Math.round(totalTimeMs), Math.round(avgLatency),
                cancelled ? " [CANCELLED]" : "");
        return new AgentExecutor.ExecutionResult(outputs, metrics, cancelled);
    }

    /** 串行调度优先级（与 AgentExecutor.computePriority 同规则：PLAYER > DM > NPC）。 */
    private AgentExecutor.Priority computeSerialPriority(String agentName) {
        if (agentName.equals(protagonist) || agentName.equals("me")) {
            return AgentExecutor.Priority.PLAYER;
        }
        if (agentName.equals(directorCharacter)) {
            return AgentExecutor.Priority.DM;
        }
        return AgentExecutor.Priority.NPC;
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

    /** C-2: 串行调度开关（roleplay.round.serial，默认 false）。测试/运行时切换用。 */
    public void setSerialRound(boolean serialRound) { this.serialRound = serialRound; }
    public boolean isSerialRound() { return serialRound; }
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

    /** P-0802-F: 狼人杀模式 —— 绑定当前狼人杀局，使身份（含狼人互认）能在 buildAgentContext 注入对应角色。 */
    public void setWerewolfGame(WerewolfService.GameState game) {
        this.werewolfGame = game;
        log.info("Werewolf game registered to router ({} players)", game != null ? game.alive.size() : 0);
    }

    /** P-0802-F: 解绑狼人杀局。 */
    public void clearWerewolfGame() {
        this.werewolfGame = null;
    }

    /** 狼人杀角色中文标签（供角色卡上下文注入）。 */
    private static String werewolfRoleLabel(WerewolfService.Role role) {
        return switch (role) {
            case WEREWOLF -> "狼人";
            case SEER -> "预言家";
            case WITCH -> "女巫";
            case HUNTER -> "猎人";
            case VILLAGER -> "村民";
        };
    }

    /** 狼人杀阶段中文标签（供角色卡上下文注入）。 */
    private static String werewolfPhaseLabel(WerewolfService.Phase phase) {
        return switch (phase) {
            case NIGHT -> "夜晚（选择行动或闭眼等待）";
            case DAY_DISCUSS -> "白天讨论（发言推理找出狼人）";
            case DAY_VOTE -> "投票阶段（选出你怀疑的狼人）";
            case JUDGMENT -> "判定中";
            case ENDED -> "游戏已结束";
        };
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
        // D8: 角色加入推送
        if (sse != null) sse.broadcastAgentAdded(name, "active");
    }

    public void removeAgent(String name) {
        agents.remove(name);
        // Update state
        List<String> agentNames = new ArrayList<>(agents.keySet());
        state.put("agents", agentNames);
        state.put("agent_count", agentNames.size());
        // D8: 角色离开推送
        if (sse != null) sse.broadcastAgentRemoved(name);
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
    //  D8: SSE 辅助（回合广播）
    // ═══════════════════════════════════════════════════════════

    /** 由本轮轨道配置构造任务列表（arbiter_task 事件，前端「本轮任务分配」面板）。 */
    private List<Map<String, Object>> buildTaskList(TrackConfig config) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Track track : config.getTracks()) {
            String taskDesc = "参与轨道「" + track.getLabel() + "」("
                + track.getMode().name().toLowerCase() + "模式)";
            for (String agentName : track.getActiveAgents()) {
                tasks.add(Map.of("agent_name", agentName, "task", taskDesc));
            }
        }
        return tasks;
    }

    /** 轨道增删广播（track_created / track_closed），基于新旧轨道 id 差集。 */
    private void publishTrackLifecycle(List<Map<String, Object>> oldTracks,
                                       List<Map<String, Object>> newTracks) {
        if (sse == null) return;
        Set<String> oldIds = trackIds(oldTracks);
        Set<String> newIds = trackIds(newTracks);
        for (Map<String, Object> t : newTracks) {
            String id = String.valueOf(t.getOrDefault("id", "track"));
            if (!oldIds.contains(id)) {
                sse.broadcastTrackCreated(id, String.valueOf(t.getOrDefault("label", id)));
            }
        }
        for (Map<String, Object> t : oldTracks) {
            String id = String.valueOf(t.getOrDefault("id", "track"));
            if (!newIds.contains(id)) {
                sse.broadcastTrackClosed(id, String.valueOf(t.getOrDefault("label", id)));
            }
        }
    }

    private Set<String> trackIds(List<Map<String, Object>> tracks) {
        Set<String> ids = new HashSet<>();
        if (tracks != null) {
            for (Map<String, Object> t : tracks) {
                ids.add(String.valueOf(t.getOrDefault("id", "track")));
            }
        }
        return ids;
    }

    // ═══════════════════════════════════════════════════════════
    //  N1: 历史契约数据源（供 HistoryController /api/history）
    // ═══════════════════════════════════════════════════════════

    /** 当前会话全部消息（/api/history 的 messages 数据源）。 */
    public List<Message> getConversationMessages() {
        return memory.hasSession() ? memory.getSession().getMessages() : List.of();
    }

    /** 当前会话 round_log（/api/history 的 round_logs 数据源）。 */
    public List<Map<String, Object>> getConversationRoundLogs() {
        return memory.hasSession() ? memory.getSession().getRoundLog() : List.of();
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
        // P0-2：同 runTurns —— 停止后恢复
        if (!running && !agents.isEmpty()) running = true;
        for (int i = 0; i < turns && autoRunning; i++) {
            RoundResult result = runRound(i == 0 ? userInput : null, null);
            results.add(result);
            if (result.status.contains("error")) break;
        }
        autoRunning = false;
        // D8: 自动对话结束推送
        if (sse != null) sse.broadcastAutoComplete(results.size());
        return results;
    }

    public void stopAutoRounds() { autoRunning = false; }
    public boolean isAutoRunning() { return autoRunning; }

    // ═══════════════════════════════════════════════════════════
    //  D13: turns 多轮执行（/api/round/start）
    // ═══════════════════════════════════════════════════════════

    /**
     * D13: 明确收束信号 —— 主控旁白 / 角色台词出现这些标记时判定剧情目标达成。
     */
    private static final List<String> CLOSURE_MARKERS = List.of(
        "（完）", "（全文完）", "全剧终", "剧终", "故事结束", "本剧终",
        "完结", "(完)", "END", "The End");

    /**
     * 按 turns 执行 N 轮对话（前端"三轮"按钮等）。每轮走 {@link #runRound}。
     *
     * <p>停止条件（任一满足即提前结束，实际轮数 &lt; turns）：
     * <ol>
     *   <li>中途 stop —— running 被置 false（/api/stop、/stop 命令）；</li>
     *   <li>目标达成 —— 保守启发式 {@link #goalsAchieved()}（仅当设置过剧情目标且
     *       最近对话出现明确收束信号）；</li>
     *   <li>单轮错误 —— 无活动会话 / 生成被中断。</li>
     * </ol>
     *
     * @param userInput 仅第一轮携带的用户输入，后续轮次自动推进（null）
     * @param turns     目标轮数（&lt;=0 视为 1）
     * @return 实际执行的轮次结果列表（可能少于 turns）
     */
    public List<RoundResult> runTurns(String userInput, int turns) {
        List<RoundResult> results = new ArrayList<>();
        int target = Math.max(1, turns);
        // P0-2：会话已停止但角色仍在 → 自动恢复（/api/round/start 后不再「No active session / 0 轮」）
        if (!running && !agents.isEmpty()) running = true;
        for (int i = 0; i < target && running; i++) {
            RoundResult result = runRound(i == 0 ? userInput : null, null);
            results.add(result);
            if (result.status != null && result.status.startsWith("error")) break;
            if (goalsAchieved()) break;
        }
        // D8: 多轮自动对话结束推送（前端 "自动对话结束，共 N 轮"；单轮走 round_complete 不重复广播）
        if (sse != null && target > 1) sse.broadcastAutoComplete(results.size());
        return results;
    }

    /**
     * D13: 目标达成检测（保守启发式，避免误提前终止）。
     *
     * <p>仅当：剧情目标非空 且 最近 30 条消息中出现明确收束信号时返回 true；
     * 未设置目标时恒为 false（跑满 turns）。
     */
    private boolean goalsAchieved() {
        if (goals == null || goals.isEmpty() || !memory.hasSession()) return false;
        List<Message> msgs = memory.getSession().getMessages();
        int from = Math.max(0, msgs.size() - 30);
        for (int i = msgs.size() - 1; i >= from; i--) {
            Message m = msgs.get(i);
            if (m.getContent() == null) continue;
            for (String marker : CLOSURE_MARKERS) {
                if (m.getContent().contains(marker)) return true;
            }
        }
        return false;
    }

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
