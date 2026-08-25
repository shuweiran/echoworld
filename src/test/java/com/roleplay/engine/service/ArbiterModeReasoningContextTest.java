package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArbiterModeReasoningContextTest {

    @Test
    void werewolfControllerReceivesHistoryAndPrivacyReasoningProtocol() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());
        ArbiterService arbiter = new ArbiterService(llm);

        arbiter.configureTracks("月夜村庄", List.of("阿明", "小雪"), "昨夜无人死亡，阿明怀疑小雪", "werewolf",
                null, List.of(), List.of(), Set.of());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).callJson(prompt.capture(), org.mockito.ArgumentMatchers.eq(600));
        assertTrue(prompt.getValue().contains("昨夜无人死亡，阿明怀疑小雪"));
        assertTrue(prompt.getValue().contains("【狼人杀主控推理协议】"));
        assertTrue(prompt.getValue().contains("不能把未公开的身份或夜间信息泄露到公开轨道"));
    }

    @Test
    void generalControllerSeparatesFactsClaimsAndHypotheses() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(Map.of());
        ArbiterService arbiter = new ArbiterService(llm);

        arbiter.configureTracks("旧书店", List.of("甲", "乙"), "甲说发现一封信", "free",
                null, List.of(), List.of("核验信件来源"), Set.of());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).callJson(prompt.capture(), org.mockito.ArgumentMatchers.eq(600));
        assertTrue(prompt.getValue().contains("已发生事实"));
        assertTrue(prompt.getValue().contains("待验证推测"));
    }
}
