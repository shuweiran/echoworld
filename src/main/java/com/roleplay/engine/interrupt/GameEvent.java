package com.roleplay.engine.interrupt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 世界事件（需求文档第八条 §十一：event/GameEvent）。
 *
 * <p>事件驱动中断系统的消息载体：{@link WorldEventBus} 发布 →
 * {@link InterruptManager} 订阅 → 判定并取消相关 Agent 任务。
 *
 * <p>内置事件类型常量：
 * <ul>
 *   <li>{@link #TYPE_TRACK_CHANGED} 轨道变化（TrackDirector 发布）</li>
 *   <li>{@link #TYPE_TASK_CANCELLED} 任务已取消（InterruptManager 发布）</li>
 *   <li>{@link #TYPE_TASK_FAILED} 任务执行失败（InterruptManager 发布，D22）</li>
 *   <li>{@link #TYPE_TASK_STARTED} / {@link #TYPE_TASK_DONE} 任务生命周期</li>
 *   <li>{@link #TYPE_COMBAT_START} 战斗开始（§六 示例事件）</li>
 * </ul>
 */
public class GameEvent {

    public static final String TYPE_TRACK_CHANGED = "TRACK_CHANGED";
    public static final String TYPE_TASK_CANCELLED = "TASK_CANCELLED";
    public static final String TYPE_TASK_FAILED = "TASK_FAILED";
    public static final String TYPE_TASK_STARTED = "TASK_STARTED";
    public static final String TYPE_TASK_DONE = "TASK_DONE";
    public static final String TYPE_COMBAT_START = "COMBAT_START";
    public static final String TYPE_PLAYER_ACTION = "PLAYER_ACTION";
    public static final String TYPE_WORLD_CHANGED = "WORLD_CHANGED";
    /** 公告/广播（演讲与广播合并地基）：AnnouncementService flush 时发布，订阅方可做进程内响应。 */
    public static final String TYPE_ANNOUNCEMENT = "ANNOUNCEMENT";

    private final String id;
    private final String type;
    private final String source;
    private final long timestamp;
    private final Map<String, Object> payload;

    public GameEvent(String type, String source, Map<String, Object> payload) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.source = source;
        this.timestamp = System.currentTimeMillis();
        this.payload = payload != null ? Map.copyOf(payload) : Map.of();
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getSource() { return source; }
    public long getTimestamp() { return timestamp; }
    public Map<String, Object> getPayload() { return payload; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("source", source);
        m.put("timestamp", timestamp);
        m.put("payload", payload);
        return m;
    }

    @Override
    public String toString() {
        return "GameEvent{" + type + " from " + source + " @" + timestamp + "}";
    }
}
