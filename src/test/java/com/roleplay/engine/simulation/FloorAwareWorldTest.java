package com.roleplay.engine.simulation;

import com.roleplay.engine.simulation.navigation.PathStep;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.spatial.Vec3;
import com.roleplay.engine.simulation.navigation.portal.*;
import com.roleplay.engine.simulation.track.SpatialTrackResolver;
import com.roleplay.engine.simulation.director.TrackDirectorService;
import com.roleplay.engine.core.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FloorAwareWorldTest {
    @Test
    void movementChangesFloorOnlyThroughAnAuthoritativePortalStep() {
        AgentState agent = new AgentState("a", 10, 10);
        agent.getSpatial().setNavLocation(new NavLocation("s1", "f1", new Vec3(10, 0, 10), -1));
        agent.setInConversation(true);
        agent.setNavigationSteps(List.of(PathStep.usePortal("stairs", new Vec3(10, 3, 10), "f2", "s2")));
        new MovementSystem(100, 100, 0, new SpatialGrid(100, 100, 20)).update(List.of(agent), 0.2);
        assertEquals("f2", agent.navLocation().floorId());
        assertEquals("s2", agent.navLocation().surfaceId());
        assertEquals(0, agent.getVx());
        assertEquals(0, agent.getVy());
    }

    @Test
    void sealedFloorsDoNotHearEvenWhenXYOverlaps() {
        SpatialGrid grid = new SpatialGrid(100, 100, 20);
        HearingSystem hearing = new HearingSystem(grid);
        AgentState a = new AgentState("a", 10, 10);
        AgentState b = new AgentState("b", 10, 10);
        a.getSpatial().setNavLocation(new NavLocation("s1", "f1", new Vec3(10, 0, 10), -1));
        b.getSpatial().setNavLocation(new NavLocation("s2", "f2", new Vec3(10, 3, 10), -1));
        grid.rebuild(List.of(a, b));
        assertFalse(hearing.canHear(a, b, SpeechVolume.NORMAL));
    }

    @Test
    void stairConnectorProvidesAttenuatedCrossFloorHearing() {
        SpatialGrid grid = new SpatialGrid(100, 100, 20);
        HearingSystem hearing = new HearingSystem(grid);
        SemanticPortal stairs = new SemanticPortal("stairs", SemanticPortal.Kind.STAIRS,
                new PortalEndpoint("f1", "s1", new Vec3(10, 0, 10)),
                new PortalEndpoint("f2", "s2", new Vec3(10, 3, 10)), true, 1, "", Set.of("acoustic"));
        hearing.setSemanticPortals(List.of(stairs), Map.of("stairs", PortalRuntimeState.available("stairs")));
        AgentState a = new AgentState("a", 10, 10);
        AgentState b = new AgentState("b", 10, 10);
        a.getSpatial().setNavLocation(new NavLocation("s1", "f1", new Vec3(10, 0, 10), -1));
        b.getSpatial().setNavLocation(new NavLocation("s2", "f2", new Vec3(10, 3, 10), -1));
        assertTrue(hearing.canHear(a, b, SpeechVolume.NORMAL));
    }

    @Test
    void eachConnectorHopAttenuatesFurtherAndClosedConnectorRemovesThePath() {
        SpatialGrid grid = new SpatialGrid(100, 100, 20);
        HearingSystem hearing = new HearingSystem(grid);
        SemanticPortal s12 = stairs("s12", "f1", "s1", "f2", "s2", 10);
        SemanticPortal s23 = stairs("s23", "f2", "s2", "f3", "s3", 10);
        Map<String, PortalRuntimeState> states = new java.util.HashMap<>();
        states.put("s12", PortalRuntimeState.available("s12"));
        states.put("s23", PortalRuntimeState.available("s23"));
        hearing.setSemanticPortals(List.of(s12, s23), states);
        AgentState a = agent("a", "f1", "s1");
        AgentState b = agent("b", "f2", "s2");
        AgentState c = agent("c", "f3", "s3");
        grid.rebuild(List.of(a, b, c));

        Map<String, HearingSystem.HearingResult> fromA = hearing.computeAudibility(List.of(a, b, c)).stream()
                .filter(result -> result.speakerName().equals("a"))
                .collect(java.util.stream.Collectors.toMap(HearingSystem.HearingResult::listenerName, result -> result));
        assertTrue(fromA.get("b").effectiveRange() > fromA.get("c").effectiveRange());
        assertEquals(0.45, fromA.get("b").effectiveRange() / a.getHearRange(), 0.001);
        assertEquals(0.45 * 0.45, fromA.get("c").effectiveRange() / a.getHearRange(), 0.001);

        states.put("s23", new PortalRuntimeState("s23", PortalRuntimeState.Availability.CLOSED, 1, "test"));
        assertFalse(hearing.canHear(a, c, SpeechVolume.NORMAL));
    }

    @Test
    void sourceFloorWallAndConnectorLossUseOneAcousticRoute() {
        SpatialGrid grid = new SpatialGrid(100, 100, 20);
        HearingSystem hearing = new HearingSystem(grid);
        SemanticPortal stairs = stairs("stairs", "f1", "s1", "f2", "s2", 50);
        hearing.setSemanticPortals(List.of(stairs), Map.of("stairs", PortalRuntimeState.available("stairs")));
        AgentState a = agent("a", "f1", "s1", 10, 10);
        AgentState b = agent("b", "f2", "s2", 50, 10);
        assertTrue(hearing.canHear(a, b, SpeechVolume.NORMAL));

        hearing.setObstacles(List.of(new Obstacle(Obstacle.Type.WALL, 28, 0, 4, 30,
                true, "sealed source room", "f1")));
        assertFalse(hearing.canHear(a, b, SpeechVolume.SHOUT),
                "a wall before the acoustic connector must compose with connector propagation");
    }

    @Test
    void trackIsIsolatedAcrossSealedFloorsAndWeakAcrossStairwell() {
        SpatialGrid grid = new SpatialGrid(100, 100, 20);
        HearingSystem hearing = new HearingSystem(grid);
        AgentState a = agent("a", "f1", "s1");
        AgentState b = agent("b", "f2", "s2");
        SpatialTrackResolver resolver = new SpatialTrackResolver(70, Set.of(), hearing);
        assertEquals(Track.Mode.ISOLATED, resolver.resolve(List.of(a, b)).get("a").type());

        SemanticPortal stairs = new SemanticPortal("stairs", SemanticPortal.Kind.STAIRS,
                new PortalEndpoint("f1", "s1", new Vec3(10, 0, 10)),
                new PortalEndpoint("f2", "s2", new Vec3(10, 3, 10)), true, 1, "", Set.of("acoustic"));
        hearing.setSemanticPortals(List.of(stairs), Map.of("stairs", PortalRuntimeState.available("stairs")));
        assertEquals(Track.Mode.WEAK, resolver.resolve(List.of(a, b)).get("a").type());
    }

    @Test
    void secretAgentRemainsIsolatedEvenWithAcousticConnector() {
        SpatialGrid grid = new SpatialGrid(100, 100, 20);
        HearingSystem hearing = new HearingSystem(grid);
        AgentState a = agent("a", "f1", "s1");
        AgentState b = agent("b", "f2", "s2");
        hearing.setSemanticPortals(List.of(new SemanticPortal("stairs", SemanticPortal.Kind.STAIRS,
                new PortalEndpoint("f1", "s1", new Vec3(10, 0, 10)),
                new PortalEndpoint("f2", "s2", new Vec3(10, 3, 10)), true, 1, "", Set.of("acoustic"))),
                Map.of("stairs", PortalRuntimeState.available("stairs")));
        TrackDirectorService director = new TrackDirectorService();
        director.setHearingSystem(hearing);
        director.addSecretAgent("b");
        assertEquals(Track.Mode.ISOLATED, director.assign(List.of(a, b)).get("b").type());
    }

    private static AgentState agent(String name, String floor, String surface) {
        return agent(name, floor, surface, 10, 10);
    }

    private static AgentState agent(String name, String floor, String surface, double x, double y) {
        AgentState state = new AgentState(name, x, y);
        state.getSpatial().setNavLocation(new NavLocation(surface, floor, new Vec3(x, 0, y), -1));
        return state;
    }

    private static SemanticPortal stairs(String id, String sourceFloor, String sourceSurface,
                                         String targetFloor, String targetSurface, double x) {
        return new SemanticPortal(id, SemanticPortal.Kind.STAIRS,
                new PortalEndpoint(sourceFloor, sourceSurface, new Vec3(x, 0, 10)),
                new PortalEndpoint(targetFloor, targetSurface, new Vec3(x, 3, 10)),
                true, 1, "", Set.of("acoustic"));
    }
}
