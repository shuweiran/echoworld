package com.roleplay.engine.interrupt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/**
 * 中断管理器（需求文档第八条 §二 / §十 / §十一：InterruptManager.java）。
 *
 * <pre>
 *                  SimulationWorld
 *                        |
 *                 InterruptManager
 *              /        |        \
 *        PlayerEvent  WorldEvent  TrackEvent
 *              \        |        /
 *                 Agent Task
 *                      |
 *                     LLM
 * </pre>
 *
 * <p>职责：
 * <ul>
 *   <li>任务注册表：task id → {@link AgentTask}（新增/查询/取消/列表）</li>
 *   <li>三种停止类型下发：{@link StopType#HARD}（立即中断线程）/
 *       {@link StopType#SOFT}（协作式，保存未完成状态）/
 *       {@link StopType#STATE_INVALID}（意图失效 → INTERRUPTED）</li>
 *   <li>关联线程中断：HARD/STATE_INVALID 时对挂接的 {@link Future} 调用
 *       {@code cancel(true)}，abort 进行中的 LLM HTTP 调用（HttpClient.send 可中断）</li>
 *   <li>事件驱动：订阅 {@link GameEvent#TYPE_TRACK_CHANGED} → 自动取消不属于
 *       新轨道的任务（需求文档 §七）；自身发布 TASK_STARTED/TASK_CANCELLED/TASK_DONE</li>
 * </ul>
 */
@Component
public class InterruptManager {

    private static final Logger log = LoggerFactory.getLogger(InterruptManager.class);

