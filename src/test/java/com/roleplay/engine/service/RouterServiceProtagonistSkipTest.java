package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0810-25-2：玩家角色（protagonist）不参与 LLM 生成 —— 玩家发言只来自玩家输入（/api/send），AI 绝不能代答。
 *
 * <p>根因（已实证）：并行路径 AgentExecutor.buildTasks 调 computePriority(agentName, trackMode, "", "")
 * 硬编码传空串 → PLAYER 优先级永不生效 → 玩家角色按 NPC 参与 LLM 生成；串行路径 computeSerialPriority
 * 虽能识别 PLAYER 但仅排序靠前，executeRoundSerial 里玩家角色仍执行 generateWithContextStream 生成发言。
 * 两条路径都缺「PLAYER 角色跳过 LLM 生成」机制。
 *
 * <p>修复：RouterService.runRound 构造生成名单 agentMap 后，protagonist 非空时 agentMap.remove(protagonist)
 * —— 并行路径 buildTasks 对缺失 agent 自动跳过（agent == null continue），串行路径 executeRoundSerial
 * 对缺失 agent 自动跳过（!agentMap.containsKey continue），两条路径一次修复。
 *
 * <p>验证：protagonist 模式下 runRound 后 agent_outputs 不含玩家角色发言；玩家角色仍在 agents 列表
 * （state 正常）；free/director 模式玩家角色为空时不影响 AI 自动对话；玩家发言仍正常入 memory。
 *
 * <p>mock 策略：与 RouterServiceSerialRoundTest 同款 —— ArbiterService/Compressor/TrackRequestService 等
 * mock，LLMClient mock 捕获每次 callSync 收到的上下文（USER 消息即 buildAgentContext 输出），
 * 按上下文中的身份标识返回对应发言（含「你是 P」哨兵，命中即代表玩家角色被请求生成 = 缺陷复现）。
 */
class RouterServiceProtagonistSkipTest {

    private static final String SESSION_ID = "protagonist-skip-test";
    private static final String SCENE = "夜晚的庄园，侦探、管家与玩家角色在客厅。";
    private static final String A = "A";
    private static final String B = "B";
    private static final String P = "P"; // 玩家角色（protagonist）

    /** 捕获的上下文（全部消息内容拼接）列表，按 LLM 调用顺序。并行路径多 agent 并发调用 → 必须线程安全。 */
    private final List<String> capturedContexts = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 命中「你是 P」哨兵（= 玩家角色被请求 LLM 生成 = 缺陷）。并行路径身份在 SYSTEM 提示、串行路径在 USER 上下文 → 全角色扫描。 */
    private boolean protagonistAskedToGenerate() {
        synchronized (capturedContexts) {
            return capturedContexts.stream().anyMatch(c -> c.contains("你是 " + P));
        }
    }

