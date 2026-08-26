package com.roleplay.engine.simulation.track;

import com.roleplay.engine.core.Track;
import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.HearingSystem;
import com.roleplay.engine.simulation.Obstacle;
import com.roleplay.engine.simulation.SpatialGrid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The core invariant: an agent cannot receive a merged context through a sound-blocking wall. */
class SpatialTrackResolverObstacleIntegrationTest {

    @Test
    void nearbyAgentsBecomeIsolatedWhenBlocksSoundWallIsInserted() {
        AgentState alice = agent("Alice", 470, 300);
        AgentState bob = agent("Bob", 530, 300);
        SpatialGrid grid = new SpatialGrid(1000, 600, 100);
        grid.rebuild(List.of(alice, bob));
        HearingSystem hearing = new HearingSystem(grid);
        SpatialTrackResolver resolver = new SpatialTrackResolver(70, java.util.Set.of(), hearing);

        assertEquals(Track.Mode.MERGED, resolver.resolve(List.of(alice, bob)).get("Alice").type());

        hearing.setObstacles(List.of(new Obstacle(Obstacle.Type.WALL, 495, 240, 10, 120, true, "sound wall")));
        Map<String, TrackAssignment> afterWall = resolver.resolve(List.of(alice, bob));
        assertEquals(Track.Mode.ISOLATED, afterWall.get("Alice").type());
        assertEquals(Track.Mode.ISOLATED, afterWall.get("Bob").type());
    }

    private static AgentState agent(String name, double x, double y) {
        AgentState state = new AgentState(name, x, y);
        state.setHearRange(200);
        return state;
    }
}
