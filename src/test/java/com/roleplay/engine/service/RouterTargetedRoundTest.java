package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.AgentTaskManager;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RouterTargetedRoundTest {

    @Test
    void targetedRoundOnlyLetsSelectedConversationMembersReply() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList(), any())).thenReturn("定向回复");
        ArbiterService arbiter = mock(ArbiterService.class);
        List<String> names = List.of("甲", "乙", "丙");
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("agents", new ArrayList<>(names));
        track.put("agent_actions", Map.of("甲", "active", "乙", "active", "丙", "active"));
        when(arbiter.configureTracks(anyString(), anyList(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anySet(), any())).thenReturn(
                new ArbiterService.TrackConfigResult(List.of(track), "test"));
        when(arbiter.classifyUserInput(anyString(), anyString(), anyList()))
                .thenReturn(ArbiterService.UserInputCategory.SUPPLEMENT);
        when(arbiter.processUserInput(anyString(), any(), anyString(), anyList(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorldEventBus eventBus = new WorldEventBus();
        InterruptManager interrupts = new InterruptManager(eventBus);
        RouterService router = new RouterService(arbiter,
                new AgentExecutor(interrupts, new AgentTaskManager(interrupts)), new MemoryStore(),
                mock(Compressor.class), mock(Monitor.class), mock(GeneratorService.class),
                mock(TrackRequestService.class), llm, null, null, interrupts, eventBus, null, null);
        router.initSession("targeted", names.stream().map(name -> new Persona(name, "角色")).toList(),
                "客厅", "protagonist", "玩家", "");

        RouterService.RoundResult result = router.runRoundTargeted(
                "我们单独谈谈", null, "玩家", null, List.of("乙", "丙"));

        assertEquals(Set.of("乙", "丙"), result.agentOutputs.stream()
                .map(output -> String.valueOf(output.get("agent_name"))).collect(java.util.stream.Collectors.toSet()));

        RouterService.RoundResult lightweightFirstContact = router.runRoundTargeted(
                "你好", null, "玩家", null, List.of("尚未晋升路人"));
        assertFalse(lightweightFirstContact.status.startsWith("error"));
        assertEquals(0, lightweightFirstContact.agentOutputs.size(),
                "轻量路人首次有效消息先完成补卡晋升，不应让未创建的 Agent 冒充回复");
    }
}
