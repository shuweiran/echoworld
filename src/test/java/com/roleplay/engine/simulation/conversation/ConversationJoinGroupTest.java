package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0813-K「多人交流」近期档——玩家靠近对话中的 AI 群 → 加入群对话。
 *
 * <p>覆盖（任务书 5 项）：
 * ① 加入群（成员列表含玩家 + conversation-status 反映）；
 * ② 玩家发言进群轨道（群成员收到）且单次消费（不重复回放，P-0813-K 修正）；
 * ③ 退出群（成员移除/状态恢复 + currentTrack 清空）；
 * ④ 模式守卫（剧本杀/狼人杀各局自有 ConversationManager 实例与 2D 世界隔离，join 零影响）；
 * ⑤ 玩家加入的群视为当前轨道（P-0813-F/H 节奏兼容：该轨道全速、其余 ×inactive）。
 *
 * <p>直构世界 + ConversationManager（与 ConversationJoinTest 同风格，零 Spring）；
 * 群组经 createScriptDiscussionGroup 注册（同步、无后台轮次循环，确定性）；
 * 玩家消息消费链路走 executeRound 玩家分支（与生产后台轮次循环同代码路径）。
 */
class ConversationJoinGroupTest {

    private static final long NOW = 1_000_000L;

    // ── 基建 ──────────────────────────────────────────────────

    private void register(SimulationWorld world, String name, double x, double y) {
        Persona persona = new Persona(name, "测试人格" + name);
        Agent agent = new Agent(persona, "test", null);   // null LLM: test-only
        world.registerAgent(agent, x, y, 200.0, 50.0);
    }

