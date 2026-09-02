package com.roleplay.engine.service;

import com.roleplay.engine.agent.Agent;
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
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0813-B：校准轮实现（roleplay.round.calibrate-every）测试。
 *
 * <p>直接构造 RouterService（mock LLM/Arbiter，RouterServiceAutoContinueTest 同款 harness），验证：
 * ① 配置默认值（AppConfig.RoundConfig.calibrateEvery=10，0=禁用语义）+ yml 双份默认 10（P-0813-C）；\n
 * ② 每 calibrate-every 个 AI 自主推进轮向会话消息列表注入校准提醒（layer0 前 3 条 + 反差 + 角色关系）；\n
 * ③ 校准消息出现在 LLM 上下文（串行路径【对话历史】捕获 LLM 调用参数）；\n
 * ④ calibrate-every=0 → 禁用（多轮后无校准消息）；\n
 * ⑤ 玩家发言轮不触发校准（仅轮次推进触发）；\n
 * ⑥ initSession 重置计数（会话重建不误触发）；\n
 * ⑦ buildDriftPreventionPrompt 结尾措辞软化；\n
 * ⑧ 非一般模式（werewolf/script）不注入；\n
 * ⑨ Agent.buildContext 对【校准提醒】SYSTEM 消息放行、其余 SYSTEM 仍跳过（绕过 SYSTEM 过滤的实现）。
 */
class RouterServiceCalibrationTest {

    private static final String SESSION_ID = "calibration-test";
    private static final String SCENE = "夜晚的咖啡馆，小铃与凯尔相对而坐。";

    /** 五层角色（layer0 3 条 + 反差 + 表层）—— 校准消息只针对五层角色注入。 */
    private static Persona fiveLayerPersona(String name) {
        Persona p = new Persona(name);
        p.setLayers(Map.of(
                "layer0", List.of("当客人说咖啡不好喝时，先道歉再重做，不辩解。", "你永远不会承认自己认输。", "第三条行为准则"),
                "contrast", Map.of("surface", "温柔体贴", "actual", "胜负欲强", "hint", "涉及咖啡手艺时显现"),
                "layer2", Map.of("sentenceStyle", "短句为主")
        ));
        return p;
    }

