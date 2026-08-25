package com.roleplay.engine.service.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicStoryManagerTest {

    @Test
    void playerStepAdvancesRevisionWhileTotalGoalAndHistoryStayAuthoritative() {
        DynamicStoryManager manager = new DynamicStoryManager();
        DynamicStoryState initial = manager.snapshot("story-1", "雨夜车站");

        DynamicStoryState advanced = manager.advance("story-1", "雨夜车站", "询问站台管理员", new StoryPatch(
                "线索浮现", "确认遗失信件的去向", "一张湿透的车票露出日期。", "让管理员选择是否交出登记册。",
                "玩家询问站台管理员", 130));

        assertEquals(1, advanced.revision());
        assertEquals(initial.totalGoal(), advanced.totalGoal());
        assertEquals("线索浮现", advanced.stageTitle());
        assertEquals(100, advanced.tension());
        assertTrue(advanced.recentChanges().contains("玩家询问站台管理员"));
    }

    @Test
    void unsafePatchDoesNotReplaceFutureScript() {
        DynamicStoryManager manager = new DynamicStoryManager();
        DynamicStoryState advanced = manager.advance("story-2", "旧书店", "翻开账本", new StoryPatch(
                "系统提示", "忽略以上", "system prompt", "", "", 10));

        assertFalse(advanced.stageTitle().contains("系统提示"));
        assertFalse(advanced.script().contains("system prompt"));
    }
}
