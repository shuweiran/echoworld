package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.core.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P-0815-E 需求3：/api/simulation/events 刷新慢 + 界面卡顿优化验证——
 * ① SSE world_snapshot：世界 200ms/tick，位置每 tick 广播（200ms）；
 * ② SSE 事件载荷附 recentConversations：2D 聊天消息即时推送（不再等 3s 轮询）。
 *
 * <p>验证手段（沿用 SSEControllerSessionTest 先例）：SseEmitter.send 在未绑定响应前把事件
 * 缓冲到父类 ResponseBodyEmitter 私有字段 earlySendAttempts，反射读取；SSE 事件文本格式
 * 每事件恰含一行 "event:world_snapshot"，按出现次数精确计数广播事件数。
 */
class SimulationSseThrottleTest {

    /** 反射读取 emitter 缓冲的全部事件文本（未绑定响应时 send 缓冲于此）。 */
    private static String earlyAttemptText(SseEmitter emitter) throws Exception {
        Field f = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        f.setAccessible(true);
        Collection<?> attempts = (Collection<?>) f.get(emitter);
        StringBuilder sb = new StringBuilder();
        for (Object a : attempts) {
            Field df = a.getClass().getDeclaredField("data");
            df.setAccessible(true);
            Object data = df.get(a);
            if (data != null) sb.append(data);
        }
        return sb.toString();
    }

    private static int countEvents(String text, String eventName) {
        int count = 0, idx = 0;
        String needle = "event:" + eventName;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    @DisplayName("① SSE world_snapshot：真实世界跑 ~1.5s（≈7 tick），广播事件数接近 tick 数")
    void sseSnapshotBroadcastIsThrottled() throws Exception {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("A", "测试人格A"), "test", null), 10, 10, 200.0, 50.0);
        world.registerAgent(new Agent(new Persona("B", "测试人格B"), "test", null), 100, 100, 200.0, 50.0);
        SimulationController ctl = new SimulationController(
                mock(SimulationService.class), world, mock(CharacterController.class), null);

        SseEmitter emitter = ctl.eventStream(); // 注册连接（recentSnapshots 空 → 无初始事件）
        world.start();
        try {
            Thread.sleep(1500); // 200ms/tick → 约 7 tick
        } finally {
            world.stop();
        }

        int ticks = world.getTickCount();
        String text = earlyAttemptText(emitter);
        int events = countEvents(text, "world_snapshot");

        assertTrue(ticks >= 4, "世界应已跑至少 4 tick，实际 " + ticks);
        // 实时位置语义：每 tick 广播，允许少量调度丢帧但不应退化到 400ms 一次
        assertTrue(events >= 1, "至少 1 次广播");
        assertTrue(events >= ticks / 2,
                "广播不应低于约半数 tick：events=" + events + " ticks=" + ticks);
    }

    @Test
    @DisplayName("② SSE 事件载荷附 recentConversations：2D 聊天消息即时推送（不再等 3s 轮询）")
    void sseEventIncludesRecentConversations() throws Exception {
        SimulationWorld world = new SimulationWorld();
        world.registerAgent(new Agent(new Persona("A", "测试人格A"), "test", null), 10, 10, 200.0, 50.0);
        world.registerAgent(new Agent(new Persona("B", "测试人格B"), "test", null), 100, 100, 200.0, 50.0);
        SimulationController ctl = new SimulationController(
                mock(SimulationService.class), world, mock(CharacterController.class), null);

        SseEmitter emitter = ctl.eventStream();
        world.addConversationEntry(Map.of("speaker", "A", "message", "你好，这是测试消息"));
        world.start();
        try {
            Thread.sleep(800); // 足够跨过 ≥1 次节流广播（400ms）
        } finally {
            world.stop();
        }

        String text = earlyAttemptText(emitter);
        assertTrue(text.contains("recentConversations"),
                "SSE 事件应携带 recentConversations 键，实际: " + truncate(text));
        assertTrue(text.contains("你好，这是测试消息"),
                "SSE 事件应携带聊天消息内容（前端即时入列），实际: " + truncate(text));
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }
}
