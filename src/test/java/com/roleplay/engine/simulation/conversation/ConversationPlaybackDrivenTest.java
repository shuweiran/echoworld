package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0814-A：2D 空间轨道对话点击驱动（ConversationManager 组轮间门）——roleplay.round.playback-driven。
 *
 * <p>核心语义：玩家拉入轨道（本测试走真实 tick 的玩家消息 → DYAD 建组路径，与生产 2D 玩家流程
 * 同链路）后一轮生成完即停，不再按 pacing 间隔自动连跑（不再「拉入对应轨道即自动对话」）；
 * 收到「播出完毕」信号（notifyPlaybackDone）才生成下一轮；无玩家时同样播完即停。
 * 未注入 playback-driven 的实例（剧本杀/狼人杀各局 CM）保持旧行为（pacing 间隔自动连跑）。
 *
 * <p>harness：零 Spring——SimulationWorld + mock LLM Agent + cm.tick 驱动真实建组与后台轮次循环
 * （与生产 startGroup 后台循环同路径；玩家消息路径不依赖空间网格，确定性建组）。
 */
class ConversationPlaybackDrivenTest {

    private static final long NOW = 1_000_000L;

    private void register(SimulationWorld world, String name, double x, double y, LLMClient llm) {
        Persona persona = new Persona(name, "测试人格" + name);
        Agent agent = new Agent(persona, "test", llm);
        world.registerAgent(agent, x, y, 200.0, 50.0);
    }

