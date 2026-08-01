package com.roleplay.engine.service;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.model.CompressedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Conversation history compressor using lightweight LLM calls.
 * Maps from Python core/compressor.py → Compressor.
 */
@Service
public class Compressor {
    private static final Logger log = LoggerFactory.getLogger(Compressor.class);

    private final LLMClient llmClient;
    private final int compressionInterval;

    private static final String COMPRESS_PROMPT = """
        你是一个对话压缩专家。请将以下对话压缩为结构化摘要。

        要求：
        - 摘要：40字以内，概括核心对话内容和情绪走向
        - 关键事件：列出2-3个关键事件或转折点
        - 未解决线索：列出0-2个尚未解决的伏笔或问题
        - 重要性（0-1）：评估这段对话对整体剧情的重要性

        对话内容：
        {messages}

        请用JSON格式回复，字段：summary, key_events (list), open_loops (list), importance (float)""";

    @Autowired
    public Compressor(LLMClient llmClient) {
        this.llmClient = llmClient;
        this.compressionInterval = 5;
    }

    public Compressor(LLMClient llmClient, int compressionInterval) {
        this.llmClient = llmClient;
        this.compressionInterval = compressionInterval;
    }

    public int getCompressionInterval() { return compressionInterval; }

    /** Compress a batch of messages into structured summary. */
    public CompressedChunk compress(List<Map<String, String>> messages,
                                     int startRound, int endRound) {
        if (messages == null || messages.isEmpty()) {
            return new CompressedChunk("chunk_" + startRound + "_" + endRound,
                startRound, endRound, "(无对话)", List.of(), List.of(), 0.0);
        }

        String lines = messages.stream()
            .map(m -> "[" + m.getOrDefault("name", m.getOrDefault("role", "?")) + "]: "
                 + m.getOrDefault("content", "").substring(0, Math.min(200, m.getOrDefault("content", "").length())))
            .collect(Collectors.joining("\n"));

        String prompt = COMPRESS_PROMPT.replace("{messages}", lines);

        try {
            // D-023：压缩摘要 JSON（summary 40 字 + key_events 2-3 + open_loops 0-2 + importance），150 偏紧，提升至 300
            Map<String, Object> data = llmClient.callJson(prompt, 300);

            @SuppressWarnings("unchecked")
            List<String> keyEvents = data.containsKey("key_events")
                ? ((List<Object>) data.get("key_events")).stream()
                    .map(Object::toString).limit(5).collect(Collectors.toList())
                : List.of();

            @SuppressWarnings("unchecked")
            List<String> openLoops = data.containsKey("open_loops")
                ? ((List<Object>) data.get("open_loops")).stream()
                    .map(Object::toString).limit(3).collect(Collectors.toList())
                : List.of();

            String summary = (String) data.getOrDefault("summary", "");
            if (summary.length() > 120) summary = summary.substring(0, 120);

            double importance = Math.min(1.0, Math.max(0.0,
                ((Number) data.getOrDefault("importance", 0.5)).doubleValue()));

            return new CompressedChunk("chunk_" + startRound + "_" + endRound,
                startRound, endRound, summary, keyEvents, openLoops, importance);

        } catch (Exception e) {
            log.warn("Compression failed: {}", e.getMessage());
            return new CompressedChunk("chunk_" + startRound + "_" + endRound,
                startRound, endRound, messages.size() + "条消息的对话",
                List.of(), List.of(), 0.3);
        }
    }

    /** Build context string from compressed chunks + recent raw messages. */
    public String getCompressedContext(List<CompressedChunk> chunks,
                                        List<Map<String, String>> recentRaw,
                                        int maxChunks, int maxRecent) {
        List<String> parts = new ArrayList<>();

        int start = Math.max(0, chunks.size() - maxChunks);
        List<CompressedChunk> recentChunks = chunks.subList(start, chunks.size());
        for (CompressedChunk c : recentChunks) {
            parts.add(c.getContextString());
        }

        if (recentRaw != null && !recentRaw.isEmpty()) {
            parts.add("--- 最近对话 ---");
            int from = Math.max(0, recentRaw.size() - maxRecent);
            for (Map<String, String> m : recentRaw.subList(from, recentRaw.size())) {
                String name = m.getOrDefault("name", m.getOrDefault("role", "?"));
                String content = m.getOrDefault("content", "");
                if (content.length() > 150) content = content.substring(0, 150);
                parts.add("[" + name + "]: " + content);
            }
        }

        return String.join("\n", parts);
    }

    /** Should compression happen at this round? */
    public boolean shouldCompress(int roundCount) {
        return roundCount > 0 && roundCount % compressionInterval == 0;
    }
}
