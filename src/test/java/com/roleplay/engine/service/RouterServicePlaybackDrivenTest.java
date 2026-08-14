package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.core.Message;
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
 * P-0814-A：点击驱动对话模式（一般模式轮间门）——roleplay.round.playback-driven。
 *
 * <p>核心语义（主人拍板）：一轮生成完即停，不再后台无限自动跑；收到「播出完毕」信号
 * （POST /api/simulation/playback_done → {@link RouterService#onPlaybackDone()}）后才生成下一轮；
 * 无玩家（导演模式）播完即停等导演点击；auto-continue-ms（D-056）定时自续被忽略；
 * false=回退旧行为（定时自续）。玩家输入仍走「入史 → 下一轮上下文读取」既有机制影响剧情。
 *
 * <p>harness 同 RouterServiceAutoContinueTest（mock LLM/Arbiter 直构）。
 */
class RouterServicePlaybackDrivenTest {

    private static final String SESSION_ID = "playback-driven-test";
    private static final String SCENE = "夜晚的咖啡馆，小铃与凯尔相对而坐。";

    /** 捕获全局广播事件（round_complete 计数经 eventTypes 轮询）。 */
    static class CaptureSSE extends SSEController {
        final List<String> eventTypes = new ArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            eventTypes.add(eventType);
        }
    }

    /** 捕获的上下文（USER 消息内容）列表，按 LLM 调用顺序（串行路径顺序确定）。 */
    private final List<String> capturedContexts = java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * 构建一般模式 RouterService。
     *
     * @param embedPlayerInput true 时 arbiter.processUserInput 返回的旁白内嵌玩家原文
     *                         （模拟真实主控把玩家发言并入旁白），用于「输入影响下一轮」断言；
     *                         false 时返回固定旁白
     */
    private RouterService newRouter(CaptureSSE sse, String mode, boolean serial, boolean embedPlayerInput) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Message> msgs = inv.getArgument(0);
            String userContent = msgs.stream()
                    .filter(m -> m.getRole() == Message.Role.USER)
                    .map(Message::getContent)
                    .findFirst().orElse("");
            capturedContexts.add(userContent);
            if (userContent.contains("你是小铃")) return "小铃回应：好的呀。";
            if (userContent.contains("你是凯尔")) return "凯尔回应：明白了。";
            return "AI回应";
        });
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
        when(arbiter.classifyUserInput(anyString(), anyString(), anyList()))
                .thenReturn(UserInputCategory.SUPPLEMENT);
        if (embedPlayerInput) {
            when(arbiter.processUserInput(anyString(), any(UserInputCategory.class), anyString(), anyList(), anyList()))
                    .thenAnswer(inv -> "主控旁白（玩家说）：" + inv.getArgument(0));
        } else {
            when(arbiter.processUserInput(anyString(), any(UserInputCategory.class), anyString(), anyList(), anyList()))
                    .thenReturn("主控旁白：玩家说道……");
        }

        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));
        CharacterRepository repo = mock(CharacterRepository.class);

        RouterService router = new RouterService(
                arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, interruptManager, new WorldEventBus(), sse,
                new PlayerIdentityService(repo));
        router.setSerialRound(serial);
        router.initSession(SESSION_ID,
                List.of(new Persona("小铃", "温柔的女仆"), new Persona("凯尔", "沉默的管家")),
                SCENE, mode, "", "");
        return router;
    }

    /** 等待 N 次 round_complete（轮询事件计数；超时 fail）。 */
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

    // ── ① 配置默认值 ──

    @Test
    @DisplayName("① AppConfig.RoundConfig 默认 playbackDriven=true（主人拍板新语义），可配置关闭")
    void appConfig_defaultIsTrue() {
        AppConfig appConfig = new AppConfig();
        assertTrue(appConfig.getRound().isPlaybackDriven(), "默认 true=点击驱动（一轮完即停）");
        appConfig.getRound().setPlaybackDriven(false);
        assertFalse(appConfig.getRound().isPlaybackDriven(), "false=回退旧定时续轮可配置");
    }

    // ── ② 一轮生成完即停（不再后台无限自动跑） ──

    @Test
    @DisplayName("② playback-driven：round 1 完成后停住——无 pending 定时任务、置等待标志，auto-continue-ms 被忽略")
    void roundCompletes_thenStops() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director", false, false);
        router.setPlaybackDriven(true);
        router.setAutoContinueMs(200); // 应被忽略：不再定时自续

        router.runRound(null, null); // 同步跑一轮
        assertEquals(1, router.getState().get("round"));
        assertFalse(router.hasPendingAutoContinue(), "点击驱动下不应调度定时续轮任务");
        assertTrue(router.isAwaitingPlayback(), "轮完应置「等待播出完毕」标志");

        Thread.sleep(500); // 睡过 autoContinueMs 窗口
        assertEquals(1, router.getState().get("round"), "无信号不自动跑下一轮（后台无限自动跑已停）");
        assertEquals(1, countEvent(sse, "round_complete"), "仅第 1 轮完成事件");
        router.stop();
    }

    // ── ③ 播出完毕信号 → 下一轮 ──

    @Test
    @DisplayName("③ onPlaybackDone（播出完毕信号）→ 生成下一轮；信号逐轮消费，stop 后不再接受")
    void playbackDone_triggersNextRound_noopOnDuplicate() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director", false, false);
        router.setPlaybackDriven(true);
        router.setAutoContinueMs(200); // 应被忽略

        // 未跑轮时信号 no-op（无等待态可消费）
        assertFalse(router.onPlaybackDone(), "未跑轮时信号应 no-op");

        router.runRound(null, null); // 第 1 轮
        assertTrue(router.isAwaitingPlayback());

        assertTrue(router.onPlaybackDone(), "播出完毕信号应推进下一轮");
        awaitRoundCompletes(sse, 2, 10);
        assertEquals(2, router.getState().get("round"), "信号驱动了第 2 轮");
        // 第 2 轮完成后重新进入等待态（等第 3 轮播出完毕）——信号是逐轮消费的
        assertTrue(router.isAwaitingPlayback(), "轮完重新等待播出完毕");

        // 停止后不再接受推进信号
        router.stop();
        assertFalse(router.onPlaybackDone(), "stop 后信号应 no-op");
        assertEquals(2, router.getState().get("round"), "stop 后不再推进");
    }

    // ── ④ 玩家输入即时驱动（无需播出完毕信号；轮完重新等待） ──

    @Test
    @DisplayName("④ 玩家输入即时驱动轮次（点击驱动下输入即推进）；轮完重新等待播出完毕")
    void playerInput_cancelsAwaiting() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "free", false, false);
        router.setPlaybackDriven(true);

        router.runRound(null, null); // 第 1 轮（AI 自主）
        assertTrue(router.isAwaitingPlayback());

        // 等待态下玩家发言：输入即推进（无需播出完毕信号，不会被等待态阻塞）
        RouterService.RoundResult r = router.runRound("玩家发言", null, null); // 第 2 轮（玩家驱动）
        assertFalse(r.status.startsWith("error"), "玩家轮不应报错: " + r.status);
        assertEquals(2, router.getState().get("round"), "玩家输入驱动了第 2 轮");

        // 玩家轮完成后重新进入等待态（等播出完毕再推进第 3 轮）
        assertTrue(router.isAwaitingPlayback(), "玩家轮完成后重新等待播出完毕");
        assertTrue(router.onPlaybackDone(), "播出完毕信号推进第 3 轮");
        awaitRoundCompletes(sse, 3, 10);
        assertEquals(3, router.getState().get("round"), "信号驱动了第 3 轮");
        router.stop();
    }

    // ── ⑤ 非一般模式不进入等待 ──

    @Test
    @DisplayName("⑤ 非一般模式（werewolf/script）不进入等待态（走各自状态机）")
    void nonGeneralMode_noPlaybackWait() {
        for (String mode : new String[]{"werewolf", "script"}) {
            CaptureSSE sse = new CaptureSSE();
            RouterService router = newRouter(sse, mode, false, false);
            router.setPlaybackDriven(true);

            router.runRound(null, null);
            assertEquals(1, router.getState().get("round"), "mode=" + mode + " round=1");
            assertFalse(router.isAwaitingPlayback(), "mode=" + mode + " 不应进入等待态");
            assertFalse(router.onPlaybackDone(), "mode=" + mode + " 播出完毕信号应 no-op");
            router.stop();
        }
    }

    // ── ⑥ 开关兼容旧行为（playback-driven=false → D-056 定时自续） ──

    @Test
    @DisplayName("⑥ playback-driven=false：回退 D-056 旧行为（auto-continue-ms 定时自续）")
    void disabled_oldTimerBehaviorPreserved() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "director", false, false);
        router.setPlaybackDriven(false); // 旧行为
        router.setAutoContinueMs(300);

        router.runRound(null, null); // 第 1 轮完成 → 调度定时续轮
        assertEquals(1, router.getState().get("round"));
        assertTrue(router.hasPendingAutoContinue(), "旧行为应调度定时续轮任务");
        assertFalse(router.isAwaitingPlayback(), "旧行为不置等待标志");

        awaitRoundCompletes(sse, 2, 10); // 定时自续第 2 轮
        assertEquals(2, router.getState().get("round"), "旧行为自动跑下一轮");
        router.stop();
    }

    // ── ⑦ 玩家输入影响下一轮剧情（入史 → 下一轮上下文读取） ──

    @Test
    @DisplayName("⑦ 玩家输入影响下一轮剧情：round 1 玩家发言 → 播出完毕信号 → round 2 AI 上下文包含该输入")
    void playerInput_affectsNextRoundContext() throws Exception {
        CaptureSSE sse = new CaptureSSE();
        RouterService router = newRouter(sse, "protagonist", true, true);
        router.setPlaybackDriven(true);
        router.setSerialRound(true); // 串行路径显式传全量上下文（D-024/D-027 语义）

        router.runRound("我怀疑管家在说谎", null, null); // 第 1 轮：玩家输入 + AI 回应
        assertEquals(1, router.getState().get("round"));
        assertTrue(router.isAwaitingPlayback());

        int before = capturedContexts.size(); // 第 1 轮已捕获（A/B 各 1 条）
        assertTrue(before >= 2, "第 1 轮两角色应各有一次 LLM 调用，实际=" + before);

        assertTrue(router.onPlaybackDone(), "播出完毕信号推进第 2 轮");
        awaitRoundCompletes(sse, 2, 10);
        assertEquals(2, router.getState().get("round"));

        // 第 2 轮（AI 自主轮）的上下文应包含第 1 轮玩家输入（经主控旁白入史 → buildAgentContext 读取）
        List<String> round2 = new ArrayList<>(capturedContexts.subList(before, capturedContexts.size()));
        assertFalse(round2.isEmpty(), "第 2 轮应有 LLM 调用");
        for (String ctx : round2) {
            assertTrue(ctx.contains("我怀疑管家在说谎"),
                    "第 2 轮上下文应包含玩家输入（入史影响剧情），实际片段：" + ctx);
        }
        router.stop();
    }
}
