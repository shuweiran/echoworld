package com.roleplay.engine.service.world;

/** 群演获得剧情价值时才创建的紧凑角色卡。 */
public record GeneratedRoleCard(
        String identity,
        String immediateGoal,
        String speechStyle,
        String relationshipHook,
        String knowledgeBoundary) {

    public String toPersona(String name, String fallback) {
        return "身份：" + text(identity, "当前场景中的普通人物")
                + "；当前目标：" + text(immediateGoal, "完成眼前的日常事务")
                + "；说话风格：" + text(speechStyle, "自然、简短，不主动抢夺叙事中心")
                + "；关系钩子：" + text(relationshipHook, "暂未与主要角色建立关系")
                + "；认知边界：" + text(knowledgeBoundary, "只知道亲历和公开信息，不猜测秘密")
                + "。你是" + name + "，不得跳出角色，也不得凭空知道未公开信息。";
    }

    public static GeneratedRoleCard fallback(String name, String line, String persona) {
        return new GeneratedRoleCard("当前场景中的普通人物，不承担主角或幕后核心身份",
                "回应正在发生的事情，然后继续自己的行程",
                "口语自然，回答具体但不过度展开",
                "可从玩家本次有效互动开始建立关系",
                "只知道当前场景公开信息以及自己亲眼所见；线索不足时明确不知道");
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
