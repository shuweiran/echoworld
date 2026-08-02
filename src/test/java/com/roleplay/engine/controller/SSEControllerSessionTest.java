package com.roleplay.engine.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0802-I：SSEController 会话定向推送测试 —— stream(?session_id=) 注册过滤，
 * broadcastToSession 只送达匹配会话的连接，全局 broadcast 仍全量送达（向后兼容）。
 *
 * <p>验证手段：SseEmitter.send 在未绑定响应前把事件缓冲到父类 ResponseBodyEmitter 私有字段
 * earlySendAttempts（Spring 6.2 为 LinkedHashSet），测试经反射读取计数并用增量断言
 * （单次事件可能拆成多个 part，绝对计数不可用；增量 = 本连接实际收到该事件的 part 数）。
 * 反射先例：D27 检查用反射验证绑定生效。
 */
class SSEControllerSessionTest {

    private static int attemptCount(SseEmitter emitter) throws Exception {
        Field f = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        f.setAccessible(true);
        return ((java.util.Collection<?>) f.get(emitter)).size();
    }

    @Test
    @DisplayName("P1: 定向推送 —— broadcastToSession 只送达注册该 session 的连接，其他会话零接收")
    void targetedDeliveryOnlyReachesMatchingSession() throws Exception {
        SSEController ctl = new SSEController();
        SseEmitter sessA1 = ctl.stream("sessA");
        SseEmitter sessA2 = ctl.stream("sessA");
        SseEmitter sessB = ctl.stream("sessB");
        SseEmitter noSession = ctl.stream(null); // 无过滤连接

        assertEquals(2, ctl.getConnectionCount("sessA"), "会话 A 两条连接");
        assertEquals(1, ctl.getConnectionCount("sessB"), "会话 B 一条连接");
        assertEquals(4, ctl.getConnectionCount(), "总连接 4 条");

        // 定向 A：A 两条连接收到，B/无过滤连接零接收
        int a1Before = attemptCount(sessA1);
        ctl.broadcastToSession("sessA", "werewolf_phase", Map.of("phase", "night"));
        assertTrue(attemptCount(sessA1) > a1Before, "A1 收到定向事件");
        assertTrue(attemptCount(sessA2) > a1Before, "A2 收到定向事件");
        assertEquals(0, attemptCount(sessB), "B 不收到 A 的事件（互不串扰）");
        assertEquals(0, attemptCount(noSession), "无过滤连接不收到定向事件");

        // 无匹配会话：静默丢弃不抛异常
        assertDoesNotThrow(() -> ctl.broadcastToSession("no-such", "werewolf_phase", Map.of("phase", "night")));

        // 定向到 B：只有 B 收到
        int bBefore = attemptCount(sessB);
        int a1Before2 = attemptCount(sessA1);
        ctl.broadcastToSession("sessB", "werewolf_phase", Map.of("phase", "day_vote"));
        assertTrue(attemptCount(sessB) > bBefore, "B 收到自己的事件");
        assertEquals(a1Before2, attemptCount(sessA1), "A1 不收到 B 的事件");
        assertEquals(a1Before2, attemptCount(sessA2), "A2 不收到 B 的事件");

        // 全局 broadcast 仍然全量送达（向后兼容：agent_output/announcement 等不因过滤丢失）
        int a1Before3 = attemptCount(sessA1);
        int bBefore2 = attemptCount(sessB);
        int nsBefore = attemptCount(noSession);
        ctl.broadcast("announcement", Map.of("text", "hi"));
        assertTrue(attemptCount(sessA1) > a1Before3, "全局广播仍送达 A1");
        assertTrue(attemptCount(sessB) > bBefore2, "全局广播仍送达 B");
        assertTrue(attemptCount(noSession) > nsBefore, "全局广播送达无过滤连接");

        // 清理：complete 触发移除由容器回调承担（无响应绑定时 complete 仅标记，测试不依赖）；
        // 关键验证：complete 后定向投递不抛异常
        sessA1.complete();
        sessA2.complete();
        sessB.complete();
        noSession.complete();
        assertDoesNotThrow(() -> ctl.broadcastToSession("sessA", "werewolf_phase", Map.of("phase", "ended")));
    }

