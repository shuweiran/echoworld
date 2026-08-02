package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-0801-G 验收测试：邀请码功能显式开关（roleplay.auth.invite-enabled，默认 false=关闭）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>G-1（Spring 上下文，application-test.yml invite-enabled=true）：配置的持久化邀请码
 *       B3283A78 可验证通过 → 200 + token；错误邀请码 → 401；token 可过 /api/auth/me；
 *       DEFAULT2024 兼容保留可验证</li>
 *   <li>G-2（直构 AuthController(false, ...)，无 Spring）：关闭时 /verify 一律 403
 *       「邀请码功能未启用」——含正确的配置码也不放行（不暴露邀请码是否正确）</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerInviteSwitchTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── G-1：启用路径（application-test.yml invite-enabled=true + invite-code=B3283A78） ──

    @Test
    @DisplayName("G-1a 启用时配置码 B3283A78 验证通过 → 200 + token")
    void enabled_configuredCodeVerifies() throws Exception {
        String body = mapper.writeValueAsString(Map.of("code", "B3283A78"));
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.user").value("player"))
                .andExpect(jsonPath("$.message").value("验证成功"));
    }

    @Test
    @DisplayName("G-1b 启用时错误邀请码 → 401 无效的邀请码")
    void enabled_wrongCodeRejected() throws Exception {
        String body = mapper.writeValueAsString(Map.of("code", "WRONG-CODE"));
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("无效的邀请码"));
    }

    @Test
    @DisplayName("G-1c 启用时 DEFAULT2024 兼容保留仍可验证")
    void enabled_defaultCodeStillValid() throws Exception {
        String body = mapper.writeValueAsString(Map.of("code", "DEFAULT2024"));
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    @DisplayName("G-1d 验证成功发的 token 可通过 /api/auth/me")
    void enabled_tokenWorksOnMe() throws Exception {
        String body = mapper.writeValueAsString(Map.of("code", "B3283A78"));
        String resp = mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(resp).get("token").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    // ── G-2：关闭路径（直构，无 Spring：invite-enabled=false，模拟默认关闭） ──

    @Test
    @DisplayName("G-2a 关闭时 /verify 返回 403 邀请码功能未启用（含正确配置码也不放行）")
    void disabled_verifyReturns403() {
        AuthController c = new AuthController(false, "B3283A78");
        var resp = c.verify(Map.of("code", "B3283A78"));
        assertEquals(403, resp.getStatusCode().value());
        assertEquals(Map.of("error", "邀请码功能未启用"), resp.getBody());
    }

    @Test
    @DisplayName("G-2b 关闭时任意邀请码均 403（不暴露邀请码是否正确）")
    void disabled_anyCodeRejectedWith403() {
        AuthController c = new AuthController(false, "B3283A78");
        var resp = c.verify(Map.of("code", ""));
        assertEquals(403, resp.getStatusCode().value());
        assertEquals("邀请码功能未启用", resp.getBody().get("error"));
    }

    @Test
    @DisplayName("G-2c 构造时配置码已入映射：invite-enabled=true 直构可验证配置码（重启不丢语义）")
    void enabled_directConstructionUsesConfiguredCode() {
        AuthController c = new AuthController(true, "B3283A78");
        var resp = c.verify(Map.of("code", "B3283A78"));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("验证成功", resp.getBody().get("message"));
    }
}
