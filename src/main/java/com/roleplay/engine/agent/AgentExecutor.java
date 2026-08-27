package com.roleplay.engine.agent;

import jakarta.annotation.PreDestroy;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Track;
import com.roleplay.engine.core.TrackConfig;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.AgentTaskStatus;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.StopType;
import com.roleplay.engine.interrupt.TaskCancelledException;
import com.roleplay.engine.interrupt.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ⭐ Parallel agent executor — the KEY performance improvement over Python.
 *
 * <p>In Python, agents are executed SERIALLY within each track
 * （see {@code router.py → _run_round_agents}, 5 agents = 5x LLM delay）.
 *
 * <p>In Java, each agent runs in its OWN VIRTUAL THREAD, so all agents
 * call the LLM in TRUE PARALLEL. 5 agents = 1x LLM delay.
 *
 * <p>Strategy:
 * <ul>
 *   <li><b>Isolated tracks</b> — agents run fully in parallel（no shared context）</li>
 *   <li><b>Merged/WEAK tracks</b> — agents within same track run in parallel
 *       （they share history context but NOT same-round peer outputs）</li>
 *   <li><b>Priority ordering</b> — PLAYER > DM > NPC</li>
 * </ul>
 *
 * <p>Maps from Python {@code core/scheduler.py}（which was DEAD CODE —
 * Router never used it）. In Java this IS the execution engine.
 */
