package com.roleplay.engine.controller;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptGameService;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P-0816-P1（后端 action 链路 403 修复）验收测试 —— 依据复现证据
 * （POST /api/script/action?session_id=c266e519-801 + player/action_id/player_key → 403）。
 *
 * <p>根因（两段）：① 新增端点（actions/action/vote-status/goal）忽略调用方显式传入的 session_id
 * （复现 curl 把 session_id 放在 query，端点只看 player_key 反查 + currentSessionId 兜底）；
 * ② 服务重启后旧对局仅剩 H2 快照，player_key 反查（仅扫内存 games）失败 → 回退 currentSessionId
 * （可能指向别的对局）→ checkPlayerAccess 403「身份校验失败/游戏不存在」。
 *
 * <p>修复：显式 session_id（query/body）优先于 player_key 反查；鉴权前 ensureGameLoaded
 * （内存缺失按快照恢复，对齐 C3 resume 语义）。本测试锁定解析契约 + 快照恢复路径。
 */
class ScriptActionEndpointFixTest {

    private static final String SESSION_X = "sessionX";
    private static final String PLAYER = "Alice";
    private static final String KEY_A = "keyA";

    // ═══════════════════════════════════════════════════════════
    //  Controller 层：session_id 解析契约（显式 > player_key 反查 > 兜底）
    // ═══════════════════════════════════════════════════════════

    private ScriptController newController(ScriptGameService svc) {
        return new ScriptController(svc, mock(RouterService.class), mock(SimulationService.class),
                mock(PlayerIdentityService.class));
    }

