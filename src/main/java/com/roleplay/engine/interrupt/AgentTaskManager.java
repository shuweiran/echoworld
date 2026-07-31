package com.roleplay.engine.interrupt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 任务生命周期管理器（需求文档第八条 §十一：agent/AgentTaskManager.java）。
 *
 * <p>负责 Agent 生成任务的完整生命周期：
 * 创建（Task ID 分配）→ 启动 → 状态机迁移 → 完成/取消。
 * 实际取消动作委托给 {@link InterruptManager}（单注册表，避免双份状态）。
 *
 * <pre>
 *   AgentTaskManager
 *      ├─ createTask()   → IDLE（注册到 InterruptManager）
 *      ├─ startTask()    → RUNNING
 *      ├─ completeTask() → DONE（移出活跃注册表）
 *      └─ cancelTask()   → CANCELLED / INTERRUPTED（委托 InterruptManager）
 * </pre>
 */
@Component
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private final InterruptManager interruptManager;
    /** 任务序号（Task ID 后缀）。 */
    private final AtomicLong seq = new AtomicLong(1);

    public AgentTaskManager(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
    }

    /**
     * 创建任务（IDLE 状态并注册）。
     *
     * @param agentName 执行该任务的 Agent 名
     * @param type      任务类型（DIALOGUE / MOVE / ...）
     * @param metadata  附加上下文：建议放 trackId / groupId / round / sessionId
     * @return 已注册的任务（Task ID 格式：{agent}_{type}_{seq}，如 小明_dialogue_3）
     */
    public AgentTask createTask(String agentName, TaskType type, Map<String, Object> metadata) {
        String id = buildTaskId(agentName, type);
        AgentTask task = new AgentTask(id, agentName, type, metadata);
        interruptManager.register(task);
        log.debug("Task created: {}", task);
        return task;
    }

    /** IDLE → PLANNING。 */
    public AgentTask planTask(AgentTask task) {
        if (task != null) task.toPlanning();
        return task;
    }

    /** → RUNNING（IDLE 会自动先过 PLANNING）。 */
    public AgentTask startTask(AgentTask task) {
        if (task != null) task.toRunning();
        return task;
    }

    /** RUNNING → DONE，并移出活跃注册表（转入历史归档）。 */
    public AgentTask completeTask(AgentTask task) {
        if (task == null) return null;
        task.toDone();
        interruptManager.unregister(task.getId());
        return task;
    }

    /**
     * 执行失败 → {@link AgentTaskStatus#FAILED}（D22）。
     *
     * <p>区别于主动中断（CANCELLED/INTERRUPTED）：失败不是停止动作，不占用取消语义；
     * 根因（如 LLM 401）写入任务 reason，供 {@code /api/interrupt/tasks} 调用方识别。
     */
    public AgentTask failTask(AgentTask task, String reason) {
        if (task == null) return null;
        return interruptManager.markFailed(task.getId(), reason);
    }

    // ── 取消委托（三种停止类型见 StopType） ────────────────────

    /** 按 Task ID 取消。 */
    public AgentTask cancelTask(String taskId, StopType stopType, String reason) {
        return interruptManager.cancel(taskId, stopType, reason);
    }

    /** 按 Task ID 取消（便捷重载：HARD）。 */
    public AgentTask cancelTask(String taskId, String reason) {
        return interruptManager.cancel(taskId, StopType.HARD, reason);
    }

    /** 取消指定 Agent 的全部任务。 */
    public List<AgentTask> cancelAgent(String agentName, StopType stopType, String reason) {
        return interruptManager.cancelAgent(agentName, stopType, reason);
    }

    /** 取消指定轨道上的全部任务（TrackChangeEvent 场景）。 */
    public List<AgentTask> cancelTrack(String trackId, String reason) {
        List<AgentTask> cancelled = new java.util.ArrayList<>();
        for (AgentTask t : interruptManager.listTasks(null, null, null)) {
            Object tid = t.getMetadata().get("trackId");
            if (tid != null && String.valueOf(tid).equals(trackId) && t.getStatus().isActive()) {
                interruptManager.cancel(t.getId(), StopType.STATE_INVALID, reason);
                cancelled.add(t);
            }
        }
        return cancelled;
    }

    // ── 查询 ──────────────────────────────────────────────────

    public AgentTask getTask(String taskId) { return interruptManager.getTask(taskId); }
    public List<AgentTask> listTasks(String agent, TaskType type, AgentTaskStatus status) {
        return interruptManager.listTasks(agent, type, status);
    }
    public InterruptManager getInterruptManager() { return interruptManager; }

    // ── Task ID 生成 ──────────────────────────────────────────

    /**
     * Task ID 格式对齐需求文档示例 {@code npc_001_dialogue_88}：
     * {@code {agent}_{type}_{seq}}（中文 agent 名同样适用）。
     */
    private String buildTaskId(String agentName, TaskType type) {
        String agentPart = agentName != null && !agentName.isBlank() ? agentName : "agent";
        String typePart = type != null ? type.name().toLowerCase() : "task";
        return agentPart + "_" + typePart + "_" + seq.getAndIncrement();
    }
}
