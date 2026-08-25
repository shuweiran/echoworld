package com.roleplay.engine.service.world;

/** 后台地图任务的完整生命周期。 */
public enum MapJobStatus {
    QUEUED,
    GENERATING,
    VALIDATING,
    READY,
    PUBLISHED,
    FAILED,
    EXPIRED
}
