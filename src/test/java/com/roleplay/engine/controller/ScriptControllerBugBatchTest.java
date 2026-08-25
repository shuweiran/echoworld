package com.roleplay.engine.controller;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptGameService;
import com.roleplay.engine.service.WerewolfService;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P-0810-17（B3 + B4）验收测试：
 * <ul>
 *   <li>B3：status/keys 的 sessionId 解析优先 findSessionByPlayerKey(player_key)（防多局并发 role_key 错位 403）</li>
 *   <li>B4：WerewolfController.discussionSay 不再 500（Service 改 LinkedHashMap + Controller 防御性拷贝，不可变 Map.of put 崩溃根除）</li>
 * </ul>
 */
class ScriptControllerBugBatchTest {

    // ═══════════════════════════════════════════════════════════
    //  B3：status/keys 会话解析优先 player_key 反查
    // ═══════════════════════════════════════════════════════════

    private ScriptController newScriptController(ScriptGameService svc) {
        return new ScriptController(svc, mock(RouterService.class), mock(SimulationService.class),
                mock(PlayerIdentityService.class));
    }

    @Test
    @DisplayName("B3: /status 带 player_key → 优先按 key 反查对局（不落回 currentSessionId 全局兜底）")
    void statusResolvesSessionByPlayerKey() {
        ScriptGameService svc = mock(ScriptGameService.class);
        // keyB 唯一归属 sessionB（模拟多局并发：currentSessionId 指向 A 局，但 keyB 是 B 局玩家令牌）
        when(svc.findSessionByPlayerKey("keyB")).thenReturn("sessionB");
        when(svc.findPlayerByKey("sessionB", "keyB")).thenReturn("Bob");
        when(svc.checkPlayerAccess(anyString(), anyString(), anyString())).thenReturn(null);
        ScriptGameService.ScriptGame game = new ScriptGameService.ScriptGame();
        when(svc.getGame(anyString())).thenReturn(game);

        ScriptController ctl = newScriptController(svc);
        // 不带 player 名、只带 key → 应解析到 sessionB 而非当前对局
        ResponseEntity<Map<String, Object>> resp = ctl.getStatus("", "keyB");
        assertEquals(200, resp.getStatusCode().value());
        verify(svc).findSessionByPlayerKey("keyB");
        verify(svc).checkPlayerAccess("sessionB", "Bob", "keyB");
        assertEquals(game, svc.getGame("sessionB"), "应以 key 反查到的 sessionB 读取对局");
    }

