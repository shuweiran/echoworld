package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.broadcast.BroadcastMessage;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.SimulationWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 方案B（分步落地）核心测试：SpeechStrategy.processResults 内联区域广播。
 *
 * <p>覆盖：① split 模式下演讲产出 → AnnouncementService 收到 area 广播（带 speaker 坐标/半径）；
 * ② auto 模式下 SpeechStrategy 静默（方案A 回调路径接管，不重复推送）；
 * ③ 同一运行实例运行时切换（auto↔split，setSpeechMode）；
 * ④ 远近判定复用 HearingSystem（近处有听众 / 远处无人可听）。
 *
 * <p>直接构造 SpeechStrategy（非 Spring 类），AnnouncementService 用记录型假 SseBroadcaster，
 * HearingSystem 用真实 SimulationWorld（与生产 2D 世界同一实现）。
 */
class SpeechStrategySplitModeTest {

    /** 记录型假 SSE 广播器（与 AnnouncementServiceTest 同模式）。 */
    private static class RecordingBroadcaster implements SseBroadcaster {
        final List<Map.Entry<String, Object>> pushes = new ArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            pushes.add(Map.entry(eventType, data));
        }
    }

    /** 测试夹具：世界（2 近 1 远）+ 公告服务（可切模式）+ SpeechStrategy。 */
    private static class Harness {
        final SimulationWorld world = new SimulationWorld();
        final RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        final AnnouncementService service =
                new AnnouncementService(broadcaster, new WorldEventBus(), new AppConfig());
        final SpeechStrategy strategy;

        /** 布局：小明(100,100) 小红(150,100) 相距50 互听；小林(950,100) 远离所有人。 */
        Harness() {
            world.registerAgent(new Agent(new Persona("小明", "开朗年轻人"), "npc", null), 100, 100, 200, 60);
            world.registerAgent(new Agent(new Persona("小红", "温柔女孩"), "npc", null), 150, 100, 200, 60);
            world.registerAgent(new Agent(new Persona("小林", "文艺青年"), "npc", null), 950, 100, 200, 60);
            // 与生产 2D 世界一致：移动 tick 会 rebuild 空间网格，这里手动重建供听觉计算
            world.getSpatialGrid().rebuild(world.getAllStates().values());

            strategy = new SpeechStrategy(
                    name -> world.getAgent(name),
                    s -> world.getWorldNarration(),
                    new TopicManager(),
                    service,
                    world::getHearingSystem,
                    () -> world.getAllStates().values());
        }

        /** 演讲组：PUBLIC_SPEAKING 组只有 speaker 一人（ModeClassifier.determineMode 语义）。 */
        ConversationGroup speechGroup(String speaker) {
            AgentState state = world.getState(speaker);
            ConversationGroup g = new ConversationGroup("g-" + speaker, ConversationMode.PUBLIC_SPEAKING,
                    List.of(state));
            g.setCurrentSpeaker(speaker);
            return g;
        }

        /** 跑一轮 processResults（speaker 产出演讲文本）。随后手动 flush（调度器只在 Spring 容器启动）。 */
        void runSpeechRound(String speaker, String text) {
            strategy.processResults(speechGroup(speaker), Map.of(speaker, text), null);
            service.flush();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastPayload(Harness h) {
        assertFalse(h.broadcaster.pushes.isEmpty(), "应有 SSE 推送");
        assertEquals(AnnouncementService.SSE_EVENT, h.broadcaster.pushes.get(h.broadcaster.pushes.size() - 1).getKey());
        return (Map<String, Object>) h.broadcaster.pushes.get(h.broadcaster.pushes.size() - 1).getValue();
    }

    @Test
    @DisplayName("方案B split：演讲产出 → area 区域广播（speaker 坐标 + 半径 + speech 形态）")
    void splitModeEnqueuesAreaSpeechWithCoords() {
        Harness h = new Harness();
        h.service.setSpeechMode("split");
        h.runSpeechRound("小明", "诸位听我一言！我们要团结起来。");

        Map<String, Object> payload = lastPayload(h);
        assertAll("区域广播载荷",
                () -> assertEquals("area", payload.get("channel")),
                () -> assertEquals("speech", payload.get("mode")),
                () -> assertEquals("NPC", payload.get("level")),
                () -> assertEquals("小明", payload.get("speaker")),
                () -> assertEquals(100.0, payload.get("x")),
                () -> assertEquals(100.0, payload.get("y")),
                () -> assertEquals(200.0, payload.get("radius")),
                () -> assertEquals("诸位听我一言！我们要团结起来。", payload.get("text")));
    }

    @Test
    @DisplayName("默认 merged（正式版）：SpeechStrategy 静默不广播（由管线层回调判定路径接管，防双发回归）")
    void mergedDefaultStaysSilent() {
        Harness h = new Harness();
        assertEquals("merged", h.service.getSpeechMode(), "默认应为正式版 merged");
        h.runSpeechRound("小明", "诸位听我一言！");

        assertTrue(h.broadcaster.pushes.isEmpty(), "merged/auto 模式下 SpeechStrategy 不应内联广播（避免与管线层回调重复）");
    }

    @Test
    @DisplayName("同一运行实例运行时切换：merged→split 后再跑一轮即出区域广播，切回 merged 恢复静默")
    void runtimeSwitchSameInstance() {
        Harness h = new Harness();
        h.runSpeechRound("小明", "第一轮（merged）");
        assertTrue(h.broadcaster.pushes.isEmpty());

        h.service.setSpeechMode("split");
        h.runSpeechRound("小明", "第二轮（split）");
        Map<String, Object> payload = lastPayload(h);
        assertEquals("area", payload.get("channel"));
        assertEquals("第二轮（split）", payload.get("text"));
        assertFalse(h.broadcaster.pushes.isEmpty());

        h.service.setSpeechMode("merged");
        h.runSpeechRound("小明", "第三轮（merged）");
        long count = h.broadcaster.pushes.size();
        assertEquals(1, count, "切回 merged 后不再内联广播（只保留 split 轮的一条）");
    }

    @Test
    @DisplayName("远近判定复用 HearingSystem：近处听众可听 ≥1，远处（950px 外）无人可听 =0")
    void hearingDistanceJudgment() {
        Harness h = new Harness();

        AgentState nearSpeaker = h.world.getState("小明");
        assertEquals(1, h.strategy.countHearingListeners(nearSpeaker),
                "小明(100,100) 与小红(150,100) 相距50 < 有效听觉范围 → 1 名听众");

        AgentState farSpeaker = h.world.getState("小林");
        assertEquals(0, h.strategy.countHearingListeners(farSpeaker),
                "小林(950,100) 距所有人 >900px，距离衰减后不可听 → 0 名听众");
    }

    @Test
    @DisplayName("方案B split：无人可听时仍发区域广播（半径携带范围，消费侧按距离衰减自然无人展示）")
    void splitModeBroadcastsEvenWithNoAudience() {
        Harness h = new Harness();
        h.service.setSpeechMode("split");
        // 小林孤立（无听众）——方案B 无「听众判定」，演讲照样变区域广播，半径=hearRange
        h.runSpeechRound("小林", "孤身一人的演讲");

        Map<String, Object> payload = lastPayload(h);
        assertEquals("area", payload.get("channel"));
        assertEquals(950.0, payload.get("x"));
        assertEquals(200.0, payload.get("radius"));
        // 与方案A 对比点：方案A 无听众 → 全局公告（global）；方案B 恒为区域广播（area）
        assertEquals("area", payload.get("channel"));
    }
}
