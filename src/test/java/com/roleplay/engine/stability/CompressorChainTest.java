package com.roleplay.engine.stability;

import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.Compressor;
import com.roleplay.engine.simulation.track.EavesdropSummarizer;
import com.roleplay.engine.model.CompressedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LONG-03：摘要链超长稳定性（方案 7.3）。
 *
 * <p>① Compressor 链式压缩 50 轮（每轮 20 条 × 200 字 → ≤500 字摘要），断言三要素保留、
 * 压缩率 ≥ 50%、无栈溢出/OOM；② EavesdropSummarizer 规则降级处理 10 万字单条输入，耗时 &lt; 5s。
 */
class CompressorChainTest {

    private static final String SUMMARY_MARKER = "摘要锚点SUM-MARKER-001";

    @Test
    @DisplayName("LONG-03: 50轮链式压缩 + 10万字规则降级摘要，无栈溢出/OOM")
    void summaryChainStability() throws Exception {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of(
                "summary", SUMMARY_MARKER + " 剧情持续推进，核心对话已归档。",
                "key_events", List.of("事件A：发现线索", "事件B：关系变化"),
                "open_loops", List.of("疑点X：未解之谜"),
                "importance", 0.5
        ));
        Compressor compressor = new Compressor(llm, 1);

        // ① 50 轮链式压缩：每轮 20 条 × 200 字
        List<CompressedChunk> chunks = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int round = 1; round <= 50; round++) {
            List<Map<String, String>> msgs = new ArrayList<>();
            StringBuilder allInput = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                String content = "第%02d轮第%02d条：" .formatted(round, i) + "长文本填充数据".repeat(28); // ~200字
                msgs.add(Map.of("role", "user", "name", "小明", "content", content));
                allInput.append(content);
            }
            CompressedChunk chunk = compressor.compress(msgs, round * 20 - 20, round * 20);
            chunks.add(chunk);

            // 三要素保留：summary 非空 + key_events 非空
            assertNotNull(chunk.getSummary());
            assertFalse(chunk.getSummary().isBlank(), "摘要不应为空（三要素之“主题”）");
            assertFalse(chunk.getKeyEvents().isEmpty(), "关键事件不应为空");

            // 压缩率 ≥ 50%（输入 ~4000 字 vs 摘要 ≤500 字）
            double rate = 1.0 - (double) chunk.getSummary().length() / Math.max(1, allInput.length());
            assertTrue(rate >= 0.5, "第" + round + "轮压缩率 " + rate + " 应 ≥ 50%");
        }
        long chainMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("LONG-03: 50轮链式压缩完成，耗时 " + chainMs + "ms，摘要块数=" + chunks.size());

        // ② EavesdropSummarizer 规则降级：10 万字单条输入
        EavesdropSummarizer summarizer = new EavesdropSummarizer(); // 无 LLM → 规则路径
        List<String> hugeLines = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            hugeLines.add("小明: " + "机密细节对话内容".repeat(10)); // ~70字/行 → 14万字
        }
        long t1 = System.nanoTime();
        String obs = summarizer.summarizeLines(hugeLines);
        long sumMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("LONG-03: 14万字规则摘要耗时 " + sumMs + "ms，输出=" + obs);

        assertTrue(sumMs < 5000, "10万字单条摘要耗时 " + sumMs + "ms 应 < 5s");
        assertFalse(obs.isBlank(), "规则摘要不应为空");
        assertTrue(obs.length() < 500, "摘要应精简（输出 " + obs.length() + " 字）");

        // ③ 抽样关键事实保留率：规则摘要应体现“说话者+主题”（三要素）
        assertTrue(obs.contains("小明") || obs.contains("正在") || obs.contains("讨论"),
                "摘要应含三要素（说话者/主题/情绪基调）: " + obs);

        System.out.println("LONG-03 PASS：无栈溢出/OOM，压缩率≥50%，10万字摘要<5s");
    }
}
