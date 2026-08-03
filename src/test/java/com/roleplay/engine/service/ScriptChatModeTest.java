package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P-0803-K（剧本杀双版本）验收测试：简单对话版（mode=chat）—— 无取证、无地图、直接多人对话讨论。
 *
 * <p>设计出处：蓝图《剧本杀差距分析-待办.md》v3 Step 3v「降级路径（轮次发言：按 assignments 轮流
 * 每人 1 条消息，轮数可配置，结束自动进 VOTE）」—— chat 模式复用全部剧本生成/角色/秘密/讨论引擎，
 * 仅跳过 INVESTIGATION（无搜证）与自动地图串联。
 *
 * <p>覆盖验收：
 * <ul>
 *   <li>C-1：initGame(mode=chat) 后 phase==DISCUSSION、mode=="chat"、toMap 暴露 mode 且无 map 键</li>
 *   <li>C-2：搜证被阶段守卫拦截（当前不是搜证阶段）</li>
 *   <li>C-3：讨论引擎自动启动（discussionActive）→ 讨论结束自动进 VOTE（蓝图降级路径自动收束）</li>
 *   <li>C-4：缺省/显式 full 模式零变化（phase==INVESTIGATION、mode=="full"）</li>
 *   <li>C-5：mode 随快照落库，新实例 resumeGame 从快照恢复仍为 chat（重连后前端仍按简单版渲染）</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptChatModeTest {

    private static final String SESSION_CHAT = "test-chat-mode";
    private static final String SESSION_FULL = "test-full-mode";
    private static final String SAMPLE_LINE = "我认为凶手就在我们中间【情绪：平静】";

    @Autowired
    private ScriptGameService svc;

    @Autowired
    private DatabaseService databaseService;

    @MockBean
    private LLMClient llmClient;

    /** 剧本 mock（chat 模式无地图 LLM 调用，单一 callJson stub 即可；full 模式地图走宽松解析→BSP 兜底）。 */
    private void mockScriptLlm() {
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
        when(llmClient.callJson(anyString(), anyInt())).thenReturn(script);
        when(llmClient.callSync(anyList())).thenAnswer(inv -> {
            Thread.sleep(50);
            return SAMPLE_LINE;
        });
    }

    /** 轮询等待讨论结束（phase==VOTE 且发言落盘），超时 10s 判失败（对齐 ScriptGameDiscussionTest）。 */
    private void awaitChatDiscussionFinished(String session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ScriptGameService.ScriptGame g = svc.getGame(session);
            if (g != null && g.phase == ScriptGameService.Phase.VOTE
                    && !g.discussionActive && !g.discussionTranscript.isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        ScriptGameService.ScriptGame g = svc.getGame(session);
        fail("讨论未在超时内结束进入 VOTE: phase=" + (g == null ? "null" : g.phase)
                + " active=" + (g == null ? "null" : g.discussionActive)
                + " turns=" + (g == null ? 0 : g.discussionTranscript.size()));
    }

    @Test
    @DisplayName("C-1: chat 模式 init 后直达 DISCUSSION（无搜证）、toMap 暴露 mode 且无地图")
    void chatModeSkipsInvestigationAndStartsDiscussion() {
        mockScriptLlm();
        Map<String, Object> state = svc.initGame(SESSION_CHAT, "庄园", List.of("Alice", "Bob", "Carol"), "chat");

        ScriptGameService.ScriptGame g = svc.getGame(SESSION_CHAT);
        assertNotNull(g, "对局应已创建");
        assertEquals(ScriptGameService.Phase.DISCUSSION, g.phase, "C-1: 简单对话版应跳过搜证直达讨论");
        assertEquals("chat", g.mode, "C-1: 对局模式应为 chat");
        assertTrue(g.discussionActive, "C-1: 讨论引擎应在 init 后自动启动");
        assertNull(g.mapData, "C-1: 简单对话版不应自动生成地图");

        Map<String, Object> m = g.toMap("Alice");
        assertEquals("chat", m.get("mode"), "C-1: toMap 应暴露 mode=chat（前端据此隐藏搜证/地图 UI）");
        assertEquals("discussion", m.get("phase"), "C-1: 状态 phase 应为 discussion");
        assertFalse(m.containsKey("map"), "C-1: 状态不应携带地图键");
        assertNotNull(state.get("session_id"), "init 响应应携带 session_id（重连定位）");
    }

    @Test
    @DisplayName("C-2: 简单对话版搜证被阶段守卫拦截（无取证）")
    void chatModeRejectsSearch() {
        mockScriptLlm();
        svc.initGame(SESSION_CHAT, "庄园", List.of("Alice", "Bob", "Carol"), "chat");

        Map<String, Object> res = svc.search(SESSION_CHAT, "Alice", "客厅");
        assertEquals("当前不是搜证阶段", res.get("error"), "C-2: 非 INVESTIGATION 阶段搜证应被拒绝");
        assertFalse(res.containsKey("clues"), "C-2: 拒绝时不应下发线索");
    }

    @Test
    @DisplayName("C-3: 讨论引擎自动驱动，结束自动进 VOTE（蓝图降级路径收束）")
    void chatModeDiscussionEndsIntoVote() throws Exception {
        mockScriptLlm();
        svc.initGame(SESSION_CHAT, "庄园", List.of("Alice", "Bob", "Carol"), "chat");

        awaitChatDiscussionFinished(SESSION_CHAT);

        ScriptGameService.ScriptGame g = svc.getGame(SESSION_CHAT);
        assertEquals(ScriptGameService.Phase.VOTE, g.phase, "C-3: 讨论结束应自动进入投票");
        assertFalse(g.discussionActive, "C-3: 讨论结束后 active 应为 false");
        assertFalse(g.discussionTranscript.isEmpty(), "C-3: 讨论发言记录应非空（多人对话有产出）");
        assertTrue(g.toMap("Alice").containsKey("discussion"), "C-3: toMap 应暴露讨论记录");
    }

    @Test
    @DisplayName("C-4: 缺省/显式 full 模式零变化（真剧本杀仍从 INVESTIGATION 开始）")
    void defaultModeUnchanged() {
        mockScriptLlm();
        svc.initGame(SESSION_FULL, "庄园", List.of("Alice", "Bob", "Carol"), "full");

        ScriptGameService.ScriptGame g = svc.getGame(SESSION_FULL);
        assertEquals(ScriptGameService.Phase.INVESTIGATION, g.phase, "C-4: 真剧本杀仍从搜证阶段开始");
        assertEquals("full", g.mode, "C-4: 缺省模式应为 full");
        assertFalse(g.discussionActive, "C-4: 真剧本杀 init 不自动启动讨论");
        assertEquals("investigation", g.toMap("Alice").get("phase"), "C-4: 状态 phase 应为 investigation");
    }

    @Test
    @DisplayName("C-5: mode 随快照落库，重启后 resumeGame 从快照恢复仍为 chat")
    void chatModeSnapshotRestoreKeepsMode() throws Exception {
        mockScriptLlm();
        svc.initGame(SESSION_CHAT, "庄园", List.of("Alice", "Bob", "Carol"), "chat");
        // 讨论结束后最新快照已含 mode（等待期间多次 saveSnapshot：init 快照 + 讨论结束 VOTE 快照）
        awaitChatDiscussionFinished(SESSION_CHAT);
        String key = svc.getGame(SESSION_CHAT).getPlayerKeys().values().iterator().next();
        assertNotNull(key);

        // 新实例（同 databaseService，games 缓存为空）→ resumeGame 走快照重建
        ScriptGameService svc2 = new ScriptGameService(llmClient, new ApprovalService(), databaseService, null, null);
        Map<String, Object> view = svc2.resumeGame(SESSION_CHAT, key);

        assertEquals("chat", view.get("mode"), "C-5: 快照恢复后对局模式应保持 chat");
        assertTrue(Boolean.TRUE.equals(view.get("resumed")) || Boolean.TRUE.equals(view.get("restored")),
                "C-5: 应走恢复路径");
        assertNotNull(view.get("phase"), "C-5: 恢复视图应含阶段");
    }
}
