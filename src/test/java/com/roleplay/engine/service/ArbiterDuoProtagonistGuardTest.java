package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0815-F：双人 protagonist 死锁防护 —— 会话唯一 AI 角色必须 active（玩家恒有回应）。
 *
 * <p>Bug 背景（2026-08-15 真机实测 19:35 铁证）：一般模式双人（AI+玩家，2 agents，
 * mode=protagonist）玩家连发消息 AI 完全不回复，日志每轮
 * 「Agent round complete (serial): 0 agents in 0ms」；19:39 同配置却 1 agents 正常。
 * 根因：protagonist 模式 prompt 允许主控把 AI 角色分配为 silent（P-0811-G 开局节奏），
 * 双人局唯一 AI 被 silent 时——玩家角色被 enforcement 强制 active，hasActive 兜底见已有
 * active（玩家）不再补强 → RouterService 移除 protagonist 后 agentMap 无 active AI →
 * 串行/并行双路径任务列表为空 → 0 agents 死锁。修复：configureTracks 对
 * protagonist 模式且 AI 恰 1 个的会话强制该 AI active（不依赖 LLM 漂移）；多人不受影响。</p>
 *
 * <p>Section A：ArbiterService.configureTracks 单元（真实 configureTracks + mock LLM）；
 * Section B：RouterService 集成（真实 ArbiterService + mock LLM，串行/并行双路径）。
 */
class ArbiterDuoProtagonistGuardTest {

    private static final String SCENE = "夜晚的咖啡馆";

    // ═══════════════════════════════════════════════════════════════
    //  Section A — ArbiterService.configureTracks 单元
    // ═══════════════════════════════════════════════════════════════

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

