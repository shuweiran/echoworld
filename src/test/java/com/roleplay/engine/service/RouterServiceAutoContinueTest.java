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
 * P0 点击驱动：一般模式定时自动续轮已彻底移除（roleplay.round.auto-continue-ms 退役）。
 *
 * <p>直接构造 RouterService（mock LLM/Arbiter，RouterServiceAutoFirstRoundTest 同款 harness），验证：
 * ① 配置默认值（AppConfig.RoundConfig.autoContinueMs=3000 键保留，行为上恒忽略）；\n
 * ② auto-continue-ms&gt;0 也不再自动跑下一轮（轮完进入等待点击推进）；\n
 * ③ auto-continue-ms=0 → 同样等待点击（恒无 pending、无自续）；\n
 * ④ 玩家发言清除等待态，玩家轮跑完重新进入等待（无重复自动轮）；\n
 * ⑤ 会话销毁清理 → stop()/initSession() 清除等待态（stop 后点击信号 no-op）；\n
 * ⑥ 非一般模式（werewolf/script）不进入等待；\n
 * ⑦ 手动批量（runTurns）批量中不逐轮置位，批量结束后统一进入等待（点击可继续）。
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

    // ── ② auto-continue-ms>0 也不再自动跑轮（P0 点击驱动） ──

    @Test
    @DisplayName("② auto-continue-ms=300ms：轮完进入等待点击推进，不自动跑下一轮")
    void autoContinue_triggersNextRound() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(300); // 已退役：恒被忽略

        router.triggerAutoFirstRound();
        awaitRoundCompletes(sse, 1, 10); // 第 1 轮（起局自动）
        assertEquals(1, router.getState().get("round"), "起局自动第一轮 round=1");
        assertTrue(router.isAwaitingPlayback(), "轮完应进入等待点击推进");

        // 睡过旧自动续轮窗口：不得出现第 2 轮
        Thread.sleep(500);
        assertEquals(1, router.getState().get("round"), "无点击/输入不自动跑下一轮");
        assertEquals(1, countEvent(sse, "round_start"), "仅 1 次 round_start");
        assertEquals(1, countEvent(sse, "round_complete"), "仅 1 次 round_complete");

        // 点击推进一次 → 第 2 轮
        assertTrue(router.onPlaybackDone(), "点击信号应推进下一轮");
        awaitRoundCompletes(sse, 2, 10);
        assertEquals(2, router.getState().get("round"), "点击推进后 round=2");

        // 收尾：停止会话，确认点击信号 no-op
        router.stop();
        assertFalse(router.onPlaybackDone(), "stop 后点击信号应 no-op");
        Thread.sleep(300);
        assertEquals(2, countEvent(sse, "round_complete"), "stop 后不应再有轮次");
    }

    // ── ③ auto-continue-ms=0 → 同样等待点击（恒无 pending） ──

    @Test
    @DisplayName("③ auto-continue-ms=0：轮完进入等待点击推进（恒无 pending、无自续）")
    void autoContinue_disabledWhenZero() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(0);

        router.runRound(null, null); // 同步跑一轮
        assertEquals(1, router.getState().get("round"));
        assertFalse(router.hasPendingAutoContinue(), "定时续轮已移除，恒无 pending");
        assertTrue(router.isAwaitingPlayback(), "轮完应进入等待点击推进");

        Thread.sleep(400);
        assertEquals(1, countEvent(sse, "round_complete"), "无点击/输入不自动跑下一轮");
        assertEquals(1, router.getState().get("round"), "round 保持 1");
        router.stop();
    }

    // ── ④ 玩家发言清除等待态，玩家轮跑完重新进入等待 ──

    @Test
    @DisplayName("④ 玩家发言清除等待态并驱动一轮，玩家轮跑完重新进入等待（无重复自动轮）")
    void playerSend_cancelsPendingAutoContinue() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "free");
        router.setAutoContinueMs(500); // 已退役：恒被忽略

        router.runRound(null, null); // 第 1 轮完成 → 等待点击推进
        assertEquals(1, router.getState().get("round"));
        assertTrue(router.isAwaitingPlayback(), "第 1 轮后应进入等待点击推进");

        // 玩家发言：清除等待态并驱动第 2 轮；玩家轮跑完重新进入等待
        RouterService.RoundResult playerResult = router.runRound("玩家发言", null, null);
        assertFalse(playerResult.status.startsWith("error"), "玩家发言轮不应报错: " + playerResult.status);
        assertEquals(2, router.getState().get("round"), "玩家发言驱动了第 2 轮");
        assertTrue(router.isAwaitingPlayback(), "玩家轮跑完重新进入等待点击推进");

        // 停止并睡过旧窗口：无重复自动轮
        router.stop();
        assertFalse(router.isAwaitingPlayback(), "stop 后等待态应清除");
        Thread.sleep(600);
        assertEquals(2, router.getState().get("round"), "玩家发言后 round=2（无重复自动轮）");
        assertEquals(2, countEvent(sse, "round_complete"), "仅 2 轮，无多余自动轮");
    }

    // ── ⑤ 会话销毁/停止清理等待态 ──

    @Test
    @DisplayName("⑤ stop() 清除等待点击推进态（stop 后点击信号 no-op，不再跑轮）")
    void stop_cancelsPendingAutoContinue() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(200);

        router.runRound(null, null);
        assertTrue(router.isAwaitingPlayback(), "轮后应进入等待点击推进");
        router.stop();
        assertFalse(router.isAwaitingPlayback(), "stop 后等待态应清除");
        assertFalse(router.onPlaybackDone(), "stop 后点击信号应 no-op");

        Thread.sleep(500);
        assertEquals(1, countEvent(sse, "round_complete"), "stop 后不得再跑轮");
        assertEquals(1, router.getState().get("round"));
    }

    @Test
    @DisplayName("⑤b initSession（新会话重初始化）清除等待态（防旧会话等待态串场）")
    void reinit_cancelsPendingAutoContinue() {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(500);

        router.runRound(null, null);
        assertTrue(router.isAwaitingPlayback(), "轮后应进入等待点击推进");
        router.initSession(SESSION_ID,
                List.of(new Persona("小铃", "温柔的女仆"), new Persona("凯尔", "沉默的管家")),
                SCENE, "director", "", "");
        assertFalse(router.isAwaitingPlayback(), "重新 init 后等待态应清除");
        assertFalse(router.hasPendingAutoContinue(), "恒无 pending 定时任务");
        router.stop();
    }

    // ── ⑥ 非一般模式不进入等待 ──

    @Test
    @DisplayName("⑥ 非一般模式（werewolf/script）不进入等待（走各自状态机，点击信号 no-op）")
    void nonGeneralMode_noAutoContinue() throws Exception {
        for (String mode : new String[]{"werewolf", "script"}) {
            CaptureSSE sse = new CaptureSSE();
            RouterService router = newRouter(sse, mode);
            router.setAutoContinueMs(100);

            router.runRound(null, null);
            assertEquals(1, router.getState().get("round"), "mode=" + mode + " round=1");
            assertFalse(router.isAwaitingPlayback(), "mode=" + mode + " 不应进入等待态");
            assertFalse(router.onPlaybackDone(), "mode=" + mode + " 点击信号应 no-op");

            Thread.sleep(300);
            assertEquals(1, countEvent(sse, "round_complete"), "mode=" + mode + " 不应自动跑下一轮");
            router.stop();
        }
    }

    // ── ⑦ 手动批量（runTurns）批量中不置位，批量后统一进入等待 ──

    @Test
    @DisplayName("⑦ 手动批量（runTurns）批量后进入等待点击推进（无额外自动轮，点击可继续）")
    void runTurns_cancelsPending_noContinueAfterBatch() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director");
        router.setAutoContinueMs(300);

        router.runRound(null, null); // 第 1 轮完成 → 等待点击推进
        assertTrue(router.isAwaitingPlayback(), "第 1 轮后应进入等待点击推进");

        List<RouterService.RoundResult> results = router.runTurns(null, 2); // 手动批量：第 2、3 轮
        assertEquals(2, results.size(), "手动批量应执行 2 轮");
        assertTrue(router.isAwaitingPlayback(), "批量结束后应进入等待点击推进（点击可继续）");

        // 睡过旧 pending 触发窗口：无额外自动轮；点击可继续第 4 轮
        Thread.sleep(500);
        assertEquals(3, router.getState().get("round"), "批量后停在手动轮数，无额外自动轮");
        assertEquals(3, countEvent(sse, "round_complete"), "共 3 轮（1 + 2 手动批量），无多余自动轮");
        assertTrue(router.onPlaybackDone(), "批量后点击应可继续推进");
        awaitRoundCompletes(sse, 4, 10);
        assertEquals(4, router.getState().get("round"), "点击推进了第 4 轮");
        router.stop();
    }
}
