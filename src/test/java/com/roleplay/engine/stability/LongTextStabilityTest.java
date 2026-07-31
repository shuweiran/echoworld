package com.roleplay.engine.stability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.model.Session;
import com.roleplay.engine.service.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * LONG-01（必做）：单角色 10 万字上下文稳定性。
 *
 * <p>方案 7.1：单会话累计 ≥ 10 万字（500 轮 × ~200 字），LLM 走 mock（固定 50 字回复），
 * 验证：不 OOM、不卡死（每轮 &lt; 10s）、锚点词内容不丢失、压缩链生效、堆增长有界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LongTextStabilityTest {

    private static final int ROUNDS = 500;
    private static final String ANCHOR = "锚点词XYZ-0001";
    private static final String SUMMARY_MARKER = "摘要锚点SUM-MARKER-001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemoryStore memoryStore;

    @MockBean
    private LLMClient llmClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // mock：agent 回复固定 50 字
        when(llmClient.callSync(anyList())).thenReturn("（模拟回复）这是固定的五十字回复内容用于长文本稳定性压测。");
        when(llmClient.callSync(anyList(), anyString(), anyInt(), anyDouble()))
                .thenReturn("（模拟回复）这是固定的五十字回复内容用于长文本稳定性压测。");
        when(llmClient.callSimple(anyString(), anyInt())).thenAnswer(inv -> {
            // 真实 LLM 会把用户输入转换为旁白（保留语义）。mock 回显“用户输入：”之后的文本，
            // 使第 1 轮锚点词能进入持久化消息（贴近真实行为，用于内容不丢失验证）。
            String prompt = inv.getArgument(0);
            String marker = "用户输入：";
            int idx = prompt.lastIndexOf(marker);
            String tail = idx >= 0 ? prompt.substring(idx + marker.length()) : prompt;
            int nl = tail.indexOf('\n');
            String userText = (nl >= 0 ? tail.substring(0, nl) : tail).trim();
            if (userText.length() > 120) userText = userText.substring(0, 120);
            return "【场景变化】" + userText;
            // ⚠️ 此回显是锚点词进入消息流的必要条件（真实 LLM 保留用户输入语义），勿改回固定回复，否则锚点词断言必然失败。
        });
        when(llmClient.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "summary", SUMMARY_MARKER + " 剧情持续推进，核心对话已归档。",
                "key_events", List.of("事件A：发现线索", "事件B：关系变化"),
                "open_loops", List.of("疑点X：未解之谜"),
                "importance", 0.5,
                "tracks", List.of(),
                "reasoning", "mock",
                "narration", "模拟旁白：夜色渐深。",
                "scene_progress", "场景无变化",
                "next_round", Map.of()
        ));
    }

    @Test
    @DisplayName("LONG-01: 500轮/10万字上下文 无OOM、不卡死、内容不丢失、压缩链生效")
    void longContextStability() throws Exception {
        // ① 建 1 角色会话
        String initBody = """
                {"characters":[{"name":"小明","persona":"健谈开朗"}],"scene":"默认场景","mode":"free"}
                """;
        mockMvc.perform(post("/api/init")
                        .contentType(MediaType.APPLICATION_JSON).content(initBody))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus()));

        // ② 500 轮 send，每轮 ~200 字
        List<Double> roundTimesMs = new ArrayList<>();
        List<Long> heapSamples = new ArrayList<>();
        List<Integer> failureRounds = new ArrayList<>();
        String filler = "长文本填充".repeat(40); // 5字×40 = 200字

        for (int round = 1; round <= ROUNDS; round++) {
            String msg = (round == 1 ? ANCHOR + " " : "")
                    + String.format("测试消息第%04d轮。", round) + filler;
            String body = mapper.writeValueAsString(Map.of("message", msg));

            long t0 = System.nanoTime();
            var resp = mockMvc.perform(post("/api/send")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
            double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
            roundTimesMs.add(elapsedMs);

            int http = resp.getResponse().getStatus();
            if (http != 200) failureRounds.add(round);
            // 每轮 < 10s（mock 下应 < 1s）——卡死/指数爆炸检测
            assertTrue(elapsedMs < 10_000,
                    "round " + round + " 耗时 " + String.format("%.0f", elapsedMs) + "ms 超过 10s（疑似卡死/指数爆炸）");

            if (round % 50 == 0) {
                Runtime rt = Runtime.getRuntime();
                long used = rt.totalMemory() - rt.freeMemory();
                heapSamples.add(used);
                System.out.printf("round=%d p50=%.0fms max=%.0fms heapUsed=%.1fMB%n",
                        round, percentile(roundTimesMs, 0.50), max(roundTimesMs), used / 1048576.0);
            }
        }

        // ③ 断言：全程无 HTTP 500 / 失败轮次
        assertTrue(failureRounds.isEmpty(), "失败轮次: " + failureRounds);

        // ④ 锚点词内容不丢失（原始消息全量保留）
        Session session = memoryStore.getSession();
        assertNotNull(session, "session 不应为 null");
        boolean anchorFound = session.getMessages().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains(ANCHOR));
        assertTrue(anchorFound, "第1轮锚点词 " + ANCHOR + " 在 500 轮后必须仍可检索（内容不丢失）");

        // ⑤ 压缩链生效：摘要块已生成，摘要上下文可检索
        assertFalse(session.getCompressedChunks().isEmpty(), "压缩链应已产生摘要块");
        String summaryCtx = memoryStore.getSummaryContext();
        assertTrue(summaryCtx.contains(SUMMARY_MARKER), "摘要上下文应包含压缩摘要标记");

        // ⑥ 会话状态完整：/api/state 可访问且 round 正确
        String stateJson = mockMvc.perform(get("/api/state"))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus()))
                .andReturn().getResponse().getContentAsString();
        assertTrue(stateJson.contains("\"round\""), "state 应含 round 字段");

        // ⑦ P95 单轮耗时 < 10s（mock 下应 < 1s）
        double p95 = percentile(roundTimesMs, 0.95);
        System.out.println("=== LONG-01 汇总 ===");
        System.out.println("总轮数=" + ROUNDS + " P50=" + String.format("%.0f", percentile(roundTimesMs, 0.50))
                + "ms P95=" + String.format("%.0f", p95) + "ms Max=" + String.format("%.0f", max(roundTimesMs)) + "ms");
        System.out.println("堆采样(MB): " + heapSamples.stream().map(b -> String.format("%.1f", b / 1048576.0)).toList());
        assertTrue(p95 < 10_000, "P95 " + p95 + "ms 应 < 10s");
        assertTrue(max(roundTimesMs) < 10_000, "最大单轮耗时应 < 10s");

        // ⑧ 堆增长有界：末段(400-500轮)最小堆 vs 中段(200-300轮)最小堆，涨幅 < 30%
        if (heapSamples.size() >= 10) {
            long midMin = Collections.min(heapSamples.subList(3, 6)); // 200-300轮
            long endMin = Collections.min(heapSamples.subList(7, 10)); // 400-500轮
            double growth = (endMin - midMin) / (double) midMin;
            System.out.println("堆增长(末段min vs 中段min) = " + String.format("%.1f%%", growth * 100));
            assertTrue(growth < 0.30, "堆增长 " + String.format("%.1f%%", growth * 100) + " 应 < 30%（Compressor 生效）");
        }

        // ⑨ 无 OutOfMemoryError：能执行到此处即未 OOM
        System.out.println("LONG-01 PASS：无 OOM、无卡死、锚点词可检索、压缩链生效");
    }

    private static double percentile(List<Double> data, double p) {
        List<Double> sorted = new ArrayList<>(data);
        sorted.sort(Double::compareTo);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static double max(List<Double> data) {
        return data.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }
}
