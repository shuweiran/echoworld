package com.roleplay.engine.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P-0820-O：房间墙体必须阻断普通对话声学链路。 */
class HearingSystemObstacleTest {

    @Test
    @DisplayName("同一听觉范围内，blocksSound 墙体阻断室内外互听；移除墙体后可恢复")
    void wallBlocksConversationHearing() {
        AgentState inside = new AgentState("室内", 470, 300);
        AgentState outside = new AgentState("室外", 530, 300);
        inside.setHearRange(200);
        outside.setHearRange(200);
        SpatialGrid grid = new SpatialGrid(1000, 600, 100);
        grid.rebuild(List.of(inside, outside));
        HearingSystem hearing = new HearingSystem(grid);

        assertTrue(hearing.canHearEachOther(inside, outside), "无墙时近距离角色应可互听");
        hearing.setObstacles(List.of(new Obstacle(Obstacle.Type.WALL, 495, 240, 10, 120, true, "房间外墙")));
        assertFalse(hearing.canHearEachOther(inside, outside), "有 blocksSound 外墙时不得隔墙对话");
        assertTrue(hearing.computeAudibility(List.of(inside, outside)).isEmpty(), "遮挡组合不应进入可听候选集");
    }
}
