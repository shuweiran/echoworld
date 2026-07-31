package com.roleplay.engine.interrupt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 任务（需求文档第八条 §三：每个 Agent 任务必须有 Task ID）。
 *
 * <p>替代裸 {@code agent.say()}：每个任务携带 id / agent / type / status /
 * CancellationToken，从而支持"停止指定任务"。
 *
 * <p>示例：{@code {"id":"npc_001_dialogue_88","agent":"A","type":"DIALOGUE","status":"RUNNING"}}
 *
 * <p>状态迁移（由 {@link AgentTaskManager} / {@link InterruptManager} 驱动）：
 * IDLE → PLANNING → RUNNING → DONE；任何活跃态可 → CANCELLED / INTERRUPTED。
 */
public class AgentTask {

    /** 任务唯一 ID（格式：{agent}_{type}_{seq}，如 小明_dialogue_3）。 */
    private final String id;
    private final String agentName;
    private final TaskType type;
    /** 附加上下文：trackId / groupId / round / sessionId 等，供事件驱动取消判定。 */
    private final Map<String, Object> metadata;
    /** 协作式取消令牌（取消信号检查点）。 */
    private final CancellationToken cancelToken = new CancellationToken();
    private final long createdAt = System.currentTimeMillis();

    private volatile AgentTaskStatus status = AgentTaskStatus.IDLE;
    private volatile long updatedAt = createdAt;
    /** 软停止时保存的未完成内容（已生成但未提交）。 */
    private volatile String unfinishedContent = "";
    private volatile StopType stopType;
    private volatile String stopReason = "";

    public AgentTask(String id, String agentName, TaskType type, Map<String, Object> metadata) {
        this.id = id;
        this.agentName = agentName;
        this.type = type != null ? type : TaskType.GENERATION;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public String getId() { return id; }
    public String getAgentName() { return agentName; }
    public TaskType getType() { return type; }
    public Map<String, Object> getMetadata() { return metadata; }
    public CancellationToken getCancelToken() { return cancelToken; }
    public long getCreatedAt() { return createdAt; }
    public AgentTaskStatus getStatus() { return status; }
    public long getUpdatedAt() { return updatedAt; }
    public String getUnfinishedContent() { return unfinishedContent; }
    public StopType getStopType() { return stopType; }
    public String getStopReason() { return stopReason; }

    // ── 状态迁移（带守卫的状态机） ──────────────────────────────

    /** 保存未完成内容（SOFT 停止时由生成链路回调）。 */
    public void saveUnfinished(String partial) {
        if (partial != null) this.unfinishedContent = partial;
    }

    /** IDLE → PLANNING。 */
    public boolean toPlanning() {
        return transition(AgentTaskStatus.PLANNING);
    }

    /** PLANNING → RUNNING。 */
    public boolean toRunning() {
        if (status == AgentTaskStatus.IDLE) toPlanning();
        return transition(AgentTaskStatus.RUNNING);
    }

    /** RUNNING → DONE（正常完成）。 */
    public boolean toDone() {
        return transition(AgentTaskStatus.DONE);
    }

    /** 任意活跃态 → CANCELLED（HARD / SOFT 停止）。 */
    public boolean toCancelled(StopType stopType, String reason) {
        return terminate(AgentTaskStatus.CANCELLED, stopType, reason);
    }

    /** 任意活跃态 → INTERRUPTED（STATE_INVALID 停止）。 */
    public boolean toInterrupted(String reason) {
        return terminate(AgentTaskStatus.INTERRUPTED, StopType.STATE_INVALID, reason);
    }

    /** 任意活跃态 → FAILED（执行失败，如 LLM 401/超时；非停止动作，无 stopType，D22）。 */
    public boolean toFailed(String reason) {
        return terminate(AgentTaskStatus.FAILED, null, reason);
    }

    private synchronized boolean transition(AgentTaskStatus target) {
        if (!canTransitionTo(target)) return false;
        this.status = target;
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    private synchronized boolean terminate(AgentTaskStatus target, StopType stopType, String reason) {
        if (!status.isActive()) return false;      // 终态不可再取消
        if (target == AgentTaskStatus.CANCELLED) {
            // HARD/SOFT 统一落到 CANCELLED；STATE_INVALID 由 toInterrupted 处理
            this.stopType = stopType != null ? stopType : StopType.HARD;
        } else if (target == AgentTaskStatus.FAILED) {
            // 失败非停止动作 → 无停止类型（toMap 输出 stop_type=""，调用方可依 status=FAILED 区分）
            this.stopType = null;
        } else {
            this.stopType = StopType.STATE_INVALID;
        }
        this.stopReason = reason != null ? reason : "";
        this.status = target;
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    private boolean canTransitionTo(AgentTaskStatus target) {
        return switch (target) {
            case PLANNING -> status == AgentTaskStatus.IDLE;
            case RUNNING -> status == AgentTaskStatus.IDLE || status == AgentTaskStatus.PLANNING;
            case DONE -> status == AgentTaskStatus.RUNNING;
            default -> false;
        };
    }

    /** 序列化视图（API 输出）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("agent", agentName);
        m.put("type", type.name());
        m.put("status", status.name());
        m.put("stop_type", stopType != null ? stopType.name() : "");
        m.put("reason", stopReason);
        m.put("created_at", createdAt);
        m.put("updated_at", updatedAt);
        m.put("unfinished", unfinishedContent);
        if (!metadata.isEmpty()) m.put("metadata", metadata);
        return m;
    }

    @Override
    public String toString() {
        return "AgentTask{" + id + ", agent=" + agentName + ", type=" + type
                + ", status=" + status + "}";
    }
}
