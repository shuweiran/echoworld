package com.roleplay.engine.broadcast;

import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.interrupt.GameEvent;
import com.roleplay.engine.interrupt.WorldEventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 演讲+广播合并地基核心测试（调研报告落地计划 Step 1 验收）。
 *
 * <p>覆盖：优先级队列（SYSTEM&gt;EVENT&gt;PLAYER&gt;NPC）、滑动窗口节流、同 key 合并
 * （×N 防刷屏）、AI 自动选择（有听众→演讲 area / 无听众→全局公告）、
 * WorldEventBus 进程内分发、SSE 推送、断线补发缓冲。
 *
 * <p>直接构造 AnnouncementService（记录型假 SseBroadcaster + 真实 WorldEventBus），
 * 手动调 flush()（@PostConstruct 调度器只在 Spring 容器中启动，单元测试不受 100ms 节拍影响）。
 */
class AnnouncementServiceTest {

    /** 记录型假 SSE 广播器：记录 (eventType, payload)。 */
    private static class RecordingBroadcaster implements SseBroadcaster {
        final List<Map.Entry<String, Object>> pushes = new ArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            pushes.add(Map.entry(eventType, data));
        }
    }

    /** 测试夹具：service + 记录器。 */
    private static class Harness {
        final RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        final AnnouncementService service;

        Harness(AppConfig config) {
            AppConfig cfg = config != null ? config : new AppConfig();
            service = new AnnouncementService(broadcaster, new WorldEventBus(), cfg);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastPayload(Harness h) {
        assertFalse(h.broadcaster.pushes.isEmpty(), "should have pushed to SSE");
        assertEquals(AnnouncementService.SSE_EVENT, h.broadcaster.pushes.get(h.broadcaster.pushes.size() - 1).getKey());
        return (Map<String, Object>) h.broadcaster.pushes.get(h.broadcaster.pushes.size() - 1).getValue();
    }

    @Test
    @DisplayName("优先级：SYSTEM > EVENT > PLAYER > NPC，乱序入队、按序出队")
    void priorityOrder() {
        Harness h = new Harness(null);
        // 故意乱序入队：NPC → PLAYER → SYSTEM
        h.service.enqueue(BroadcastMessage.of(BroadcastMessage.Level.NPC, "global", "路人", "闲聊", -1, -1, 0, "announcement"));
        h.service.enqueue(BroadcastMessage.of(BroadcastMessage.Level.PLAYER, "global", "玩家", "喊话", -1, -1, 0, "announcement"));
        h.service.enqueue(BroadcastMessage.of(BroadcastMessage.Level.SYSTEM, "system", "system", "阶段切换", -1, -1, 0, "announcement"));

        h.service.flush();
        List<Map<String, Object>> pushed = h.broadcaster.pushes.stream()
                .map(e -> (Map<String, Object>) e.getValue())
                .toList();
        assertEquals(3, pushed.size());
        assertAll("优先级升序出队",
                () -> assertEquals("SYSTEM", pushed.get(0).get("level")),
                () -> assertEquals("PLAYER", pushed.get(1).get("level")),
                () -> assertEquals("NPC", pushed.get(2).get("level")));
        assertEquals(0, h.service.pendingCount());
    }

    @Test
    @DisplayName("滑动窗口节流：同 channel 每窗口最多 5 条，第 6 条丢弃")
    void throttle() {
        AppConfig cfg = new AppConfig();
        cfg.getBroadcast().setMaxPerWindow(5);
        Harness h = new Harness(cfg);
        int accepted = 0;
        for (int i = 0; i < 6; i++) {
            BroadcastMessage m = h.service.enqueue(BroadcastMessage.of(
                    BroadcastMessage.Level.NPC, "global", "路人" + i, "第" + i + "条", -1, -1, 0, "announcement"));
            if (m != null) accepted++;
        }
        assertEquals(5, accepted, "窗口内只放行 5 条");
        assertEquals(5, h.service.pendingCount());
    }

    @Test
    @DisplayName("同 key 合并：同人同 channel 连续发言合并为 ×N，不重复入队")
    void coalesce() {
        Harness h = new Harness(null);
        h.service.enqueue(BroadcastMessage.of(BroadcastMessage.Level.NPC, "global", "小明", "第一条", -1, -1, 0, "announcement"));
        h.service.enqueue(BroadcastMessage.of(BroadcastMessage.Level.NPC, "global", "小明", "第二条", -1, -1, 0, "announcement"));
        assertEquals(1, h.service.pendingCount(), "同 key 只入队首条");
        h.service.flush();
        Map<String, Object> payload = lastPayload(h);
        assertEquals("第一条（×2）", payload.get("text"));
    }

    @Test
    @DisplayName("AI 自动选择：有听众 → 演讲（area+半径），无听众 → 全局公告")
    void autoSelectSpeechVsBroadcast() {
        Harness h = new Harness(null);

        h.service.enqueueAutoSpeech("小明", "大家听我说！", 100, 200, 250, true);
        h.service.flush();
        Map<String, Object> speech = lastPayload(h);
        assertEquals("speech", speech.get("mode"));
        assertEquals("area", speech.get("channel"));
        assertEquals(100.0, speech.get("x"));
        assertEquals(200.0, speech.get("y"));
        assertEquals(250.0, speech.get("radius"));

        h.service.enqueueAutoSpeech("小明", "紧急通知！", 100, 200, 250, false);
        h.service.flush();
        Map<String, Object> broadcast = lastPayload(h);
        assertEquals("announcement", broadcast.get("mode"));
        assertEquals("global", broadcast.get("channel"));
        assertEquals(0.0, broadcast.get("radius"));
    }

    @Test
    @DisplayName("WorldEventBus 进程内分发：flush 发布 TYPE_ANNOUNCEMENT 事件，订阅方可收到")
    void eventBusDispatch() {
        WorldEventBus bus = new WorldEventBus();
        AtomicReference<GameEvent> received = new AtomicReference<>();
        bus.subscribe(GameEvent.TYPE_ANNOUNCEMENT, received::set);
        AnnouncementService svc = new AnnouncementService(new RecordingBroadcaster(), bus, new AppConfig());

        svc.enqueue(BroadcastMessage.of(BroadcastMessage.Level.SYSTEM, "system", "system", "天黑请闭眼", -1, -1, 0, "announcement"));
        svc.flush();

        GameEvent evt = received.get();
        assertNotNull(evt, "订阅方应收到 ANNOUNCEMENT 事件");
        assertEquals(GameEvent.TYPE_ANNOUNCEMENT, evt.getType());
        assertEquals("天黑请闭眼", evt.getPayload().get("text"));
    }

    @Test
    @DisplayName("SSE 推送：announcement 事件载荷含完整字段（玩家广播路径）")
    void ssePushPayload() {
        Harness h = new Harness(null);
        h.service.enqueue(BroadcastMessage.of(BroadcastMessage.Level.PLAYER, "global", "玩家", "全服公告", -1, -1, 0, "announcement"));
        h.service.flush();

        Map<String, Object> payload = lastPayload(h);
        assertEquals("PLAYER", payload.get("level"));
        assertEquals("global", payload.get("channel"));
        assertEquals("玩家", payload.get("speaker"));
        assertEquals("announcement", payload.get("mode"));
        assertEquals("全服公告", payload.get("text"));
        assertNotNull(payload.get("id"));
        assertNotNull(payload.get("timestamp"));
    }

    @Test
    @DisplayName("断线补发：recentSince 只返回 since 之后的消息")
    void recentSince() {
        Harness h = new Harness(null);
        // 显式时间戳（确定性，避免毫秒级竞态）：msg1@1000，msg2@2000
        BroadcastMessage m1 = new BroadcastMessage("id-1", BroadcastMessage.Level.SYSTEM, "system", "system",
                "第一条", -1, -1, 0, "announcement", "sys|system", 1000L);
        BroadcastMessage m2 = new BroadcastMessage("id-2", BroadcastMessage.Level.PLAYER, "global", "玩家",
                "第二条", -1, -1, 0, "announcement", "玩家|global", 2000L);
        h.service.enqueue(m1);
        h.service.flush();
        h.service.enqueue(m2);
        h.service.flush();

        assertEquals(2, h.service.recentSince(0).size(), "since=0 应返回全部");
        List<BroadcastMessage> after = h.service.recentSince(1500);
        assertEquals(1, after.size(), "since=1500 只返回其后一条");
        assertEquals("玩家", after.get(0).speaker());
        assertTrue(h.service.recentSince(2000).isEmpty(), "since=2000 应为空（严格大于）");
    }
}
