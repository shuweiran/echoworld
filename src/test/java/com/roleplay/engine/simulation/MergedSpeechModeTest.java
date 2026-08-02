package com.roleplay.engine.simulation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.AnnouncementService;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ScriptGameService;
import com.roleplay.engine.simulation.conversation.ConversationGroup;
import com.roleplay.engine.simulation.conversation.ConversationMode;
import com.roleplay.engine.simulation.conversation.SpeechStrategy;
import com.roleplay.engine.simulation.conversation.TopicManager;
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
 * 正式版 merged 模式测试（合并方案：A 的管线架构 + B 的 HearingSystem 声学判定 + 可配置兜底）。
 *
 * <p>覆盖（任务要求）：① 声学判定正确性——半径内可听→区域演讲；半径外无听众 + fallback=true
 * →全局公告；fallback=false→不升级（仅区域演讲）；② 剧本杀阶段 SYSTEM 广播在 merged 下默认触发
 * （进正式版）；③ speech-mode=merged 时 SpeechStrategy 不重复推送（防双发回归）。
 *
 * <p>管线层直构：真实 SimulationWorld + 真实 SimulationService（mock LLM/DB，真实
 * InterruptManager/AgentTaskManager/AnnouncementService）+ 记录型假 SseBroadcaster，
 * 走 publishAiSpeech（与生产 POST /api/simulation/speech 同路径）。
 */
class MergedSpeechModeTest {

