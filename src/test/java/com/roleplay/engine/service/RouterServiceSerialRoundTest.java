package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-2 一般模式串行调度（roleplay.round.serial）测试。
 *
 * <p>验证：serial=true 时 runRound 走串行循环——每个 agent 输出完成立即入史，
 * 后发言者 buildAgentContext 读到的对话历史包含前面角色本轮已完成的发言
 * （同轮上下文共享）；serial=false（默认）保持既有并行行为不变。
 *
 * <p>mock 策略：ArbiterService/Compressor/TrackRequestService 等 mock，
 * LLMClient mock 捕获每次 callSync 收到的上下文（USER 消息即 buildAgentContext 输出），
 * 按上下文中的身份标识返回对应发言。
 */
class RouterServiceSerialRoundTest {

    private static final String SESSION_ID = "serial-round-test";
    private static final String SCENE = "夜晚的庄园，管家与女仆在客厅。";

    // ── Harness ────────────────────────────────────────────────

    /** 捕获的上下文（USER 消息内容）列表，按 LLM 调用顺序。并行路径两个 agent 并发调用 → 必须线程安全。 */
    private final List<String> capturedContexts = java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * 构建 RouterService：
     * - 双角色 A/B 单条 MERGED 轨道（均 active）
     * - LLM mock：捕获 USER 消息内容，按「你是 A/B」身份标识返回对应发言
     * - Arbiter mock：configureTracks 返回固定单轨道；integrateOutputs 返回固定旁白
     */
    private RouterService newRouter(boolean serial) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Message> msgs = inv.getArgument(0);
            String userContent = msgs.stream()
                    .filter(m -> m.getRole() == Message.Role.USER)
                    .map(Message::getContent)
                    .findFirst().orElse("");
            capturedContexts.add(userContent);
            if (userContent.contains("你是 A")) return "A发言：我看到了碎玻璃。";
            if (userContent.contains("你是 B")) return "B发言：我也觉得管家可疑。";
            return "通用发言";
        });

        ArbiterService arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", List.of("A", "B"));
        track.put("agent_actions", Map.of("A", "active", "B", "active"));
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet(), any()))
                .thenReturn(new TrackConfigResult(List.of(track), "test"));
        when(arbiter.integrateOutputs(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(Map.of("narration", "整合旁白"));

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
                null,            // historyController（runRound 内 null 守卫）
                null,            // lorebookService（runRound 内 null 守卫）
                interruptManager,
                new WorldEventBus(),
                null,           // sse（runRound 内 null 守卫）
                null);          // identityService（P-0802-P2；runRound 内 null 守卫，本批用例不走 playerId）
        router.setSerialRound(serial);
        router.initSession(SESSION_ID, List.of(
                new Persona("A", "你是一个细心观察的侦探。"),
                new Persona("B", "你是一个谨慎的仆人。")),
                SCENE, "free", "", "");
        return router;
    }

    /** 会话中角色（AGENT）消息按时间顺序。 */
    private List<Message> agentMessages(RouterService router) {
        return router.getConversationMessages().stream()
                .filter(m -> m.getRole() == Message.Role.AGENT)
                .toList();
    }

    // ── 用例 ───────────────────────────────────────────────────

    @Test
    @DisplayName("serial=true：同轮串行上下文共享——后发言者 B 的上下文包含 A 本轮发言")
    void serialTrue_sharesSameRoundContext() {
        RouterService router = newRouter(true);

        RouterService.RoundResult result = router.runRound(null, null);

        // 正常完成，两个角色都有输出
        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size());

        // LLM 被调用 2 次，顺序 = 轨道内 agent 顺序：A 先、B 后
        assertEquals(2, capturedContexts.size(), "serial mode calls LLM once per agent, in order");

        // 后发言者 B 的上下文（第 2 次调用）包含 A 本轮已完成的发言
        String contextB = capturedContexts.get(1);
        assertTrue(contextB.contains("你是 B"), "context of second speaker should be B");
        assertTrue(contextB.contains("A发言：我看到了碎玻璃。"),
                "second speaker's context must contain first speaker's same-round speech");

        // 内存顺序：A 的消息先入史、B 后入史（即时入史生效）
        List<Message> msgs = agentMessages(router);
        assertEquals(2, msgs.size());
        assertEquals("A", msgs.get(0).getName());
        assertEquals("B", msgs.get(1).getName());

        // 输出顺序 = 发言顺序
        assertEquals("A", result.agentOutputs.get(0).get("agent_name"));
        assertEquals("B", result.agentOutputs.get(1).get("agent_name"));
    }

    @Test
    @DisplayName("serial=false（默认）：保持既有并行行为——两角色均有输出且入史")
    void serialFalse_parallelBehaviorUnchanged() {
        RouterService router = newRouter(false);

        RouterService.RoundResult result = router.runRound(null, null);

        // 行为不变：回合正常完成，两个角色都有输出
        assertFalse(result.status.startsWith("error"), "round should not error: " + result.status);
        assertEquals(2, result.agentOutputs.size());

        // 并行路径：两条消息均入史（Step 4 批量入史）
        List<Message> msgs = agentMessages(router);
        assertEquals(2, msgs.size());
        Set<String> names = Set.of(msgs.get(0).getName(), msgs.get(1).getName());
        assertEquals(Set.of("A", "B"), names);
    }

    @Test
    @DisplayName("serial=false（默认）：并行路径不共享同轮上下文（后发言者上下文不含前者本轮发言）")
    void serialFalse_noSameRoundSharing() {
        RouterService router = newRouter(false);

        router.runRound(null, null);

        // 并行路径：ctxBuilder 构建的上下文在 AgentExecutor 内被丢弃（既有行为），
        // LLM 收到的消息不含 USER 上下文；即使含上下文，A 输出在 executeRound 返回后才入史，
        // B 生成时读不到 A 本轮发言 —— 同轮上下文不共享（现状保持）
        // 注意：并行路径两个 agent 并发调用 mock（虚拟线程），capturedContexts 用线程安全列表；
        // 断言的是“任一调用都不含同轮发言”，与调用次数/顺序解耦（2 次是常规期望，允许因并发波动少记）
        synchronized (capturedContexts) {
            assertFalse(capturedContexts.isEmpty(), "parallel path must call LLM at least once");
            for (String ctx : capturedContexts) {
                assertFalse(ctx.contains("A发言：我看到了碎玻璃。"),
                        "parallel path must not leak same-round speech into context");
                assertFalse(ctx.contains("B发言：我也觉得管家可疑。"),
                        "parallel path must not leak same-round speech into context");
            }
        }
    }

    @Test
    @DisplayName("配置开关：roleplay.round.serial 默认 false，setSerialRound 可切换")
    void serialConfig_defaultFalseAndSwitchable() {
        // AppConfig 字段默认 false（yml 默认值 false 由 Spring 绑定，见 application.yml / application-test.yml）
        AppConfig cfg = new AppConfig();
        assertFalse(cfg.getRound().isSerial(), "AppConfig roleplay.round.serial default must be false");

        RouterService router = newRouter(false);
        assertFalse(router.isSerialRound(), "router serialRound default must be false");
        router.setSerialRound(true);
        assertTrue(router.isSerialRound(), "setSerialRound(true) must take effect");
    }
}
