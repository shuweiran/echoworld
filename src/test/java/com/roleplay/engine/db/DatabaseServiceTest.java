package com.roleplay.engine.db;

import com.roleplay.engine.db.service.DatabaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LONG-02：对话日志 5000+ 条持久化稳定性（方案 7.2）。
 *
 * <p>H2 真实库批量插入 5000 条 ConversationLogEntity，验证：
 * 写入无 OOM、查询 < 2s、首/中/尾抽样一致（无截断）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class DatabaseServiceTest {

    @Autowired
    private DatabaseService databaseService;

    @Test
    @DisplayName("LONG-02: 5000条对话日志批量持久化，查询<2s，抽样一致")
    void persist5000ConversationLogs() {
        String groupId = "test-group-" + UUID.randomUUID().toString().substring(0, 8);
        int total = 5000;
        List<String> contentSamples = new ArrayList<>();

        long t0 = System.nanoTime();
        for (int i = 0; i < total; i++) {
            String content = "日志内容第%05d条：" .formatted(i) + "长文本填充数据".repeat(10); // ~70字
            if (i == 0 || i == total / 2 || i == total - 1) contentSamples.add(content);
            databaseService.logConversation(groupId, "stress",
                    List.of("小明", "小红"),
                    List.of(Map.of("speaker", "小明", "message", content)),
                    i);
        }
        long writeMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        List<Map<String, Object>> logs = databaseService.getConversationLogs(groupId);
        long queryMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf("LONG-02: 插入 %d 条耗时 %dms, 查询返回 %d 条耗时 %dms%n",
                total, writeMs, logs.size(), queryMs);

        // ① 条数一致（无丢数据）
        assertEquals(total, logs.size(), "查询条数应等于插入条数（无丢失）");

        // ② 查询 < 2s
        assertTrue(queryMs < 2000, "查询耗时 " + queryMs + "ms 应 < 2s");

        // ③ 首/中/尾抽样内容一致（无截断）
        assertTrue(logs.size() >= 3);
        for (String sample : contentSamples) {
            boolean found = logs.stream().anyMatch(m ->
                    String.valueOf(m.getOrDefault("messages", "")).contains(sample));
            assertTrue(found, "抽样内容应可检索: " + sample.substring(0, Math.min(20, sample.length())) + "...");
        }

        System.out.println("LONG-02 PASS：5000条持久化稳定、查询快速、抽样一致");
    }
}
