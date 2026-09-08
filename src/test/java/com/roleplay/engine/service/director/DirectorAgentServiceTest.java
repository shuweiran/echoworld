package com.roleplay.engine.service.director;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectorAgentServiceTest {

    @Test
    void playerIdentityIsStoryScopedAndDurableInAuthoritativeState() {
        DirectorSession state = sampleSession();
        assertEquals("未然", state.playerName());
        assertEquals("pid-test", state.player().get("player_id"));
        assertEquals("谨慎但会主动保护朋友", state.player().get("persona"));
        assertTrue(state.authoritativeDirective().contains("玩家扮演：未然"));
        assertTrue(state.authoritativeDirective().contains("谨慎但会主动保护朋友"));
    }

    @Test
    void whaleStageChangesDoNotChangeRoster() {
        DirectorSession state = sampleSession();
        assertTrue(state.setStage("鲸鱼", false).accepted());
        assertTrue(state.cast().stream().anyMatch(c -> "鲸鱼".equals(c.get("name"))));
        assertFalse(state.onstage().contains("鲸鱼"));
        assertTrue(state.offstageNames().contains("鲸鱼"));

        assertTrue(state.setStage("鲸鱼", true).accepted());
        assertTrue(state.onstage().contains("鲸鱼"));
        assertFalse(state.offstageNames().contains("鲸鱼"));
    }

    @Test
    void playerCannotBeSilentlyRemovedByDirector() {
        DirectorSession state = sampleSession();
        DirectorSession.StageResult result = state.setStage("未然", false);
        assertFalse(result.accepted());
        assertTrue(state.onstage().contains("未然"));
    }

    @Test
    void mixedClauseDoesNotApplyWhaleDepartureToRabbit() {
        DirectorAgentService service = new DirectorAgentService(null, null, null, null);
        Map<String, Object> created = service.createPreflight(preflightBody());
        String id = String.valueOf(state(created).get("preflight_id"));

        Map<String, Object> changed = service.chatPreflight(id, "先让兔子和我在场，鲸鱼先离场");
        Map<String, Object> s = state(changed);
        assertTrue(strings(s.get("onstage")).contains("兔子"));
        assertFalse(strings(s.get("onstage")).contains("鲸鱼"));
        assertTrue(strings(s.get("offstage")).contains("鲸鱼"));

        Map<String, Object> query = service.chatPreflight(id, "现在谁在场？");
        String reply = String.valueOf(query.get("reply"));
        assertTrue(reply.contains("兔子"));
        assertTrue(reply.contains("鲸鱼"));

        Map<String, Object> returned = service.chatPreflight(id, "把鲸鱼拉进来");
        assertTrue(strings(state(returned).get("onstage")).contains("鲸鱼"));
    }

    @Test
    void characterEntryIsNotGameStartConfirmation() {
        assertFalse(DirectorAgentService.isExplicitEntryConfirmation("让兔子进入场景，鲸鱼先等等"));
        assertFalse(DirectorAgentService.isExplicitEntryConfirmation("把鲸鱼拉进来"));
        assertTrue(DirectorAgentService.isExplicitEntryConfirmation("确认进入场景"));
        assertTrue(DirectorAgentService.isExplicitEntryConfirmation("就这样吧，开始"));

        DirectorAgentService service = new DirectorAgentService(null, null, null, null);
        Map<String, Object> created = service.createPreflight(preflightBody());
        String id = String.valueOf(state(created).get("preflight_id"));
        Map<String, Object> characterEntry = service.chatPreflight(id, "让兔子进入场景，鲸鱼先等等");
        assertEquals(Boolean.FALSE, state(characterEntry).get("confirmed"));
        Map<String, Object> confirmed = service.chatPreflight(id, "确认进入场景");
        assertEquals(Boolean.TRUE, state(confirmed).get("confirmed"));
    }

    @Test
    void directorHistoryIsBoundedButFactsRemain() {
        DirectorSession state = sampleSession();
        for (int i = 0; i < 75; i++) state.addMessage(i % 2 == 0 ? "user" : "assistant", "m" + i);
        assertEquals(DirectorSession.MAX_MESSAGES, state.messages().size());
        assertEquals("未然", state.playerName());
        assertEquals(List.of("未然", "兔子", "鲸鱼"), state.onstage());
    }

    @Test
    void entryOrderFiltersUnknownAndDeduplicates() {
        DirectorSession state = sampleSession();
        state.setEntryOrder(List.of("鲸鱼", "不存在", "兔子", "鲸鱼"));
        assertEquals(List.of("鲸鱼", "兔子", "未然"), state.entryOrder());
    }

    @Test
    void stateRoundTripKeepsStableFactsAndStageTruth() {
        DirectorSession state = sampleSession();
        state.setStage("鲸鱼", false);
        state.addRelationship("未然与兔子是旧友");
        state.addSceneNote("鲸鱼稍后从门外进入");
        DirectorSession restored = DirectorSession.fromMap(state.toMap());
        assertEquals("未然", restored.playerName());
        assertEquals("pid-test", restored.player().get("player_id"));
        assertTrue(restored.offstageNames().contains("鲸鱼"));
        assertTrue(restored.relationships().contains("未然与兔子是旧友"));
        assertTrue(restored.authoritativeDirective().contains("鲸鱼稍后从门外进入"));
    }

    @Test
    void dynamicNpcRegistrationAddsRosterAndStageWithoutReplacingExistingCharacters() {
        DirectorSession state = sampleSession();
        DirectorSession.RegisterResult result = state.registerCharacter(
                role("林夏", "怕生、谨慎，但会观察周围人的情绪"), true);

        assertTrue(result.accepted());
        assertTrue(state.knowsCharacter("林夏"));
        assertTrue(state.onstage().contains("林夏"));
        assertTrue(state.entryOrder().contains("林夏"));
        assertEquals("活泼敏锐", state.cast().stream()
                .filter(c -> "兔子".equals(c.get("name")))
                .findFirst().orElseThrow().get("persona"));
        assertTrue(state.authoritativeDirective().contains("林夏"));
    }

    @Test
    void dynamicNpcRegistrationCanStartOffstageAndRejectsDuplicateName() {
        DirectorSession state = sampleSession();
        DirectorSession.RegisterResult first = state.registerCharacter(
                role("林夏", "安静的临时服务员"), false);
        DirectorSession.RegisterResult duplicate = state.registerCharacter(
                role("林夏", "恶意覆盖的人设"), true);

        assertTrue(first.accepted());
        assertFalse(duplicate.accepted());
        assertFalse(state.onstage().contains("林夏"));
        assertTrue(state.offstageNames().contains("林夏"));
        assertEquals("安静的临时服务员", state.cast().stream()
                .filter(c -> "林夏".equals(c.get("name")))
                .findFirst().orElseThrow().get("persona"));
    }

    @Test
    void dynamicNpcSurvivesDirectorStateRoundTrip() {
        DirectorSession state = sampleSession();
        assertTrue(state.registerCharacter(role("林夏", "怕生"), true).accepted());

        DirectorSession restored = DirectorSession.fromMap(state.toMap());
        assertTrue(restored.knowsCharacter("林夏"));
        assertTrue(restored.onstage().contains("林夏"));
        assertTrue(restored.entryOrder().contains("林夏"));
    }

    private static DirectorSession sampleSession() {
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("name", "未然");
        player.put("player_id", "pid-test");
        player.put("persona", "谨慎但会主动保护朋友");
        return new DirectorSession("pf-test", "测试场景", "客厅夜谈", player,
                List.of(
                        role("未然", "谨慎但会主动保护朋友"),
                        role("兔子", "活泼敏锐"),
                        role("鲸鱼", "慢热温和")
                ),
                List.of("未然与兔子是旧友"),
                List.of("未然", "兔子", "鲸鱼"),
                List.of("未然", "兔子", "鲸鱼"));
    }

    private static Map<String, Object> preflightBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scene_id", "测试场景");
        body.put("scene_description", "客厅夜谈");
        body.put("player", Map.of(
                "name", "未然",
                "player_id", "pid-test",
                "persona", "谨慎但会主动保护朋友"));
        body.put("characters", List.of(
                role("未然", "谨慎但会主动保护朋友"),
                role("兔子", "活泼敏锐"),
                role("鲸鱼", "慢热温和")));
        body.put("relationships", List.of("未然与兔子是旧友"));
        body.put("entry_order", List.of("未然", "兔子", "鲸鱼"));
        body.put("onstage", List.of("未然", "兔子", "鲸鱼"));
        return body;
    }

    private static Map<String, Object> role(String name, String persona) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("persona", persona);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Map<String, Object> response) {
        return (Map<String, Object>) response.get("state");
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) out.add(String.valueOf(item));
        return out;
    }
}
