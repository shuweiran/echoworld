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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0810-23-D2：AI 角色发言超长提醒（仅下一轮生效）测试。
 *
 * <p>验证（用户最终规则）：
 * ① AI 角色单次发言中文字符数 > 阈值 → 记录 pendingReminder；
 * ② 下一轮该角色构建系统提示（SYSTEM 消息）时注入「系统提醒：你上一轮发言超过 N 字，本轮请精简输出」；
 * ③ 再下一轮已清除（注入即消费，不持续）；
 * ④ 正常短发言不触发；阈值 <=0 关闭检测；
 * ⑤ 串行（serial=true，生产默认）与并行（serial=false）两条生成路径均生效。
 *
 * <p>mock 策略（同 RouterServiceSerialRoundTest）：LLM mock 捕获每次调用收到的 SYSTEM/USER 消息，
 * 按「该 agent 第几次发言」返回超长/短发言（每 agent 首次发言返回超长文本，之后返回短文本）。
 */
class RouterServiceReminderTest {

    private static final String SESSION_ID = "reminder-test";
    private static final String SCENE = "夜晚的庄园，管家与女仆在客厅。";
    /** 超长发言：中文字符数 > 测试阈值 5（实为 30 个汉字）。 */
    private static final String LONG_TEXT = "今天天气真的很好我们一起去公园散步吧阳光洒在湖面上波光粼粼的";
    private static final String SHORT_TEXT = "好的。";

    // ── Harness ────────────────────────────────────────────────

    /** 捕获的 SYSTEM 消息内容列表（按 LLM 调用顺序；并行路径并发调用 → 线程安全）。 */
    private final List<String> capturedSystems = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 每 agent 已发言次数（mock 据此前 N 次调用返回超长/短文本）。 */
    private final Map<String, AtomicInteger> callsPerAgent = new ConcurrentHashMap<>();

    private String systemOf(List<Message> msgs) {
        return msgs.stream()
                .filter(m -> m.getRole() == Message.Role.SYSTEM)
                .map(Message::getContent)
                .findFirst().orElse("");
    }

    private String userOf(List<Message> msgs) {
        return msgs.stream()
                .filter(m -> m.getRole() == Message.Role.USER)
                .map(Message::getContent)
                .findFirst().orElse("");
    }

    /**
     * 构建 RouterService（双角色 A/B 单条 MERGED 轨道）。
     * LLM mock：serial 模式 stub callStream（串行路径走流式；不 stub callSync 防双调）；
     * 并行模式 stub callSync（并行路径走 generateSync）。两者都捕获 SYSTEM 消息。
     * longRule："A"=仅 A 首次发言超长（B 恒短，验证「短发言者不受影响」）；"all"=每 agent 首次发言均超长。
     * agent 识别：串行路径身份在 USER（buildAgentContext 输出），并行路径身份只在 SYSTEM
     * （AgentExecutor 传空 history，无 USER 消息）→ 用 USER+SYSTEM 拼接检测「你是 A/B」。
     */
    private RouterService newRouter(boolean serial, String longRule) {
        LLMClient llm = mock(LLMClient.class);
        java.util.function.Function<List<Message>, String> agentOf = msgs -> {
            String blob = userOf(msgs) + systemOf(msgs);
            return blob.contains("你是 A") ? "A" : (blob.contains("你是 B") ? "B" : "?");
        };
        java.util.function.Function<String, String> speechOf = agentName -> {
            int n = callsPerAgent.computeIfAbsent(agentName, k -> new AtomicInteger()).incrementAndGet();
            boolean longFirst = "all".equals(longRule)
                    || ("A".equals(longRule) && "A".equals(agentName));
            return (longFirst && n == 1) ? LONG_TEXT : SHORT_TEXT;
        };
        if (serial) {
            when(llm.callStream(anyList(), any(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<Message> msgs = inv.getArgument(0);
                capturedSystems.add(systemOf(msgs));
                return speechOf.apply(agentOf.apply(msgs));
            });
        } else {
            when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<Message> msgs = inv.getArgument(0);
                capturedSystems.add(systemOf(msgs));
                return speechOf.apply(agentOf.apply(msgs));
            });
        }

