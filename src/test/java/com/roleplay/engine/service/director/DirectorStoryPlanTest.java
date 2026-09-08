package com.roleplay.engine.service.director;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectorStoryPlanTest {

    @Test
    void openingPlanKeepsStoryStructureAsAuthoritativeData() {
        DirectorStoryPlan plan = new DirectorStoryPlan();
        plan.setPremise("暴雨封路后，一名住客在山间旅店失踪");
        plan.setTone("克制的悬疑群像");
        plan.setOpeningSituation("所有人被迫留在旅店大厅，停电刚刚恢复");
        plan.setStakes("如果天亮前找不到失踪者，山路恢复后关键证据会被带走");
        assertTrue(plan.setSceneIdentity("兔子", "以采访暴雨灾情为名入住的记者").accepted());
        assertTrue(plan.setCharacterGoal("兔子", "找出失踪者最后见过的人").accepted());
        assertTrue(plan.setCharacterSecret("兔子", "她其实提前收到过失踪者的求救短信").accepted());
        assertTrue(plan.setRelationship("未然", "兔子", "临时合作", "彼此需要但互不完全信任").accepted());
        assertTrue(plan.addStoryBeat("大厅的备用电话被发现遭到人为剪线").accepted());
        assertTrue(plan.addOpeningEvent("当玩家第一次追问停电时间时，旅店老板拿出互相矛盾的维修记录").accepted());
        assertTrue(plan.addWorldFact("暴雨导致唯一出山道路暂时中断").accepted());

        Map<String, Object> raw = plan.toMap();
        DirectorStoryPlan restored = DirectorStoryPlan.fromMap(raw);

        assertEquals("暴雨封路后，一名住客在山间旅店失踪", restored.premise());
        assertEquals("以采访暴雨灾情为名入住的记者", restored.sceneIdentities().get("兔子"));
        assertEquals("找出失踪者最后见过的人", restored.characterGoals().get("兔子"));
        assertEquals("她其实提前收到过失踪者的求救短信", restored.characterSecrets().get("兔子"));
        assertEquals(1, restored.storyBeats().size());
        assertEquals(1, restored.openingEvents().size());
        assertEquals(1, restored.relationships().size());
    }

    @Test
    void sharedDirectiveNeverContainsCharacterSecretsOrPrivateGoals() {
        DirectorStoryPlan plan = new DirectorStoryPlan();
        plan.setPremise("封闭旅店失踪案");
        plan.setSceneIdentity("兔子", "记者");
        plan.setCharacterGoal("兔子", "暗中确认鲸鱼是否撒谎");
        plan.setCharacterSecret("兔子", "持有失踪者的录音");
        plan.addStoryBeat("第二幕再揭示录音存在");

        String shared = plan.authoritativeDirective();
        assertTrue(shared.contains("封闭旅店失踪案"));
        assertTrue(shared.contains("兔子：记者"));
        assertTrue(shared.contains("第二幕再揭示录音存在"));
        assertFalse(shared.contains("暗中确认鲸鱼是否撒谎"));
        assertFalse(shared.contains("持有失踪者的录音"));
    }

    @Test
    void eachRoleOnlyReceivesItsOwnPrivateGuidance() {
        DirectorStoryPlan plan = new DirectorStoryPlan();
        plan.setCharacterGoal("兔子", "找到录音来源");
        plan.setCharacterSecret("兔子", "你认识失踪者");
        plan.setCharacterGoal("鲸鱼", "避免身份暴露");
        plan.setCharacterSecret("鲸鱼", "你昨晚进入过地下室");

        Map<String, String> guidance = plan.privateGuidance(List.of("兔子", "鲸鱼", "未然"));
        assertTrue(guidance.get("兔子").contains("找到录音来源"));
        assertTrue(guidance.get("兔子").contains("你认识失踪者"));
        assertFalse(guidance.get("兔子").contains("地下室"));
        assertTrue(guidance.get("鲸鱼").contains("避免身份暴露"));
        assertTrue(guidance.get("鲸鱼").contains("地下室"));
        assertFalse(guidance.get("鲸鱼").contains("录音来源"));
        assertFalse(guidance.containsKey("未然"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeViewHidesNpcSecretsGoalsAndFutureBeatsButShowsPlayersOwnCard() {
        DirectorStoryPlan plan = new DirectorStoryPlan();
        plan.setCharacterGoal("未然", "找出谁在撒谎");
        plan.setCharacterSecret("未然", "你收到匿名警告");
        plan.setCharacterGoal("兔子", "取得录音");
        plan.setCharacterSecret("兔子", "你知道地下室入口");
        plan.addStoryBeat("第三轮后停电");
        plan.addOpeningEvent("调查电话时触发争吵");

        Map<String, Object> view = plan.runtimeView("未然");
        assertEquals("找出谁在撒谎", view.get("your_goal"));
        assertEquals("你收到匿名警告", view.get("your_secret"));
        assertEquals(Boolean.TRUE, view.get("hidden_plan"));
        assertEquals(List.of(), view.get("story_beats"));
        assertEquals(List.of(), view.get("opening_events"));
        assertFalse(view.containsKey("character_goals"));
        assertFalse(view.containsKey("character_secrets"));
        assertFalse(view.toString().contains("地下室入口"));
        assertFalse(view.toString().contains("取得录音"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestCanSeedExistingSceneMetadataBeforeDirectorChat() {
        DirectorStoryPlan plan = DirectorStoryPlan.fromRequest(Map.of(
                "story_premise", "社团活动结束后有人发现教室门被反锁",
                "story_tone", "校园悬疑",
                "opening_situation", "三个人仍留在教室",
                "character_goals", Map.of("未然", "找到备用钥匙", "兔子", "查清是谁锁门"),
                "character_secrets", Map.of("兔子", "你其实看见过钥匙被拿走"),
                "world_facts", List.of("窗户从内部上锁")));

        assertEquals("校园悬疑", plan.tone());
        assertEquals("三个人仍留在教室", plan.openingSituation());
        assertEquals("查清是谁锁门", plan.characterGoals().get("兔子"));
        assertEquals("你其实看见过钥匙被拿走", plan.characterSecrets().get("兔子"));
        assertEquals(List.of("窗户从内部上锁"), plan.worldFacts());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sceneAndCharacterPromptsBecomeDirectorOnlyAuthoringSource() {
        DirectorStoryPlan plan = DirectorStoryPlan.fromRequest(Map.of(
                "scene_description", "雨夜便利店。凌晨两点，暴雨导致街区停电，卷帘门暂时无法打开。",
                "characters", List.of(
                        Map.of(
                                "name", "未然",
                                "persona", "谨慎、善于观察，不轻易相信陌生人",
                                "background", "刚结束夜班，偶然被困在店内",
                                "talk_style", "短句、先观察后回应"),
                        Map.of(
                                "name", "兔子",
                                "personality", "外向机灵，但遇到真正危险会掩饰紧张",
                                "background", "自称只是来躲雨",
                                "intro", "总在留意门口和监控",
                                "talkStyle", "轻快，偶尔用玩笑转移话题"))));

        assertTrue(plan.scenePrompt().contains("暴雨导致街区停电"));
        assertTrue(plan.characterPrompts().get("未然").contains("谨慎、善于观察"));
        assertTrue(plan.characterPrompts().get("未然").contains("刚结束夜班"));
        assertTrue(plan.characterPrompts().get("兔子").contains("总在留意门口和监控"));
        assertTrue(plan.characterPrompts().get("兔子").contains("轻快，偶尔用玩笑"));

        Map<String, Object> authoring = plan.toMap();
        Map<String, Object> source = (Map<String, Object>) authoring.get("source_material");
        assertTrue(String.valueOf(source.get("authoring_rule")).contains("优先依据场景 Prompt 与人物 Prompt"));
        assertTrue(String.valueOf(source.get("character_prompts")).contains("兔子"));

        DirectorStoryPlan restored = DirectorStoryPlan.fromMap(authoring);
        assertEquals(plan.scenePrompt(), restored.scenePrompt());
        assertEquals(plan.characterPrompts(), restored.characterPrompts());

        Map<String, Object> runtime = plan.runtimeView("未然");
        assertFalse(runtime.containsKey("source_material"));
        assertFalse(runtime.toString().contains("总在留意门口和监控"));
        assertFalse(runtime.toString().contains("刚结束夜班"));
    }
}
