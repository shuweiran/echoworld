package com.roleplay.engine.simulation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpeechDecisionAndPerceptionTest {
    @Test
    void noOutputIsAControlProtocolAndVolumeIsRemovedFromVisibleText() {
        assertFalse(SpeechDecision.parse("<NO_OUTPUT>").speak());
        SpeechDecision decision = SpeechDecision.parse("小声些。【音量：LOW】");
        assertAll(() -> assertTrue(decision.speak()),
                () -> assertEquals(SpeechVolume.LOW, decision.volume()),
                () -> assertEquals("小声些。", decision.text()));
    }

    @Test
    void perceptionContainsOnlyAudiblePeersAndExplicitVolumeChangesRange() {
        AgentState self = new AgentState("我", 0, 0);
        AgentState near = new AgentState("近处", 20, 0);
        AgentState far = new AgentState("远处", 400, 0);
        SpatialGrid grid = new SpatialGrid(1000, 600, 100);
        grid.rebuild(List.of(self, near, far));
        HearingSystem hearing = new HearingSystem(grid);

        LocalPerceptionSnapshot snapshot = LocalPerceptionSnapshot.from(self, List.of(self, near, far), hearing);
        assertEquals(List.of("近处"), snapshot.peers().stream().map(LocalPerceptionSnapshot.Peer::name).toList());

        double normal = hearing.computeAudibility(List.of(self, near), Map.of()).getFirst().rawRange();
        double shout = hearing.computeAudibility(List.of(self, near), Map.of("我", SpeechVolume.SHOUT)).getFirst().rawRange();
        assertEquals(normal * SpeechVolume.SHOUT.multiplier(), shout, 0.0001);
    }
}
