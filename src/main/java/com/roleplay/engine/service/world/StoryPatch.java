package com.roleplay.engine.service.world;

/** 主控对“尚未发生的剧情”提出的受限补丁；历史事实与总目标不在此处可写。 */
public record StoryPatch(
        String stageTitle,
        String stageGoal,
        String scriptPatch,
        String nextBeat,
        String change,
        Integer tension) {
}
