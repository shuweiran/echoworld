package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0811-G 主控调度增强测试：
 * ① protagonist 模式（带玩家角色）≤3 人开局：不强制全员 active，主控按 LLM 分配（AI 角色可 silent），
 *    玩家角色恒 active；
 * ② next_round 闭环：configureTracks 注入上轮预测 → prompt 优先遵守；LLM 未遵守时兜底首位预测角色 active；
 * ③ 其余模式（free/director）≤3 人仍强制全员 active（既有兜底不回归）。
 */
class ArbiterTrackPredictionTest {

    private static final String SCENE = "夜晚的咖啡馆";

    /** 构造 TrackConfigResult 用的单轨道 map。 */
    private static Map<String, Object> oneTrack(List<String> agents, Map<String, String> actions) {
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("agents", new ArrayList<>(agents));
        track.put("agent_actions", new LinkedHashMap<>(actions));
        track.put("mode", "merged");
        track.put("label", "主线");
        return track;
    }

    @Test
    @DisplayName("① protagonist 模式 ≤3 人开局：LLM 分配 silent 时不被强制全 active，玩家恒 active")
    void protagonist_under3_llmAssignmentRespected() {
        LLMClient llm = mock(LLMClient.class);
        // LLM 返回：凯尔（玩家）active + 小铃 silent + 夜行人 silent —— 主控开局只让一人陪玩家说话
        Map<String, Object> track = oneTrack(List.of("凯尔", "小铃", "夜行人"), Map.of(
                "凯尔", "active", "小铃", "silent", "夜行人", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "开局聚焦玩家", "tracks", List.of(track)));

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("小铃", "凯尔", "夜行人"), "(新对话)", "protagonist", "凯尔",
                List.of(), List.of(), Set.of(), null);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("凯尔"), "玩家角色必须 active");
        assertEquals("silent", actions.get("小铃"), "protagonist 开局不强制全员 active（主控可 silent AI）");
        assertEquals("silent", actions.get("夜行人"), "protagonist 开局不强制全员 active");
    }

    @Test
    @DisplayName("①b free 模式 ≤3 人仍强制全员 active（既有兜底不回归）")
    void freeMode_under3_allActive_unchanged() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> track = oneTrack(List.of("小铃", "凯尔"), Map.of(
                "小铃", "silent", "凯尔", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(track)));

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("小铃", "凯尔"), "(新对话)", "free", "",
                List.of(), List.of(), Set.of(), null);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("小铃"), "free 模式 ≤3 人仍强制全 active");
        assertEquals("active", actions.get("凯尔"), "free 模式 ≤3 人仍强制全 active");
    }

    @Test
    @DisplayName("② next_round 闭环：LLM 未遵守预测（预测角色未 active）→ 兜底首位预测角色 active")
    void nextRound_predictionEnforcedWhenLlmIgnored() {
        LLMClient llm = mock(LLMClient.class);
        // LLM 本轮完全无视预测：全部 silent（无 active）——用 director 模式（不进 ≤3 强制全 active 分支）
        Map<String, Object> track = oneTrack(List.of("小铃", "凯尔", "夜行人", "白露"), Map.of(
                "小铃", "silent", "凯尔", "silent", "夜行人", "silent", "白露", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(track)));

        // 上轮主控预测：本轮让夜行人出场（首预测角色）
        Map<String, Object> nextRound = Map.of(
                "agents", List.of("夜行人"),
                "order", List.of("夜行人"),
                "reason", "夜行人掌握的线索需要推进");

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("小铃", "凯尔", "夜行人", "白露"), "(历史)", "director", "",
                List.of(), List.of(), Set.of(), nextRound);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("夜行人"), "预测兜底：首位预测角色应被强制 active");
        assertTrue(r.reasoning.contains("预测执行兜底"), "reasoning 应注明兜底执行");
    }

    @Test
    @DisplayName("②b next_round 闭环：LLM 已遵守预测 → 不再重复兜底（保持 LLM 配置）")
    void nextRound_llmComplies_noForcedChange() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> track = oneTrack(List.of("小铃", "凯尔", "夜行人", "白露"), Map.of(
                "小铃", "active", "凯尔", "silent", "夜行人", "silent", "白露", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(track)));

        Map<String, Object> nextRound = Map.of(
                "agents", List.of("小铃"),
                "order", List.of("小铃"),
                "reason", "小铃抛出咖啡馆的线索");

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("小铃", "凯尔", "夜行人", "白露"), "(历史)", "director", "",
                List.of(), List.of(), Set.of(), nextRound);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("小铃"), "LLM 已遵守预测");
        assertEquals("silent", actions.get("凯尔"), "保持 LLM 配置不强行改");
        assertFalse(r.reasoning.contains("预测执行兜底"), "LLM 已遵守时不应触发兜底");
    }

    @Test
    @DisplayName("③ 预测角色受 restricted 限制时兜底跳过（硬性禁止优先）")
    void nextRound_predictionOverriddenByRestricted() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> track = oneTrack(List.of("小铃", "凯尔", "夜行人", "白露"), Map.of(
                "小铃", "silent", "凯尔", "silent", "夜行人", "silent", "白露", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(track)));

        Map<String, Object> nextRound = Map.of(
                "agents", List.of("夜行人"),
                "order", List.of("夜行人"),
                "reason", "test");

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("小铃", "凯尔", "夜行人", "白露"), "(历史)", "director", "",
                List.of(), List.of(), Set.of("夜行人"), nextRound);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("offline", actions.get("夜行人"), "restricted 硬性禁止优先于预测兜底");
    }

    private static Map<String, String> actionOf(ArbiterService.TrackConfigResult r, String id) {
        for (Map<String, Object> t : r.tracks) {
            if (id.equals(t.get("id"))) {
                @SuppressWarnings("unchecked")
                Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
                return actions;
            }
        }
        throw new AssertionError("track not found: " + id);
    }
}
