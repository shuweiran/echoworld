package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.entity.CharacterEntity;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.ArbiterService.TrackConfigResult;
import com.roleplay.engine.service.ArbiterService.UserInputCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 改造方案《玩家角色改名与 AI 识别》Phase 2 判定测试（方案 §8 用例 2，P-0802-P2）：
 * 一般模式 speaker 豁免 —— runRound 带 player_id 解析式豁免主控代声。
 *
 * <p>场景：角色库中「小明」已改名为「大明」（playerId 绑定随新名迁移，Phase 1 特性），
 * 前端仍传旧名 speaker=小明 + player_id → 判定解析出「大明」命中 agent 名单 → 豁免：
 * 原文入史（Role.AGENT）、本轮该角色排除 LLM 生成（无双声）、无主控旁白。
 *
 * <p>关键回归断言：无 player_id 请求行为与现状逐字节一致 ——
 * speaker 在名单 → 豁免（旧行为）；speaker 不在名单 → 主控旁白（旧行为）。
 *
 * <p>直接构造 RouterService（mock LLM/Arbiter，null 守卫依赖，RouterServiceSerialRoundTest 同款），
 * 走并行路径（serial=false 默认），mock {@code llm.callSync(anyList(), any())}。
 */
class RouterRenameTest {

    private static final String SESSION_ID = "router-rename-test";
    private static final String SCENE = "夜晚的庄园，管家与女仆在客厅。";

    /** 捕获的 LLM 上下文（USER 消息内容），按调用顺序（并行路径线程安全）。 */
    private final List<String> capturedContexts = Collections.synchronizedList(new ArrayList<>());

    /** 角色库绑定夹具：pid → 当前角色名（模拟改名后绑定随新名，Phase 1 特性）。 */
    private CharacterRepository boundRepo(String pid, String currentName) {
        CharacterRepository repo = mock(CharacterRepository.class);
        CharacterEntity entity = new CharacterEntity();
        entity.setName(currentName);
        when(repo.findByPlayerId(pid)).thenReturn(Optional.of(entity));
        return repo;
    }

    /** 空绑定仓库：任何 player_id 都解析不到（未绑定场景）。 */
    private CharacterRepository emptyRepo() {
        return mock(CharacterRepository.class);
    }