        ArbiterService arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", List.of("A", "B"));
        track.put("agent_actions", Map.of("A", "active", "B", "active"));
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
                null, null, interruptManager, new WorldEventBus(), null, null);
        router.setSerialRound(serial);
        // D2：测试阈值 5（默认 150 由 @Value 注入，测试经 setter 覆盖；>5 汉字即触发）
        router.setRemindThreshold(5);
        router.initSession(SESSION_ID, List.of(
                new Persona("A", "你是一个细心观察的侦探。"),
                new Persona("B", "你是一个谨慎的仆人。")),
                SCENE, "free", "", "");
        return router;
    }

    /** 断言第 n 次（1 起）LLM 调用的 SYSTEM 是否含提醒块。 */
    private void assertSystemHasReminder(int callIndex1Based, boolean expect, String label) {
        assertTrue(capturedSystems.size() >= callIndex1Based,
                label + "：应至少捕获 " + callIndex1Based + " 次 LLM 调用（实际 " + capturedSystems.size() + "）");
        String sys = capturedSystems.get(callIndex1Based - 1);
        if (expect) {
            assertTrue(sys.contains("【系统提醒】") && sys.contains("超过 5 字"),
                    label + "：第 " + callIndex1Based + " 次调用 SYSTEM 应含提醒（实际: " + sys + "）");
        } else {
            assertFalse(sys.contains("【系统提醒】"),
                    label + "：第 " + callIndex1Based + " 次调用 SYSTEM 不应含提醒（实际: " + sys + "）");
        }
    }

    private RouterService newRouter(boolean serial) {
        return newRouter(serial, "A");
    }

    @Test
    @DisplayName("D2-1 串行：超长发言 → 下一轮系统提示注入提醒 → 再下一轮清除；短发言者不受影响")
    void serialRound_longSpeech_reminderInjectedNextRoundThenCleared() {
        RouterService router = newRouter(true);

        // 第 1 轮：A 超长（触发提醒）、B 正常（不触发）
        assertFalse(router.runRound(null, null).status.startsWith("error"));
        // 第 1 轮两角色生成时的 SYSTEM：A 先发言（当时无提醒）、B 无提醒
        assertSystemHasReminder(1, false, "round1-A");
        assertSystemHasReminder(2, false, "round1-B");

        // 第 2 轮：A 的系统提示含提醒（上一轮超长），B 不含
        assertFalse(router.runRound(null, null).status.startsWith("error"));
        assertSystemHasReminder(3, true, "round2-A（注入提醒）");
        assertSystemHasReminder(4, false, "round2-B（未超长不触发）");

        // 第 3 轮：提醒已消费清除 → A 不再含提醒
        assertFalse(router.runRound(null, null).status.startsWith("error"));
        assertSystemHasReminder(5, false, "round3-A（已清除）");
        assertSystemHasReminder(6, false, "round3-B");
    }

    @Test
    @DisplayName("D2-2 串行：正常短发言不触发提醒（阈值极大时连续多轮 SYSTEM 均无提醒块）")
    void serialRound_shortSpeech_noReminder() {
        RouterService router = newRouter(true);
        // 阈值提到极大：超长文本（26 汉字）也远不触发，等价“正常发言不触发”
        router.setRemindThreshold(100000);

        for (int round = 0; round < 2; round++) {
            assertFalse(router.runRound(null, null).status.startsWith("error"));
        }
        // 4 次调用全部无提醒
        assertEquals(4, capturedSystems.size());
        for (String sys : capturedSystems) {
            assertFalse(sys.contains("【系统提醒】"), "正常发言不应出现提醒块: " + sys);
        }
    }

    @Test
    @DisplayName("D2-3 计数工具：countChineseChars 只统计汉字（字母/数字/标点不计）")
    void countChineseChars_countsOnlyHan() {
        assertEquals(0, RouterService.countChineseChars(null));
        assertEquals(0, RouterService.countChineseChars("abc 123 !!!"));
        assertEquals(4, RouterService.countChineseChars("你好abc世界"));
        assertEquals(30, RouterService.countChineseChars(LONG_TEXT));
    }

    // ── 用例（并行路径） ────────────────────────────────────────

    @Test
    @DisplayName("D2-4 并行（serial=false）：两角色均超长 → 下一轮双方系统提示均注入提醒 → 再下轮清除")
    void parallelRound_bothLong_remindersInjectedNextRoundThenCleared() {
        RouterService router = newRouter(false, "all");

        assertFalse(router.runRound(null, null).status.startsWith("error"));
        assertFalse(router.runRound(null, null).status.startsWith("error"));
        assertFalse(router.runRound(null, null).status.startsWith("error"));

        // 并行路径每轮 2 次调用（A、B 各 1）：第 1 轮无提醒、第 2 轮双方含提醒、第 3 轮已清除。
        // 同轮内并发顺序不确定 → 按“轮次窗口”断言（窗口内全部满足即可）。
        assertEquals(6, capturedSystems.size(), "三轮共 6 次调用");
        for (int i = 0; i < 2; i++) {
            assertFalse(capturedSystems.get(i).contains("【系统提醒】"),
                    "第 1 轮（call " + (i + 1) + "）不应含提醒");
        }
        for (int i = 2; i < 4; i++) {
            assertTrue(capturedSystems.get(i).contains("【系统提醒】"),
                    "第 2 轮（call " + (i + 1) + "）应含提醒: " + capturedSystems.get(i));
        }
        for (int i = 4; i < 6; i++) {
            assertFalse(capturedSystems.get(i).contains("【系统提醒】"),
                    "第 3 轮（call " + (i + 1) + "）已清除不应含提醒");
        }
    }
}