    /**
     * 构建 RouterService：
     * - 三角色 A/B/P 单条 MERGED 轨道（均 active）
     * - LLM mock：捕获 USER 消息内容，按「你是 X」身份标识返回对应发言；命中 P 返回哨兵发言
     * - Arbiter mock：configureTracks 返回固定单轨道；integrateOutputs 返回固定旁白
     */
    private RouterService newRouter(String mode, String protagonist, boolean serial) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Message> msgs = inv.getArgument(0);
            // 并行路径：身份在 SYSTEM 提示（buildLightweightPrompt 开头「你是 X」）；
            // 串行路径：身份在 USER 上下文（buildAgentContext 开头「你是 X」）→ 全角色扫描拼接。
            String all = msgs.stream()
                    .map(Message::getContent)
                    .reduce("", (a, b) -> a + "\n" + b);
            capturedContexts.add(all);
            if (all.contains("你是 " + P)) return "P发言：AI 不该替我说！";
            if (all.contains("你是 " + A)) return "A发言：我看到了碎玻璃。";
            if (all.contains("你是 " + B)) return "B发言：我也觉得管家可疑。";
            return "通用发言";
        });

        ArbiterService arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", List.of(A, B, P));
        track.put("agent_actions", Map.of(A, "active", B, "active", P, "active"));
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet()))
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
                null);          // identityService（runRound 内 null 守卫，本批用例不走 playerId）
        router.setSerialRound(serial);
        router.initSession(SESSION_ID, List.of(
                new Persona(A, "你是一个细心观察的侦探。"),
                new Persona(B, "你是一个谨慎的仆人。"),
                new Persona(P, "你是玩家扮演的角色。")),
                SCENE, mode, protagonist, "");
        return router;
    }

    /** agent_outputs 中的 agent_name 列表。 */
    private List<String> outputAgentNames(RouterService.RoundResult result) {
        return result.agentOutputs.stream()
                .map(o -> String.valueOf(o.get("agent_name")))
                .toList();
    }

    /** 会话中角色（AGENT）消息按时间顺序。 */
    private List<Message> agentMessages(RouterService router) {
        return router.getConversationMessages().stream()
                .filter(m -> m.getRole() == Message.Role.AGENT)
                .toList();
    }

    // ── 用例 ───────────────────────────────────────────────────

    @Test
    @DisplayName("protagonist 模式并行路径：自动轮 runRound 后 agent_outputs 不含玩家角色发言，且从未请求玩家角色生成")
    void protagonistMode_parallelRound_protagonistNeverGenerated() {
        RouterService router = newRouter("protagonist", P, false);

        RouterService.RoundResult result = router.runRound(null, null);

        List<String> names = outputAgentNames(result);
        assertFalse(names.isEmpty(), "AI 角色应正常发言");
        assertFalse(names.contains(P), "agent_outputs 不得包含玩家角色：实际 " + names);
        assertFalse(protagonistAskedToGenerate(), "LLM 从未被请求为玩家角色生成发言");
        // 玩家角色发言只来自玩家输入 —— 纯自动轮里 memory 也不该出现玩家角色发言
        assertTrue(agentMessages(router).stream().noneMatch(m -> P.equals(m.getName())),
                "memory 不得出现玩家角色发言（AI 代答）");
    }

    @Test
    @DisplayName("protagonist 模式串行路径（serial=true）：同样跳过玩家角色生成")
    void protagonistMode_serialRound_protagonistNeverGenerated() {
        RouterService router = newRouter("protagonist", P, true);

        RouterService.RoundResult result = router.runRound(null, null);

        List<String> names = outputAgentNames(result);
        assertFalse(names.isEmpty(), "AI 角色应正常发言");
        assertFalse(names.contains(P), "agent_outputs 不得包含玩家角色：实际 " + names);
        assertFalse(protagonistAskedToGenerate(), "串行路径 LLM 从未被请求为玩家角色生成发言");
        assertTrue(agentMessages(router).stream().noneMatch(m -> P.equals(m.getName())),
                "memory 不得出现玩家角色发言（AI 代答）");
    }

    @Test
    @DisplayName("玩家角色仍在 agents 列表（state 正常，仅生成名单排除）")
    void protagonistMode_stateKeepsProtagonistAgent() {
        RouterService router = newRouter("protagonist", P, false);

        @SuppressWarnings("unchecked")
        List<String> agents = (List<String>) router.getState().get("agents");
        Map<String, Object> state = router.getState();
        assertEquals(3, state.get("agent_count"), "agent_count 不变（玩家角色仍是会话成员）");
        assertTrue(agents.contains(P), "agents 列表仍含玩家角色");
        assertEquals(P, state.get("protagonist"));
        assertEquals("protagonist", state.get("mode"));
    }

    @Test
    @DisplayName("director/free 模式玩家角色为空：AI 自动对话不受影响（三角色均正常生成）")
    void emptyProtagonist_directorMode_allAgentsStillGenerate() {
        RouterService router = newRouter("director", "", false);

        RouterService.RoundResult result = router.runRound(null, null);

        List<String> names = outputAgentNames(result);
        assertEquals(3, names.size(), "director 模式三 AI 角色均应发言：实际 " + names);
        assertTrue(names.containsAll(List.of(A, B, P)), "P 在无玩家角色时是普通 NPC：实际 " + names);
        assertTrue(protagonistAskedToGenerate(), "无玩家角色时 P 应正常参与 LLM 生成");
    }

    @Test
    @DisplayName("玩家发言仍正常入 memory（/api/send → player_name → 历史消息，AI 上下文可读）；同轮仍不代答")
    void protagonistMode_playerInput_recordedToMemory_andExcludedFromGeneration() {
        RouterService router = newRouter("protagonist", P, false);

        RouterService.RoundResult result = router.runRound("大家好，我是玩家。", null, P, "");

        // 玩家发言原文入史（speakerIsAgent → 角色说）
        assertTrue(router.getConversationMessages().stream()
                        .anyMatch(m -> P.equals(m.getName()) && "大家好，我是玩家。".equals(m.getContent())),
                "玩家发言必须进入 memory（AI 上下文可读到）");
        // 同轮 LLM 生成名单仍不含玩家角色
        List<String> names = outputAgentNames(result);
        assertFalse(names.contains(P), "玩家发言同轮也不得被 LLM 代答：实际 " + names);
        assertFalse(protagonistAskedToGenerate(), "LLM 从未被请求为玩家角色生成发言");
    }

    @Test
    @DisplayName("runAutoRounds/runTurns 自动轮走同一 runRound 入口：多轮均不代答玩家角色")
    void protagonistMode_autoRounds_allRoundsExcludeProtagonist() {
        RouterService router = newRouter("protagonist", P, false);

        List<RouterService.RoundResult> results = router.runAutoRounds(2);

        assertEquals(2, results.size());
        for (RouterService.RoundResult r : results) {
            assertFalse(outputAgentNames(r).contains(P), "自动轮 agent_outputs 不得含玩家角色");
        }
        assertFalse(protagonistAskedToGenerate(), "自动轮 LLM 从未被请求为玩家角色生成发言");
        assertTrue(agentMessages(router).stream().noneMatch(m -> P.equals(m.getName())),
                "自动轮 memory 不得出现玩家角色发言（AI 代答）");
    }
}