    @Test
    @DisplayName("P2: 空 session_id 定向回退全局广播；连接清理后不再接收")
    void blankSessionFallsBackToGlobalAndCleanup() throws Exception {
        SSEController ctl = new SSEController();
        SseEmitter em1 = ctl.stream("sessX");
        SseEmitter em2 = ctl.stream("");

        // 空/空白 session_id → 回退全局（与旧版一致）
        int em1Before = attemptCount(em1);
        int em2Before = attemptCount(em2);
        ctl.broadcastToSession("", "werewolf_phase", Map.of("phase", "night"));
        assertTrue(attemptCount(em1) > em1Before, "空白 session 定向回退全局 → 有过滤连接也收到");
        assertTrue(attemptCount(em2) > em2Before, "空白 session 定向回退全局 → 无过滤连接收到");

        // complete 后不再接收（连接从注册表移除由容器回调承担；此处验证 complete 后定向投递不抛异常）
        em1.complete();
        int em1After = attemptCount(em1);
        ctl.broadcastToSession("sessX", "werewolf_phase", Map.of("phase", "day_vote"));
        assertEquals(em1After, attemptCount(em1), "已 complete 的连接不再收到（且不抛异常）");
        em2.complete();
    }

    @Test
    @DisplayName("P3: script_* 定向推送（P-0802-J）—— broadcastScriptPhase/Status/Reveal 只送达注册该 session 的连接，全局广播仍全量送达")
    void scriptEventsAreSessionTargeted() throws Exception {
        SSEController ctl = new SSEController();
        SseEmitter sessA = ctl.stream("scriptA");
        SseEmitter sessB = ctl.stream("scriptB");
        SseEmitter noSession = ctl.stream(null); // 无过滤连接

        // script_phase 定向 A：A 收到，B/无过滤连接零接收
        int aBefore = attemptCount(sessA);
        ctl.broadcastScriptPhase("scriptA", "investigation");
        assertTrue(attemptCount(sessA) > aBefore, "A 收到 script_phase（定向）");
        assertEquals(0, attemptCount(sessB), "B 不收到 A 的 script_phase（多局互不串扰）");
        assertEquals(0, attemptCount(noSession), "无过滤连接不收到定向 script_phase");

        // script_status 定向 B：只有 B 收到
        int bBefore = attemptCount(sessB);
        int aBefore2 = attemptCount(sessA);
        ctl.broadcastScriptStatus("scriptB", Map.of("phase", "discussion", "players", java.util.List.of("x")));
        assertTrue(attemptCount(sessB) > bBefore, "B 收到 script_status（定向）");
        assertEquals(aBefore2, attemptCount(sessA), "A 不收到 B 的 script_status");
        assertEquals(0, attemptCount(noSession), "无过滤连接不收到定向 script_status");

        // script_reveal 定向 A
        int aBefore3 = attemptCount(sessA);
        int bBefore3 = attemptCount(sessB);
        ctl.broadcastScriptReveal("scriptA", Map.of("result", "approved", "most_voted", "管家"));
        assertTrue(attemptCount(sessA) > aBefore3, "A 收到 script_reveal（定向）");
        assertEquals(bBefore3, attemptCount(sessB), "B 不收到 A 的 script_reveal");

        // 全局广播仍全量送达（announcement/agent_output 等不因 script 定向过滤丢失）
        int aBefore4 = attemptCount(sessA);
        int bBefore2 = attemptCount(sessB);
        int nsBefore = attemptCount(noSession);
        ctl.broadcast("announcement", Map.of("text", "hi"));
        assertTrue(attemptCount(sessA) > aBefore4, "全局广播仍送达 A");
        assertTrue(attemptCount(sessB) > bBefore2, "全局广播仍送达 B");
        assertTrue(attemptCount(noSession) > nsBefore, "全局广播送达无过滤连接");

        sessA.complete();
        sessB.complete();
        noSession.complete();
    }
}
