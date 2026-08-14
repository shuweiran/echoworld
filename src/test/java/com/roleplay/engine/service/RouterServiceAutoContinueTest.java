package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import com.roleplay.engine.service.ArbiterService.UserInputCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0813-A：一般模式后端自动续轮（roleplay.round.auto-continue-ms）。
 *
 * <p>直接构造 RouterService（mock LLM/Arbiter，RouterServiceAutoFirstRoundTest 同款 harness），验证：
 * ① 配置默认值（AppConfig.RoundConfig.autoContinueMs=3000，0=禁用语义）；\n
 * ② auto-continue-ms&gt;0 → 每轮完成后延时自动跑下一轮（缩短延时验证调度：round 1 完成后自动出现 round 2）；\n
 * ③ auto-continue-ms=0 → 禁用（轮后无 pending、无续轮）；\n
 * ④ 玩家发言打断 → pending 自动续轮任务被取消（防玩家发言驱动轮次与自动续轮重复/冲突）；\n
 * ⑤ 会话销毁清理 → stop()/initSession() 取消 pending（防泄漏，stop 后不再自动跑轮）；\n
 * ⑥ 非一般模式（werewolf/script）不续轮。
 */
class RouterServiceAutoContinueTest {

    private static final String SESSION_ID = "auto-continue-test";
    private static final String SCENE = "夜晚的咖啡馆，小铃与凯尔相对而坐。";

