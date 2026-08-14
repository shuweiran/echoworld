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
 * P-0815-A（调研报告-移动与分组问题.md 2.4 #2）：组类型字段——ConversationGroup 加 kind
 * （AI_AUTO / USER_JOINED / SCRIPT_DISCUSSION / WEREWOLF_DISCUSSION），getStatus 下发 kind
 * + participantInfo（逐项附 isPlayer 标记）；participants string[] 旧契约不动（前端 includes 兼容）。
 */
class GroupKindStatusTest {

    private static final long NOW = 1_000_000L;

    private void register(SimulationWorld world, String name, double x, double y) {
        Persona persona = new Persona(name, "测试人格" + name);
        Agent agent = new Agent(persona, "test", null);
        world.registerAgent(agent, x, y, 200.0, 50.0);
    }

    private ConversationManager manager(SimulationWorld world) {
        ConversationManager cm = new ConversationManager();
        cm.init(world, null, world::getAgent, world::getWorldNarration);
        cm.setTrackDirector(new TrackDirectorService());
        cm.setGoalSupplier(() -> Map.of());
        return cm;
    }

    /** 场景：A(0,0) B(3,0) 组内；P(2,0) 玩家。 */
    private SimulationWorld worldGroupPlusPlayer() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "B", 3, 0);
        register(world, "P", 2, 0);
        world.getState("P").setPlayerControlled(true);
        return world;
    }

    @Test
    @DisplayName("① 默认 kind=AI_AUTO：直接构造的组不标记任何加入语义")
    void defaultKind_isAiAuto() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationGroup g = new ConversationGroup("g", ConversationMode.GROUP_DISCUSSION,
                List.of(world.getState("A"), world.getState("B")));
        assertEquals(GroupKind.AI_AUTO, g.getKind(), "新建组默认 AI_AUTO");
    }

    @Test
    @DisplayName("② 剧本杀讨论组 → SCRIPT_DISCUSSION（createScriptDiscussionGroup）")
    void scriptDiscussionGroup_kind() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm = manager(world);
        ConversationGroup g = cm.createScriptDiscussionGroup("g1",
                List.of(world.getState("A"), world.getState("B")), Map.of());
        assertEquals(GroupKind.SCRIPT_DISCUSSION, g.getKind(), "剧本杀讨论组 → SCRIPT_DISCUSSION");
        assertEquals(GroupKind.SCRIPT_DISCUSSION, cm.getActiveGroups().iterator().next().getKind());
    }

    @Test
    @DisplayName("③ 玩家 joinGroup → USER_JOINED")
    void joinGroup_kind_becomesUserJoined() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm = manager(world);
        cm.createScriptDiscussionGroup("g1",
                List.of(world.getState("A"), world.getState("B")), Map.of());

        ConversationManager.JoinResult r = cm.joinGroup("g1", "P");

        assertTrue(r.success(), r.message());
        assertEquals(GroupKind.USER_JOINED, r.group().getKind(), "玩家加入后组标记 USER_JOINED");
        assertEquals(GroupKind.USER_JOINED, cm.getActiveGroups().iterator().next().getKind());
    }

    @Test
    @DisplayName("④ getStatus 下发 kind + participantInfo（isPlayer 逐项标记），participants 旧契约不动")
    void getStatus_exposesKindAndParticipantInfo() {
        SimulationWorld world = worldGroupPlusPlayer();
        ConversationManager cm = manager(world);
        cm.createScriptDiscussionGroup("g1",
                List.of(world.getState("A"), world.getState("B"), world.getState("P")), Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) cm.getStatus().get("groups");
        assertEquals(1, groups.size());
        Map<String, Object> gs = groups.get(0);

        assertEquals("SCRIPT_DISCUSSION", gs.get("kind"), "getStatus 应下发组类型");
        // 旧契约：participants 保持 string[]（前端 includes 消费兼容）
        @SuppressWarnings("unchecked")
        List<String> participants = (List<String>) gs.get("participants");
        assertEquals(List.of("A", "B", "P"), participants);
        // 新契约：participantInfo 逐项附 isPlayer
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> info = (List<Map<String, Object>>) gs.get("participantInfo");
        assertEquals(3, info.size());
        assertEquals(Map.of("name", "A", "isPlayer", false), info.get(0));
        assertEquals(Map.of("name", "B", "isPlayer", false), info.get(1));
        assertEquals(Map.of("name", "P", "isPlayer", true), info.get(2), "玩家成员 isPlayer=true");
    }

    @Test
    @DisplayName("⑤ 玩家消息自动 DYAD（tick）→ kind 保持 AI_AUTO（非 USER_JOINED）")
    void playerAutoDyad_kindStaysAiAuto() {
        SimulationWorld world = new SimulationWorld();
        register(world, "A", 0, 0);
        register(world, "P", 3, 0);
        world.getState("P").setPlayerControlled(true);
        world.getState("P").setCurrentMessage("你好");
        ConversationManager cm = manager(world);

        try {
            cm.tick(NOW);   // 玩家消息 → 自动 DYAD（P+A，3px < MAX_AUTO_DYAD_DISTANCE）
            List<ConversationGroup> groups = new java.util.ArrayList<>(cm.getActiveGroups());
            assertEquals(1, groups.size(), "tick 应建玩家 DYAD 组");
            assertEquals(GroupKind.AI_AUTO, groups.get(0).getKind(),
                    "系统自动建的 DYAD 组 kind=AI_AUTO（不是玩家手动加入）");
        } finally {
            cm.stopAll();
        }
    }
}
