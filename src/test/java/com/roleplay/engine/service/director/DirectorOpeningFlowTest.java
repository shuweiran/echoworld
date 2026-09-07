package com.roleplay.engine.service.director;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectorOpeningFlowTest {

    @Test
    @SuppressWarnings("unchecked")
    void preflightStartsWithStoryDesignInsteadOfOnlyStageOrder() {
        DirectorAgentService service = new DirectorAgentService(null, null, null, null);
        Map<String, Object> created = service.createPreflight(Map.ofEntries(
                Map.entry("scene_id", "雨夜旅店"),
                Map.entry("scene_description", "暴雨封路的山间旅店"),
                Map.entry("player", Map.of("name", "未然", "persona", "谨慎")),
                Map.entry("characters", List.of(
                        Map.of("name", "未然", "persona", "谨慎"),
                        Map.of("name", "兔子", "persona", "敏锐"))),
                Map.entry("relationships", List.of("未然和兔子刚认识")),
                Map.entry("entry_order", List.of("未然", "兔子")),
                Map.entry("onstage", List.of("未然", "兔子")),
                Map.entry("story_premise", "旅店中一名住客离奇失踪"),
                Map.entry("story_tone", "悬疑群像"),
                Map.entry("opening_situation", "停电刚恢复，所有人聚在大厅"),
                Map.entry("character_goals", Map.of("兔子", "找到失踪者最后见过的人")),
                Map.entry("character_secrets", Map.of("兔子", "你收到过失踪者的求救短信"))));

        Map<String, Object> plan = (Map<String, Object>) created.get("story_plan");
        assertEquals("旅店中一名住客离奇失踪", plan.get("premise"));
        assertEquals("悬疑群像", plan.get("tone"));
        assertEquals("停电刚恢复，所有人聚在大厅", plan.get("opening_situation"));
        assertTrue(created.get("reply").toString().contains("剧本"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void offlineFallbackCanEditPremiseToneOpeningIdentityGoalAndSecret() {
        DirectorAgentService service = new DirectorAgentService(null, null, null, null);
        Map<String, Object> created = service.createPreflight(Map.of(
                "scene_id", "测试",
                "scene_description", "测试场景",
                "player", Map.of("name", "未然"),
                "characters", List.of(Map.of("name", "未然"), Map.of("name", "兔子")),
                "entry_order", List.of("未然", "兔子"),
                "onstage", List.of("未然", "兔子")));
        String id = String.valueOf(((Map<String, Object>) created.get("state")).get("preflight_id"));

        Map<String, Object> storyChanged = service.chatPreflight(id,
                "剧本设定是雨夜失踪案。基调是压抑悬疑。开场局势是停电刚恢复。核心冲突是天亮前必须找到失踪者");
        Map<String, Object> plan = (Map<String, Object>) storyChanged.get("story_plan");
        assertEquals("雨夜失踪案", plan.get("premise"));
        assertEquals("压抑悬疑", plan.get("tone"));
        assertEquals("停电刚恢复", plan.get("opening_situation"));
        assertEquals("天亮前必须找到失踪者", plan.get("stakes"));

        Map<String, Object> identityChanged = service.chatPreflight(id, "兔子的身份是调查记者");
        Map<String, Object> identityPlan = (Map<String, Object>) identityChanged.get("story_plan");
        assertEquals("调查记者", ((Map<String, String>) identityPlan.get("scene_identities")).get("兔子"));

        Map<String, Object> goalChanged = service.chatPreflight(id, "兔子的目标是找出最后见过失踪者的人");
        Map<String, Object> goalPlan = (Map<String, Object>) goalChanged.get("story_plan");
        assertEquals("找出最后见过失踪者的人", ((Map<String, String>) goalPlan.get("character_goals")).get("兔子"));

        Map<String, Object> secretChanged = service.chatPreflight(id, "兔子的秘密是她提前收到过求救短信");
        Map<String, Object> secretPlan = (Map<String, Object>) secretChanged.get("story_plan");
        assertEquals("她提前收到过求救短信", ((Map<String, String>) secretPlan.get("character_secrets")).get("兔子"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authoringStoryDoesNotAccidentallyConfirmGameStart() {
        DirectorAgentService service = new DirectorAgentService(null, null, null, null);
        Map<String, Object> created = service.createPreflight(Map.of(
                "scene_id", "测试",
                "scene_description", "测试场景",
                "player", Map.of("name", "未然"),
                "characters", List.of(Map.of("name", "未然"), Map.of("name", "兔子")),
                "entry_order", List.of("未然", "兔子"),
                "onstage", List.of("未然", "兔子")));
        String id = String.valueOf(((Map<String, Object>) created.get("state")).get("preflight_id"));

        Map<String, Object> changed = service.chatPreflight(id, "剧本设定是校园悬疑，兔子的身份是记者");
        Map<String, Object> state = (Map<String, Object>) changed.get("state");
        assertEquals(Boolean.FALSE, state.get("confirmed"));
    }
}
