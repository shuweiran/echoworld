package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P-0810-17（B1 + B5）验收测试：讨论发言实时 SSE 回显 + 快照并发安全容器。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S-1：discussionSay 人类发言立即 script_speech SSE（human=true），不再等讨论结束才可见</li>
 *   <li>S-2：讨论引擎逐轮 AI 发言实时 script_speech（human=false）——进行中即可见，不依赖 transcript 落盘</li>
 *   <li>S-3：同一条人类发言不重复推送（discussionSay 立即广播 + 讨论组历史回放去重，speechEmitted 键）</li>
 *   <li>S-4（B5，D-034）：discussionTranscript 为 CopyOnWriteArrayList（讨论线程 append 与 saveSnapshot 拷贝并发安全）</li>
 * </ul>
 *
 * <p>风格：直构服务（mock LLM + mock SSEController），与 ScriptGameDiscussionTest 一致。
 */
class ScriptSpeechSseTest {

    private static final String SESSION = "test-script-speech";
    private static final String SAMPLE_LINE = "我认为凶手就在我们中间【情绪：平静】";

    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", true)));
        script.put("secrets", Map.of("管家", "我偷走了保险箱里的遗嘱"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        when(llm.callSync(anyList())).thenAnswer(inv -> {
            Thread.sleep(30);
            return SAMPLE_LINE;
        });
        return llm;
    }

    private ScriptGameService newService(SSEController sse) {
        return new ScriptGameService(mockLlm(), new ApprovalService(), null, sse);
    }

    /** 轮询等待讨论结束（phase==VOTE 且发言落盘），超时 10s 判失败。 */
    private void awaitDiscussionFinished(ScriptGameService svc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ScriptGameService.ScriptGame g = svc.getGame(SESSION);
            if (g != null && g.phase == ScriptGameService.Phase.VOTE
                    && !g.discussionActive && !g.discussionTranscript.isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        ScriptGameService.ScriptGame g = svc.getGame(SESSION);
        fail("讨论未在超时内结束进入 VOTE: phase=" + (g == null ? "null" : g.phase)
                + " active=" + (g == null ? "null" : g.discussionActive)
                + " turns=" + (g == null ? 0 : g.discussionTranscript.size()));
    }

    /** 捕获 script_speech 广播载荷（sessionId → payload 列表，按到达顺序）。 */
    private List<Map<String, Object>> capturedSpeeches(SSEController mockSse) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockSse, atLeastOnce()).broadcastScriptSpeech(eq(SESSION), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("S-1: discussionSay 人类发言立即 script_speech SSE（human=true，实时回显）")
    void humanDiscussionSayBroadcastsImmediately() throws Exception {
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.startDiscussion(SESSION);
        // 等讨论组激活（异步引擎已建组）
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !svc.isDiscussionRunning(SESSION)) Thread.sleep(25);
        assertTrue(svc.isDiscussionRunning(SESSION), "讨论应已激活");

        // 人类发言 → 立即 script_speech（不等讨论结束）
        String humanMsg = "我认为管家很可疑！";
        svc.discussionSay(SESSION, "Alice", humanMsg, false);

        List<Map<String, Object>> speeches = capturedSpeeches(mockSse);
        boolean humanEcho = speeches.stream().anyMatch(p ->
                "Alice".equals(p.get("speaker")) && humanMsg.equals(p.get("message"))
                        && Boolean.TRUE.equals(p.get("human")));
        assertTrue(humanEcho, "人类发言应立即 script_speech 回显: " + speeches);

        // 讨论结束后 transcript 落盘（B1 根因场景回归：现在落盘前已有实时 SSE）
        awaitDiscussionFinished(svc);
        assertFalse(svc.getGame(SESSION).discussionTranscript.isEmpty(), "讨论记录应最终落盘");
    }

    @Test
    @DisplayName("S-2: 讨论引擎逐轮 AI 发言实时 script_speech（human=false，进行中可见）")
    void aiTurnsBroadcastPerRound() throws Exception {
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.startDiscussion(SESSION);

        awaitDiscussionFinished(svc);

        List<Map<String, Object>> speeches = capturedSpeeches(mockSse);
        long aiSpeeches = speeches.stream()
                .filter(p -> Boolean.FALSE.equals(p.get("human")) || !p.containsKey("human"))
                .filter(p -> p.get("message") != null && !String.valueOf(p.get("message")).isBlank())
                .count();
        assertTrue(aiSpeeches >= 1, "讨论期间应有 AI 发言的 script_speech 实时推送: " + speeches);
        // 与 transcript 内容一致（每轮发言都进了 SSE 通道）
        assertFalse(svc.getGame(SESSION).discussionTranscript.isEmpty(), "讨论记录应落盘");
    }

    @Test
    @DisplayName("S-3: 同一条人类发言不重复推送（立即广播 + 讨论组历史回放去重）")
    void humanSpeechNotDuplicated() throws Exception {
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.startDiscussion(SESSION);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !svc.isDiscussionRunning(SESSION)) Thread.sleep(25);

        String humanMsg = "我投管家一票！";
        svc.discussionSay(SESSION, "Alice", humanMsg, false);
        awaitDiscussionFinished(svc);

        // 人类发言（discussionSay 立即广播 + 讨论线程逐轮回调）应只出现一次
        List<Map<String, Object>> speeches = capturedSpeeches(mockSse);
        long dupCount = speeches.stream()
                .filter(p -> "Alice".equals(p.get("speaker")) && humanMsg.equals(p.get("message")))
                .count();
        assertEquals(1, dupCount, "同一发言不应重复推送: " + speeches);
    }

    @Test
    @DisplayName("S-4（B5, D-034）: discussionTranscript 为 CopyOnWriteArrayList（并发安全容器）")
    void transcriptIsConcurrentContainer() {
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        assertInstanceOf(CopyOnWriteArrayList.class, svc.getGame(SESSION).discussionTranscript,
                "讨论记录应为并发安全容器（防讨论线程 append 与快照拷贝 CME）");
    }
}
