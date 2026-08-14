package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0810-11 roundHistory 跨会话残留回归测试。
 *
 * <p>背景：startScene 起局复用默认单例 router，roundHistory（RouterService 字段）
 * 跨会话不清理 → POST /api/round/rollback 回滚时会恢复旧会话的消息快照
 * （P-0810-08 前端走查实测：单例会话 8 消息回滚 → 0，恢复的是旧快照）。
 *
 * <p>修法（D-007 同类：新会话 init 时清状态）：initSession 清空 roundHistory。
 * 验证：①局内回滚语义不变（快照按轮积累、可回滚）；②同单例实例再 initSession
 * 起新局后，旧会话快照不可再被回滚恢复（越界拒绝，而非恢复旧快照）；
 * ③新局内快照从 0 重新编号，回滚恢复的是新局状态。
 */
class RouterServiceRoundHistoryTest {

    private static final String SCENE = "夜晚的庄园，管家与女仆在客厅。";

    // ── Harness ────────────────────────────────────────────────

    /**
     * 构建 RouterService（同 RouterServiceSerialRoundTest 模式）：
     * 单角色 A 单条 MERGED 轨道；LLM mock 返回固定发言；Arbiter mock
     * 返回固定轨道与旁白；historyController/lorebookService/sse/identityService
     * 传 null（runRound 内 null 守卫）。
     */
    private RouterService newRouter() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("A发言：我看到了碎玻璃。");

        ArbiterService arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", List.of("A"));
        track.put("agent_actions", Map.of("A", "active"));
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any()))
                .thenReturn(new TrackConfigResult(List.of(track), "test"));
        when(arbiter.integrateOutputs(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(Map.of("narration", "整合旁白"));

        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));

        return new RouterService(
                arbiter,
                executor,
                new MemoryStore(),
                mock(Compressor.class),
                mock(Monitor.class),
                mock(GeneratorService.class),
                mock(TrackRequestService.class),
                llm,
                null,            // historyController（runRound 内 null 守卫）
                null,            // lorebookService（runRound 内 null 守卫）
                interruptManager,
                new WorldEventBus(),
                null,            // sse（runRound 内 null 守卫）
                null);           // identityService（runRound 内 null 守卫）
    }

    private void init(RouterService router, String sessionId) {
        router.initSession(sessionId, List.of(new Persona("A", "你是一个细心观察的侦探。")),
                SCENE, "free", "", "");
    }

    // ── 用例 ───────────────────────────────────────────────────

    @Test
    @DisplayName("局内回滚语义不变：runRound 积累快照，可回滚到第 0 轮")
    void withinSession_rollbackStillWorks() {
        RouterService router = newRouter();
        init(router, "s1");

        RouterService.RoundResult r = router.runRound(null, null);
        assertFalse(r.status.startsWith("error"), "round should not error: " + r.status);

        // 局内：roundHistory 已有 1 个快照（第 0 轮前），可回滚
        assertEquals("已回滚到第 0 轮", router.rollbackToRound("s1", 0));
        // 越界仍拒绝
        assertEquals("无效回合: 1", router.rollbackToRound("s1", 1));
    }

    @Test
    @DisplayName("同单例再 initSession 起新局：旧会话 roundHistory 清空，回滚不得恢复旧快照")
    void startSceneNewSession_mustNotRestoreOldSnapshot() {
        RouterService router = newRouter();
        // 第一局（模拟旧会话）
        init(router, "scene-1");
        assertFalse(router.runRound(null, null).status.startsWith("error"));
        // 局内回滚可用（旧会话快照存在）
        assertEquals("已回滚到第 0 轮", router.rollbackToRound("scene-1", 0));

        // 同实例起新局（startScene 复用默认单例 router）→ initSession 必须清空 roundHistory
        init(router, "scene-2");

        // 旧会话快照不得再被回滚恢复：roundHistory 为空 → 越界拒绝（而非恢复 scene-1 的消息）
        assertEquals("无效回合: 0", router.rollbackToRound("scene-2", 0));
        // 且新会话消息为空历史（未被旧快照污染）
        assertTrue(router.getConversationMessages().isEmpty());
    }

    @Test
    @DisplayName("新局快照从 0 重新编号：回滚恢复的是新局状态，旧局快照不可达")
    void newSession_snapshotIndexRestartsFromZero() {
        RouterService router = newRouter();
        // 旧会话跑 2 轮 → 旧快照 [s1空, s1一轮后]
        init(router, "s1");
        assertFalse(router.runRound(null, null).status.startsWith("error"));
        assertFalse(router.runRound(null, null).status.startsWith("error"));

        // 新会话（同单例）跑 1 轮 → 新快照 [s2空]
        init(router, "s2");
        assertFalse(router.runRound(null, null).status.startsWith("error"));

        // 回滚到 0 → 恢复 s2 的初始（空）消息，而不是 s1 的旧快照
        assertEquals("已回滚到第 0 轮", router.rollbackToRound("s2", 0));
        assertTrue(router.getConversationMessages().isEmpty());
        // s2 只有 1 个快照（旧 s1 快照不可达）
        assertEquals("无效回合: 1", router.rollbackToRound("s2", 1));
    }
}