    /** 活跃任务注册表（含 IDLE/PLANNING/RUNNING）。 */
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
    /** 任务 id → 关联的执行 Future（用于线程中断）。 */
    private final Map<String, CopyOnWriteArrayList<Future<?>>> futures = new ConcurrentHashMap<>();
    /** 历史归档（终态任务，上限 1000 条，便于 API 查询）。 */
    private final Map<String, AgentTask> history = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentTask> eldest) {
                    return size() > 1000;
                }
            });

    private final WorldEventBus eventBus;

    public InterruptManager(WorldEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    void init() {
        // 事件驱动：轨道变化 → 判定当前生成任务是否仍属于新轨道，不属于则取消（§七）。
        eventBus.subscribe(GameEvent.TYPE_TRACK_CHANGED, this::onTrackChanged);
        log.info("InterruptManager initialized (track-change listener registered)");
    }

    // ═══════════════════════════════════════════════════════════
    //  注册表
    // ═══════════════════════════════════════════════════════════

    /** 注册任务（IDLE 状态）。 */
    public AgentTask register(AgentTask task) {
        if (task == null) return null;
        tasks.put(task.getId(), task);
        return task;
    }

    /** 从活跃注册表移除（终态任务转入历史归档）。 */
    public void unregister(String taskId) {
        AgentTask removed = tasks.remove(taskId);
        if (removed != null) {
            history.put(taskId, removed);
            futures.remove(taskId);
        }
    }

    /** 按 Task ID 查询（先活跃表，再历史归档）。 */
    public AgentTask getTask(String taskId) {
        AgentTask t = tasks.get(taskId);
        if (t != null) return t;
        synchronized (history) {
            return history.get(taskId);
        }
    }

    /** 任务列表（按 agent / type / status 过滤，任意条件为 null 表示不过滤）。 */
    public List<AgentTask> listTasks(String agent, TaskType type, AgentTaskStatus status) {
        List<AgentTask> result = new ArrayList<>();
        List<AgentTask> all = new ArrayList<>(tasks.values());
        synchronized (history) {
            all.addAll(history.values());
        }
        for (AgentTask t : all) {
            if (agent != null && !agent.isBlank() && !agent.equals(t.getAgentName())) continue;
            if (type != null && type != t.getType()) continue;
            if (status != null && status != t.getStatus()) continue;
            result.add(t);
        }
        return result;
    }

    /** 当前活跃任务数（IDLE/PLANNING/RUNNING）。 */
    public int activeTaskCount() {
        return (int) tasks.values().stream().filter(t -> t.getStatus().isActive()).count();
    }

    public boolean hasActiveTasks() {
        return tasks.values().stream().anyMatch(t -> t.getStatus().isActive());
    }

    // ═══════════════════════════════════════════════════════════
    //  线程关联（供 HARD / STATE_INVALID 立即中断）
    // ═══════════════════════════════════════════════════════════

    /** 挂接执行 Future：取消该任务时可中断对应线程（LLM HTTP 调用立即 abort）。 */
    public void attachFuture(String taskId, Future<?> future) {
        if (taskId == null || future == null) return;
        AgentTask task = tasks.get(taskId);
        if (task == null) return;   // 任务已终态/归档 → 无需挂接（避免孤儿引用）
        // 若令牌已被取消（竞态），立即中断新挂接的线程。
        if (task.getCancelToken().isCancelled()) {
            future.cancel(true);
            return;
        }
        futures.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(future);
    }

    /** 解除挂接（任务正常结束时调用，避免误中断已完成线程）。 */
    public void detachFuture(String taskId, Future<?> future) {
        CopyOnWriteArrayList<Future<?>> list = futures.get(taskId);
        if (list != null) list.remove(future);
    }

    private void interruptTaskThreads(String taskId) {
        CopyOnWriteArrayList<Future<?>> list = futures.get(taskId);
        if (list == null) return;
        for (Future<?> f : list) {
            f.cancel(true);   // 中断虚拟线程 → httpClient.send 抛 InterruptedException
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  取消 API —— 三种停止类型（需求文档 §四）
    // ═══════════════════════════════════════════════════════════

    /**
     * 按 Task ID 取消。
     *
     * @return 被取消的任务；任务不存在返回 null
     */
    public AgentTask cancel(String taskId, StopType stopType, String reason) {
        AgentTask task = tasks.get(taskId);
        if (task == null) {
            log.info("Cancel ignored: task {} not active", taskId);
            return null;
        }
        doCancel(task, stopType, reason);
        return task;
    }

    /** 取消指定 Agent 的全部活跃任务。 */
    public List<AgentTask> cancelAgent(String agentName, StopType stopType, String reason) {
        List<AgentTask> cancelled = new ArrayList<>();
        for (AgentTask t : tasks.values()) {
            if (t.getAgentName().equals(agentName) && t.getStatus().isActive()) {
                doCancel(t, stopType, reason);
                cancelled.add(t);
            }
        }
        return cancelled;
    }

    /** 取消指定类型（如 COMBAT_START → 取消全部 MOVE）的全部活跃任务。 */
    public List<AgentTask> cancelByType(TaskType type, StopType stopType, String reason) {
        List<AgentTask> cancelled = new ArrayList<>();
        for (AgentTask t : tasks.values()) {
            if (t.getType() == type && t.getStatus().isActive()) {
                doCancel(t, stopType, reason);
                cancelled.add(t);
            }
        }
        return cancelled;
    }

    /** 取消全部活跃任务（如玩家退出 / 模拟停止）。 */
    public List<AgentTask> cancelAll(StopType stopType, String reason) {
        List<AgentTask> cancelled = new ArrayList<>();
        for (AgentTask t : new ArrayList<>(tasks.values())) {
            if (t.getStatus().isActive()) {
                doCancel(t, stopType, reason);
                cancelled.add(t);
            }
        }
        if (!cancelled.isEmpty()) {
            log.info("InterruptManager cancelled {} tasks ({}): {}", cancelled.size(), stopType, reason);
        }
        return cancelled;
    }

    /**
     * 取消执行核心：
     * <ol>
     *   <li>协作式令牌置位（所有检查点生效）</li>
     *   <li>状态机迁移：STATE_INVALID → INTERRUPTED；HARD/SOFT → CANCELLED</li>
     *   <li>HARD / STATE_INVALID → 立即中断关联线程（abort 进行中的 LLM 调用）；
     *       SOFT → 不中断，等当前调用结束后在检查点退出（保存未完成状态）</li>
     *   <li>发布 {@link GameEvent#TYPE_TASK_CANCELLED} 事件</li>
     * </ol>
     */
    private void doCancel(AgentTask task, StopType stopType, String reason) {
        if (task == null || !task.getStatus().isActive()) return;
        StopType st = stopType != null ? stopType : StopType.HARD;

        // 1) 令牌置位（先于状态迁移，保证任何检查点立刻抛出 TaskCancelledException）
        task.getCancelToken().cancel(st, reason);

        // 2) 状态机迁移
        if (st == StopType.STATE_INVALID) {
            task.toInterrupted(reason);
        } else {
            task.toCancelled(st, reason);
        }

        // 3) 线程中断（HARD/STATE_INVALID 立即 abort；SOFT 协作式等待）
        if (st != StopType.SOFT) {
            interruptTaskThreads(task.getId());
        }

        // 4) 事件发布（观察者/前端可订阅 TASK_CANCELLED）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", task.getId());
        payload.put("agent", task.getAgentName());
        payload.put("type", task.getType().name());
        payload.put("status", task.getStatus().name());
        payload.put("stop_type", st.name());
        payload.put("reason", reason);
        eventBus.publish(new GameEvent(GameEvent.TYPE_TASK_CANCELLED, "interrupt", payload));

        log.info("Task {} ({}/{}) {} — {}", task.getId(), task.getAgentName(),
                task.getType(), task.getStatus(), reason);
    }

    // ═══════════════════════════════════════════════════════════
    //  事件驱动：轨道变化 → 取消失效任务（需求文档 §七）
    // ═══════════════════════════════════════════════════════════

    /**
     * TRACK_CHANGED 处理器：判断当前生成任务是否仍属于新轨道，不属于则取消。
     *
     * <p>判定规则：
     * <ul>
     *   <li>任务 metadata.trackId 不在新轨道集合 → 轨道已被移除 → 取消（STATE_INVALID）</li>
     *   <li>任务所在轨道的新参与者列表不含该 agent → 角色已离开该轨道 → 取消</li>
     * </ul>
     */
    private void onTrackChanged(GameEvent event) {
        List<String> newTrackIds = event.getPayload() != null
                ? castStringList(event.getPayload().get(TrackChangeEvent.KEY_TRACK_IDS))
                : List.of();
        Map<String, List<String>> trackAgents = castStringListMap(
                event.getPayload() != null ? event.getPayload().get(TrackChangeEvent.KEY_TRACK_AGENTS) : null);

        int cancelledCount = 0;
        for (AgentTask t : new ArrayList<>(tasks.values())) {
            if (!t.getStatus().isActive()) continue;
            Object trackIdObj = t.getMetadata().get("trackId");
            if (trackIdObj == null) continue;
            String trackId = String.valueOf(trackIdObj);

            if (!newTrackIds.contains(trackId)) {
                doCancel(t, StopType.STATE_INVALID,
                        "轨道变更，任务所在轨道已移除: " + trackId);
                cancelledCount++;
                continue;
            }
            List<String> agents = trackAgents.get(trackId);
            if (agents != null && !agents.isEmpty() && !agents.contains(t.getAgentName())) {
                doCancel(t, StopType.STATE_INVALID,
                        "轨道变更，角色已离开轨道 " + trackId);
                cancelledCount++;
            }
        }
        if (cancelledCount > 0) {
            log.info("Track change cancelled {} stale tasks", cancelledCount);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object e : list) out.add(String.valueOf(e));
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> castStringListMap(Object o) {
        if (o instanceof Map<?, ?> map) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object v = e.getValue();
                if (v instanceof List<?> list) {
                    List<String> vals = new ArrayList<>();
                    for (Object x : list) vals.add(String.valueOf(x));
                    out.put(String.valueOf(e.getKey()), vals);
                }
            }
            return out;
        }
        return Map.of();
    }
}