    private ConversationManager manager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        return cm;
    }

    /**
     * mock LLM：记录每次调用收到的上下文全文（拼接全部消息内容），返回固定回应。
     * captured 供「玩家输入影响下一轮」断言。
     */
    private LLMClient mockLlm(List<String> captured) {
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

    /** 轮询直到条件满足（超时 fail）。 */
    private static void await(java.util.function.BooleanSupplier cond, String what, long timeoutSec) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                org.junit.jupiter.api.Assertions.fail("应在 " + timeoutSec + "s 内等到: " + what);
            }
            Thread.sleep(20);
        }
    }

    private static List<ConversationGroup> groupsOf(ConversationManager cm) {
        return new ArrayList<>(cm.getActiveGroups());
    }

    /** 场景：NPC A + 玩家 P（玩家消息触发 tick 自动建 DYAD，与生产 2D 玩家流程同链路）。 */
    private SimulationWorld worldNpcPlusPlayer(LLMClient llm) {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0, llm);
        register(world, "P", 2, 0, llm);
        world.getState("P").setPlayerControlled(true);
        return world;
    }

    /** tick 建 DYAD 组并等待第一轮完成（P-0814-B：等 isAwaitingPlayback——
     *  轮次完整结束（含 LLM 输出入史）后才返回，避免仅在 pre-pass 计数后提前返回的时序竞态）。 */
    private ConversationGroup startGroupAndFirstRound(ConversationManager cm, SimulationWorld world) throws Exception {
        cm.tick(NOW); // 玩家消息 → DYAD（P+最近空闲 NPC）
        await(() -> groupsOf(cm).size() == 1, "tick 建组", 5);
        ConversationGroup g = groupsOf(cm).get(0);
        await(() -> g.isAwaitingPlayback(), "第一轮完成并进入等待播出完毕", 10);
        return g;
    }

    // ── ① 一轮生成完即停（不自动连跑） ──

    @Test
    @DisplayName("① playback-driven：组一轮生成完即停——无信号不跑第二轮（不再拉入轨道即自动对话）")
    void group_roundCompletes_thenStops() throws Exception {
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(new ArrayList<>()));
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        world.getState("P").setCurrentMessage("你好");

        ConversationGroup g = startGroupAndFirstRound(cm, world);
        assertTrue(g.isAwaitingPlayback(), "一轮完应进入等待播出完毕状态");

        // 睡过默认 DYAD 轮次间隔（2000ms）——若无门控第二轮早已出现
        Thread.sleep(600);
        assertEquals(1, g.getRoundCount(), "无信号不自动跑第二轮（点击驱动，不再自动连跑）");
        cm.stopAll();
    }

    // ── ② 播出完毕信号 → 下一轮 ──

    @Test
    @DisplayName("② notifyPlaybackDone（播出完毕信号）→ 该组生成下一轮（一轮=组内成员各一句）")
    void playbackDone_triggersNextRound() throws Exception {
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(new ArrayList<>()));
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        world.getState("P").setCurrentMessage("你好");

        ConversationGroup g = startGroupAndFirstRound(cm, world);
        assertTrue(g.isAwaitingPlayback());

        assertTrue(cm.notifyPlaybackDone(g.getGroupId()), "播出完毕信号应送达等待中的组");
        await(() -> g.getRoundCount() >= 2, "信号驱动第二轮", 10);
        assertEquals(2, g.getRoundCount(), "第二轮已生成（成员各一句）");
        cm.stopAll();
    }

    // ── ③ 未知组 no-op；连点不丢信号（持续置位） ──

    @Test
    @DisplayName("③ 幂等：未知组 no-op；等待中连点 N 个信号逐轮消费不丢（持续置位，每信号至多推进一轮）")
    void playbackDone_unknownOrRepeated_signalsNotLost() throws Exception {
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(new ArrayList<>()));
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        world.getState("P").setCurrentMessage("你好");

        assertFalse(cm.notifyPlaybackDone("不存在的组"), "未知组信号应 false");

        ConversationGroup g = startGroupAndFirstRound(cm, world);
        await(() -> g.isAwaitingPlayback(), "进入等待播出完毕", 5);

        // P-0814-B（信号计数持续置位）：等待中连点 2 次 —— 每信号至多推进一轮、不丢轮
        // （2 人组 roundCount=(turnCount+1)/2：r1=P+A=2turns、r2=A=3turns、r3=A=4turns）
        cm.notifyPlaybackDone(g.getGroupId());
        cm.notifyPlaybackDone(g.getGroupId());
        await(() -> g.getTurnCount() == 4 && g.isAwaitingPlayback(),
                "两个信号均消费且重新进入稳定等待态", 10);
        assertEquals(4, g.getTurnCount(), "连点不丢信号：恰 2 信号=2 轮（无多余轮次）");
        assertEquals(2, g.getRoundCount(), "三轮后 2 人组 roundCount=2（turn 计数语义）");
        cm.stopAll();
    }

    // ── ④ stopAll 唤醒等待者（防线程悬挂） ──

    @Test
    @DisplayName("④ stopAll 唤醒等待中的组循环——组被解散、线程退出（防悬挂）")
    void stopAll_wakesPlaybackWaiters() throws Exception {
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(new ArrayList<>()));
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        world.getState("P").setCurrentMessage("你好");

        ConversationGroup g = startGroupAndFirstRound(cm, world);
        assertTrue(g.isAwaitingPlayback(), "等待播出完毕");

        cm.stopAll();
        await(() -> groupsOf(cm).isEmpty(), "stopAll 后组被解散", 5);
        assertFalse(g.isActive(), "组应标记为非活跃");
        assertEquals(1, g.getRoundCount(), "stop 后不再有新轮次");
    }

    // ── ⑤ 未注入 playback-driven → 旧行为（pacing 间隔自动连跑；剧本杀/狼人杀实例同款） ──

    @Test
    @DisplayName("⑤ 未注入 playback-driven（剧本杀/狼人杀各局 CM 同款）：按 pacing 间隔自动连跑，零影响")
    void notInjected_oldBehaviorAutoRuns() throws Exception {
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(new ArrayList<>()));
        ConversationManager cm = manager(world); // 不调用 setPlaybackDriven
        cm.setPacing(true, 200, 200, 8_000, 1.0, 1.0); // 缩短轮次间隔加速断言
        world.getState("P").setCurrentMessage("你好");

        cm.tick(NOW);
        await(() -> groupsOf(cm).size() == 1, "tick 建组", 5);
        ConversationGroup g = groupsOf(cm).get(0);

        await(() -> g.getRoundCount() >= 2, "旧行为自动连跑第二轮", 10);
        assertFalse(g.isAwaitingPlayback(), "旧行为不进入等待态");
        cm.stopAll();
    }

    // ── ⑥ 玩家输入消费当轮即进上下文（P-0814-B 调序修复：不再错位一轮） ──

    @Test
    @DisplayName("⑥ 玩家输入消费当轮即进上下文：round 1 玩家发言 → round 1 NPC 上下文已包含（修复错位一轮）；历史保留至下一轮")
    void playerInput_inContextSameRound() throws Exception {
        List<String> captured = java.util.Collections.synchronizedList(new ArrayList<>());
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(captured));
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        world.getState("P").setCurrentMessage("我怀疑管家在说谎");

        ConversationGroup g = startGroupAndFirstRound(cm, world);
        // P-0814-B：玩家消息先入史再构建上下文 —— 消费该消息的当轮（round 1）NPC 上下文即含玩家输入
        assertFalse(captured.isEmpty(), "第一轮 NPC 应有 LLM 调用，实际=" + captured.size());
        for (String ctx : captured) {
            assertTrue(ctx.contains("我怀疑管家在说谎"),
                    "消费当轮（round 1）NPC 上下文应包含玩家输入（不再错位一轮），实际片段：" + ctx);
        }

        // 下一轮（信号驱动）上下文仍含该输入（历史保留，跨轮影响语义不变）
        int before = captured.size();
        assertTrue(cm.notifyPlaybackDone(g.getGroupId()), "点击推进第二轮");
        await(() -> g.getRoundCount() >= 2, "第二轮完成", 10);
        List<String> round2 = new ArrayList<>(captured.subList(before, captured.size()));
        assertFalse(round2.isEmpty(), "第二轮应有 LLM 调用");
        for (String ctx : round2) {
            assertTrue(ctx.contains("我怀疑管家在说谎"),
                    "第二轮 NPC 上下文应包含玩家输入（历史保留），实际片段：" + ctx);
        }
        cm.stopAll();
    }

    // ── ⑦ 无玩家组（AI-AI）等待超时自动解散（P-0814-B：防 awaitPlayback 永久阻塞角色冻结） ──

    @Test
    @DisplayName("⑦ AI-AI 组（无玩家）等待超时自动解散——不永久卡死、组内角色解冻")
    void aiAiGroup_awaitTimeout_dissolves() throws Exception {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0, mockLlm(new ArrayList<>()));
        register(world, "B", 1, 0, mockLlm(new ArrayList<>()));
        world.getSpatialGrid().rebuild(new ArrayList<>(world.getAllStates().values())); // 空间网格入格（tick 听觉建组前置）
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        cm.setGroupAwaitTimeoutMs(400); // 短超时加速断言

        cm.tick(NOW); // 空间听觉 → AI-AI 组
        await(() -> groupsOf(cm).size() == 1, "tick 建 AI-AI 组", 5);
        ConversationGroup g = groupsOf(cm).get(0);
        await(() -> g.getRoundCount() >= 1, "第一轮完成", 10);
        assertTrue(g.isAwaitingPlayback(), "一轮完应进入等待播出完毕");

        // 无玩家 + 等待超时 → tick 自动解散（周期 tick 驱动判定，模拟生产世界 tick）
        await(() -> {
            cm.tick(System.currentTimeMillis());
            return groupsOf(cm).isEmpty();
        }, "无玩家组等待超时自动解散", 10);
        assertFalse(g.isActive(), "解散后组应非活跃");
        List<AgentState> members = g.getParticipantList();
        assertFalse(members.isEmpty(), "解散后组对象仍保留成员（状态已清）");
        for (AgentState s : members) {
            assertFalse(s.isInConversation(), "组内角色应解冻（inConversation 恢复 false）：" + s.getAgentName());
        }
    }

    // ── ⑧ 玩家输入唤醒所在组等待（AI-user 解卡；sendUserMessage 同款链路） ──

    @Test
    @DisplayName("⑧ 玩家输入唤醒所在组等待：wakeGroupForAgent 后组生成下一轮且消费该输入（同轮入史）")
    void playerInput_wakesAwaitingGroup() throws Exception {
        List<String> captured = java.util.Collections.synchronizedList(new ArrayList<>());
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(captured));
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        world.getState("P").setCurrentMessage("你好");

        ConversationGroup g = startGroupAndFirstRound(cm, world);
        int before = captured.size();

        // 玩家在组内再次输入（SimulationService.sendUserMessage 同款：setCurrentMessage + 唤醒）
        world.getState("P").setCurrentMessage("再说说你的想法");
        assertTrue(cm.wakeGroupForAgent("P"), "唤醒应命中玩家所在组");
        await(() -> g.getRoundCount() >= 2, "玩家输入驱动下一轮（AI-user 解卡）", 10);
        await(() -> captured.size() > before, "第二轮 AI 输出到达", 10);

        List<String> round2 = new ArrayList<>(captured.subList(before, captured.size()));
        assertFalse(round2.isEmpty(), "第二轮应有 AI 输出");
        for (String ctx : round2) {
            assertTrue(ctx.contains("再说说你的想法"),
                    "输入消费当轮上下文应包含玩家输入（同轮入史），实际片段：" + ctx);
        }
        assertNull(world.getState("P").getCurrentMessage(), "玩家消息消费后应清空（单次消费）");
        cm.stopAll();
    }

    // ── ⑨ 单 AI 组（玩家离开后组内仅 1 AI）不悬挂（P-0814-B：同样走等待/超时路径） ──

    @Test
    @DisplayName("⑨ 单 AI 组（玩家离开后组内仅 1 AI）不悬挂：无玩家 → 等待超时自动解散")
    void singleAiGroup_afterPlayerLeave_noHang() throws Exception {
        SimulationWorld world = worldNpcPlusPlayer(mockLlm(new ArrayList<>()));
        ConversationManager cm = manager(world);
        cm.setPlaybackDriven(true);
        cm.setGroupAwaitTimeoutMs(400);
        world.getState("P").setCurrentMessage("你好");

        ConversationGroup g = startGroupAndFirstRound(cm, world);
        await(() -> g.isAwaitingPlayback(), "进入等待播出完毕", 5);

        // 玩家离开 → 组内仅 1 AI（leaveGroup 成员非空不解散、组继续运行）
        ConversationManager.JoinResult r = cm.leaveGroup(g.getGroupId(), "P");
        assertTrue(r.success(), "玩家应可离开：" + r.message());
        assertFalse(g.containsAgent("P"), "玩家应已离开组");

        // 仅 1 AI 的组无唤醒源 → 走无玩家等待超时路径自动解散（不悬挂）
        await(() -> {
            cm.tick(System.currentTimeMillis());
            return groupsOf(cm).isEmpty();
        }, "单 AI 组等待超时自动解散", 10);
        assertFalse(g.isActive(), "组应已解散（线程已收束，不悬挂）");
        cm.stopAll();
    }
}
