package com.roleplay.engine.controller;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.GeneratorService;
import com.roleplay.engine.service.RouterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0810-25：startScene 玩家角色保护修复 —— me 字段决定 mode/protagonist。
 *
 * <p>根因（用户实测）：经典视图（RoleSelectPage → GameBridge）起局勾选「带玩家+选角色」，
 * LLM 自动接管了玩家角色发言 —— SceneController.startScene 硬编码 mode="free" 且
 * protagonist 传空，玩家角色被当作 AI 处理（日志实锤「4 agents, mode=free」= 3 AI + 玩家角色）。
 * AgentExecutor.computePriority 已有 PLAYER 保护（agentName.equals(protagonist) || "me"），
 * 只要 protagonist 正确设置玩家角色就不会被 AI 接管。
 *
 * <p>修复：body 的 me 字段优先、query me 回退（双保险）；me 非空 → mode=protagonist +
 * protagonist=me（镜像 init 同步传参）；me 为空 → mode=director（导演模式：无玩家角色，
 * AI 角色们自己对话，语义与 P-0810-24 前端一致）。
 *
 * <p>用例：
 * <ol>
 *   <li>body 传 me → initSession 收到 mode=protagonist + protagonist=me，响应 mode=protagonist</li>
 *   <li>body 无 me 但 query 传 me → 同样 protagonist（query 回退双保险）</li>
 *   <li>body/query 均无 me → mode=director（无玩家角色，AI 角色自己对话），响应 mode=director</li>
 *   <li>响应 mode 与 initSession 实际 mode 一致（旧实现硬编码 "free"）</li>
 * </ol>
 */
class SceneStartSceneModeTest {

    /** 构建 SceneController（默认单例 router mock；sessionRegistry=null 走 5 参构造零 registry 路径）。 */
    private SceneController build(RouterService router) {
        CharacterController cc = mock(CharacterController.class);
        when(cc.getAll()).thenReturn(List.of());
        return new SceneController(mock(GeneratorService.class), router, cc,
                mock(DatabaseService.class), null);
    }

    // ── ① body 传 me → protagonist ──

    @Test
    @DisplayName("① startScene body 传 me → initSession 收到 mode=protagonist + protagonist=me；响应 mode=protagonist")
    void bodyMe_protagonistMode() {
        RouterService router = mock(RouterService.class);
        SceneController ctrl = build(router);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", List.of("小铃", "凯尔", "测试玩家"));
        body.put("me", "测试玩家");

        ResponseEntity<Map<String, Object>> resp = ctrl.startScene("scene-1", "", "", body);

        verify(router).initSession(anyString(), anyList(), anyString(),
                eq("protagonist"), eq("测试玩家"), eq(""));
        assertNotNull(resp.getBody());
        assertEquals("protagonist", resp.getBody().get("mode"), "响应 mode 应为 protagonist（非旧硬编码 free）");
        assertEquals("测试玩家", resp.getBody().get("protagonist"), "响应应回传 protagonist");
    }

    // ── ② query 回退 ──

    @Test
    @DisplayName("② body 无 me 但 query 传 me → 同样 protagonist（query 回退双保险）")
    void queryMeFallback_protagonistMode() {
        RouterService router = mock(RouterService.class);
        SceneController ctrl = build(router);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", List.of("小铃", "凯尔", "测试玩家"));

        ResponseEntity<Map<String, Object>> resp = ctrl.startScene("scene-1", "", "测试玩家", body);

        verify(router).initSession(anyString(), anyList(), anyString(),
                eq("protagonist"), eq("测试玩家"), eq(""));
        assertNotNull(resp.getBody());
        assertEquals("protagonist", resp.getBody().get("mode"));
    }

    // ── ③ 均无 me → director ──

    @Test
    @DisplayName("③ body/query 均无 me → mode=director（无玩家角色，AI 角色们自己对话）；响应 mode=director")
    void noMe_directorMode() {
        RouterService router = mock(RouterService.class);
        SceneController ctrl = build(router);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", List.of("小铃", "凯尔"));

        ResponseEntity<Map<String, Object>> resp = ctrl.startScene("scene-1", "", "", body);

        verify(router).initSession(anyString(), anyList(), anyString(),
                eq("director"), eq(""), eq(""));
        assertNotNull(resp.getBody());
        assertEquals("director", resp.getBody().get("mode"), "无 me 时应为导演模式");
    }

    // ── ④ body 优先于 query ──

    @Test
    @DisplayName("④ body 与 query 同时传 me 时 body 优先（前端 GameBridge 双通道传参一致性）")
    void bodyMe_overridesQuery() {
        RouterService router = mock(RouterService.class);
        SceneController ctrl = build(router);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", List.of("小铃", "凯尔", "测试玩家"));
        body.put("me", "测试玩家");

        // query 传了另一个名字 —— body 应优先
        ResponseEntity<Map<String, Object>> resp = ctrl.startScene("scene-1", "", "查询参数玩家", body);

        verify(router).initSession(anyString(), anyList(), anyString(),
                eq("protagonist"), eq("测试玩家"), eq(""));
        assertNotNull(resp.getBody());
        assertEquals("protagonist", resp.getBody().get("mode"));
        assertEquals("测试玩家", resp.getBody().get("protagonist"));
    }

    // ── ⑤ 旧客户端仅 query 传 me + 无 body ──

    @Test
    @DisplayName("⑤ 旧客户端仅 query 传 me（无 body）→ protagonist 仍生效（向后兼容）")
    void queryMeNoBody_protagonistMode() {
        RouterService router = mock(RouterService.class);
        SceneController ctrl = build(router);

        ResponseEntity<Map<String, Object>> resp = ctrl.startScene("scene-1", "小铃,凯尔,测试玩家", "测试玩家", null);

        verify(router).initSession(anyString(), anyList(), anyString(),
                eq("protagonist"), eq("测试玩家"), eq(""));
        assertNotNull(resp.getBody());
        assertEquals("protagonist", resp.getBody().get("mode"));
        assertTrue(resp.getBody().get("session_id") != null && !String.valueOf(resp.getBody().get("session_id")).isBlank(),
                "应返回 session_id");
    }

    // ── ⑥ me 为空白字符串视同无 me ──

    @Test
    @DisplayName("⑥ me 为空白字符串 → 视同无玩家 → director")
    void blankMe_directorMode() {
        RouterService router = mock(RouterService.class);
        SceneController ctrl = build(router);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", List.of("小铃", "凯尔"));
        body.put("me", "   ");

        ResponseEntity<Map<String, Object>> resp = ctrl.startScene("scene-1", "", "", body);

        verify(router).initSession(anyString(), anyList(), anyString(),
                eq("director"), eq(""), eq(""));
        assertNotNull(resp.getBody());
        assertEquals("director", resp.getBody().get("mode"));
    }
}
