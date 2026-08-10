package com.roleplay.engine.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-0809-B（阶段② API 逻辑链追踪）端到端集成测试
 * （@SpringBootTest + H2 mem + mock LLM，D-008 基建；TraceFilter + DebugTraceController + 真实端点）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>① 开关关闭（默认）：零打点（无 X-Request-Id 回写、缓冲 0 条）+ 端点 404，既有行为零影响</li>
 *   <li>② 开关开启：GET 请求被记录（方法/路径/状态码/耗时字段）+ 客户端 X-Request-Id 回显 + 来源=frontend</li>
 *   <li>③ 无 X-Request-Id：自动生成 UUID 回写 + 来源=auto + 请求体摘要（截断防爆）</li>
 *   <li>④ trace 端点：limit 默认 50 / 上限 200 / 列表新→旧 / 详情 200 / 未知 404</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TraceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TraceService traceService;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 测试隔离：默认关闭 + 清空缓冲（TraceService 为共享单例，防跨用例/跨类污染）
        traceService.setEnabled(false);
        traceService.clear();
        TraceContext.clear();
    }

    @AfterEach
    void tearDown() {
        traceService.setEnabled(false);
        traceService.clear();
        TraceContext.clear();
    }

    @Test
    @DisplayName("① 开关关闭（默认）：零打点零影响 + 端点 404")
    void disabled_zeroImpactAnd404() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/characters"))
                .andExpect(status().isOk())
                .andReturn();

        assertNull(res.getResponse().getHeader("X-Request-Id"), "开关关闭不应回写 X-Request-Id");
        assertEquals(0, traceService.count(), "开关关闭零打点");
        mockMvc.perform(get("/api/debug/trace"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("逻辑链追踪未开启（roleplay.debug.trace-enabled=false）"));
    }

    @Test
    @DisplayName("② 开关开启：REST 请求被记录 + X-Request-Id 回显 + 来源=frontend")
    void enabled_recordsRequestWithEcho() throws Exception {
        traceService.setEnabled(true);

        mockMvc.perform(get("/api/characters").header("X-Request-Id", "trace-test-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "trace-test-1"));

        TraceEntry entry = traceService.get("trace-test-1");
        assertNotNull(entry, "请求应被记录");
        assertEquals("GET", entry.method);
        assertEquals("/api/characters", entry.path);
        assertEquals(200, entry.status);
        assertTrue(entry.ms >= 0, "应记录耗时 ms");
        assertEquals("frontend", entry.source);
        assertTrue(traceService.count() >= 1);
    }

    @Test
    @DisplayName("③ 无 X-Request-Id：自动生成回写 + 来源=auto + 请求体摘要")
    void enabled_autoRequestIdAndBodySummary() throws Exception {
        traceService.setEnabled(true);

        MvcResult res = mockMvc.perform(post("/api/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"trace-body-链路摘要\",\"level\":\"PLAYER\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String rid = res.getResponse().getHeader("X-Request-Id");
        assertNotNull(rid, "应自动生成并回写 X-Request-Id");
        assertFalse(rid.isBlank());

        TraceEntry entry = traceService.get(rid);
        assertNotNull(entry);
        assertEquals("auto", entry.source, "无客户端头 → 来源=auto");
        assertEquals("POST", entry.method);
        assertTrue(entry.bodySummary.contains("trace-body-链路摘要"), "请求体摘要应包含发送内容");
    }

    @Test
    @DisplayName("④ trace 端点：limit 默认 50 / 上限 200 / 列表新→旧 / 详情 / 404")
    void endpoints_pagingCapAndDetail() throws Exception {
        traceService.setEnabled(true);
        // 直接入队 250 条（绕过 HTTP，快速构造分页数据）
        for (int i = 0; i < 250; i++) {
            traceService.enqueue(new TraceEntry("bulk-" + i, 1000L + i, "GET", "/api/bulk"));
        }

        // 上限 200
        mockMvc.perform(get("/api/debug/trace?limit=500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.count").value(200))
                .andExpect(jsonPath("$.entries[0].request_id").value("bulk-249"))
                .andExpect(jsonPath("$.entries[199].request_id").value("bulk-50"));

        // 默认 50
        mockMvc.perform(get("/api/debug/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(50));

        // 详情
        mockMvc.perform(get("/api/debug/trace/bulk-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("bulk-100"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.llm_count").value(0));

        // 未知 ID → 404
        mockMvc.perform(get("/api/debug/trace/unknown-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("⑤ 开关关闭后端点恢复 404（与①互补，防污染）")
    void disabled_afterEnable_returns404() throws Exception {
        traceService.setEnabled(false);
        mockMvc.perform(get("/api/debug/trace/trace-test-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("⑥ 详情端点返回 LLM 子调用与 SSE 事件标记")
    void detail_containsLlmAndSse() throws Exception {
        traceService.setEnabled(true);
        TraceEntry entry = traceService.start("detail-1", "POST", "/api/script/vote",
                null, "sess-9", "frontend");
        entry.addLlmCall("test-model", 321);
        entry.addSseEvent("script_status", "sess-9");
        traceService.finish(entry, 200, 350, "{\"player\":\"A\"}", null);
        traceService.enqueue(entry);
        TraceContext.clear();

        MvcResult res = mockMvc.perform(get("/api/debug/trace/detail-1"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = mapper.readTree(res.getResponse().getContentAsString());
        assertEquals("detail-1", node.get("request_id").asText());
        assertEquals(1, node.get("llm_count").asInt());
        assertEquals(1, node.get("sse_count").asInt());
        assertEquals("test-model", node.get("llm_calls").get(0).get("model").asText());
        assertEquals(321, node.get("llm_calls").get(0).get("ms").asLong());
        assertEquals("script_status", node.get("sse_events").get(0).get("event_type").asText());
        assertEquals("sess-9", node.get("sse_events").get(0).get("session_id").asText());
        assertTrue(node.get("body_summary").asText().contains("\"player\""));
    }
}