    @Test
    @DisplayName("P1-1: POST /action?session_id= 显式对局优先 —— 即使 player_key 反查指向别的对局也以显式为准（403 根因①）")
    void postActionPrefersExplicitQuerySessionId() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.findSessionByPlayerKey(KEY_A)).thenReturn("other-session");
        when(svc.checkPlayerAccess(anyString(), anyString(), anyString())).thenReturn(null);
        when(svc.executeAction(anyString(), anyString(), anyString())).thenReturn(Map.of("ok", true));

        ScriptController ctl = newController(svc);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("player", PLAYER);
        body.put("action_id", "research|客厅");
        body.put("player_key", KEY_A);

        ResponseEntity<Map<String, Object>> resp = ctl.action(body, SESSION_X);
        assertEquals(200, resp.getStatusCode().value());
        verify(svc).ensureGameLoaded(SESSION_X);
        verify(svc).checkPlayerAccess(SESSION_X, PLAYER, KEY_A);
        verify(svc).executeAction(SESSION_X, PLAYER, "research|客厅");
        verify(svc, never()).findSessionByPlayerKey(anyString());
    }

    @Test
    @DisplayName("P1-2: POST /action body 带 session_id 同样生效（query 缺省时）")
    void postActionPrefersExplicitBodySessionId() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.checkPlayerAccess(anyString(), anyString(), anyString())).thenReturn(null);
        when(svc.executeAction(anyString(), anyString(), anyString())).thenReturn(Map.of("ok", true));

        ScriptController ctl = newController(svc);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("player", PLAYER);
        body.put("action_id", "ask|Bob");
        body.put("player_key", KEY_A);
        body.put("session_id", "body-session");

        ResponseEntity<Map<String, Object>> resp = ctl.action(body, "");
        assertEquals(200, resp.getStatusCode().value());
        verify(svc).ensureGameLoaded("body-session");
        verify(svc).checkPlayerAccess("body-session", PLAYER, KEY_A);
        verify(svc).executeAction("body-session", PLAYER, "ask|Bob");
    }

    @Test
    @DisplayName("P1-3: 无显式 session_id → 回退 player_key 反查（旧行为不变，前端现契约）")
    void postActionFallsBackToPlayerKeyLookup() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.findSessionByPlayerKey(KEY_A)).thenReturn("sessionB");
        when(svc.checkPlayerAccess(anyString(), anyString(), anyString())).thenReturn(null);
        when(svc.executeAction(anyString(), anyString(), anyString())).thenReturn(Map.of("ok", true));

        ScriptController ctl = newController(svc);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("player", PLAYER);
        body.put("action_id", "present|c1");
        body.put("player_key", KEY_A);

        ResponseEntity<Map<String, Object>> resp = ctl.action(body, "");
        assertEquals(200, resp.getStatusCode().value());
        verify(svc).ensureGameLoaded("sessionB");
        verify(svc).checkPlayerAccess("sessionB", PLAYER, KEY_A);
        verify(svc).executeAction("sessionB", PLAYER, "present|c1");
    }

    @Test
    @DisplayName("P1-4: GET /actions?session_id= 显式对局优先（对照复现证据：带 session_id 应返回 200 而非 403）")
    void getActionsPrefersExplicitSessionId() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.checkPlayerAccess(anyString(), anyString(), anyString())).thenReturn(null);
        when(svc.listActions(anyString(), anyString())).thenReturn(Map.of("ok", true, "actions", List.of()));

        ScriptController ctl = newController(svc);
        ResponseEntity<Map<String, Object>> resp = ctl.actions(SESSION_X, PLAYER, KEY_A);
        assertEquals(200, resp.getStatusCode().value());
        verify(svc).ensureGameLoaded(SESSION_X);
        verify(svc).checkPlayerAccess(SESSION_X, PLAYER, KEY_A);
        verify(svc).listActions(SESSION_X, PLAYER);
    }

    @Test
    @DisplayName("P1-5: 鉴权失败仍 403（修复不绕过身份校验）")
    void actionDeniedStill403() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.checkPlayerAccess(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("error", "身份校验失败：player_key 与玩家不匹配"));

        ScriptController ctl = newController(svc);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("player", PLAYER);
        body.put("action_id", "research|客厅");
        body.put("player_key", "wrongKey");

        ResponseEntity<Map<String, Object>> resp = ctl.action(body, SESSION_X);
        assertEquals(403, resp.getStatusCode().value());
        verify(svc, never()).executeAction(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("P1-6: 解析不到对局 → 200 {error:缺少 session_id}，空 body 不 500")
    void actionMissingSessionReturnsError200() {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.findSessionByPlayerKey(anyString())).thenReturn("");

        ScriptController ctl = newController(svc);
        ResponseEntity<Map<String, Object>> resp = ctl.action(null, "");
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(String.valueOf(resp.getBody().get("error")).contains("session_id"));

        Map<String, String> body = new LinkedHashMap<>();
        body.put("player", PLAYER);
        body.put("action_id", "research|客厅");
        body.put("player_key", KEY_A);
        ResponseEntity<Map<String, Object>> resp2 = ctl.action(body, "");
        assertEquals(200, resp2.getStatusCode().value());
    }

    // ═══════════════════════════════════════════════════════════
    //  Service 层：ensureGameLoaded —— 重启后旧对局按快照恢复（403 根因②）
    // ═══════════════════════════════════════════════════════════

    private static final String SNAP = "snap-session-1";

    private Map<String, Object> snapshotMap() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("session_id", SNAP);
        snap.put("phase", "INVESTIGATION");
        snap.put("name", "重启后对局");
        snap.put("players", List.of("Alice", "Bob"));
        snap.put("player_keys", Map.of("Alice", "keyA", "Bob", "keyB"));
        snap.put("roles", List.of("管家", "女仆"));
        snap.put("assignments", Map.of("Alice", "管家", "Bob", "女仆"));
        snap.put("locations", List.of("客厅", "书房"));
        snap.put("clues", List.of(Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false)));
        snap.put("player_ap", Map.of("Alice", 3, "Bob", 3));
        snap.put("player_ap_max", Map.of("Alice", 3, "Bob", 3));
        snap.put("searched_locations", List.of("客厅"));
        return snap;
    }

    private ScriptGameService newServiceWithDb(DatabaseService db) {
        return new ScriptGameService(mock(LLMClient.class), new ApprovalService(), db, null);
    }

    @Test
    @DisplayName("P1-7: ensureGameLoaded 按快照恢复对局 —— 恢复后 player_key 鉴权通过、listActions 正常（403 根因②闭环）")
    void ensureGameLoadedRestoresFromSnapshot() {
        DatabaseService db = mock(DatabaseService.class);
        when(db.getLatestScriptSnapshot(SNAP)).thenReturn(Optional.of(snapshotMap()));
        ScriptGameService svc = newServiceWithDb(db);

        ScriptGameService.ScriptGame game = svc.ensureGameLoaded(SNAP);
        assertNotNull(game, "快照存在应恢复对局");

        // 鉴权：正确 key 通过（复现证据场景：显式 session_id + 正确 player_key → 不再 403）
        assertNull(svc.checkPlayerAccess(SNAP, "Alice", "keyA"), "正确 key 应通过鉴权");
        // 错误 key 仍拒绝（不绕过身份校验）
        assertNotNull(svc.checkPlayerAccess(SNAP, "Alice", "wrong"), "错误 key 仍应 403");

        // 行动建议在恢复对局上可用：已搜客厅 → 回看 ap_cost 0（U7 语义在恢复路径保持一致）
        Map<String, Object> res = svc.listActions(SNAP, "Alice");
        assertEquals(Boolean.TRUE, res.get("ok"));
        List<Map<String, Object>> actions = (List<Map<String, Object>>) res.get("actions");
        Map<String, Object> replay = null;
        for (Map<String, Object> a : actions) {
            if ("research".equals(a.get("type")) && "客厅".equals(a.get("target"))) replay = a;
        }
        assertNotNull(replay, "已搜地点应出回看建议");
        assertEquals(0, replay.get("ap_cost"), "回看不扣 AP（决策 U7）");
    }

    @Test
    @DisplayName("P1-8: 无快照 → ensureGameLoaded 返回 null 不崩溃（按既有错误路径 403「游戏不存在」）")
    void ensureGameLoadedNoSnapshotReturnsNull() {
        DatabaseService db = mock(DatabaseService.class);
        when(db.getLatestScriptSnapshot("ghost")).thenReturn(Optional.empty());
        ScriptGameService svc = newServiceWithDb(db);
        assertNull(svc.ensureGameLoaded("ghost"));
    }

    @Test
    @DisplayName("P1-9: 已在内存 → 直返不查库（ensureGameLoaded 零开销）")
    void ensureGameLoadedInMemorySkipsDb() {
        DatabaseService db = mock(DatabaseService.class);
        when(db.getLatestScriptSnapshot(SNAP)).thenReturn(Optional.of(snapshotMap()));
        ScriptGameService svc = newServiceWithDb(db);

        assertNotNull(svc.ensureGameLoaded(SNAP)); // 首次：快照恢复
        assertNotNull(svc.ensureGameLoaded(SNAP)); // 二次：内存命中
        verify(db, times(1)).getLatestScriptSnapshot(SNAP);
    }
}