    @Test
    @DisplayName("A1 双人 protagonist：LLM 把唯一 AI 标 silent → 强制 active（死锁防护）")
    void duoProtagonist_aiSilent_forcedActive() {
        LLMClient llm = mock(LLMClient.class);
        // 主控本轮只让玩家说话（AI 小铃 silent）——正是 19:35 死锁现场的同款 LLM 输出
        Map<String, Object> track = oneTrack(List.of("凯尔", "小铃"), Map.of(
                "凯尔", "active", "小铃", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "开局聚焦玩家", "tracks", List.of(track)));

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("凯尔", "小铃"), "(新对话)", "protagonist", "凯尔",
                List.of(), List.of(), Set.of(), null);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("凯尔"), "玩家角色必须 active");
        assertEquals("active", actions.get("小铃"), "双人局唯一 AI 必须被强制 active（玩家恒有回应）");
        assertTrue(r.reasoning.contains("双人防护"), "reasoning 应注明双人防护执行");
    }

    @Test
    @DisplayName("A2 双人 protagonist：LLM 把唯一 AI 标 offline → 强制 active")
    void duoProtagonist_aiOffline_forcedActive() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> track = oneTrack(List.of("凯尔", "小铃"), Map.of(
                "凯尔", "active", "小铃", "offline"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(track)));

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("凯尔", "小铃"), "(新对话)", "protagonist", "凯尔",
                List.of(), List.of(), Set.of(), null);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("小铃"), "offline 同样触发双人防护强制 active");
        assertTrue(r.reasoning.contains("双人防护"), "reasoning 应注明双人防护执行");
    }

    @Test
    @DisplayName("A3 双人 protagonist：LLM 全员 silent → hasActive 兜底只救首 agent，双人防护补齐 AI")
    void duoProtagonist_bothSilent_forcedActive() {
        LLMClient llm = mock(LLMClient.class);
        // 玩家在首位：hasActive 兜底把凯尔置 active（agents.get(0)），小铃仍 silent → 双人防护补齐
        Map<String, Object> track = oneTrack(List.of("凯尔", "小铃"), Map.of(
                "凯尔", "silent", "小铃", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(track)));

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("凯尔", "小铃"), "(新对话)", "protagonist", "凯尔",
                List.of(), List.of(), Set.of(), null);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("凯尔"), "hasActive 兜底：首位角色置 active");
        assertEquals("active", actions.get("小铃"), "双人防护：唯一 AI 必须 active");
        assertTrue(r.reasoning.contains("双人防护"), "reasoning 应注明双人防护执行");
    }

    @Test
    @DisplayName("A4 双人 protagonist：AI 本已 active → 零改动（不重复兜底）")
    void duoProtagonist_aiAlreadyActive_unchanged() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> track = oneTrack(List.of("凯尔", "小铃"), Map.of(
                "凯尔", "active", "小铃", "active"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(track)));

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("凯尔", "小铃"), "(新对话)", "protagonist", "凯尔",
                List.of(), List.of(), Set.of(), null);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("小铃"));
        assertFalse(r.reasoning.contains("双人防护"), "AI 已 active 时不应触发双人防护");
    }

    @Test
    @DisplayName("A5 多人 protagonist（2 AI）：主控可 silent 部分 AI（P-0811-G 节奏设计不回归）")
    void multiAiProtagonist_silentAi_notForced() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> track = oneTrack(List.of("凯尔", "小铃", "夜行人"), Map.of(
                "凯尔", "active", "小铃", "silent", "夜行人", "silent"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "开局聚焦玩家", "tracks", List.of(track)));

        ArbiterService arbiter = new ArbiterService(llm);
        ArbiterService.TrackConfigResult r = arbiter.configureTracks(
                SCENE, List.of("凯尔", "小铃", "夜行人"), "(新对话)", "protagonist", "凯尔",
                List.of(), List.of(), Set.of(), null);

        Map<String, String> actions = actionOf(r, "main");
        assertEquals("active", actions.get("凯尔"), "玩家角色必须 active");
        assertEquals("silent", actions.get("小铃"), "多人局保留主控自由分配（≥2 AI 不触发双人防护）");
        assertEquals("silent", actions.get("夜行人"), "多人局保留主控自由分配");
        assertFalse(r.reasoning.contains("双人防护"), "多人局不应触发双人防护");
    }

    @Test
    @DisplayName("A6 非 protagonist 模式（free）双人：既有 ≤3 全员 active 兜底不回归")
    void freeMode_duo_unchanged() {
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
        assertEquals("active", actions.get("小铃"), "free 模式 ≤3 人仍强制全 active（既有兜底）");
        assertEquals("active", actions.get("凯尔"), "free 模式 ≤3 人仍强制全 active");
        assertFalse(r.reasoning.contains("双人防护"), "非 protagonist 模式不应触发双人防护");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Section B — RouterService 集成（真实 ArbiterService + mock LLM）
    // ═══════════════════════════════════════════════════════════════

    private static final String SESSION_ID = "duo-protagonist-guard-test";

    /**
     * 构建 RouterService：真实 ArbiterService + mock LLM。
     * configureTracks（callJson）返回固定单轨道（agent_actions 由入参决定）；
     * 生成路径（callSync）返回固定发言；processUserInput（callSimple）返回固定旁白。
     */
    private RouterService newRouter(String mode, String protagonist,
                                    List<String> agents, Map<String, String> actions,
                                    boolean serial) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "reasoning", "test", "tracks", List.of(oneTrack(agents, actions))));
        when(llm.callSync(anyList(), any())).thenReturn("测试发言");
        when(llm.callSimple(anyString(), anyInt())).thenReturn("【场景变化】你望向窗外。");

        ArbiterService arbiter = new ArbiterService(llm);
        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));

        RouterService router = new RouterService(
                arbiter,
                executor,
                new MemoryStore(),
                mock(Compressor.class),
                mock(Monitor.class),
                mock(GeneratorService.class),
                mock(TrackRequestService.class),
                llm,
                null,            // historyController
                null,            // lorebookService
                interruptManager,
                new WorldEventBus(),
                null,            // sse
                null);           // identityService
        router.setSerialRound(serial);
        List<Persona> personas = agents.stream().map(n -> new Persona(n, "你是一个角色。")).toList();
        router.initSession(SESSION_ID, personas, SCENE, mode, protagonist, "");
        return router;
    }

    /** 死锁现场的同款 LLM 输出：唯一 AI（小铃）被 silent，玩家（凯尔）active。 */
    private static Map<String, String> deadlockActions() {
        return Map.of("凯尔", "active", "小铃", "silent");
    }

    @Test
    @DisplayName("B1 串行路径：双人 protagonist AI 被 silent → runRound 仍产出 1 个 AI 发言（修复 0 agents 死锁）")
    void serialPath_duoProtagonist_aiResponds() {
        RouterService router = newRouter(
                "protagonist", "凯尔", List.of("凯尔", "小铃"), deadlockActions(), true);

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(1, result.agentOutputs.size(),
                "双人局唯一 AI 必须参与生成（修复前此处为 0 agents）");
        assertEquals("小铃", result.agentOutputs.get(0).get("agent_name"),
                "发言者应为被强制 active 的 AI 角色");
    }

    @Test
    @DisplayName("B2 并行路径：双人 protagonist AI 被 silent → runRound 仍产出 1 个 AI 发言")
    void parallelPath_duoProtagonist_aiResponds() {
        RouterService router = newRouter(
                "protagonist", "凯尔", List.of("凯尔", "小铃"), deadlockActions(), false);

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(1, result.agentOutputs.size(),
                "并行路径 buildTasks 同样不能因唯一 AI 被 silent 而空转");
        assertEquals("小铃", result.agentOutputs.get(0).get("agent_name"));
    }

    @Test
    @DisplayName("B3 玩家连发消息场景（R2/R3/R4 复现）：玩家消息入史且 AI 每轮必回应")
    void serialPath_duoProtagonist_playerMessage_aiResponds() {
        RouterService router = newRouter(
                "protagonist", "凯尔", List.of("凯尔", "小铃"), deadlockActions(), true);

        for (int i = 1; i <= 3; i++) {
            // 玩家以自己角色身份发言（/api/send + player_name 路径）→ 原文入史 + 该角色本轮不参与生成
            RouterService.RoundResult result = router.runRound("玩家第" + i + "条消息", null, "凯尔");
            assertFalse(result.status.startsWith("error"), "round " + i + " should not error: " + result.status);
            assertEquals(1, result.agentOutputs.size(),
                    "玩家每轮发言后 AI 必须回应（修复前 R2/R3/R4 AI 零回应死锁）");
            assertEquals("小铃", result.agentOutputs.get(0).get("agent_name"));
        }
        assertTrue(router.getConversationMessages().stream()
                        .anyMatch(m -> m.getContent() != null && m.getContent().contains("玩家第2条消息")),
                "玩家消息应已入史");
    }

    @Test
    @DisplayName("B4 多人 protagonist（2 AI 一 silent）：仅 active AI 生成，silent AI 不被强制（节奏设计保留）")
    void multiAiProtagonist_silentAi_onlyActiveSpeaks() {
        RouterService router = newRouter(
                "protagonist", "凯尔",
                List.of("凯尔", "小铃", "夜行人"),
                Map.of("凯尔", "active", "小铃", "active", "夜行人", "silent"),
                true);

        RouterService.RoundResult result = router.runRound(null, null);

        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(1, result.agentOutputs.size(),
                "多人局 silent 的 AI 不生成，只有 active 的 1 个 AI 发言（不强制全员）");
        assertEquals("小铃", result.agentOutputs.get(0).get("agent_name"));
    }
}
