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
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-0817-L（结构树契约 + 生成 API）：POST /api/structure/generate 端点验证。
 * @SpringBootTest MOCK + MockMvc（真实 Spring 容器 + 真实 controller 路由，H2 mem 走 application-test.yml）。
 * 覆盖：400（缺 theme / 未知 kind / 负尺寸）；200 单图契约完整；200 多图 + warp 连接。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StructureEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("缺 theme → 400 error")
    void missingTheme() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/structure/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        Map<?, ?> body = mapper.readValue(r.getResponse().getContentAsString(), Map.class);
        assertTrue(String.valueOf(body.get("error")).contains("theme"));
    }

    @Test
    @DisplayName("未知 kind → 400 error")
    void unknownKind() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/structure/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("theme", "测试", "kind", "pyramid"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        Map<?, ?> body = mapper.readValue(r.getResponse().getContentAsString(), Map.class);
        assertTrue(String.valueOf(body.get("error")).contains("未知 kind"));
    }

    @Test
    @DisplayName("负尺寸 → 400 error")
    void negativeSize() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/structure/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("theme", "测试", "kind", "castle",
                                "width", -5, "height", 40))))
                .andExpect(status().isBadRequest())
                .andReturn();
        Map<?, ?> body = mapper.readValue(r.getResponse().getContentAsString(), Map.class);
        assertTrue(String.valueOf(body.get("error")).contains("width/height"));
    }

    @Test
    @DisplayName("非法 map_mode / 尺寸字符串 → 400，不静默回退")
    void invalidModeAndSizeAreRejected() throws Exception {
        mockMvc.perform(post("/api/structure/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("theme", "测试", "map_mode", "teleport"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/structure/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("theme", "测试", "width", "oops"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("城堡单图 → 200 契约完整 + validation ok")
    void castleSingle() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("theme", "晨曦城堡");
        req.put("kind", "castle");
        req.put("seed", 20260817L);
        MvcResult r = mockMvc.perform(post("/api/structure/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = mapper.readValue(r.getResponse().getContentAsString(), Map.class);
        assertEquals("castle", ((Map<?, ?>) body.get("structure")).get("kind"));
        Map<?, ?> maps = (Map<?, ?>) body.get("maps");
        assertTrue(maps.containsKey("map_1"));
        assertEquals("map_1", body.get("current_map_id"));
        Map<?, ?> gen = (Map<?, ?>) body.get("generator");
        assertEquals("single", gen.get("map_mode"));
        assertTrue(((Map<?, ?>) gen.get("validation")).get("ok").equals(true));
        assertFalse(((List<?>) body.get("connections")).isEmpty(), "单图应有 exit 连接");
    }

    @Test
    @DisplayName("街区 multi → 200 ≥2 图 + warp 连接")
    void cityBlockMulti() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("theme", "城市街区");
        req.put("kind", "city_block");
        req.put("seed", 20260817L);
        req.put("width", 40);
        req.put("height", 40);
        req.put("map_mode", "multi");
        MvcResult r = mockMvc.perform(post("/api/structure/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = mapper.readValue(r.getResponse().getContentAsString(), Map.class);
        Map<?, ?> maps = (Map<?, ?>) body.get("maps");
        assertTrue(maps.size() >= 2, "multi 应 ≥2 图");
        assertEquals("multi", ((Map<?, ?>) body.get("generator")).get("map_mode"));
        assertTrue(((List<?>) body.get("connections")).stream()
                .anyMatch(c -> "warp".equals(((Map<?, ?>) c).get("type"))));
    }
}
