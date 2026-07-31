package com.roleplay.engine.interrupt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轨道变化事件（需求文档第八条 §十一：track/TrackChangeEvent；§七 与 Track System 结合）。
 *
 * <p>场景：A+B 秘密聊天（Track1）时 C 加入 → 轨道变化 A=====B=====C，
 * 原 A/B 上下文失效。TrackDirector 发布本事件 → {@link InterruptManager} 判断
 * 当前生成任务是否仍属于新轨道，不属于则取消（STATE_INVALID → INTERRUPTED）。
 */
public class TrackChangeEvent extends GameEvent {

    /** 事件载荷键：新轨道 id 列表。 */
    public static final String KEY_TRACK_IDS = "track_ids";
    /** 事件载荷键：轨道 id → 参与者列表。 */
    public static final String KEY_TRACK_AGENTS = "track_agents";
    /** 事件载荷键：被移除的轨道 id 列表。 */
    public static final String KEY_REMOVED_TRACKS = "removed_tracks";
    /** 事件载荷键：轨道变化涉及的角色（受影响 agent 列表）。 */
    public static final String KEY_AFFECTED_AGENTS = "affected_agents";

    public TrackChangeEvent(String source,
                            List<String> newTrackIds,
                            Map<String, List<String>> trackAgents,
                            List<String> removedTracks,
                            List<String> affectedAgents) {
        super(GameEvent.TYPE_TRACK_CHANGED, source, buildPayload(
                newTrackIds, trackAgents, removedTracks, affectedAgents));
    }

    private static Map<String, Object> buildPayload(List<String> newTrackIds,
                                                    Map<String, List<String>> trackAgents,
                                                    List<String> removedTracks,
                                                    List<String> affectedAgents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY_TRACK_IDS, newTrackIds != null ? newTrackIds : List.of());
        payload.put(KEY_TRACK_AGENTS, trackAgents != null ? trackAgents : Map.of());
        payload.put(KEY_REMOVED_TRACKS, removedTracks != null ? removedTracks : List.of());
        payload.put(KEY_AFFECTED_AGENTS, affectedAgents != null ? affectedAgents : List.of());
        return payload;
    }

    @SuppressWarnings("unchecked")
    public List<String> getNewTrackIds() {
        Object v = getPayload().getOrDefault(KEY_TRACK_IDS, List.of());
        return v instanceof List<?> list ? new ArrayList<>((List<String>) list) : List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<String>> getTrackAgents() {
        Object v = getPayload().getOrDefault(KEY_TRACK_AGENTS, Map.of());
        return v instanceof Map<?, ?> map ? (Map<String, List<String>>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> getRemovedTracks() {
        Object v = getPayload().getOrDefault(KEY_REMOVED_TRACKS, List.of());
        return v instanceof List<?> list ? new ArrayList<>((List<String>) list) : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> getAffectedAgents() {
        Object v = getPayload().getOrDefault(KEY_AFFECTED_AGENTS, List.of());
        return v instanceof List<?> list ? new ArrayList<>((List<String>) list) : List.of();
    }
}
