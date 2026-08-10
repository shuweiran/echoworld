package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.broadcast.SseBroadcaster;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0810-17（B4）验收测试：WerewolfService.discussionSay 返回可变 map（根因修复）。
 *
 * <p>旧实现返回 {@code Map.of("ok", true, "player", player, "message", text)}（不可变），
 * WerewolfController.discussionSay 对其再 {@code put("session_id", ...)} 抛
 * UnsupportedOperationException → HTTP 500（P-0810-06 真机复现）；改 LinkedHashMap 一行修复。
 * 本用例跑真实讨论链路验证返回 map 可直接 put。
 */
class WerewolfDiscussionSayMutableTest {

    /** 录制式 SSE（WerewolfService 3 参构造用）。 */
    private static class RecordingSse implements SseBroadcaster {
        final List<Map.Entry<String, Map<?, ?>>> events = new CopyOnWriteArrayList<>();
        @Override
        public void broadcast(String eventType, Object data) {
            events.add(Map.entry(eventType, data instanceof Map<?, ?> m ? m : Map.of()));
        }
    }

    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callSync(anyList())).thenAnswer(inv -> {
            Thread.sleep(20);
            return "我认为 A 就是狼人。情绪：平静。";
        });
        return llm;
    }

    private List<String> sixPlayers() {
        return new ArrayList<>(List.of("A", "B", "C", "D", "E", "F"));
    }

    private Map<String, String> sixRoles() {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("A", "wolf");
        roles.put("B", "werewolf");
        roles.put("C", "预言家");
        roles.put("D", "witch");
        roles.put("E", "hunter");
        roles.put("F", "villager");
        return roles;
    }

    private void await(String desc, long timeoutMs, java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("等待超时: " + desc);
    }

    @Test
    @DisplayName("B4: Service 层 discussionSay 返回可变 map（可直接 put，根因修复——不再 Map.of 不可变）")
    void discussionSayReturnsMutableMap() throws Exception {
        RecordingSse sse = new RecordingSse();
        WerewolfService svc = new WerewolfService(new ApprovalService(), mockLlm(), sse);
        svc.setPlanner(new WerewolfAiPlanner(8L));
        String sid = "b4-svc-" + System.nanoTime();
        svc.initGame(sid, sixPlayers(), sixRoles());
        svc.setHumanPlayers(sid, Set.of("F"));
        svc.setAutoPlay(sid, true);
        svc.startNight(sid);
        await("讨论激活", 15_000, () -> svc.getGame(sid).discussionActive);

        Map<String, Object> r = svc.discussionSay(sid, "F", "我投 A");
        assertEquals(Boolean.TRUE, r.get("ok"), "发言应入队成功");
        assertDoesNotThrow(() -> r.put("session_id", sid),
                "B4: service 返回 map 应可变（根因 Map.of 不可变 put 崩溃）");
        assertEquals(sid, r.get("session_id"), "put 后应可读回");
    }
}