    /** 记录型假 SSE 广播器（与 AnnouncementServiceTest 同模式）。 */
    private static class RecordingBroadcaster implements SseBroadcaster {
        final List<Map.Entry<String, Object>> pushes = new ArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            pushes.add(Map.entry(eventType, data));
        }
    }

    /** 管线夹具：世界（小明近小红 / 小林孤立）+ SimulationService（可配置 AppConfig）。 */
    private static class PipelineHarness {
        final SimulationWorld world = new SimulationWorld();
        final RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        final AnnouncementService service;
        final SimulationService sim;

        PipelineHarness(AppConfig config) {
            AppConfig cfg = config != null ? config : new AppConfig();
            service = new AnnouncementService(broadcaster, new WorldEventBus(), cfg);
            InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
            sim = new SimulationService(world, mock(LLMClient.class), mock(DatabaseService.class),
                    interruptManager, new AgentTaskManager(interruptManager),
                    new WorldEventBus(), service, null); // identityService（P-0802-P2；本批用例不走 playerId）
            // 布局：小明(100,100) 小红(150,100) 相距50 互听；小林(950,100) 远离所有人（与 SpeechStrategySplitModeTest 同）
            world.registerAgent(new Agent(new Persona("小明", "开朗年轻人"), "npc", null), 100, 100, 200, 60);
            world.registerAgent(new Agent(new Persona("小红", "温柔女孩"), "npc", null), 150, 100, 200, 60);
            world.registerAgent(new Agent(new Persona("小林", "文艺青年"), "npc", null), 950, 100, 200, 60);
            // 与生产 2D 世界一致：移动 tick 会 rebuild 空间网格，这里手动重建供听觉计算
            world.getSpatialGrid().rebuild(world.getAllStates().values());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastPayload(PipelineHarness h) {
        assertFalse(h.broadcaster.pushes.isEmpty(), "应有 SSE 推送");
        assertEquals(AnnouncementService.SSE_EVENT, h.broadcaster.pushes.get(h.broadcaster.pushes.size() - 1).getKey());
        return (Map<String, Object>) h.broadcaster.pushes.get(h.broadcaster.pushes.size() - 1).getValue();
    }

    // ═══════════════════════════════════════════════════════════
    //  ① 声学判定正确性（HearingSystem 单事实源）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("① 声学判定单事实源：HearingSystem.countHearingListeners 近处可听=1 / 半径外=0")
    void acousticJudgmentNearAndFar() {
        PipelineHarness h = new PipelineHarness(null);
        AgentState nearSpeaker = h.world.getState("小明");
        AgentState farSpeaker = h.world.getState("小林");

        assertEquals(1, h.world.getHearingSystem().countHearingListeners(nearSpeaker, h.world.getAllStates().values()),
                "小明(100,100) 与小红(150,100) 相距50 < 有效听觉范围 → 1 名可听听众");
        assertEquals(0, h.world.getHearingSystem().countHearingListeners(farSpeaker, h.world.getAllStates().values()),
                "小林(950,100) 距所有人 >900px，距离衰减后不可听 → 0 名可听听众");
    }

    @Test
    @DisplayName("① merged：半径内可听 → 区域演讲（area+坐标+半径，形态 speech）")
    void mergedNearAudienceProducesAreaSpeech() {
        PipelineHarness h = new PipelineHarness(null);
        assertEquals("merged", h.service.getSpeechMode(), "默认应为正式版 merged");

        Map<String, Object> resp = h.sim.publishAiSpeech("小明", "诸位听我一言！");
        assertEquals("speech", resp.get("mode"), "有听众应判定为演讲");
        assertEquals(true, resp.get("has_audience"));
        h.service.flush();

        Map<String, Object> payload = lastPayload(h);
        assertAll("区域演讲载荷",
                () -> assertEquals("area", payload.get("channel")),
                () -> assertEquals("speech", payload.get("mode")),
                () -> assertEquals("NPC", payload.get("level")),
                () -> assertEquals("小明", payload.get("speaker")),
                () -> assertEquals(100.0, payload.get("x")),
                () -> assertEquals(100.0, payload.get("y")),
                () -> assertEquals(200.0, payload.get("radius")));
    }

    @Test
    @DisplayName("① merged：半径外无听众 + fallback=true（默认）→ 升级全局公告（channel=global）")
    void mergedNoAudienceFallbackTrueUpgradesToGlobal() {
        PipelineHarness h = new PipelineHarness(null);
        assertTrue(h.service.isFallbackToGlobal(), "默认兜底应为 true");

        Map<String, Object> resp = h.sim.publishAiSpeech("小林", "孤身一人的喊话");
        assertEquals("announcement", resp.get("mode"), "无听众+兜底开 → 全局公告");
        assertEquals(false, resp.get("has_audience"));
        h.service.flush();

        Map<String, Object> payload = lastPayload(h);
        assertEquals("global", payload.get("channel"));
        assertEquals("announcement", payload.get("mode"));
        assertEquals("小林", payload.get("speaker"));
    }

    @Test
    @DisplayName("① merged：半径外无听众 + fallback=false → 不升级，仅区域演讲（纯空间语义）")
    void mergedNoAudienceFallbackFalseStaysArea() {
        AppConfig cfg = new AppConfig();
        cfg.getBroadcast().setFallbackToGlobal(false);
        PipelineHarness h = new PipelineHarness(cfg);
        assertFalse(h.service.isFallbackToGlobal(), "配置 fallback-to-global=false 应生效");

        Map<String, Object> resp = h.sim.publishAiSpeech("小林", "孤身一人的演讲");
        assertEquals("speech", resp.get("mode"), "关闭兜底后无听众也不升级，保持区域演讲");
        h.service.flush();

        Map<String, Object> payload = lastPayload(h);
        assertEquals("area", payload.get("channel"));
        assertEquals("speech", payload.get("mode"));
        assertEquals(950.0, payload.get("x"));
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 防双发回归：merged 下 SpeechStrategy 不内联推送
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("③ merged：SpeechStrategy.processResults 不内联广播（防双发回归，回调路径是唯一通道）")
    void mergedSpeechStrategyStaysSilent() {
        PipelineHarness h = new PipelineHarness(null);
        assertEquals("merged", h.service.getSpeechMode());

        SpeechStrategy strategy = new SpeechStrategy(
                name -> h.world.getAgent(name),
                s -> h.world.getWorldNarration(),
                new TopicManager(),
                h.service,
                h.world::getHearingSystem,
                () -> h.world.getAllStates().values());
        AgentState speaker = h.world.getState("小明");
        ConversationGroup g = new ConversationGroup("g-小明", ConversationMode.PUBLIC_SPEAKING, List.of(speaker));
        g.setCurrentSpeaker("小明");

        strategy.processResults(g, Map.of("小明", "诸位听我一言！"), null);
        h.service.flush();

        assertTrue(h.broadcaster.pushes.isEmpty(),
                "merged 下 SpeechStrategy 必须静默（内联只属于 split），否则会与管线层回调双发");
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 剧本杀阶段 SYSTEM 广播进正式版（merged 下无条件触发，总开关可关）
    // ═══════════════════════════════════════════════════════════

    private static final String SESSION = "test-merged-phase";

    /** 与 ScriptGamePhaseAnnouncementTest 同形状：3 角色（管家=真凶），truth 明示凶手是管家。 */
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

    @Test
    @DisplayName("② 剧本杀阶段 SYSTEM 广播：merged（默认正式版）下 initGame 即触发，无条件启用")
    void scriptPhaseBroadcastFiresInMergedDefault() {
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AnnouncementService service = new AnnouncementService(broadcaster, new WorldEventBus(), new AppConfig());
        assertEquals("merged", service.getSpeechMode(), "默认 merged（正式版）");
        assertTrue(service.isScriptPhaseBroadcast(), "阶段广播总开关默认 true");

        ScriptGameService svc = new ScriptGameService(mockLlm(), new ApprovalService(), null, null, service);
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        service.flush();

        assertFalse(broadcaster.pushes.isEmpty(), "merged 下阶段切换应发 SYSTEM 广播（不再依赖 split 模式）");
        Map<String, Object> p = (Map<String, Object>) broadcaster.pushes.get(0).getValue();
        assertEquals("SYSTEM", p.get("level"));
        assertEquals("system", p.get("channel"));
        assertTrue(((String) p.get("text")).contains("搜证阶段"), "banner 应显示阶段切换文案: " + p.get("text"));
    }

    @Test
    @DisplayName("② 剧本杀阶段广播总开关：script-phase-broadcast=false 时静默")
    void scriptPhaseBroadcastCanBeDisabled() {
        AppConfig cfg = new AppConfig();
        cfg.getBroadcast().setScriptPhaseBroadcast(false);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AnnouncementService service = new AnnouncementService(broadcaster, new WorldEventBus(), cfg);
        assertFalse(service.isScriptPhaseBroadcast(), "配置 script-phase-broadcast=false 应生效");

        ScriptGameService svc = new ScriptGameService(mockLlm(), new ApprovalService(), null, null, service);
        svc.initGame(SESSION + "-off", "庄园", List.of("Alice", "Bob", "Carol"));
        service.flush();

        assertTrue(broadcaster.pushes.isEmpty(), "总开关关闭后阶段切换不应发 SYSTEM 广播");
    }
}
