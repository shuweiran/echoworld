package com.roleplay.engine.service.world;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 会话级、仅公开投影的动态剧本快照。 */
public record DynamicStoryState(
        String sessionId,
        long revision,
        String title,
        String totalGoal,
        String stageTitle,
        String stageGoal,
        String script,
        String nextBeat,
        int tension,
        List<String> recentChanges,
        Instant updatedAt) {

    public DynamicStoryState {
        recentChanges = recentChanges == null ? List.of() : List.copyOf(recentChanges);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    /** 不暴露主控内部提示、私密角色目标或未确认事实。 */
    public Map<String, Object> publicMap() {
        return Map.of(
                "revision", revision,
                "title", title,
                "total_goal", totalGoal,
                "stage", Map.of("title", stageTitle, "goal", stageGoal, "tension", tension),
                "script", script,
                "next_beat", nextBeat,
                "recent_changes", recentChanges,
                "updated_at", updatedAt.toString());
    }

    public String directorContext() {
        return "总目标：" + totalGoal + "；当前阶段：" + stageTitle + "；阶段目标：" + stageGoal
                + "；当前剧本：" + script + "；下一拍：" + nextBeat + "；张力=" + tension
                + "；已发生：" + String.join(" / ", recentChanges);
    }
}
