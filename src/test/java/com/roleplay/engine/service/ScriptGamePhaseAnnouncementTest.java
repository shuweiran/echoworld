package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.broadcast.BroadcastMessage;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 方案B Step 3 测试：剧本杀阶段切换 → SYSTEM 级广播到 announcement 全局横幅通道。
 *
 * <p>覆盖：① 五处阶段切换（initGame→investigation / startDiscussion→discussion /
 * startVoting→vote / resolveVote→reveal / confirmEnded→ended）各发一条 SYSTEM 级
 * announcement（channel=system，banner 显示「阶段切换」）；② 与 script_phase SSE
 * （会话面板通道）并存：两个通道各自收到推送，互不冲突；③ 文本含剧本名。
 *
 * <p>直构 ScriptGameService（5 参构造：mock LLM + 真实 ApprovalService +
 * mock SSEController + 记录型 AnnouncementService），与 ScriptGameEndedTest 风格一致。
 */
class ScriptGamePhaseAnnouncementTest {

    private static final String SESSION = "test-script-phase-announce";

    /** 记录型假 SSE 广播器。 */
    private static class RecordingBroadcaster implements SseBroadcaster {
        final List<Map.Entry<String, Object>> pushes = new ArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            pushes.add(Map.entry(eventType, data));
        }
    }

    /** 与 ScriptGameEndedTest 同形状：3 角色（管家=真凶），truth 明示凶手是管家。 */
    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false, "related_role", "管家"),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", true, "related_role", "")));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    /** 审批门走后台线程揭晓，主线程批准放行（与 ScriptGameEndedTest 同模式）。 */
    private Map<String, Object> resolveWithApproval(ScriptGameService svc, ApprovalService approval) throws Exception {
        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(SESSION));
        Thread.sleep(150);
        assertEquals("pending", approval.getStatus(SESSION), "揭晓应挂起等待 DM 审批");
        assertTrue(approval.approve(SESSION), "批准应成功");
        return fut.get(5, TimeUnit.SECONDS);
    }

    private Map<String, Object> lastAnnouncement(RecordingBroadcaster b) {
        assertFalse(b.pushes.isEmpty(), "应至少有一条 announcement 推送");
        return (Map<String, Object>) b.pushes.get(b.pushes.size() - 1).getValue();
    }

    /** 手动 flush（调度器只在 Spring 容器启动，单测与 AnnouncementServiceTest 同模式）。 */
    private void flush(AnnouncementService svc) {
        svc.flush();
    }

    @Test
    @DisplayName("Step 3：五处阶段切换各发 SYSTEM 级 announcement（全局横幅通道），含剧本名")
    void phaseTransitionsEmitSystemAnnouncements() throws Exception {
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AnnouncementService announcementService =
                new AnnouncementService(broadcaster, new WorldEventBus(), new AppConfig());
        ApprovalService approval = new ApprovalService();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = new ScriptGameService(mockLlm(), approval, null, mockSse, announcementService);

        // ① initGame → investigation
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        flush(announcementService);
        Map<String, Object> p1 = lastAnnouncement(broadcaster);
        assertEquals("SYSTEM", p1.get("level"));
        assertEquals("system", p1.get("channel"));
        assertEquals("system", p1.get("speaker"));
        assertTrue(((String) p1.get("text")).contains("搜证阶段"), "banner 应显示阶段切换文案: " + p1.get("text"));
        assertTrue(((String) p1.get("text")).contains("庄园疑云"), "文案应含剧本名");

        // ② startVoting → vote（从 INVESTIGATION 直接推进，确定性）
        svc.startVoting(SESSION);
        flush(announcementService);
        Map<String, Object> p2 = lastAnnouncement(broadcaster);
        assertTrue(((String) p2.get("text")).contains("投票阶段"), "vote 文案: " + p2.get("text"));

        // ③ resolveVote（审批放行）→ reveal
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String murderer = playerWithRole(game, "管家");
        List<String> others = game.players.stream().filter(p -> !p.equals(murderer)).toList();
        svc.castVote(SESSION, others.get(0), murderer);
        svc.castVote(SESSION, others.get(1), murderer);
        svc.castVote(SESSION, murderer, others.get(0));
        resolveWithApproval(svc, approval);
        flush(announcementService);
        Map<String, Object> p3 = lastAnnouncement(broadcaster);
        assertTrue(((String) p3.get("text")).contains("揭晓"), "reveal 文案: " + p3.get("text"));

        // ④ confirmEnded → ended
        svc.confirmEnded(SESSION);
        flush(announcementService);
        Map<String, Object> p4 = lastAnnouncement(broadcaster);
        assertTrue(((String) p4.get("text")).contains("对局结束"), "ended 文案: " + p4.get("text"));

        // ⑤ startDiscussion → discussion（新局，避免与后台讨论线程竞态）
        RecordingBroadcaster b2 = new RecordingBroadcaster();
        AnnouncementService svc2 = new AnnouncementService(b2, new WorldEventBus(), new AppConfig());
        ScriptGameService s2 = new ScriptGameService(mockLlm(), new ApprovalService(), null, null, svc2);
        s2.initGame(SESSION + "-d", "庄园", List.of("Alice", "Bob", "Carol"));
        s2.startDiscussion(SESSION + "-d");
        flush(svc2);
        boolean discussionFound = b2.pushes.stream()
                .map(e -> (Map<String, Object>) e.getValue())
                .anyMatch(p -> ((String) p.get("text")).contains("讨论阶段"));
        assertTrue(discussionFound, "discussion 阶段应有讨论文案广播");
    }

    @Test
    @DisplayName("Step 3：announcement SYSTEM 广播与 script_phase SSE（会话面板通道）并存不冲突")
    void announcementAndScriptPhaseCoexist() {
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AnnouncementService announcementService =
                new AnnouncementService(broadcaster, new WorldEventBus(), new AppConfig());
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = new ScriptGameService(mockLlm(), new ApprovalService(), null, mockSse, announcementService);

        svc.initGame(SESSION + "-c", "庄园", List.of("Alice", "Bob", "Carol"));
        flush(announcementService);

        // script_phase SSE（会话面板通道，台账 #35 既有契约）
        verify(mockSse, atLeastOnce()).broadcastScriptPhase(anyString(), anyString());
        // announcement SYSTEM 广播（全局横幅通道，方案B Step 3 新增）
        assertFalse(broadcaster.pushes.isEmpty(), "全局横幅通道应收到阶段广播");
        Map<String, Object> p = (Map<String, Object>) broadcaster.pushes.get(0).getValue();
        assertEquals("SYSTEM", p.get("level"));
        assertEquals(BroadcastMessage.Level.SYSTEM.prio(), 0, "SYSTEM 优先级最高");
    }
}