    private ConversationManager manager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        cm.setTrackDirector(new TrackDirectorService());
        cm.setGoalSupplier(() -> Map.of());
        return cm;
    }

    /** 场景：A(0,0) B(3,0) 组内（对话中的 AI 群）；P(2,0) 玩家（playerControlled）。 */
    private SimulationWorld worldGroupPlusPlayer() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "P", 2, 0);
        world.getState("P").setPlayerControlled(true);
        return world;
    }

    /** 注册一个 GROUP_DISCUSSION 群组（同步、无后台轮次循环）。 */
    private ConversationGroup createGroup(ConversationManager cm, SimulationWorld world,
                                          String gid, String... members) {
        List<AgentState> states = java.util.Arrays.stream(members).map(world::getState).toList();
        return cm.createScriptDiscussionGroup(gid, states, Map.of());
    }

    // ── ① 加入群 ──────────────────────────────────────────────

    @Test
    @DisplayName("① 加入群：成员列表含玩家 + conversation-status participants 反映 + currentTrack=该群")
    void join_addsPlayerToGroup_statusAndCurrentTrack() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm = manager(world);
        createGroup(cm, world, "g1", "A", "B");

        ConversationManager.JoinResult r = cm.joinGroup("g1", "P");

        assertTrue(r.success(), r.message());
        assertTrue(r.group().containsAgent("P"), "成员表应含玩家");
        assertEquals(3, r.group().getParticipantCount());
        // conversation-status 反映
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) cm.getStatus().get("groups");
        assertEquals(1, groups.size());
        @SuppressWarnings("unchecked")
        List<String> participants = (List<String>) groups.get(0).get("participants");
        assertTrue(participants.contains("P"), "status participants 应含玩家：" + participants);
        // 玩家加入的群 = 当前轨道（H 节奏语义）
        assertEquals("g1", cm.getStatus().get("currentTrack"), "玩家加入后该群应为 currentTrack");
        assertEquals("g1", cm.getCurrentPlayerGroup().getGroupId(), "getCurrentPlayerGroup 应返回玩家所在群");
    }

    // ── ② 玩家发言进群轨道（单次消费） ──────────────────────────

    @Test
    @DisplayName("② 玩家发言进群轨道：发言入 transcript 且只出现一次（单次消费，不重复回放）")
    void playerSpeech_entersGroupTrack_onceOnly() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "A", "B");
        assertTrue(cm.joinGroup("g1", "P").success());

        // 玩家发言（POST /api/simulation/send/{name} → sendUserMessage 同款语义）
        world.getState("P").setCurrentMessage("大家好，我加入讨论");
        // 跑两轮：NPC 无 LLM 生成失败被跳过；玩家分支直接消费自己的消息
        ConversationManager.ScriptDiscussionResult result = cm.runScriptDiscussionRounds(g, 2);

        List<Map<String, String>> transcript = result.transcript();
        long playerTurns = transcript.stream().filter(e -> "P".equals(e.get("speaker"))).count();
        assertEquals(1, playerTurns, "玩家发言应只出现一次（单次消费），实际轮次记录：" + transcript);
        assertTrue(transcript.stream().anyMatch(e -> "P".equals(e.get("speaker"))
                        && "大家好，我加入讨论".equals(e.get("message"))),
                "玩家消息应进群轨道：" + transcript);
        // 消费后消息缓冲已清空（下一轮不再回放）
        assertNull(world.getState("P").getCurrentMessage(), "玩家消息消费后应清空");
    }

    @Test
    @DisplayName("②b 群成员收到玩家发言：A/B 的对话记录包含玩家消息（群轨道共享）")
    void playerSpeech_visibleToGroupMembers() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "A", "B");
        assertTrue(cm.joinGroup("g1", "P").success());
        world.getState("P").setCurrentMessage("你们在聊什么？带我一个");

        ConversationManager.ScriptDiscussionResult result = cm.runScriptDiscussionRounds(g, 1);

        assertTrue(result.transcript().stream().anyMatch(e -> "P".equals(e.get("speaker"))
                        && "你们在聊什么？带我一个".equals(e.get("message"))),
                "玩家发言应入群轮次记录（成员可感知）：" + result.transcript());
        // 群消息历史（members 可见来源）同步包含
        assertTrue(g.getMessageHistory().stream().anyMatch(h -> "P".equals(h.get("speaker"))
                        && "你们在聊什么？带我一个".equals(h.get("message"))),
                "群 messageHistory 应含玩家发言（群内所有成员都能听到）");
    }

    // ── ③ 退出群 ──────────────────────────────────────────────

    @Test
    @DisplayName("③ 退出群：成员移除/状态还原 + currentTrack 清空（回自由探索）")
    void leave_removesPlayer_restoresState_clearsCurrentTrack() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = createGroup(cm, world, "g1", "A", "B");
        cm.joinGroup("g1", "P");
        assertEquals("g1", cm.getStatus().get("currentTrack"), "加入后 currentTrack=g1");

        ConversationManager.JoinResult r = cm.leaveGroup("g1", "P");

        assertTrue(r.success(), r.message());
        assertFalse(g.containsAgent("P"), "成员表应移除玩家");
        AgentState p = world.getState("P");
        assertFalse(p.isInConversation(), "玩家应还原 inConversation=false（回自由探索）");
        assertFalse(g.isFrozen("P"), "玩家应解冻");
        assertEquals(2, g.getParticipantCount(), "AI 群组与剩余成员存活");
        assertEquals("", cm.getStatus().get("currentTrack"), "离开后 currentTrack 应清空（无玩家轨道）");
        assertNull(cm.getCurrentPlayerGroup(), "getCurrentPlayerGroup 应返回 null");
    }

    // ── ④ 模式守卫（剧本杀/狼人杀零影响） ────────────────────────

    @Test
    @DisplayName("④ 模式守卫：剧本杀/狼人杀各局自有 ConversationManager 与 2D 世界隔离")
    void modeGuard_scriptGameManagerIsolatedFrom2dJoin() {
        // 2D 世界 manager（模拟 SimulationService 持有的实例）
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm2d = manager(world);
        createGroup(cm2d, world, "g2d", "A", "B");

        // 剧本杀/狼人杀对局：独立 manager + 独立 world（per-game 实例，不共享 2D 世界）
        SimulationWorld scriptWorld = new SimulationWorld();
        register(scriptWorld, "凶手", 0, 0);
        register(scriptWorld, "侦探", 3, 0);
        ConversationManager cmScript = manager(scriptWorld);
        cmScript.createScriptDiscussionGroup("discuss-1",
                List.of(scriptWorld.getState("凶手"), scriptWorld.getState("侦探")), Map.of());
        assertEquals(1, cmScript.getActiveGroupCount(), "剧本杀讨论组已建立");

        // ① 2D join 看不到剧本杀讨论组（不同实例，activeGroups 互不可见）
        ConversationManager.JoinResult r = cm2d.joinGroup("discuss-1", "P");
        assertFalse(r.success(), "2D manager 不应能找到剧本杀讨论组");
        assertTrue(r.message().contains("not found"), r.message());

        // ② 2D join 不改变剧本杀 manager 的任何状态
        assertEquals(1, cmScript.getActiveGroupCount(), "剧本杀讨论组不受 2D join 影响");
        assertEquals("discuss-1", cmScript.getActiveGroups().iterator().next().getGroupId());
        assertTrue(cmScript.getActiveGroups().iterator().next().containsAgent("凶手"));
        // ③ 剧本杀玩家名不在 2D 世界 → join 被拒（模式守卫：仅 2D 世界成员可加入）
        ConversationManager.JoinResult r2 = cm2d.joinGroup("g2d", "凶手");
        assertFalse(r2.success(), "剧本杀角色不在 2D 世界 → 拒绝");
        assertTrue(r2.message().contains("agent not found"), r2.message());
    }

    // ── ⑤ 玩家群视为当前轨道（P-0813-F/H 节奏兼容） ──────────────

    @Test
    @DisplayName("⑤ 玩家加入的群 = 当前轨道：该群全速、其他群 ×inactive（H 节奏语义）")
    void joinedGroupIsCurrentTrack_pacingFullSpeedOthersInactive() {
        SimulationWorld world = worldGroupPlusPlayer();
        // 需要第 4 个 agent 组成“其他群”
        register(world, "C", 20, 0);
        ConversationManager cm = manager(world);
        // 注入 H 默认节奏（inactive ×4；与 ConversationPacingTest 同款）
        cm.setPacing(true, 2_000, 3_000, 8_000, 1.5, 4.0);
        createGroup(cm, world, "g1", "A", "B");     // 对话中的 AI 群（玩家尚未加入）
        ConversationGroup other = createGroup(cm, world, "g2", "C", "A");

        // 未加入前：无玩家轨道 → 全轨道 idle ×1.5
        assertEquals((long) (3_000 * 1.5), cm.computeRoundCooldownMs(null, ConversationMode.GROUP_DISCUSSION),
                "未加入前无玩家轨道 → idle 节奏");

        // 玩家加入 g1 → g1 成为当前轨道
        assertTrue(cm.joinGroup("g1", "P").success());
        assertEquals("g1", cm.getCurrentPlayerGroup().getGroupId(), "加入后 g1 为当前轨道");
        assertEquals(3_000, cm.computeRoundCooldownMs(cm.getActiveGroups().stream()
                        .filter(g -> "g1".equals(g.getGroupId())).findFirst().orElseThrow(),
                ConversationMode.GROUP_DISCUSSION),
                "玩家轨道全速（3000ms，H 语义）");
        assertEquals(3_000 * 4, cm.computeRoundCooldownMs(other, ConversationMode.GROUP_DISCUSSION),
                "其他群 ×4 大幅拉长（12000ms，H 默认）");

        // 退出 → 恢复无玩家轨道（idle）
        assertTrue(cm.leaveGroup("g1", "P").success());
        assertNull(cm.getCurrentPlayerGroup(), "退出后无玩家轨道");
        assertEquals((long) (3_000 * 1.5), cm.computeRoundCooldownMs(null, ConversationMode.GROUP_DISCUSSION),
                "退出后恢复 idle 节奏");
    }
}
