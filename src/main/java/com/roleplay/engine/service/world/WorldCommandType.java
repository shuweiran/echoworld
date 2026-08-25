package com.roleplay.engine.service.world;

/** 主控可提议、后端执行器可校验的世界动作白名单。 */
public enum WorldCommandType {
    ASSIGN_TRACK,
    SPAWN_EXTRA,
    PROMOTE_ROLE,
    SUSPEND_ROLE,
    RESUME_ROLE,
    RETIRE_ROLE,
    GENERATE_MAP,
    PUBLISH_MAP
}