    /**
     * 构建 RouterService：
     * - agentNames 全员进单条 MERGED 轨道（均 active）
     * - LLM mock：捕获上下文返回「AI回应」
     * - Arbiter mock：configureTracks 固定单轨道 / processUserInput 固定旁白 / classifyUserInput SUPPLEMENT
     * - identityService = 真实 PlayerIdentityService(characterRepo)
     */
    private RouterService newRouter(CharacterRepository repo, List<String> agentNames) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Message> msgs = inv.getArgument(0);
            String userContent = msgs.stream()
                    .filter(m -> m.getRole() == Message.Role.USER)
                    .map(Message::getContent)
                    .findFirst().orElse("");
            capturedContexts.add(userContent);
            return "AI回应";
        });

        ArbiterService arbiter = mock(ArbiterService.class);
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", new ArrayList<>(agentNames));
        Map<String, String> actions = new LinkedHashMap<>();
        for (String n : agentNames) actions.put(n, "active");
        track.put("agent_actions", actions);
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(),
                anyString(), anyList(), anyList(), anySet()))
                .thenReturn(new TrackConfigResult(List.of(track), "test"));
        when(arbiter.integrateOutputs(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(Map.of("narration", "整合旁白"));
        when(arbiter.classifyUserInput(anyString(), anyString(), anyList()))
                .thenReturn(UserInputCategory.SUPPLEMENT);
        when(arbiter.processUserInput(anyString(), any(), anyString(), anyList(), anyList()))
                .thenReturn("主控旁白文本");

        InterruptManager interruptManager = new InterruptManager(new WorldEventBus());
        AgentExecutor executor = new AgentExecutor(interruptManager, new AgentTaskManager(interruptManager));

        RouterService router = new RouterService(
                arbiter, executor, new MemoryStore(), mock(Compressor.class),
                mock(Monitor.class), mock(GeneratorService.class), mock(TrackRequestService.class),
                llm, null, null, interruptManager, new WorldEventBus(), null,
                new PlayerIdentityService(repo));
        router.initSession(SESSION_ID,
                agentNames.stream().map(n -> new Persona(n, n + "的性格")).toList(),
                SCENE, "free", "", "");
        return router;
    }

    private List<Message> messages(RouterService router) {
        return router.getConversationMessages();
    }

    private boolean hasAgentMessage(RouterService router, String content) {
        return messages(router).stream()
                .anyMatch(m -> m.getRole() == Message.Role.AGENT && content.equals(m.getContent()));
    }

    private boolean hasUserMessage(RouterService router, String content) {
        return messages(router).stream()
                .anyMatch(m -> m.getRole() == Message.Role.USER && content.equals(m.getContent()));
    }

    private void assertRoundOk(RouterService.RoundResult result) {
        assertFalse(result.status.startsWith("error"), "round should complete: " + result.status);
    }

    // ── ① player_id 解析式豁免：角色已改名，旧名 speaker + player_id → 命中新名豁免 ──

    @Test
    @DisplayName("① player_id 解析式豁免：角色改名后旧名 speaker + player_id → 原文入史 AGENT、本轮该角色排除 LLM、无旁白")
    void speakerExemptViaPlayerId_afterRename() {
        // 角色库：pid-1 → 大明（「小明」已改名「大明」，绑定随新名）
        RouterService router = newRouter(boundRepo("pid-1", "大明"), List.of("大明", "小红"));

        RouterService.RoundResult result = router.runRound("我发现了新线索", null, "小明", "pid-1");

        assertRoundOk(result);
        // 原文入史（Role.AGENT，speaker=前端传的小明）
        assertTrue(hasAgentMessage(router, "我发现了新线索"), "玩家发言应原文入史（AGENT 角色说）");
        // 无主控旁白（未被 AI 化）
        assertFalse(hasUserMessage(router, "我发现了新线索"), "豁免路径不应产生主控旁白");
        // 本轮该角色（解析名=大明）排除 LLM 生成：仅剩小红生成 → 恰好 1 次 LLM 调用
        assertEquals(1, capturedContexts.size(), "大明（解析名）应从本轮生成任务排除，只剩小红 1 次 LLM");
        assertEquals(1, result.agentOutputs.size(), "本轮 agent 输出应只有小红的");
        assertEquals("小红", result.agentOutputs.get(0).get("agent_name"));
    }

    // ── ② 旧名 speaker 无 player_id → 回落主控旁白（防回归：不豁免就是旧行为） ──

    @Test
    @DisplayName("② 无 player_id：旧名 speaker 不在 agent 名单 → 主控旁白化（现状行为，防回归）")
    void oldNameSpeakerWithoutPlayerId_fallsToNarration() {
        RouterService router = newRouter(boundRepo("pid-1", "大明"), List.of("大明", "小红"));

        RouterService.RoundResult result = router.runRound("在走廊发现血迹", null, "小明");

        assertRoundOk(result);
        // 旧名「小明」不在 agent 名单且无 player_id → 主控旁白（USER 消息）
        assertTrue(hasUserMessage(router, "主控旁白文本"), "无 player_id 时旧名 speaker 应走主控旁白");
        assertFalse(hasAgentMessage(router, "在走廊发现血迹"), "旁白路径不应原文入史为 AGENT");
        // 两个 agent 都参与 LLM 生成
        assertEquals(2, capturedContexts.size(), "旁白路径两个 agent 均参与生成");
        assertEquals(2, result.agentOutputs.size());
    }

    // ── ③ 无 player_id：speaker 在名单 → 豁免（现状行为，零变化回归） ──

    @Test
    @DisplayName("③ 无 player_id：speaker 直接命中 agent 名单 → 豁免（旧行为不变）")
    void speakerInAgentsWithoutPlayerId_exemptAsBefore() {
        RouterService router = newRouter(emptyRepo(), List.of("大明", "小红"));

        RouterService.RoundResult result = router.runRound("大家听我说", null, "小红");

        assertRoundOk(result);
        assertTrue(hasAgentMessage(router, "大家听我说"), "speaker 命中名单应原文入史（旧行为）");
        assertFalse(hasUserMessage(router, "大家听我说"), "豁免路径不应产生旁白");
        assertEquals(1, capturedContexts.size(), "小红被排除，只剩大明 1 次 LLM");
        assertEquals(1, result.agentOutputs.size());
        assertEquals("大明", result.agentOutputs.get(0).get("agent_name"));
    }

    // ── ④ player_id 未绑定 → 回退 speaker 字符串逻辑（现状行为，零变化回归） ──

    @Test
    @DisplayName("④ player_id 未绑定（解析空）：回退 speaker 字符串逻辑——名单内豁免、名单外旁白")
    void unboundPlayerId_fallsBackToSpeakerString() {
        RouterService router = newRouter(emptyRepo(), List.of("大明", "小红"));

        // 未绑定 player_id + speaker 在名单 → 豁免（旧行为）
        RouterService.RoundResult r1 = router.runRound("未绑定pid但speaker在名单", null, "大明", "pid-unknown");
        assertRoundOk(r1);
        assertTrue(hasAgentMessage(router, "未绑定pid但speaker在名单"), "解析空应回退 speaker 名单豁免");
        assertEquals(1, capturedContexts.size(), "大明被排除，只剩小红 1 次 LLM");

        // 未绑定 player_id + speaker 不在名单 → 主控旁白（旧行为）
        RouterService.RoundResult r2 = router.runRound("未绑定pid且speaker不在名单", null, "路人甲", "pid-unknown");
        assertRoundOk(r2);
        assertTrue(hasUserMessage(router, "主控旁白文本"), "解析空且 speaker 不在名单应走主控旁白");
        assertEquals(3, capturedContexts.size(), "旁白路径两个 agent 均参与生成");
    }

    // ── ⑤ 无 player_id 请求行为与现状逐字节一致（全量回归锚点） ──

    @Test
    @DisplayName("⑤ 全程无 player_id：豁免/旁白双分支与 Phase 1 之前完全一致（零变化）")
    void noPlayerIdAnywhere_behaviorIdenticalToLegacy() {
        RouterService router = newRouter(emptyRepo(), List.of("大明", "小红"));

        RouterService.RoundResult result = router.runRound("老规矩", null, "小红");
        assertRoundOk(result);
        assertTrue(hasAgentMessage(router, "老规矩"));
        assertEquals(1, capturedContexts.size());
    }
}
