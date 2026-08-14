package com.roleplay.engine.simulation.conversation;

/**
 * 组类型（调研报告-移动与分组问题.md 2.4 #2）：区分「AI 自动组 / 用户加入组 / 剧本杀讨论组」，
 * 解决 AI 组 / AI / 用户组混为一谈（participants 纯名字数组无类型/玩家标记）。
 *
 * <p>默认 {@link #AI_AUTO}（2D 世界听力聚类自动建组 / 玩家消息自动 DYAD）；
 * 玩家经 {@link ConversationManager#joinGroup} 加入 → {@link #USER_JOINED}；
 * 剧本杀讨论引擎组（{@code createScriptDiscussionGroup}）→ {@link #SCRIPT_DISCUSSION}；
 * {@link #WEREWOLF_DISCUSSION} 为狼人杀讨论预留（狼人杀与剧本杀共用 createScriptDiscussionGroup，
 * 按禁动纪律本批不改 WerewolfService，狼人杀讨论组当前同样标记为 SCRIPT_DISCUSSION，
 * 后续批次可在 createScriptDiscussionGroup 传 kind 区分）。
 */
public enum GroupKind {
    /** 2D 世界听力聚类自动建组（默认）。 */
    AI_AUTO,
    /** 玩家经 joinGroup 手动加入的组。 */
    USER_JOINED,
    /** 剧本杀讨论引擎组（createScriptDiscussionGroup）。 */
    SCRIPT_DISCUSSION,
    /** 狼人杀讨论引擎组（预留；当前狼人杀讨论复用 createScriptDiscussionGroup 标为 SCRIPT_DISCUSSION）。 */
    WEREWOLF_DISCUSSION
}
