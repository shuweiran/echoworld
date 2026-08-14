package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.db.service.DatabaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0814-B：SimulationService.sendUserMessage 唤醒所在组等待（AI-user 解卡；「输入=点击」语义）——
 * 真实 SimulationService + SimulationWorld tick 全链路：玩家在 DYAD 组内等待播出完毕时再次输入，
 * sendUserMessage 应唤醒该组生成下一轮，且该输入经 executeRound 调序在消费当轮即进 NPC 上下文。
 */
class SimulationServicePlaybackWakeTest {

    private static SimulationService buildSim(SimulationWorld world, LLMClient llm) {
        InterruptManager im = new InterruptManager(new WorldEventBus());
        return new SimulationService(world, llm, mock(DatabaseService.class),
                im, new AgentTaskManager(im), new WorldEventBus(),
                mock(AnnouncementService.class), null, new AppConfig(), null);
    }

    /** mock LLM：记录每次调用收到的上下文全文，返回固定回应。 */
    private static LLMClient mockLlm(List<String> captured) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<?> msgs = inv.getArgument(0);
            StringBuilder sb = new StringBuilder();
            for (Object m : msgs) {
                if (m instanceof com.roleplay.engine.core.Message msg) {
                    sb.append(msg.getContent()).append('\n');
                }
            }
            captured.add(sb.toString());
            return "回应";
        });
        when(llm.callSync(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<?> msgs = inv.getArgument(0);
            StringBuilder sb = new StringBuilder();
            for (Object m : msgs) {
                if (m instanceof com.roleplay.engine.core.Message msg) {
                    sb.append(msg.getContent()).append('\n');
                }
            }
            captured.add(sb.toString());
            return "回应";
        });
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());
        return llm;
    }

    private static void await(java.util.function.BooleanSupplier cond, String what, long timeoutSec) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                org.junit.jupiter.api.Assertions.fail("应在 " + timeoutSec + "s 内等到: " + what);
            }
            Thread.sleep(30);
        }
    }

    /** conversation-status → 指定组的轮次数（组不存在/尚未建组 → -1）。 */
    private static int groupRounds(SimulationService sim, String gid) {
        Map<String, Object> st = sim.getConversationStatus();
        Object groups = st.get("groups");
        if (!(groups instanceof List<?> list)) return -1;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && gid.equals(String.valueOf(m.get("id")))) {
                Object r = m.get("rounds");
                return r instanceof Number n ? n.intValue() : -1;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("① sendUserMessage 唤醒所在组等待：等待态组立即生成下一轮，输入消费当轮即进上下文（AI-user 解卡）")
    void sendUserMessage_wakesAwaitingGroup() throws Exception {
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        SimulationWorld world = new SimulationWorld();
        LLMClient llm = mockLlm(captured);
        world.registerAgent(new Agent(new Persona("A", "NPC 人格"), "npc", llm), 0, 0, 200, 60);
        world.registerAgent(new Agent(new Persona("P", "玩家人格"), "npc", llm), 2, 0, 200, 60);
        world.getState("P").setPlayerControlled(true);
        SimulationService sim = buildSim(world, llm);

        // 第一条玩家消息：tick 自动建 DYAD 组（P+A）→ 第一轮消费该消息（mock 无 LLM 延迟）
        sim.sendUserMessage("P", "你好");
        world.start();
        try {
            // 第一轮完整结束（rounds>=1 且 round-1 的 AI 上下文已捕获——pre-pass 入史会先让
            // rounds 计数前进，LLM 输出稍后到达，须等两者都就绪再取 before）
            await(() -> groupRounds(sim, "P+A") >= 1 && captured.size() >= 1, "tick 建组 + 第一轮完成", 15);

            // 玩家在组内再次输入：sendUserMessage 应唤醒等待中的组（AI-user 解卡，输入=点击）
            int before = captured.size();
            sim.sendUserMessage("P", "再说说看");
            await(() -> groupRounds(sim, "P+A") >= 2 && captured.size() > before, "玩家输入唤醒等待组生成下一轮", 15);

            List<String> round2 = new ArrayList<>(captured.subList(before, captured.size()));
            assertFalse(round2.isEmpty(), "第二轮应有 AI 输出");
            for (String ctx : round2) {
                assertTrue(ctx.contains("再说说看"),
                        "输入消费当轮上下文应包含玩家输入（调序入史），实际片段：" + ctx);
            }
            assertTrue(sim.isPlayerControlled("P"), "玩家控制标记应保留");
        } finally {
            sim.stop();
        }
    }
}