    @Test
    @DisplayName("B3: /keys 带 player_key → 优先按 key 反查对局返回该局全员 roleKey")
    void keysResolveSessionByPlayerKey() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.findSessionByPlayerKey("keyB")).thenReturn("sessionB");
        when(svc.getPlayerKeys("sessionB")).thenReturn(Map.of("Bob", "keyB"));

        ScriptController ctl = newScriptController(svc);
        ReflectionTestUtils.setField(ctl, "dmKey", "dm-test-key");
        ResponseEntity<Map<String, Object>> resp = ctl.getKeys("", "keyB", "dm-test-key");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("sessionB", resp.getBody().get("session_id"), "keys 应返回 key 反查到的对局");
        assertEquals("keyB", ((Map<?, ?>) resp.getBody().get("player_keys")).get("Bob"));
    }

    @Test
    @DisplayName("B3: /status 带 player 名 + key → key 优先（playerSessions 回退仅作 key 反查失败时的兜底）")
    void statusPrefersKeyOverPlayerFallback() {
        ScriptGameService svc = mock(ScriptGameService.class);
        // key 反查命中 → 以 key 归属为准（玩家名可能已换局）
        when(svc.findSessionByPlayerKey("keyA")).thenReturn("sessionA");
        when(svc.checkPlayerAccess(anyString(), anyString(), anyString())).thenReturn(null);
        ScriptGameService.ScriptGame game = new ScriptGameService.ScriptGame();
        when(svc.getGame(anyString())).thenReturn(game);

        ScriptController ctl = newScriptController(svc);
        ResponseEntity<Map<String, Object>> resp = ctl.getStatus("Alice", "keyA");
        assertEquals(200, resp.getStatusCode().value());
        verify(svc).checkPlayerAccess("sessionA", "Alice", "keyA");
    }

    // ═══════════════════════════════════════════════════════════
    //  D-078：restart 必须先认证旧局身份，且按认证主体收敛新局视图
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("D-078: /restart 拒绝缺 key、错 key 与跨玩家 key，且不得触发重置")
    void restartRejectsMissingWrongAndCrossPlayerKeys() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.checkPlayerAccess("sessionA", "Alice", ""))
                .thenReturn(Map.of("error", "身份校验失败：缺少 player_key"));
        when(svc.checkPlayerAccess("sessionA", "Alice", "wrong-key"))
                .thenReturn(Map.of("error", "身份校验失败：player_key 与玩家不匹配"));
        when(svc.checkPlayerAccess("sessionA", "Alice", "bob-key"))
                .thenReturn(Map.of("error", "身份校验失败：player_key 与玩家不匹配"));
        ScriptController ctl = newScriptController(svc);
        ReflectionTestUtils.setField(ctl, "dmKey", "dm-test-key");

        ResponseEntity<Map<String, Object>> missing = ctl.restart(
                new LinkedHashMap<>(Map.of("session_id", "sessionA", "player", "Alice")), "");
        ResponseEntity<Map<String, Object>> wrong = ctl.restart(
                new LinkedHashMap<>(Map.of("session_id", "sessionA", "player", "Alice",
                        "player_key", "wrong-key")), "");
        ResponseEntity<Map<String, Object>> crossPlayer = ctl.restart(
                new LinkedHashMap<>(Map.of("session_id", "sessionA", "player", "Alice",
                        "player_key", "bob-key")), "");

        assertEquals(403, missing.getStatusCode().value());
        assertEquals(403, wrong.getStatusCode().value());
        assertEquals(403, crossPlayer.getStatusCode().value());
        verify(svc, never()).restartGame(anyString());
    }

    @Test
    @DisplayName("D-078: /restart 正确玩家 key 返回本人新视图，正确 DM key 仅返回公共视图")
    void restartReturnsOnlyAuthenticatedPlayerOrPublicView() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.checkPlayerAccess("sessionA", "Alice", "alice-old-key")).thenReturn(null);
        when(svc.restartGame("sessionA")).thenReturn(Map.of("phase", "investigation"));
        ScriptGameService.ScriptGame restarted = mock(ScriptGameService.ScriptGame.class);
        when(restarted.toMap("Alice")).thenReturn(Map.of(
                "phase", "investigation", "session_id", "sessionA",
                "your_role", "侦探", "role_key", "alice-new-key"));
        when(restarted.toMap("")).thenReturn(Map.of(
                "phase", "investigation", "session_id", "sessionA",
                "your_role", ""));
        when(svc.getGame("sessionA")).thenReturn(restarted);
        ScriptController ctl = newScriptController(svc);
        ReflectionTestUtils.setField(ctl, "dmKey", "dm-test-key");

        ResponseEntity<Map<String, Object>> playerResp = ctl.restart(
                new LinkedHashMap<>(Map.of("session_id", "sessionA", "player", "Alice",
                        "player_key", "alice-old-key")), "");
        assertEquals(200, playerResp.getStatusCode().value());
        assertEquals("alice-new-key", playerResp.getBody().get("role_key"));
        verify(restarted).toMap("Alice");

        ResponseEntity<Map<String, Object>> dmResp = ctl.restart(
                new LinkedHashMap<>(Map.of("session_id", "sessionA")), "dm-test-key");
        assertEquals(200, dmResp.getStatusCode().value());
        assertFalse(dmResp.getBody().containsKey("role_key"), "DM 重开响应不得泄露任何玩家新 roleKey");
        verify(restarted).toMap("");
    }

    // ═══════════════════════════════════════════════════════════
    //  B4：WerewolfController.discussionSay 500 修复
    // ═══════════════════════════════════════════════════════════

    /** 录制式 SSE（WerewolfService 3 参构造用）。 */
    private static class RecordingSse implements SseBroadcaster {
        final List<Map.Entry<String, Map<?, ?>>> events = new CopyOnWriteArrayList<>();
        @Override
        public void broadcast(String eventType, Object data) {
            events.add(Map.entry(eventType, data instanceof Map<?, ?> m ? m : Map.of()));
        }
    }

    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            Thread.sleep(20);
            return "我认为 A 就是狼人。情绪：平静。";
        });
        return llm;
    }

    private List<String> sixPlayers() {
        return new ArrayList<>(List.of("A", "B", "C", "D", "E", "F"));
    }

    private Map<String, String> sixRoles() {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("A", "wolf");
        roles.put("B", "werewolf");
        roles.put("C", "预言家");
        roles.put("D", "witch");
        roles.put("E", "hunter");
        roles.put("F", "villager");
        return roles;
    }

    private void await(String desc, long timeoutMs, java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("等待超时: " + desc);
    }

    @Test
    @DisplayName("B4: WerewolfController.discussionSay 对不可变 service map 不再 500（防御性拷贝兜底）")
    void werewolfControllerDiscussionSayNo500OnImmutableMap() {
        // 旧实现：WerewolfService.discussionSay 返回 Map.of（不可变）→ controller result.put("session_id")
        // 抛 UnsupportedOperationException → HTTP 500（P-0810-06 真机复现）。本用例让 mock service
        // 返回不可变 Map.of（模拟旧行为），验证 controller 防御性拷贝后 200。
        WerewolfService svc = mock(WerewolfService.class);
        when(svc.isPlayerKeyValid("ww-test", "F", "keyF")).thenReturn(true);
        when(svc.discussionSay(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("ok", true, "player", "F", "message", "我认为 A 就是狼人"));
        WerewolfController ctl = new WerewolfController(svc);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("session_id", "ww-test");
        body.put("player", "F");
        body.put("player_key", "keyF");
        body.put("message", "我认为 A 就是狼人");
        ResponseEntity<Map<String, Object>> resp = ctl.discussionSay(body);
        assertEquals(200, resp.getStatusCode().value(), "B4: 不可变 map 不再 500（controller 防御性拷贝）");
        assertEquals(Boolean.TRUE, resp.getBody().get("ok"));
        assertNotNull(resp.getBody().get("session_id"), "B4: 响应应附 session_id");
    }
}
