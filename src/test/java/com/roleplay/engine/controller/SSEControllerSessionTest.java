package com.roleplay.engine.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0802-I：SSEController 会话定向推送测试 —— stream(?session_id=) 注册过滤，
 * broadcastToSession 只送达匹配会话的连接；载荷含 session_id 的普通 broadcast 也强制定向；
 * 剧本私聊仅送达 roleKey 认证过的双方连接。
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

    @Test
    @DisplayName("P4: 带 session_id 的普通/typed 事件由服务端强制定向，不再广播后靠前端过滤")
    void payloadSessionForcesServerSideTargeting() throws Exception {
        SSEController ctl = new SSEController();
        SseEmitter sessA = ctl.stream("sessA");
        SseEmitter sessB = ctl.stream("sessB");
        SseEmitter noSession = ctl.stream(null);

        ctl.broadcast("custom_event", Map.of("session_id", "sessA", "value", 1));
        assertTrue(attemptCount(sessA) > 0, "载荷 session_id 命中的连接收到普通事件");
        assertEquals(0, attemptCount(sessB), "其他会话不收到普通事件");
        assertEquals(0, attemptCount(noSession), "无会话连接不收到带 session_id 的事件");

        int aBefore = attemptCount(sessA);
        int bBefore = attemptCount(sessB);
        ctl.broadcastAgentOutput("sessB", "npc", "hello", "main", "主轨", "merged", List.of("all"));
        ctl.broadcastAgentToken("sessB", "npc", "h", "main", "主轨", "merged");
        ctl.broadcastRoundComplete("sessB", 2);
        ctl.broadcastUserInput("sessB", "input", "normal", "me", 2);
        assertEquals(aBefore, attemptCount(sessA), "A 不收到 B 的 agent_output/token/round/user 事件");
        assertTrue(attemptCount(sessB) > bBefore, "B 收到自己的全部 typed 事件");
        assertEquals(0, attemptCount(noSession), "无会话连接不旁听 typed 会话事件");

        sessA.complete();
        sessB.complete();
        noSession.complete();
    }

    @Test
    @DisplayName("P5: script_private 仅送达 roleKey 认证的发送方/接收方，旁观者和公开订阅均不可见")
    void scriptPrivateOnlyReachesAuthenticatedParticipants() throws Exception {
        Map<String, String> validKeys = new ConcurrentHashMap<>(Map.of(
            "Alice", "key-a", "Bob", "key-b", "Mallory", "key-m"));
        SSEController ctl = new SSEController((sessionId, player, playerKey, eventType) ->
            "scriptA".equals(sessionId) && playerKey.equals(validKeys.get(player)));

        SseEmitter alice = ctl.stream("scriptA", "Alice", "key-a");
        SseEmitter bob = ctl.stream("scriptA", "Bob", "key-b");
        SseEmitter mallory = ctl.stream("scriptA", "Mallory", "key-m");
        SseEmitter publicSession = ctl.stream("scriptA");
        SseEmitter otherSession = ctl.stream("scriptB");

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> ctl.stream("scriptA", "Alice", "wrong"), "错误 roleKey 必须拒绝建立身份订阅");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> ctl.stream("scriptA", "Alice", null), "身份参数不完整必须拒绝，不能降级成公开连接");

        ctl.broadcastScriptPrivate("scriptA", Map.of(
            "from", "Alice", "to", "Bob", "message", "secret", "reply", "ok"));
        assertTrue(attemptCount(alice) > 0, "发送方收到私聊事件");
        assertTrue(attemptCount(bob) > 0, "接收方收到私聊事件");
        assertEquals(0, attemptCount(mallory), "同局第三人收不到私聊");
        assertEquals(0, attemptCount(publicSession), "仅 session 公开订阅收不到私聊");
        assertEquals(0, attemptCount(otherSession), "其他会话收不到私聊");

        int aliceBefore = attemptCount(alice);
        int bobBefore = attemptCount(bob);
        validKeys.remove("Alice"); // 模拟同 session 重开后旧 roleKey 失效
        ctl.broadcastScriptPrivate("scriptA", Map.of(
            "from", "Alice", "to", "Bob", "message", "new-secret", "reply", "ok"));
        assertEquals(aliceBefore, attemptCount(alice), "旧令牌连接在投递时复验失败，不再收到私聊");
        assertTrue(attemptCount(bob) > bobBefore, "仍有效的接收方继续收到私聊");

        alice.complete();
        bob.complete();
        mallory.complete();
        publicSession.complete();
        otherSession.complete();
    }

    @Test
    @DisplayName("P6: 狼人杀私密角色/女巫事件复用玩家订阅，只送达指定身份")
    void werewolfPrivateEventsOnlyReachTargetPlayer() throws Exception {
        SSEController ctl = new SSEController((sessionId, player, playerKey, eventType) ->
            "wolfA".equals(sessionId) && (("Alice".equals(player) && "key-a".equals(playerKey))
                || ("Witch".equals(player) && "key-w".equals(playerKey))));
        SseEmitter alice = ctl.stream("wolfA", "Alice", "key-a");
        SseEmitter witch = ctl.stream("wolfA", "Witch", "key-w");
        SseEmitter publicSession = ctl.stream("wolfA");

        ctl.broadcastToPlayers("wolfA", "werewolf_my_role",
            Map.of("session_id", "wolfA", "role", "villager"), "Alice");
        assertTrue(attemptCount(alice) > 0, "本人收到自己的角色");
        assertEquals(0, attemptCount(witch), "其他玩家收不到角色");
        assertEquals(0, attemptCount(publicSession), "公开会话订阅收不到角色");

        int aliceBefore = attemptCount(alice);
        int witchBefore = attemptCount(witch);
        ctl.broadcastToPlayers("wolfA", "werewolf_witch_info",
            Map.of("session_id", "wolfA", "victim", "Bob"), "Witch");
        assertEquals(aliceBefore, attemptCount(alice), "非女巫收不到女巫信息");
        assertTrue(attemptCount(witch) > witchBefore, "女巫本人收到夜间信息");
        assertEquals(0, attemptCount(publicSession), "公开会话订阅仍收不到女巫信息");

        alice.complete();
        witch.complete();
        publicSession.complete();
    }
}
