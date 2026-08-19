package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.*;

/**
 * P-0810-17（阶段 1，两阶段生成）验收测试：概略先行 → 后台完整生成。
 *
 * <p>覆盖：
 * <ul>
 *   <li>O-1：initGame(outlineOnly=true) 只生成概略（phase=SETUP、toMap 含 outline 键、无完整剧本/角色/地图）——快返回</li>
 *   <li>O-2：generateFull 异步完成 → full 模式进 INVESTIGATION、角色/秘密/线索/AP 就位、地图生成（多图注册表 map_1）</li>
 *   <li>O-3：SSE 事件序列：script_phase(setup) → generate_full 后 script_ready(ready,map_ready=false) +
 *        script_phase(investigation) + script_status → script_ready(map_ready=true)（决策点 6：新增 script_ready 事件）</li>
 *   <li>O-4：chat 模式 generateFull → DISCUSSION → 讨论引擎自动收束进 VOTE</li>
 *   <li>O-5：守卫——非 SETUP 阶段 generateFull 拒绝（含 phase 键）；未知对局拒绝</li>
 *   <li>O-6：概略随快照落库，重启后 resumeGame 概略不丢（旧快照无 outline 键零影响）</li>
 *   <li>O-7（B2）：chat 单人局 init 直接进 VOTE（跳过 discussion 广播，防同请求内 discussion→vote 快速翻转）</li>
 * </ul>
 *
 * <p>风格：@SpringBootTest + @MockBean LLMClient + 真实 DatabaseService（快照恢复验证需要），
 * 手动构造 ScriptGameService 注入 mock SSEController（SSE 序列捕获），对齐 ScriptChatModeTest/ScriptGamePhaseAnnouncementTest。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptOutlineFullTest {

    private static final String SESSION = "test-outline-full";
    private static final String SAMPLE_LINE = "我认为凶手就在我们中间【情绪：平静】";

    @Autowired
    private DatabaseService databaseService;

    @MockBean
    private LLMClient llmClient;

    /** 剧本 mock（callJson 统一返回完整剧本；地图生成走宽容解析→BSP 兜底，对齐 ScriptChatModeTest）。 */
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
            Thread.sleep(30);
            return SAMPLE_LINE;
        });
    }

    private ScriptGameService newService(SSEController sse) {
        return new ScriptGameService(llmClient, new ApprovalService(), databaseService, sse);
    }


    // ═══════════════════════════════════════════════════════════
    //  O-1：init 概略先行（快返回，不含完整剧本）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("O-1: initGame(outlineOnly=true) 只生成概略 —— phase=SETUP、toMap 含 outline 键、无完整剧本/角色/地图")
    void outlineOnlyInitReturnsOutlineWithoutFullScript() {
        mockScriptLlm();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);

        Map<String, Object> state = svc.initGame(SESSION + "-o1", "庄园", List.of("Alice", "Bob", "Carol"), "full", true);

        ScriptGameService.ScriptGame g = svc.getGame(SESSION + "-o1");
        assertNotNull(g, "对局应已创建");
        assertEquals(ScriptGameService.Phase.SETUP, g.phase, "O-1: 概略先行应停在 SETUP 中间态");
        assertNull(g.scriptSchema, "O-1: 概略阶段不应有完整剧本 schema");
        assertTrue(g.roles.isEmpty(), "O-1: 概略阶段角色表应为空（完整剧本未生成）");
        assertTrue(g.assignments.isEmpty(), "O-1: 概略阶段无角色分配");
        assertTrue(g.clues.isEmpty(), "O-1: 概略阶段无完整线索");
        assertNull(g.mapData, "O-1: 概略阶段不生成地图（地图归 generate_full）");
        assertNotNull(g.outline, "O-1: 概略应已生成");
        assertFalse(g.outline.isEmpty(), "O-1: 概略不应为空");
        assertTrue(state.containsKey("outline"), "O-1: toMap 应暴露 outline 键");
        assertEquals("setup", state.get("phase"), "O-1: 状态 phase 应为 setup");
        assertEquals("庄园", state.get("name"), "O-1: 概略阶段剧本名用主题（完整剧本名待 generate_full）");
        // 概略结构：locations/roles/clues/storyline
        @SuppressWarnings("unchecked")
        Map<String, Object> outline = (Map<String, Object>) state.get("outline");
        assertTrue(outline.containsKey("locations"), "概略应含地点");
        assertTrue(outline.containsKey("roles"), "概略应含角色（名字+一句话人设）");
        assertTrue(outline.containsKey("clues"), "概略应含线索（标题+地点）");
        assertTrue(outline.containsKey("storyline"), "概略应含剧情线");
    }

    @Test
    @DisplayName("O-1b: SETUP 期间 Gal 发言可实时回显并排队，待完整剧本就绪后交给讨论引擎")
    void setupDiscussionSayQueuesForLaterRounds() {
        mockScriptLlm();
        ScriptGameService svc = newService(mock(SSEController.class));
        String sid = SESSION + "-setup-say";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob"), "chat", true);

        Map<String, Object> result = svc.discussionSay(sid, "Alice", "我们先从现场情况说起。", false);

        assertEquals(Boolean.TRUE, result.get("ok"));
        assertEquals(ScriptGameService.Phase.SETUP, svc.getGame(sid).phase);
        assertEquals(1, svc.getGame(sid).pendingHumanEvents.size());
    }

    // ═══════════════════════════════════════════════════════════
    //  O-2：generateFull 异步完成 → INVESTIGATION + 完整剧本 + 地图
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("O-2: generateFull 异步生成完整剧本+地图 → full 进 INVESTIGATION、角色/秘密/线索/AP/地图就位")
    void generateFullCompletesIntoInvestigation() throws Exception {
        mockScriptLlm();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        String sid = SESSION + "-o2";

        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"), "full", true);
        assertEquals(ScriptGameService.Phase.SETUP, svc.getGame(sid).phase);

        // 异步提交（虚拟线程），立即返回 generating 标记
        Map<String, Object> res = svc.generateFull(sid);
        assertEquals(Boolean.TRUE, res.get("ok"), "O-2: generateFull 应接受请求");
        assertEquals(Boolean.TRUE, res.get("generating"), "O-2: 响应应标记 generating");
        assertEquals("setup", res.get("phase"), "O-2: 生成期间阶段仍为 setup");

        // 轮询等待：完整剧本就位（phase=INVESTIGATION 且 generating 复位）
        long deadline = System.currentTimeMillis() + 15_000;
        ScriptGameService.ScriptGame g = null;
        while (System.currentTimeMillis() < deadline) {
            g = svc.getGame(sid);
            if (g != null && g.phase == ScriptGameService.Phase.INVESTIGATION && !g.generating) break;
            Thread.sleep(50);
        }
        assertNotNull(g, "对局应存在");
        assertEquals(ScriptGameService.Phase.INVESTIGATION, g.phase, "O-2: full 模式完整剧本就绪后应进搜证阶段");
        assertFalse(g.generating, "O-2: 生成完成后 generating 应复位");
        assertNotNull(g.scriptSchema, "O-2: 完整剧本 schema 应已就位");
        assertEquals(3, g.roles.size(), "O-2: 角色表应就位（3 角色）");
        assertEquals(3, g.assignments.size(), "O-2: 角色分配应就位");
        assertEquals(1, g.secrets.size(), "O-2: 秘密按 schema 发放（mock 仅管家有秘密，D5 部分秘密保持部分）");
        assertTrue(g.secrets.containsKey("管家"), "O-2: 管家秘密应发放");
        assertFalse(g.clues.isEmpty(), "O-2: 线索应就位");
        assertNotNull(g.mapData, "O-2: 地图应已生成（LLM→校验→BSP 兜底）");
        assertEquals("map_1", g.currentMapId, "O-2: 首个地图应注册为 map_1");
        assertFalse(g.playerAp.isEmpty(), "O-2: AP 应已按角色分配");
        // 概略保留（供前端对照；完整剧本生效后概略键仍在）
        assertNotNull(g.outline, "O-2: 概略应保留");
        // toMap 完整视图（玩家 0 视角）
        Map<String, Object> m = g.toMap("Alice");
        assertEquals("investigation", m.get("phase"));
        assertTrue(m.containsKey("outline"), "toMap 仍应含概略键");
        assertFalse(String.valueOf(m.get("your_role")).isBlank(), "完整剧本就绪后玩家应拿到角色分配");
        assertTrue(m.containsKey("roles"), "toMap 应含完整角色表");
    }

    // ═══════════════════════════════════════════════════════════
    //  O-3：SSE 事件序列（script_ready 新增事件）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("O-3: SSE 序列 —— init script_phase(setup) → generate_full script_ready(map_ready=false) + "
            + "script_phase(investigation) + script_status → script_ready(map_ready=true)")
    void sseEventSequenceOnGenerateFull() throws Exception {
        mockScriptLlm();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        String sid = SESSION + "-o3";

        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"), "full", true);
        // init 概略态应推 script_phase(setup)
        verify(mockSse).broadcastScriptPhase(sid, "setup");

        svc.generateFull(sid);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ScriptGameService.ScriptGame g = svc.getGame(sid);
            if (g != null && g.phase == ScriptGameService.Phase.INVESTIGATION && !g.generating) break;
            Thread.sleep(50);
        }
        assertEquals(ScriptGameService.Phase.INVESTIGATION, svc.getGame(sid).phase);

        // script_ready：完整剧本就绪（map_ready=false）→ 地图就绪（map_ready=true）两段
        ArgumentCaptor<Map<String, Object>> readyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockSse, atLeast(2)).broadcastScriptReady(eq(sid), readyCaptor.capture());
        List<Map<String, Object>> readyEvents = readyCaptor.getAllValues();
        boolean firstMapNotReady = readyEvents.stream().anyMatch(e -> Boolean.FALSE.equals(e.get("map_ready")));
        boolean mapReady = readyEvents.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("map_ready")));
        assertTrue(firstMapNotReady, "应推送「完整剧本就绪（地图待生成）」的 script_ready: " + readyEvents);
        assertTrue(mapReady, "地图生成完成应推送 map_ready=true 的 script_ready: " + readyEvents);
        Map<String, Object> last = readyEvents.get(readyEvents.size() - 1);
        assertEquals(Boolean.TRUE, last.get("ready"), "script_ready 应含 ready=true");
        assertEquals("investigation", last.get("phase"), "script_ready 应含当前阶段");

        // 阶段推送：setup → investigation 顺序（InOrder）
        InOrder inOrder = inOrder(mockSse);
        inOrder.verify(mockSse).broadcastScriptPhase(sid, "setup");
        inOrder.verify(mockSse, atLeastOnce()).broadcastScriptPhase(sid, "investigation");
        // 状态推送至少一次（生成完成后全量状态）
        verify(mockSse, atLeastOnce()).broadcastScriptStatus(eq(sid), anyMap());
    }

    // ═══════════════════════════════════════════════════════════
    //  O-4：chat 模式两阶段 → DISCUSSION → VOTE
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("O-4: chat 模式 outlineOnly init → generateFull → DISCUSSION 自动讨论 → VOTE")
    void chatModeTwoStageEndsInVote() throws Exception {
        mockScriptLlm();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        String sid = SESSION + "-o4";

        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"), "chat", true);
        assertEquals(ScriptGameService.Phase.SETUP, svc.getGame(sid).phase, "chat 概略态也停 SETUP");
        assertEquals("chat", svc.getGame(sid).mode, "mode 应保留 chat");

        svc.generateFull(sid);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ScriptGameService.ScriptGame g = svc.getGame(sid);
            if (g != null && (g.phase == ScriptGameService.Phase.DISCUSSION || g.phase == ScriptGameService.Phase.VOTE)
                    && !g.generating) break;
            Thread.sleep(50);
        }
        ScriptGameService.ScriptGame g = svc.getGame(sid);
        assertTrue(g.phase == ScriptGameService.Phase.DISCUSSION || g.phase == ScriptGameService.Phase.VOTE,
                "chat 完整生成应直达 DISCUSSION（可能已自动收束 VOTE）: " + g.phase);

        // 等讨论收束进 VOTE（与 ScriptChatModeTest C-3 同语义）
        deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            g = svc.getGame(sid);
            if (g != null && g.phase == ScriptGameService.Phase.VOTE
                    && !g.discussionActive && !g.discussionTranscript.isEmpty()) break;
            Thread.sleep(50);
        }
        assertEquals(ScriptGameService.Phase.VOTE, g.phase, "O-4: chat 讨论结束应自动进投票");
        assertFalse(g.discussionTranscript.isEmpty(), "O-4: 讨论发言应落盘");
    }

    // ═══════════════════════════════════════════════════════════
    //  O-5：守卫
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("O-5: 守卫 —— 非 SETUP 阶段 / 未知对局 generateFull 拒绝")
    void generateFullGuards() {
        mockScriptLlm();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);

        // 未知对局
        Map<String, Object> r1 = svc.generateFull("no-such-session");
        assertEquals("游戏不存在", r1.get("error"), "O-5: 未知对局应拒绝");

        // 已完整生成的对局（outlineOnly=false 直接完整路径）→ 非 SETUP 拒绝
        svc.initGame(SESSION + "-o5", "庄园", List.of("Alice", "Bob", "Carol"), "full", false);
        Map<String, Object> r2 = svc.generateFull(SESSION + "-o5");
        assertNotNull(r2.get("error"), "O-5: 非 SETUP 阶段应拒绝 generateFull");
        assertEquals("investigation", r2.get("phase"), "O-5: 拒绝响应应含当前 phase（前端可感知）");
    }

    // ═══════════════════════════════════════════════════════════
    //  O-6：概略随快照落库，重启恢复
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("O-6: 概略随快照落库 —— 新实例 resumeGame 恢复后 outline 键不丢、阶段仍 SETUP")
    void outlinePersistsInSnapshotAndRestores() {
        mockScriptLlm();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);
        String sid = SESSION + "-o6";

        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"), "full", true);
        // 玩家 roleKey（概略态已发放）
        String key = svc.getGame(sid).getPlayerKeys().values().iterator().next();
        assertNotNull(key, "概略态也应发放 roleKey（重连认证）");

        // 新实例（同 databaseService，games 缓存为空）→ resumeGame 走快照重建
        ScriptGameService svc2 = newService(mock(SSEController.class));
        Map<String, Object> view = svc2.resumeGame(sid, key);

        assertEquals("setup", view.get("phase"), "O-6: 恢复后应仍为 SETUP 概略态");
        assertTrue(Boolean.TRUE.equals(view.get("restored")) || Boolean.TRUE.equals(view.get("resumed")),
                "O-6: 应走快照恢复路径");
        assertTrue(view.containsKey("outline"), "O-6: 恢复视图应含 outline 键（概略不丢）");
        @SuppressWarnings("unchecked")
        Map<String, Object> outline = (Map<String, Object>) view.get("outline");
        assertTrue(outline.containsKey("locations"), "恢复的概略应完整");
    }

    // ═══════════════════════════════════════════════════════════
    //  O-7（B2）：chat 单人局 init 直接进 VOTE（防 phase 快速翻转）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("O-7（B2）: chat 单人局 init 直接进 VOTE，不推 discussion 广播（防同请求内快速翻转）")
    void chatSinglePlayerSkipsDiscussionBroadcast() {
        mockScriptLlm();
        SSEController mockSse = mock(SSEController.class);
        ScriptGameService svc = newService(mockSse);

        svc.initGame(SESSION + "-o7", "庄园", List.of("Alice"), "chat", false);
        ScriptGameService.ScriptGame g = svc.getGame(SESSION + "-o7");
        assertEquals(ScriptGameService.Phase.VOTE, g.phase, "O-7: 单人局无讨论对象应直接进投票");
        // 未推 discussion（旧行为同一请求内 discussion→vote 连发，前端竞态只见 vote）
        verify(mockSse, never()).broadcastScriptPhase(eq(SESSION + "-o7"), eq("discussion"));
        verify(mockSse, atLeastOnce()).broadcastScriptPhase(eq(SESSION + "-o7"), eq("vote"));
    }
}