    /** 捕获全局广播事件（round_complete 计数经 eventTypes 轮询）。 */
    static class CaptureSSE extends SSEController {
        final List<String> eventTypes = new ArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            eventTypes.add(eventType);
        }
    }

    /** 构建一般模式 RouterService（mock LLM/Arbiter，同 RouterServiceAutoFirstRoundTest harness）。 */
    private RouterService newRouter(CaptureSSE sse, String mode) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());

        ArbiterService arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", new ArrayList<>(List.of("小铃", "凯尔")));
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("小铃", "active");
        actions.put("凯尔", "active");
        track.put("agent_actions", actions);
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any()))
                .thenReturn(new TrackConfigResult(List.of(track), "test"));
        when(arbiter.integrateOutputs(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(Map.of("narration", "整合旁白"));
        // 玩家发言路径需要分类结果非 null（runRound 内 cat.name()）
        when(arbiter.classifyUserInput(anyString(), anyString(), anyList()))
                .thenReturn(UserInputCategory.SUPPLEMENT);
        when(arbiter.processUserInput(anyString(), any(UserInputCategory.class), anyString(), anyList(), anyList()))
                .thenReturn("主控旁白：玩家说道……");

        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));
        CharacterRepository repo = mock(CharacterRepository.class);

        RouterService router = new RouterService(
                arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, interruptManager, new WorldEventBus(), sse,
                new PlayerIdentityService(repo));
        router.initSession(SESSION_ID,
                List.of(new Persona("小铃", "温柔的女仆"), new Persona("凯尔", "沉默的管家")),
                SCENE, mode, "", "");
        return router;
    }

    /** 等待 N 次 round_complete（轮询事件计数，防 latch 误用；超时 fail）。 */
    private static void awaitRoundCompletes(CaptureSSE sse, int n, long timeoutSec) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
        while (countEvent(sse, "round_complete") < n) {
            if (System.nanoTime() > deadline) {
                org.junit.jupiter.api.Assertions.fail("应在 " + timeoutSec
                        + "s 内等到 " + n + " 次 round_complete，当前="
                        + countEvent(sse, "round_complete"));
            }
            Thread.sleep(10);
        }
    }

    private static long countEvent(CaptureSSE sse, String type) {
        return sse.eventTypes.stream().filter(type::equals).count();
    }

    // ── ① 配置默认值与表面 ──

    @Test
    @DisplayName("① AppConfig.RoundConfig 默认 autoContinueMs=3000（0=禁用语义）")
    void appConfig_defaultIs3000() {
        AppConfig appConfig = new AppConfig();
        assertEquals(3000L, appConfig.getRound().getAutoContinueMs(), "默认 3000ms");
        // 语义：<=0 禁用由 RouterService.scheduleAutoContinue 守卫（测试 ③ 验证行为）
        appConfig.getRound().setAutoContinueMs(0);
        assertEquals(0L, appConfig.getRound().getAutoContinueMs(), "0=禁用可配置");
    }

    // ── ② auto-continue-ms>0 → 每轮完成后自动续轮 ──

    @Test
    @DisplayName("② auto-continue-ms=300ms：round 1 完成后自动跑 round 2（导演模式 AI 自主推进）")
    void autoContinue_triggersNextRound() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(300);

        router.triggerAutoFirstRound();
        awaitRoundCompletes(sse, 2, 10); // 第 1 轮（起局自动）+ 第 2 轮（自动续轮）

        // 轮询检测到第 2 轮完成即断言（第 3 轮在 +300ms 后，窗口充足）
        assertEquals(2, router.getState().get("round"), "自动续轮后 round=2");
        assertEquals(2, countEvent(sse, "round_start"), "应推 2 次 round_start（两轮）");
        assertEquals(2, countEvent(sse, "round_complete"), "应推 2 次 round_complete（两轮）");

        // 收尾：停止会话（取消后续续轮），确认不再自动跑轮
        router.stop();
        long before = countEvent(sse, "round_complete");
        Thread.sleep(500);
        assertEquals(before, countEvent(sse, "round_complete"), "stop 后不应再有续轮");
    }

    // ── ③ auto-continue-ms=0 → 禁用 ──

    @Test
    @DisplayName("③ auto-continue-ms=0：禁用自动续轮（轮后无 pending、无续轮）")
    void autoContinue_disabledWhenZero() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(0);

        router.runRound(null, null); // 同步跑一轮
        assertEquals(1, router.getState().get("round"));
        assertFalse(router.hasPendingAutoContinue(), "auto-continue-ms=0 不应调度续轮");

        Thread.sleep(400);
        assertEquals(1, countEvent(sse, "round_complete"), "禁用后不应自动跑下一轮");
        assertEquals(1, router.getState().get("round"), "禁用后 round 保持 1");
    }

    // ── ④ 玩家发言打断 pending ──

    @Test
    @DisplayName("④ 玩家发言取消待执行的自动续轮任务（防玩家发言驱动轮次与自动续轮重复/冲突）")
    void playerSend_cancelsPendingAutoContinue() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "free");
        router.setAutoContinueMs(500); // 宽窗口：pending 必然存在时再发言

        router.runRound(null, null); // 第 1 轮完成 → 调度 round 2（+500ms）
        assertEquals(1, router.getState().get("round"));
        assertTrue(router.hasPendingAutoContinue(), "轮后应存在 pending 自动续轮任务");

        // 玩家发言：入口处取消 pending（第 1 轮遗留的自动续轮）；随后玩家轮完成会按规则重新调度新任务
        RouterService.RoundResult playerResult = router.runRound("玩家发言", null, null);
        assertFalse(playerResult.status.startsWith("error"), "玩家发言轮不应报错: " + playerResult.status);
        assertEquals(2, router.getState().get("round"), "玩家发言驱动了第 2 轮");

        // 若玩家发言未取消 pending：原自动续轮会在 +500ms 触发第 3 轮 → round_complete 变 3。
        // 立即停止（取消玩家轮后新调度的续轮），睡过窗口验证无重复自动轮。
        router.stop();
        Thread.sleep(600);
        assertEquals(2, router.getState().get("round"), "玩家发言后 round=2（无重复自动轮）");
        assertEquals(2, countEvent(sse, "round_complete"), "仅 2 轮（第 1 轮自动 + 玩家驱动轮），无多余自动轮");
    }

    // ── ⑤ 会话销毁/停止清理 pending ──

    @Test
    @DisplayName("⑤ stop() 取消 pending 自动续轮任务（防泄漏，stop 后不再自动跑轮）")
    void stop_cancelsPendingAutoContinue() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(200);

        router.runRound(null, null);
        assertTrue(router.hasPendingAutoContinue(), "轮后应有 pending 任务");
        router.stop();
        assertFalse(router.hasPendingAutoContinue(), "stop 后 pending 应被取消");

        Thread.sleep(500);
        assertEquals(1, countEvent(sse, "round_complete"), "stop 后不得再自动跑轮");
        assertEquals(1, router.getState().get("round"));
    }

    @Test
    @DisplayName("⑤b initSession（新会话重初始化）取消 pending 自动续轮任务（防旧会话续轮串场）")
    void reinit_cancelsPendingAutoContinue() {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(500);

        router.runRound(null, null);
        assertTrue(router.hasPendingAutoContinue(), "轮后应有 pending 任务");
        router.initSession(SESSION_ID,
                List.of(new Persona("小铃", "温柔的女仆"), new Persona("凯尔", "沉默的管家")),
                SCENE, "director", "", "");
        assertFalse(router.hasPendingAutoContinue(), "重新 init 后 pending 应被取消");
        router.stop();
    }

    // ── ⑥ 非一般模式不续轮 ──

    @Test
    @DisplayName("⑥ 非一般模式（werewolf/script）自动续轮不触发（走各自状态机）")
    void nonGeneralMode_noAutoContinue() throws Exception {
        for (String mode : new String[]{"werewolf", "script"}) {
            CaptureSSE sse = new CaptureSSE();
            RouterService router = newRouter(sse, mode);
            router.setAutoContinueMs(100);

            router.runRound(null, null);
            assertEquals(1, router.getState().get("round"), "mode=" + mode + " round=1");
            assertFalse(router.hasPendingAutoContinue(), "mode=" + mode + " 不应调度自动续轮");

            Thread.sleep(300);
            assertEquals(1, countEvent(sse, "round_complete"), "mode=" + mode + " 不应自动跑下一轮");
            router.stop();
        }
    }

    // ── ⑦ 手动批量（runTurns）接管 ──

    @Test
    @DisplayName("⑦ 手动批量（runTurns）取消遗留 pending，且批量后不再自动续轮（防「三轮」后多跑一轮）")
    void runTurns_cancelsPending_noContinueAfterBatch() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(300);

        router.runRound(null, null); // 第 1 轮完成 → 调度自动续轮（+300ms）
        assertTrue(router.hasPendingAutoContinue(), "轮后应有 pending 任务");

        List<RouterService.RoundResult> results = router.runTurns(null, 2); // 手动批量：第 2、3 轮
        assertEquals(2, results.size(), "手动批量应执行 2 轮");
        assertFalse(router.hasPendingAutoContinue(), "批量进行中/结束后不应残留或新调度续轮");

        // 不 stop，睡过原 pending 触发窗口：若未取消，第 1 轮遗留任务会在 +300ms 触发第 4 轮
        Thread.sleep(500);
        assertEquals(3, router.getState().get("round"), "批量后停在手动轮数，无额外自动轮");
        assertEquals(3, countEvent(sse, "round_complete"), "共 3 轮（1 自动 + 2 手动批量），无多余自动轮");
        router.stop();
    }
}
