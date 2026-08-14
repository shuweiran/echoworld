package com.roleplay.engine.simulation;

import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0814-A：POST /api/simulation/playback_done 端点路由——
 * group_id 路径（2D 对话组推进）与无 group_id 路径（一般模式 RouterService 轮次推进）。
 * 直接调用 controller 方法（MockMvc 直构同款，零 Spring）。
 */
class SimulationControllerPlaybackDoneTest {

    private SimulationController controller(SimulationService simulationService, SessionRegistry sessions) {
        return new SimulationController(simulationService, mock(SimulationWorld.class),
                mock(CharacterController.class), sessions);
    }

    @Test
    @DisplayName("① group_id 路径：转发到 SimulationService.notifyPlaybackDone")
    void groupId_routesToSimulationService() {
        SimulationService sim = mock(SimulationService.class);
        when(sim.notifyPlaybackDone("A+B")).thenReturn(true);
        SimulationController c = controller(sim, null); // 组路径不依赖 SessionRegistry

        Map<String, Object> r = c.playbackDone(Map.of("group_id", "A+B", "session_id", "s1"));
        assertEquals(true, r.get("ok"));
        assertEquals(true, r.get("advanced"));
        assertEquals("A+B", r.get("group_id"));
    }

    @Test
    @DisplayName("② group_id 路径：组不存在/未等待 → advanced=false + error（幂等不报错）")
    void groupId_notFound_returnsAdvancedFalse() {
        SimulationService sim = mock(SimulationService.class);
        when(sim.notifyPlaybackDone("ghost")).thenReturn(false);
        SimulationController c = controller(sim, null);

        Map<String, Object> r = c.playbackDone(Map.of("group_id", "ghost"));
        assertEquals(true, r.get("ok"));
        assertEquals(false, r.get("advanced"));
        assertTrue(String.valueOf(r.get("error")).contains("not awaiting"), "应含原因: " + r.get("error"));
    }

    @Test
    @DisplayName("③ 无 group_id 路径：转发到 SessionRegistry.get(session_id).onPlaybackDone")
    void noGroupId_routesToRouter() {
        RouterService router = mock(RouterService.class);
        when(router.onPlaybackDone()).thenReturn(true);
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.get("s1")).thenReturn(router);
        SimulationController c = controller(mock(SimulationService.class), sessions);

        Map<String, Object> r = c.playbackDone(Map.of("session_id", "s1"));
        assertEquals(true, r.get("ok"));
        assertEquals(true, r.get("advanced"));
    }

    @Test
    @DisplayName("④ 无 group_id 路径：非等待态（重复信号/未开启）→ advanced=false 不报错")
    void noGroupId_notAwaiting_returnsAdvancedFalse() {
        RouterService router = mock(RouterService.class);
        when(router.onPlaybackDone()).thenReturn(false);
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.get("s1")).thenReturn(router);
        SimulationController c = controller(mock(SimulationService.class), sessions);

        Map<String, Object> r = c.playbackDone(Map.of("session_id", "s1"));
        assertEquals(true, r.get("ok"));
        assertEquals(false, r.get("advanced"));
    }

    @Test
    @DisplayName("⑤ 无 group_id 且 SessionRegistry 不可用（3 参旧构造）→ 明确报错不崩")
    void noGroupId_noRegistry_returnsError() {
        SimulationController c = controller(mock(SimulationService.class), null);
        Map<String, Object> r = c.playbackDone(Map.of());
        assertEquals(false, r.get("ok"));
        assertTrue(String.valueOf(r.get("error")).contains("registry"), "应提示 registry 不可用");
    }

    @Test
    @DisplayName("⑥ group_id 空白视为无 group_id（回退 session 路径）")
    void blankGroupId_fallsBackToSessionPath() {
        RouterService router = mock(RouterService.class);
        when(router.onPlaybackDone()).thenReturn(true);
        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.get("s1")).thenReturn(router);
        SimulationController c = controller(mock(SimulationService.class), sessions);

        Map<String, Object> r = c.playbackDone(Map.of("session_id", "s1", "group_id", "  "));
        assertEquals(true, r.get("advanced"), "空白 group_id 应回退 session 路径");
        assertFalse(r.containsKey("group_id"), "不应按组路径处理");
    }
}
