package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.conversation.ConversationManager;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import com.roleplay.engine.simulation.director.WorldDirectorService;
import com.roleplay.engine.simulation.movement.MovementConstraint;
import com.roleplay.engine.simulation.movement.MovementTarget;
import com.roleplay.engine.simulation.track.InteractionDetector;
import com.roleplay.engine.simulation.track.TrackAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private static final long DIRECTOR_INTERVAL_MS = 10_000;
    private static final String PLAYER_AGENT_NAME = "me";

    private final SimulationWorld world;
    private final LLMClient llmClient;
    private final DatabaseService databaseService;
    /** D1: 中断管理器 —— 模拟停止时硬停止进行中的生成任务。 */
    private final InterruptManager interruptManager;
    /** D1: 任务生命周期管理器 —— 注入 ConversationManager。 */
    private final AgentTaskManager agentTaskManager;
    /** D1: 世界事件总线 —— 轨道变化事件（TrackDirector → 取消旧轨道生成）。 */
    private final WorldEventBus eventBus;
    private final ConversationManager conversationManager = new ConversationManager();
    /** Phase 3 dual-director architecture: World Director (角色想做什么). */
    private final WorldDirectorService worldDirector;
    /** Phase 3 dual-director architecture: Track Director (谁知道什么). */
    private final TrackDirectorService trackDirector = new TrackDirectorService();
    /** Phase 3 outer orchestrator (需求文档第十四条: Router → Orchestrator → Track/World). */
    private SimulationOrchestrator orchestrator;
    /** Phase 4: 轨道 → 运动约束（纯规则，零 LLM）。 */
    private final MovementConstraint movementConstraint = new MovementConstraint();
    /** Phase 4: 最近一次 orchestrator.tick 的轨道分配，供移动 tick 前的约束层使用。 */
    private volatile Map<String, TrackAssignment> lastTrackAssignments = Map.of();
    private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile long lastDirectorTime = 0;
    private final Set<String> pendingUserMessages = ConcurrentHashMap.newKeySet();
    private volatile int lastSaveTick = 0;
    private static final int SNAPSHOT_INTERVAL = 50; // save snapshot every 50 ticks

    public SimulationService(SimulationWorld world, LLMClient llmClient, DatabaseService databaseService,
                             InterruptManager interruptManager, AgentTaskManager agentTaskManager,
                             WorldEventBus eventBus) {
        this.world = world;
        this.llmClient = llmClient;
        this.databaseService = databaseService;
        this.interruptManager = interruptManager;
        this.agentTaskManager = agentTaskManager;
        this.eventBus = eventBus;
        this.worldDirector = new WorldDirectorService(llmClient);
        conversationManager.init(world, llmClient,
                name -> world.getAgent(name),
                () -> world.getWorldNarration());
        // D1: 注入中断系统（2D 对话生成可被模拟停止 / 事件驱动取消）
        conversationManager.setInterruptManager(interruptManager);
        conversationManager.setAgentTaskManager(agentTaskManager);
        // Phase 3 wiring: Track Director decides group track assignments (with World
        // Director goals for conflict detection); legacy spatial-only path stays as
        // fallback inside ConversationManager when trackDirector is null.
        conversationManager.setTrackDirector(trackDirector);
        conversationManager.setGoalSupplier(worldDirector::getAllGoals);
        this.orchestrator = new SimulationOrchestrator(world, worldDirector, trackDirector, conversationManager, eventBus);
        // Phase 4: 移动 tick 前应用轨道运动约束（使用上一 tick 的轨道分配，延迟一拍）。
        world.addPreTickHook(this::applyMovementConstraints);
        world.addTickListener(snapshot -> {
            try {
                // Phase 3: dual-director pipeline (World Director → InteractionDetector →
                // Track Director → ConversationGroup). Runs before conversation tick so
                // freshly created groups pick up the latest goals/track decisions.
                lastTrackAssignments = orchestrator.tick(System.currentTimeMillis());
            } catch (Exception e) {
                log.warn("Orchestrator tick failed: {}", e.getMessage());
            }
            conversationManager.tick(System.currentTimeMillis());
            checkDirectorCycle();
            // Periodic snapshot every SNAPSHOT_INTERVAL ticks
            int tick = world.getTickCount();
            if (tick > 0 && tick - lastSaveTick >= SNAPSHOT_INTERVAL) {
                lastSaveTick = tick;
                try {
                    saveSnapshot();
                } catch (Exception e) {
                    log.warn("Failed to save world snapshot: {}", e.getMessage());
                }
            }
        });
    }

    // ── Lifecycle ──────────────────────────────────────────────

    public void initDemo() {
        initDemo(2);
    }

    public void initDemo(int count) {
        clearAll();
        count = Math.max(2, Math.min(count, 8));

        String[][] presets = {
            {"小明", "开朗外向的年轻人，25岁，喜欢运动和户外活动", "说话轻松活泼，爱开玩笑", "在大城市打拼的年轻程序员"},
            {"小红", "温柔细心的女孩，23岁，喜欢阅读和画画，有点害羞", "说话轻声细语，偶尔紧张结巴", "美术学院学生，梦想开画廊"},
            {"阿杰", "沉着冷静的大叔，38岁，热爱钓鱼和茶道，人生阅历丰富", "说话慢条斯理，喜欢讲道理", "退休教师，在公园当志愿者"},
            {"小美", "活泼可爱的少女，19岁，喜欢追星和拍照，精力充沛", "说话快速跳跃，爱用夸张语气词", "大一新生，摄影社成员"},
            {"老陈", "严肃认真的工程师，45岁，喜欢研究机械和历史", "说话简洁有力，偶尔说冷笑话", "在科技公司工作，周末来公园散步"},
            {"阿花", "热情开朗的大妈，52岁，喜欢跳广场舞和种花", "嗓门大，说话直爽热情", "退休工人，公园广场舞领队"},
            {"小智", "聪明好动的男孩，12岁，喜欢打游戏和滑板", "说话天真活泼，充满好奇心", "小学生，放学后喜欢来公园玩"},
            {"小林", "文艺忧郁的青年，28岁，喜欢写诗和弹吉他", "说话文绉绉，偶尔吟诗", "自由撰稿人，经常在公园长椅上写作"},
        };

        for (int i = 0; i < count && i < presets.length; i++) {
            String[] p = presets[i];
            var persona = new com.roleplay.engine.core.Persona(p[0], p[1]);
            persona.setVoice(p[2]);
            persona.setBackground(p[3]);

            Agent agent = new Agent(persona, "npc", llmClient);
            double x = 100 + Math.random() * 800;
            double y = 100 + Math.random() * 400;
            double hearRange = 180 + Math.random() * 80;
            double moveSpeed = 50 + Math.random() * 60;

            world.registerAgent(agent, x, y, hearRange, moveSpeed);
            world.getState(p[0]).setEmotion(Emotion.NEUTRAL);
        }

        log.info("Demo initialized: {} agents", world.getAgentCount());
    }

    /**
     * Initialize simulation with user-defined Personas (bridging from roleplay).
     * Each persona becomes an agent in the 2D world with random starting positions.
     */
    public void initWithPersonas(List<Persona> personas, String sceneName) {
        clearAll();
        if (personas.isEmpty()) {
            log.warn("Empty persona list, falling back to demo");
            initDemo(2);
            return;
        }

        if (sceneName != null && !sceneName.isBlank()) {
            world.setScene(sceneName);
        }

        for (Persona p : personas) {
            Agent agent = new Agent(p, "npc", llmClient);
            double x = 100 + Math.random() * 800;
            double y = 100 + Math.random() * 400;
            double hearRange = 180 + Math.random() * 80;
            double moveSpeed = 50 + Math.random() * 60;

            world.registerAgent(agent, x, y, hearRange, moveSpeed);
            AgentState state = world.getState(p.getName());
            if (state != null) {
                state.setEmotion(Emotion.NEUTRAL);
                // Mark player-controlled agents ("me", or any agent with the tag)
                if (PLAYER_AGENT_NAME.equals(p.getName())) {
                    state.setPlayerControlled(true);
                }
            }
        }

        log.info("Loaded {} personas into simulation, scene={}", personas.size(), sceneName);
    }

    public void clearAll() {
        // D1: 清场时同时停止所有群组生成任务
        conversationManager.stopAll();
        world.clearAgents();
    }

    public void start() {
        conversationManager.resetStopped();
        lastDirectorTime = System.currentTimeMillis();
        world.start();
    }

    public void stop() {
        world.stop();
        // D1: 中断系统 —— 停止世界 tick 之外，硬停止所有进行中的 LLM 生成任务
        conversationManager.stopAll();
        if (interruptManager != null) {
            interruptManager.cancelAll(StopType.HARD, "模拟停止 /api/simulation/stop");
        }
        // Save final snapshot on stop
        try {
            saveSnapshot();
        } catch (Exception e) {
            log.warn("Failed to save final snapshot: {}", e.getMessage());
        }
    }

    public Map<String, Object> getState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", world.isRunning());
        result.put("tick", world.getTickCount());
        result.put("agentCount", world.getAgentCount());
        List<Map<String, Object>> agentList = new ArrayList<>();
        for (AgentState s : world.getAllStates().values()) {
            agentList.add(s.toMap());
        }
        result.put("agents", agentList);
        result.put("recentConversations", world.getRecentConversations());
        result.put("worldNarration", world.getWorldNarration());
        result.put("directorActive", world.isDirectorActive());
        return result;
    }

    public Map<String, Object> getConversationStatus() {
        return conversationManager.getStatus();
    }

    public void sendUserDirective(String directive) {
        world.setUserDirective(directive);
        log.info("User directive: {}", directive);
        runDirectorRound(true);
    }

    public void sendUserMessage(String agentName, String message) {
        AgentState state = world.getState(agentName);
        if (state == null) return;
        state.setCurrentMessage(message);
        pendingUserMessages.add(agentName);
    }

    // ── Phase 4: Track REST 支撑 ───────────────────────────────

    /** 轨道运动约束：每 tick 移动前，用最近一次轨道分配生成约束目标并写回。 */
    private void applyMovementConstraints() {
        Map<String, TrackAssignment> assignments = lastTrackAssignments;
        if (assignments == null || assignments.isEmpty()) return;
        try {
            Map<String, MovementTarget> targets = movementConstraint.compute(
                    world, assignments, trackDirector.getSecretAgents());
            movementConstraint.apply(world, targets);
        } catch (Exception e) {
            log.warn("MovementConstraint failed: {}", e.getMessage());
        }
    }

    /** Track REST: World Director 手动目标（POST /track/goal）。 */
    public void setTrackGoal(String agent, String goal) {
        orchestrator.setGoal(agent, goal);
        log.info("Track goal set: {} → {}", agent, goal);
    }

    /** Track REST: 清除手动目标，恢复规则驱动。 */
    public void clearTrackGoal(String agent) {
        orchestrator.clearGoal(agent);
        log.info("Track goal cleared: {}", agent);
    }

    /** Track REST: 秘密任务注入（POST /track/secret，强制 ISOLATED）。 */
    public void setSecretAgents(java.util.Set<String> names) {
        orchestrator.setSecretAgents(names);
        log.info("Secret agents set: {}", names);
    }

    /** Track REST: 汇总轨道状态（GET /track/state）。 */
    public Map<String, Object> getTrackState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goals", worldDirector.getAllGoals());
        result.put("secret_agents", new ArrayList<>(orchestrator.getSecretAgents()));
        InteractionDetector.TrackScore score = orchestrator.getLastTrackScore();
        result.put("last_score", score == null ? Map.of() : score.toMap());
        // 附加：当前轨道分配摘要（agent → MERGED/WEAK/ISOLATED），供前端 2D 页展示。
        Map<String, String> modeSummary = new LinkedHashMap<>();
        for (Map.Entry<String, TrackAssignment> e : lastTrackAssignments.entrySet()) {
            modeSummary.put(e.getKey(), e.getValue().type().name());
        }
        result.put("assignments", modeSummary);
        return result;
    }

    // ── Director LLM (主控) ─────────────────────────────────────

    private void checkDirectorCycle() {
        long now = System.currentTimeMillis();
        if (now - lastDirectorTime < DIRECTOR_INTERVAL_MS) return;
        if (world.isDirectorActive()) return;
        lastDirectorTime = now;

        boolean anyActivity = false;
        for (AgentState s : world.getAllStates().values()) {
            if (s.isInConversation() || Math.abs(s.getVx()) > 0.5 || Math.abs(s.getVy()) > 0.5) {
                anyActivity = true; break;
            }
        }
        if (!anyActivity && world.getTickCount() > 50) return;

        runDirectorRound(false);
    }

    private void runDirectorRound(boolean userTriggered) {
        world.setDirectorActive(true);

        CompletableFuture.runAsync(() -> {
            try {
                String prompt = buildDirectorPrompt(userTriggered);
                Map<String, Object> result = llmClient.callJson(prompt, 600);
                if (result.isEmpty()) { world.setWorldNarration("(主控思考中...)"); return; }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
                String narration = (String) result.getOrDefault("narration", "");
                if (narration != null && !narration.isBlank()) world.setWorldNarration(narration);

                if (decisions != null) {
                    for (Map<String, Object> d : decisions) {
                        String name = (String) d.get("agent");
                        AgentState state = world.getState(name);
                        if (state == null || state.isInConversation()) continue;
                        // Skip player-controlled agent — don't let AI control them
                        if (state.isPlayerControlled()) continue;

                        Object ax = d.get("target_x");
                        Object ay = d.get("target_y");
                        if (ax instanceof Number && ay instanceof Number) {
                            state.setTargetX(clamp(((Number) ax).doubleValue(), 30, SimulationWorld.WORLD_WIDTH - 30));
                            state.setTargetY(clamp(((Number) ay).doubleValue(), 30, SimulationWorld.WORLD_HEIGHT - 30));
                            state.setHasTarget(true);
                        }

                        String action = (String) d.get("action");
                        if (action != null && !action.isBlank()) {
                            state.setCurrentMessage("(主控: " + action + ")");
                        }

                        String emotionStr = (String) d.get("emotion");
                        if (emotionStr != null) {
                            Emotion e = Emotion.fromText(emotionStr);
                            if (e != Emotion.NEUTRAL) state.setEmotion(e);
                        }
                    }
                }

                // Clear pending user message flags after director processes them
                pendingUserMessages.clear();

                String userDirective = world.getUserDirective();
                if (userTriggered && userDirective != null && !userDirective.isBlank()) {
                    world.setUserDirective("");
                }

                log.info("Director: {} decisions, \"{}\"",
                        decisions != null ? decisions.size() : 0,
                        narration != null ? narration : "");
            } catch (Exception e) {
                log.warn("Director failed: {}", e.getMessage());
            } finally {
                world.setDirectorActive(false);
            }
        }, taskExecutor);
    }

    private String buildDirectorPrompt(boolean userTriggered) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是虚拟世界的导演/主控（DM）。观察所有角色的状态，决定他们下一步的目标和行动。\n\n");

        sb.append("世界：").append((int) SimulationWorld.WORLD_WIDTH).append("×")
                .append((int) SimulationWorld.WORLD_HEIGHT).append("px，Tick #")
                .append(world.getTickCount()).append("，共").append(world.getAgentCount()).append("个角色\n\n");

        sb.append("角色状态：\n");
        for (AgentState s : world.getAllStates().values()) {
            Agent agent = world.getAgent(s.getAgentName());
            sb.append("---\n");
            sb.append(s.getAgentName());
            sb.append(" | 位置(").append(Math.round(s.getX())).append(",").append(Math.round(s.getY())).append(")");
            sb.append(" | 情绪:").append(s.getEmotion().getLabel()).append(s.getEmotion().getEmoji());
            sb.append(" | 对话:").append(s.isInConversation() ? "是" : "否");
            if (s.isHasTarget()) sb.append(" | 走向(").append(Math.round(s.getTargetX())).append(",").append(Math.round(s.getTargetY())).append(")");
            if (agent != null) {
                String trait = agent.getPersona().getPersonaDesc();
                sb.append(" | ").append(trait.length() > 50 ? trait.substring(0, 50) : trait);
            }
            String msg = s.getCurrentMessage();
            if (msg != null && !msg.isEmpty() && !msg.startsWith("(主控")) {
                sb.append(" | 说:").append(msg.length() > 40 ? msg.substring(0, 40) : msg);
            }
            sb.append("\n");
        }

        List<HearingSystem.HearingResult> hearing = world.getHearingSystem().computeAudibility(world.getAllStates().values());
        boolean anyAudible = false;
        sb.append("\n声音传播（可互相听到的组合）：\n");
        for (HearingSystem.HearingResult h : hearing) {
            if (h.canHear() && h.speakerName().compareTo(h.listenerName()) < 0) {
                sb.append("- ").append(h.speakerName()).append("←→").append(h.listenerName());
                sb.append(" 距离").append(Math.round(h.distance())).append("px");
                sb.append(" 清晰度").append(Math.round(h.clarity() * 100)).append("%\n");
                anyAudible = true;
            }
        }
        if (!anyAudible) sb.append("(无)\n");

        String directive = world.getUserDirective();
        if (directive != null && !directive.isBlank()) {
            sb.append("\n【用户指令！！！最高优先级】").append(directive).append("\n");
        }

        List<Map<String, Object>> convs = world.getRecentConversations();
        if (!convs.isEmpty()) {
            int start = Math.max(0, convs.size() - 2);
            sb.append("\n最近对话：\n");
            for (int i = start; i < convs.size(); i++) {
                sb.append("- ").append(convs.get(i).get("pair")).append("\n");
            }
        }

        sb.append("\n请为每个角色生成一个决定。已正在对话的角色保持原地(target=当前位置)。\n");
        if (userTriggered) sb.append("优先执行用户指令！\n");

        // Skip player-controlled agents — don't generate decisions for human players
        List<String> playerAgents = new ArrayList<>();
        for (AgentState s : world.getAllStates().values()) {
            if (s.isPlayerControlled()) playerAgents.add(s.getAgentName());
        }
        if (!playerAgents.isEmpty()) {
            sb.append("注意：以下角色由玩家亲自控制，不要为他们生成任何决定（target_x/target_y/action/emotion 字段留空）：");
            sb.append(String.join("、", playerAgents)).append("\n");
        }

        // Skip agents with pending user messages
        if (!pendingUserMessages.isEmpty()) {
            sb.append("注意：以下角色由用户亲自控制对话，不要为他们生成对话内容（action字段留空即可）：");
            sb.append(String.join("、", pendingUserMessages)).append("\n");
        }
        sb.append("JSON格式：\n");
        sb.append("{\"narration\":\"导演旁白\",\"decisions\":[");
        sb.append("{\"agent\":\"名字\",\"target_x\":坐标,\"target_y\":坐标,\"action\":\"行为描述\",\"emotion\":\"情绪\"}");
        sb.append("]}");
        return sb.toString();
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ── DB Persistence ──────────────────────────────────────────

    /** Save current world state as a snapshot to DB. */
    private void saveSnapshot() {
        int tick = world.getTickCount();
        String scene = world.getCurrentScene();

        // Build agent states list
        List<Map<String, Object>> agentStates = new ArrayList<>();
        for (AgentState s : world.getAllStates().values()) {
            agentStates.add(s.toMap());
        }

        String snapshotName = "snapshot_tick_" + tick;
        databaseService.saveWorldSnapshot(snapshotName, agentStates, scene, tick);

        // Save recent conversations as conversation logs
        List<Map<String, Object>> convs = world.getRecentConversations();
        if (!convs.isEmpty()) {
            List<String> participants = new ArrayList<>();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Map<String, Object> conv : convs) {
                String pair = (String) conv.getOrDefault("pair", "");
                if (pair != null && !pair.isEmpty()) {
                    String[] parts = pair.split("[←→]");
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty() && !participants.contains(trimmed)) {
                            participants.add(trimmed);
                        }
                    }
                }
                Map<String, Object> msg = new LinkedHashMap<>(conv);
                messages.add(msg);
            }
            databaseService.logConversation("simulation_" + scene, "2d", participants, messages, tick);
        }
    }
}
