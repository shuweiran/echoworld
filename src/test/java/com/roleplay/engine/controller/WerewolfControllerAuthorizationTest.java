package com.roleplay.engine.controller;

import com.roleplay.engine.service.WerewolfService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** 狼人杀 Controller 身份边界：玩家操作用 roleKey，管理操作用 DM key。 */
class WerewolfControllerAuthorizationTest {

    private static final String SID = "ww-auth";
    private static final String PLAYER = "F";
    private static final String PLAYER_KEY = "player-secret";
    private static final String DM_KEY = "dm-secret";

    @Test
    @DisplayName("玩家行动必须显式携带 session/player/player_key，且 key 必须匹配")
    void playerActionsRequireMatchingRoleKey() {
        WerewolfService service = mock(WerewolfService.class);
        WerewolfController controller = new WerewolfController(service);

        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("player", PLAYER);
        assertEquals(400, controller.nightAction(missing).getStatusCode().value());
        verify(service, never()).recordNightAction(anyString(), anyString(), anyString(), anyString());

        Map<String, String> wrong = credentials("wrong-key");
        wrong.put("action", "kill");
        wrong.put("target", "A");
        when(service.isPlayerKeyValid(SID, PLAYER, "wrong-key")).thenReturn(false);
        assertEquals(403, controller.nightAction(wrong).getStatusCode().value());
        verify(service, never()).recordNightAction(anyString(), anyString(), anyString(), anyString());

        when(service.isPlayerKeyValid(SID, PLAYER, PLAYER_KEY)).thenReturn(true);
        when(service.recordNightAction(SID, PLAYER, "kill", "A")).thenReturn("ok");
        Map<String, String> valid = credentials(PLAYER_KEY);
        valid.put("action", "kill");
        valid.put("target", "A");
        ResponseEntity<Map<String, Object>> accepted = controller.nightAction(valid);
        assertEquals(200, accepted.getStatusCode().value());
        verify(service).recordNightAction(SID, PLAYER, "kill", "A");
    }

    @Test
    @DisplayName("猎人、讨论、投票和私密状态均复用同一玩家身份边界")
    void allPlayerEndpointsShareAuthorizationBoundary() {
        WerewolfService service = mock(WerewolfService.class);
        WerewolfController controller = new WerewolfController(service);
        when(service.isPlayerKeyValid(SID, PLAYER, PLAYER_KEY)).thenReturn(true);
        when(service.hunterShoot(SID, PLAYER, "A")).thenReturn("shot");
        when(service.discussionSay(SID, PLAYER, "发言")).thenReturn(Map.of("ok", true));
        when(service.castVote(SID, PLAYER, "A")).thenReturn("voted");
        when(service.statusMap(SID, PLAYER)).thenReturn(Map.of("session_id", SID, "your_role", "villager"));

        Map<String, String> hunter = credentials(PLAYER_KEY);
        hunter.put("target", "A");
        assertEquals(200, controller.hunterShoot(hunter).getStatusCode().value());

        Map<String, String> discussion = credentials(PLAYER_KEY);
        discussion.put("message", "发言");
        assertEquals(200, controller.discussionSay(discussion).getStatusCode().value());

        Map<String, Object> vote = new LinkedHashMap<>(credentials(PLAYER_KEY));
        vote.put("target", "A");
        assertEquals(200, controller.vote(vote).getStatusCode().value());

        assertEquals(200, controller.getStatus(PLAYER, "", SID, PLAYER_KEY)
                .getStatusCode().value());
        assertEquals(403, controller.getStatus(PLAYER, "", SID, "other")
                .getStatusCode().value());

        verify(service).hunterShoot(SID, PLAYER, "A");
        verify(service).discussionSay(SID, PLAYER, "发言");
        verify(service).castVote(SID, PLAYER, "A");
        verify(service).statusMap(SID, PLAYER);
    }

    @Test
    @DisplayName("roleKey 一览与管理推进必须配置并匹配 X-DM-Key")
    void dmEndpointsRequireConfiguredMatchingKey() {
        WerewolfService service = mock(WerewolfService.class);
        WerewolfController controller = new WerewolfController(service);
        when(service.getPlayerKeys(SID)).thenReturn(Map.of(PLAYER, PLAYER_KEY));
        when(service.resolveNight(SID)).thenReturn(Map.of("phase", "day_discuss"));
        when(service.resolveVote(SID)).thenReturn(Map.of("phase", "night"));

        assertEquals(403, controller.getKeys(SID, DM_KEY).getStatusCode().value(),
                "服务端未配置 DM key 时也必须拒绝，不能匿名放开");
        verify(service, never()).getPlayerKeys(anyString());

        ReflectionTestUtils.setField(controller, "dmKey", DM_KEY);
        assertEquals(403, controller.getKeys(SID, "wrong").getStatusCode().value());
        assertEquals(200, controller.getKeys(SID, DM_KEY).getStatusCode().value());

        Map<String, String> session = Map.of("session_id", SID);
        assertEquals(403, controller.resolveNight(session, "wrong").getStatusCode().value());
        assertEquals(200, controller.resolveNight(session, DM_KEY).getStatusCode().value());
        assertEquals(200, controller.startVoting(session, DM_KEY).getStatusCode().value());
        assertEquals(200, controller.resolveVote(session, DM_KEY).getStatusCode().value());

        verify(service).getPlayerKeys(SID);
        verify(service).resolveNight(SID);
        verify(service).startVoting(SID);
        verify(service).resolveVote(SID);
    }

    private Map<String, String> credentials(String key) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("session_id", SID);
        body.put("player", PLAYER);
        body.put("player_key", key);
        return body;
    }
}