    /** 构建一般模式 RouterService（mock LLM/Arbiter，同 RouterServiceAutoContinueTest harness）。 */
    private RouterService newRouter(LLMClient llm, CaptureSSE sse, String mode) {
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
                List.of(fiveLayerPersona("小铃"), fiveLayerPersona("凯尔")),
                SCENE, mode, "", "");
        return router;
    }

    /** 捕获全局广播事件。 */
    static class CaptureSSE extends SSEController {
        @Override
        public void broadcast(String eventType, Object data) {
            // 无需断言 SSE；占位保证 sse 非 null
        }
    }

    /** 统计会话消息中的校准提醒条数（前缀匹配，隔离关系图等其他 SYSTEM 消息）。 */
    private static long countCalibration(RouterService router) {
        return router.getConversationMessages().stream()
                .filter(m -> m.getContent() != null && m.getContent().startsWith("【校准提醒】"))
                .count();
    }

    /** 断言校准消息内容包含 layer0 规则 + 反差 + 角色关系。 */
    private static void assertCalibrationContent(RouterService router, String relationText) {
        Message cal = router.getConversationMessages().stream()
                .filter(m -> m.getContent() != null && m.getContent().startsWith("【校准提醒】"))
                .findFirst().orElseThrow(() -> new AssertionError("应有校准消息"));
        assertEquals(Message.Role.SYSTEM, cal.getRole(), "校准消息以 SYSTEM role 注入");
        assertTrue(cal.getContent().contains("先道歉再重做，不辩解"), "校准消息含 layer0 前 3 条规则");
        assertTrue(cal.getContent().contains("你永远不会承认自己认输。"), "校准消息含 layer0 第 2 条");
        assertTrue(cal.getContent().contains("反差：表面=温柔体贴，实际=胜负欲强"), "校准消息含反差 surface/actual");
        assertTrue(cal.getContent().contains("提示=涉及咖啡手艺时显现"), "校准消息含反差 hint");
        if (relationText != null) {
            assertTrue(cal.getContent().contains("【角色关系】"), "校准消息含角色关系段");
            assertTrue(cal.getContent().contains(relationText), "校准消息含关系详情");
        }
    }

    // ── ① 配置默认值与表面 ──

    @Test
    @DisplayName("① AppConfig.RoundConfig 默认 calibrateEvery=10（0=禁用语义）+ yml 双份默认 10（P-0813-C）")
    void appConfig_defaultIs10() throws Exception {
        AppConfig appConfig = new AppConfig();
        assertEquals(10, appConfig.getRound().getCalibrateEvery(), "默认 10");
        appConfig.getRound().setCalibrateEvery(0);
        assertEquals(0, appConfig.getRound().getCalibrateEvery(), "0=禁用可配置");

        // P-0813-C：yml 双份默认值与 AppConfig 同步为 10（防止漂移）
        String mainYml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(mainYml.contains("calibrate-every: 10"), "application.yml 默认 10（P-0813-C 6→10）");
        String testYml = new String(new ClassPathResource("application-test.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(testYml.contains("calibrate-every: 10"), "application-test.yml 默认 10（与主 yml 同步）");
    }

    // ── ② 每 N 轮注入校准消息 ──

    @Test
    @DisplayName("② calibrate-every=2：第 1 轮不注入，第 2 轮注入（每五层角色一条：layer0 前 3 + 反差 + 关系）")
    void injectsEveryNthRound() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        RouterService router = newRouter(llm, new CaptureSSE(), "free");
        router.setCalibrateEvery(2);
        router.setSerialRound(true); // 串行路径：校准消息经【对话历史】进入上下文

        router.runRound(null, null); // 第 1 轮：计数 1，未达 2 → 不注入
        assertEquals(0, countCalibration(router), "第 1 轮不应有校准消息");

        router.runRound(null, null); // 第 2 轮：计数 2 → 注入（小铃 + 凯尔 各一条）
        assertEquals(2, countCalibration(router), "第 2 轮应为 2 个五层角色各注入一条校准消息");
        assertCalibrationContent(router, null);
    }

    // ── ③ 校准消息出现在 LLM 上下文 ──

    @Test
    @DisplayName("③ 校准消息出现在 LLM 调用上下文（串行路径【对话历史】含校准文本）")
    void calibrationReachesLlmContext() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        RouterService router = newRouter(llm, new CaptureSSE(), "free");
        router.setCalibrateEvery(2);
        router.setSerialRound(true);

        router.runRound(null, null);
        router.runRound(null, null); // 第 2 轮注入 → 本轮 agent 生成时上下文含校准

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm, atLeastOnce()).callSync(captor.capture(), any());
        boolean seenInContext = false;
        for (List<Message> msgs : captor.getAllValues()) {
            for (Message m : msgs) {
                if (m.getContent() != null && m.getContent().contains("【校准提醒】")) {
                    seenInContext = true;
                    break;
                }
            }
        }
        assertTrue(seenInContext, "校准提醒必须真的出现在 LLM 调用上下文（【对话历史】区块）");
    }

    // ── ④ 0=禁用 ──

    @Test
    @DisplayName("④ calibrate-every=0：禁用（多轮后无校准消息）")
    void disabledWhenZero() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        RouterService router = newRouter(llm, new CaptureSSE(), "free");
        router.setCalibrateEvery(0);

        for (int i = 0; i < 5; i++) {
            router.runRound(null, null);
        }
        assertEquals(0, countCalibration(router), "calibrate-every=0 时任何轮次都不注入校准");
        assertEquals(5, router.getState().get("round"));
    }

    // ── ⑤ 玩家发言不触发 ──

    @Test
    @DisplayName("⑤ 玩家发言轮不触发校准（仅 AI 自主推进轮计数；calibrate-every=1 时 AI 轮才注入）")
    void playerSpeechDoesNotTrigger() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        RouterService router = newRouter(llm, new CaptureSSE(), "free");
        router.setCalibrateEvery(1);

        // 玩家发言轮：userInput 非空 → 不计数不触发（即使 calibrate-every=1）
        RouterService.RoundResult r = router.runRound("玩家发言", null, null);
        assertFalse(r.status.startsWith("error"), "玩家发言轮不应报错: " + r.status);
        assertEquals(0, countCalibration(router), "玩家发言轮不注入校准");

        // AI 自主推进轮（userInput=null）→ 计数达 1 → 注入
        router.runRound(null, null);
        assertEquals(2, countCalibration(router), "AI 轮达到间隔即注入（每角色一条）");
    }

    // ── ⑥ 会话重建不误触发 ──

    @Test
    @DisplayName("⑥ initSession 重置校准计数（旧会话已校准轮数不串场到新会话）")
    void reinitResetsCounter() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("AI回应");
        RouterService router = newRouter(llm, new CaptureSSE(), "free");
        router.setCalibrateEvery(2);

        router.runRound(null, null); // 旧会话第 1 轮：计数 1，无注入
        assertEquals(0, countCalibration(router));

        // 会话重建：计数必须归零 —— 新会话第 1 轮若计数未重置（1+1=2）就会误注入
        router.initSession(SESSION_ID,
                List.of(fiveLayerPersona("小铃"), fiveLayerPersona("凯尔")),
                SCENE, "free", "", "");
        router.runRound(null, null); // 新会话第 1 轮：计数 1，无注入
        assertEquals(0, countCalibration(router), "会话重建后计数重置，第 1 轮不误触发");

        router.runRound(null, null); // 新会话第 2 轮：计数 2 → 注入
        assertEquals(2, countCalibration(router), "新会话按自身轮次计数注入");
    }

    // ── ⑦ buildDriftPreventionPrompt 措辞软化 ──

    @Test
    @DisplayName("⑦ buildDriftPreventionPrompt 结尾措辞软化（正向引导：可即兴发挥，不得违背核心身份与关系）")
    void driftPromptEndingSoftened() {
        LLMClient llm = mock(LLMClient.class);
        RouterService router = newRouter(llm, new CaptureSSE(), "free");
        router.buildCharacterRelations(Map.of("relationships", List.of(
                Map.of("from", "小铃", "to", "凯尔", "relation", "店主与熟客", "description", "常来喝咖啡")
        )));

        String drift = router.buildDriftPreventionPrompt("小铃");
        assertTrue(drift.contains("与凯尔的关系：店主与熟客（常来喝咖啡）"), "关系详情保留");
        assertTrue(drift.contains("基于以上关系自然行动，可即兴发挥，但不得违背核心身份与关系。"),
                "结尾改为正向引导措辞");
        assertFalse(drift.contains("请严格依据以上设定行动"), "不再使用旧「请严格依据…不要偏离」措辞");
        assertFalse(drift.contains("不要偏离角色的背景"), "不再出现「不要偏离」负面措辞");
    }

    // ── ⑧ 非一般模式不注入 ──

    @Test
    @DisplayName("⑧ 非一般模式（werewolf/script）不注入校准（走各自状态机，零影响）")
    void nonGeneralModeNoCalibration() {
        for (String mode : new String[]{"werewolf", "script"}) {
            LLMClient llm = mock(LLMClient.class);
            when(llm.callSync(anyList(), any())).thenReturn("AI回应");
            RouterService router = newRouter(llm, new CaptureSSE(), mode);
            router.setCalibrateEvery(1);

            router.runRound(null, null);
            assertEquals(0, countCalibration(router), "mode=" + mode + " 不应注入校准");
        }
    }

    // ── ⑨ Agent.buildContext SYSTEM 过滤放行 ──

    @Test
    @DisplayName("⑨ Agent.buildContext：带【校准提醒】前缀的 SYSTEM 消息放行进入上下文，其余 SYSTEM 仍跳过")
    void buildContextPassesCalibrationSystemMessages() {
        Agent agent = new Agent(fiveLayerPersona("小铃"), "agent", mock(LLMClient.class));
        List<Message> history = List.of(
                new Message(Message.Role.SYSTEM, "系统", "【校准提醒】（第 6 轮，请保持言行一致）"),
                new Message(Message.Role.SYSTEM, "主控", "【角色关系图】小铃→凯尔: 店主与熟客"),
                new Message(Message.Role.AGENT, "凯尔", "今天也要加油哦")
        );

        List<Message> ctx = agent.buildContext("", history, "merged", List.of(), "", null, "");
        assertTrue(ctx.stream().anyMatch(m -> m.getContent() != null && m.getContent().startsWith("【校准提醒】")),
                "校准 SYSTEM 消息必须进入 LLM 上下文");
        assertFalse(ctx.stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("【角色关系图】")),
                "其余 SYSTEM 消息（关系图）维持跳过语义零变化");
        assertTrue(ctx.stream().anyMatch(m -> "今天也要加油哦".equals(m.getContent())),
                "普通对话消息不受影响");
    }

    @Test
    @DisplayName("⑩ Agent：人格前缀跨轮稳定，校准作为后续动态消息")
    void agentKeepsStablePersonaPrefixAndCalibrationDelta() {
        Agent agent = new Agent(fiveLayerPersona("小铃"), "agent", mock(LLMClient.class));

        String first = agent.buildContext("", List.of(), "merged", List.of(), "", null, "")
                .get(0).getContent();
        String normal = agent.buildContext("", List.of(
                new Message(Message.Role.AGENT, "凯尔", "普通对话")), "merged", List.of(), "", null, "")
                .get(0).getContent();
        List<Message> calibrationMessages = agent.buildContext("", List.of(
                new Message(Message.Role.SYSTEM, "系统", "【校准提醒】保持身份"),
                new Message(Message.Role.AGENT, "凯尔", "普通对话")), "merged", List.of(), "", null, "");
        String calibration = calibrationMessages.get(0).getContent();

        assertTrue(first.contains("【Layer 2 表达风格】"), "首轮应使用完整五层提示");
        assertEquals(first, normal, "普通轮不得改写稳定人格前缀");
        assertEquals(first, calibration, "校准轮不得破坏稳定人格前缀");
        assertTrue(calibrationMessages.stream().skip(1)
                .anyMatch(m -> m.getContent().startsWith("【校准提醒】")), "校准应作为动态 delta 保留");
    }
}