@Service
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    /** Max agents running concurrently （safety limit）. */
    private static final int MAX_CONCURRENT = 16;

    /** Virtual thread executor — each agent gets its own thread. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** D1: 中断管理器（取消时中断关联线程 + 状态机）。 */
    private final InterruptManager interruptManager;
    /** D1: Agent 任务生命周期管理（Task ID + CancellationToken）。 */
    private final AgentTaskManager agentTaskManager;

    public AgentExecutor(InterruptManager interruptManager, AgentTaskManager agentTaskManager) {
        this.interruptManager = interruptManager;
        this.agentTaskManager = agentTaskManager;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ── Priority enum ──────────────────────────────────────────

    public enum Priority {
        PLAYER(1),      // human player / protagonist
        DM(2),          // director / god
        NPC(3),         // NPCs and weak-chain bystanders
        LOWEST(4);      // offline / background

        final int level;
        Priority(int level) { this.level = level; }
    }

    // ── AgentTask record ───────────────────────────────────────

    public record AgentTask(
            String agentName,
            String trackId,
            String trackMode,
            Priority priority,
            List<String> visibleTo,
            Callable<String> task
    ) {}

    // ── Execution result ────────────────────────────────────────

    public record AgentOutput(
            String agentName,
            String content,
            String trackId,
            List<String> visibleTo,
            long elapsedMs,
            String error
    ) {
        public boolean isSuccess() { return error == null || error.isEmpty(); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agent_name", agentName);
            m.put("content", content);
            m.put("track_id", trackId);
            m.put("visible_to", visibleTo);
            m.put("elapsed_ms", elapsedMs);
            return m;
        }
    }

    // ── Metrics ────────────────────────────────────────────────

    public record ExecutorMetrics(
            int totalTasks,
            int maxConcurrent,
            double avgLatencyMs,
            double maxLatencyMs,
            double totalRoundTimeMs
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total_tasks", totalTasks);
            m.put("max_concurrent", maxConcurrent);
            m.put("avg_latency_ms", Math.round(avgLatencyMs * 100.0) / 100.0);
            m.put("max_latency_ms", Math.round(maxLatencyMs * 100.0) / 100.0);
            m.put("total_round_time_ms", Math.round(totalRoundTimeMs * 100.0) / 100.0);
            return m;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Core execution method
    // ════════════════════════════════════════════════════════════

    /**
     * Execute ALL agents in a TrackConfig in TRUE PARALLEL.
     *
     * <p>This is the direct replacement for Python's serial
     * {@code _run_round_agents()} method.
     *
     * @param config       the track configuration for this round
     * @param agents       map of agent name → Agent instance
     * @param contextBuilder builds per-agent context before calling LLM
     * @return list of agent outputs （one per active agent）
     */
    public ExecutionResult executeRound(
            TrackConfig config,
            Map<String, Agent> agents,
            ContextBuilder contextBuilder) {

        Instant roundStart = Instant.now();

        // D1: 先为本轮每个参与 Agent 注册中断任务（Task ID + CancellationToken）。
        // 轨道信息来自 config（每个 agent 归属其所在的第一条轨道）。
        Map<String, String> trackIdOfAgent = new LinkedHashMap<>();
        Map<String, String> trackModeOfAgent = new LinkedHashMap<>();
        Set<String> activeNames = new LinkedHashSet<>();
        for (Track track : config.getTracks()) {
            for (String an : track.getActiveAgents()) {
                if (!agents.containsKey(an)) continue;
                trackIdOfAgent.putIfAbsent(an, track.getId());
                trackModeOfAgent.putIfAbsent(an, track.getMode().name().toLowerCase());
                activeNames.add(an);
            }
        }
        Map<String, com.roleplay.engine.interrupt.AgentTask> interruptTasks = new HashMap<>();
        Map<String, CancellationToken> tokens = new HashMap<>();
        for (String an : activeNames) {
            com.roleplay.engine.interrupt.AgentTask it = agentTaskManager.createTask(
                    an, TaskType.DIALOGUE,
                    Map.of("trackId", trackIdOfAgent.getOrDefault(an, ""),
                           "trackMode", trackModeOfAgent.getOrDefault(an, "")));
            interruptTasks.put(an, it);
            tokens.put(an, it.getCancelToken());
        }

        List<AgentTask> tasks = buildTasks(config, agents, contextBuilder, tokens);

        if (tasks.isEmpty()) {
            for (com.roleplay.engine.interrupt.AgentTask it : interruptTasks.values()) {
                interruptManager.unregister(it.getId());
            }
            return new ExecutionResult(List.of(), new ExecutorMetrics(0, 0, 0, 0, 0));
        }

        // Submit all tasks to virtual threads in parallel
        int totalTasks = tasks.size();
        double maxLatency = 0;
        double totalLatency = 0;

        List<AgentOutput> outputs = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(totalTasks);
        ConcurrentLinkedQueue<AgentOutput> resultQueue = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        final int[] maxConcurrentRef = {0};

        for (AgentTask task : tasks) {
            com.roleplay.engine.interrupt.AgentTask it = interruptTasks.get(task.agentName());
            agentTaskManager.startTask(it);
            Future<?> f = executor.submit(() -> {
                Instant taskStart = Instant.now();
                try {
                    String content = task.task().call();
                    long elapsed = Duration.between(taskStart, Instant.now()).toMillis();
                    latencies.add(elapsed);

                    synchronized (this) {
                        int current = tasks.size() - (int) latch.getCount() + 1;
                        if (current > maxConcurrentRef[0]) maxConcurrentRef[0] = current;
                    }

                    resultQueue.add(new AgentOutput(
                            task.agentName(), content, task.trackId(),
                            task.visibleTo(), elapsed, null));
                    agentTaskManager.completeTask(it);
                } catch (TaskCancelledException e) {
                    // D1: 任务被取消 —— 结果不入成功输出（RouterService 会跳过），
                    // 软停止的未完成内容保存到任务上（需求文档 §四 软停止）。
                    long elapsed = Duration.between(taskStart, Instant.now()).toMillis();
                    it.saveUnfinished(e.getPartial());
                    resultQueue.add(new AgentOutput(
                            task.agentName(), null, task.trackId(),
                            task.visibleTo(), elapsed,
                            "cancelled" + (e.getReason() != null && !e.getReason().isBlank()
                                    ? ": " + e.getReason() : "")));
                    log.info("Agent {} task {} cancelled: {}", task.agentName(), it.getId(), e.getReason());
                } catch (Exception e) {
                    long elapsed = Duration.between(taskStart, Instant.now()).toMillis();
                    resultQueue.add(new AgentOutput(
                            task.agentName(),
                            "[" + task.agentName() + " 走神了: " + e.getMessage() + "]",
                            task.trackId(),
                            task.visibleTo(), elapsed, e.getMessage()));
                    agentTaskManager.failTask(it, e.getMessage());
                    log.warn("Agent {} failed: {}", task.agentName(), e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            // D1: 挂接 Future —— 取消时立即中断该虚拟线程（abort 进行中的 LLM HTTP 调用）
            interruptManager.attachFuture(it.getId(), f);
        }

        // Wait for ALL agents to complete
        try {
            boolean allDone = latch.await(120, TimeUnit.SECONDS);
            if (!allDone) {
                log.warn("Agent round timed out: {} tasks incomplete after 120s",
                        latch.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // D1: 请求线程被中断（取消信号）→ 取消本回合全部剩余任务
            for (com.roleplay.engine.interrupt.AgentTask it : interruptTasks.values()) {
                interruptManager.cancel(it.getId(), StopType.HARD, "回合执行线程被中断");
            }
            log.info("Agent round interrupted by cancel request");
        }

        // Collect results
        outputs.addAll(resultQueue);
        for (Long lat : latencies) {
            totalLatency += lat;
            if (lat > maxLatency) maxLatency = lat;
        }
        int completedCount = outputs.size();

        // D1: 判定本回合是否被取消（任一任务 CANCELLED/INTERRUPTED）→ 收尾终态任务
        boolean cancelled = false;
        for (com.roleplay.engine.interrupt.AgentTask it : interruptTasks.values()) {
            AgentTaskStatus st = it.getStatus();
            if (st == AgentTaskStatus.CANCELLED || st == AgentTaskStatus.INTERRUPTED) {
                cancelled = true;
            }
            if (st.isTerminal()) {
                interruptManager.unregister(it.getId());
            }
        }

        double avgLatency = completedCount > 0 ? totalLatency / completedCount : 0;
        double totalTimeMs = Duration.between(roundStart, Instant.now()).toMillis();

        ExecutorMetrics metrics = new ExecutorMetrics(
                totalTasks, maxConcurrentRef[0], avgLatency, maxLatency, totalTimeMs);

        // D24: {:.0f} 是 Python 风格占位符，SLF4J 不支持（会原样输出字面量并丢失后续参数）；
        // 改用 {} + Math.round（等价于 {:.0f} 的 0 位小数取整），保留原日志意图
        log.info("Agent round complete: {} agents in {}ms (avg {}ms/agent){}",
                completedCount, Math.round(totalTimeMs), Math.round(avgLatency),
                cancelled ? " [CANCELLED]" : "");

        return new ExecutionResult(outputs, metrics, cancelled);
    }

    // ── Task building ──────────────────────────────────────────

    private List<AgentTask> buildTasks(
            TrackConfig config,
            Map<String, Agent> agents,
            ContextBuilder contextBuilder,
            Map<String, CancellationToken> tokens) {

        List<AgentTask> tasks = new ArrayList<>();

        for (Track track : config.getTracks()) {
            for (String agentName : track.getActiveAgents()) {
                Agent agent = agents.get(agentName);
                if (agent == null) continue;

                Priority priority = computePriority(
                        agentName, track.getMode().name().toLowerCase(),
                        "", "");

                String trackMode = track.getMode().name().toLowerCase();
                String trackId = track.getId();

                Callable<String> callable = () -> {
                    CancellationToken token = tokens.get(agentName);
                    // D1: 检查点 —— 上下文构建前 / LLM 调用前，取消则立即中断
                    if (token != null) token.checkpoint();
                    String context = contextBuilder.buildContext(
                            agentName, trackMode, trackId, config);
                    if (token != null) token.checkpoint();
                    // P-0814-C 修复：并行路径此前构建 context 但未传给 generateSync（只传 persona
                    // 轻量人设 + 空 history，D-024 记录未修的欠账）→ AI 生成看不到场景/对话历史/玩家
                    // 消息（“AI 无视玩家质问、乱发挥”根因）。修复：context 作为 USER 消息传入，与
                    // 串行路径 generateWithContextStream（system=完整人设, user=context）同构。
                    return agent.generateSync(
                            null,
                            List.of(new Message(Message.Role.USER, "user", context)),
                            trackMode, List.of(), "", null, "", token);
                };

                tasks.add(new AgentTask(agentName, trackId, trackMode, priority,
                        List.copyOf(track.getActiveAgents()), callable));
            }
        }

        // Sort by priority: PLAYER first, then DM, then NPC
        tasks.sort(Comparator.comparingInt(t -> t.priority().level));
        return tasks;
    }

    // ── Priority computation ───────────────────────────────────

    private Priority computePriority(
            String agentName, String trackMode,
            String protagonist, String director) {

        if (agentName.equals(protagonist) || agentName.equals("me")) {
            return Priority.PLAYER;
        }
        if (agentName.equals(director)) {
            return Priority.DM;
        }
        return Priority.NPC;
    }

    // ── Execution result container ─────────────────────────────

    public record ExecutionResult(
            List<AgentOutput> outputs,
            ExecutorMetrics metrics,
            boolean cancelled
    ) {
        /** 兼容旧调用方（未触发取消）。 */
        public ExecutionResult(List<AgentOutput> outputs, ExecutorMetrics metrics) {
            this(outputs, metrics, false);
        }
    }

    // ── Context builder interface ──────────────────────────────

    @FunctionalInterface
    public interface ContextBuilder {
        /**
         * Build the LLM context for a single agent before generation.
         *
         * @return the context string to prepend to the agent's prompt
         */
        String buildContext(String agentName, String trackMode,
                            String trackId, TrackConfig config);
    }
}
