package com.roleplay.engine.service.world;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态剧本的 Java 权威状态机。
 * 主控只能补全尚未发生的阶段和后续拍点；总目标、已发生变化均由此类保留。
 */
public final class DynamicStoryManager {
    private final ConcurrentHashMap<String, DynamicStoryState> stories = new ConcurrentHashMap<>();

    public DynamicStoryState snapshot(String sessionId, String scene) {
        return stories.computeIfAbsent(sessionId, id -> initial(id, scene));
    }

    public DynamicStoryState advance(String sessionId, String scene, String playerStep, StoryPatch patch) {
        return stories.compute(sessionId, (id, previous) -> {
            DynamicStoryState current = previous == null ? initial(id, scene) : previous;
            String change = clean(patch == null ? "" : patch.change(), 100);
            if (change.isBlank()) change = "玩家采取行动：" + clean(playerStep, 80);
            List<String> changes = new ArrayList<>(current.recentChanges());
            changes.add(change);
            while (changes.size() > 6) changes.removeFirst();
            String stageTitle = choose(patch == null ? "" : patch.stageTitle(), current.stageTitle(), 40);
            String stageGoal = choose(patch == null ? "" : patch.stageGoal(), current.stageGoal(), 100);
            String script = choose(patch == null ? "" : patch.scriptPatch(),
                    "第" + (current.revision() + 1) + "拍：" + change, 180);
            String next = choose(patch == null ? "" : patch.nextBeat(),
                    "让角色对刚发生的变化作出可见回应，并保留玩家选择空间。", 100);
            int tension = patch != null && patch.tension() != null
                    ? Math.max(0, Math.min(100, patch.tension()))
                    : Math.min(100, current.tension() + 4);
            return new DynamicStoryState(id, current.revision() + 1, current.title(), current.totalGoal(),
                    stageTitle, stageGoal, script, next, tension, changes, Instant.now());
        });
    }

    public void remove(String sessionId) {
        if (sessionId != null) stories.remove(sessionId);
    }

    private static DynamicStoryState initial(String sessionId, String scene) {
        String label = clean(scene, 50);
        if (label.isBlank()) label = "当前场景";
        return new DynamicStoryState(sessionId, 0, "《" + label + "》", "在" + label + "中识别正在变化的关系与线索，并由玩家作出自己的选择。",
                "开场", "建立人物、地点与玩家行动之间的第一个可见联系。",
                "序幕：世界尚未替玩家写下结局。", "给玩家一个可回应、可拒绝或可追问的戏剧性线索。",
                20, List.of(), Instant.now());
    }

    private static String choose(String candidate, String fallback, int max) {
        String clean = clean(candidate, max);
        return clean.isBlank() ? fallback : clean;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String out = value.replaceAll("[\\r\\n]+", " ").replaceAll("[\\p{Cntrl}]", " ").trim();
        if (out.contains("忽略以上") || out.contains("系统提示") || out.toLowerCase().contains("system prompt")) return "";
        return out.length() <= max ? out : out.substring(0, max);
    }
}
