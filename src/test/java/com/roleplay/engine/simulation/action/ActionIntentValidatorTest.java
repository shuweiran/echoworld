package com.roleplay.engine.simulation.action;

import com.roleplay.engine.simulation.spatial.ControlAuthority;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionIntentValidatorTest {

    @Test
    void aiCannotIssuePhysicalActionForPlayerControlledActor() {
        ActionIntent intent = new ActionIntent("i1", "player", ActionSource.AI_PLANNER,
                ActionType.OPEN, "door", 10, System.currentTimeMillis() + 1000, Map.of());
        ActionWorldView world = world(ControlAuthority.PLAYER_INPUT);

        ActionResult result = new ActionIntentValidator().validate(intent, world, System.currentTimeMillis());

        assertEquals(ActionResult.Status.REJECTED, result.status());
        assertEquals("PLAYER_AUTHORITY", result.code());
    }

    @Test
    void playerIntentRemainsValidForPlayerControlledActor() {
        ActionIntent intent = new ActionIntent("i2", "player", ActionSource.PLAYER_INPUT,
                ActionType.OPEN, "door", 10, System.currentTimeMillis() + 1000, Map.of());

        assertEquals(ActionResult.Status.ACCEPTED,
                new ActionIntentValidator().validate(intent, world(ControlAuthority.PLAYER_INPUT),
                        System.currentTimeMillis()).status());
    }

    @Test
    void staleIntentMustBeReplannedAgainstCurrentWorld() {
        ActionIntent intent = new ActionIntent("i3", "ai", ActionSource.AI_PLANNER,
                ActionType.OPEN, "door", 4, System.currentTimeMillis() + 1000, Map.of());
        ActionWorldView world = new ActionWorldView() {
            public long worldVersion() { return 50; }
            public long minimumAcceptedWorldVersion() { return 45; }
            public boolean entityExists(String id) { return id.equals("ai") || id.equals("door"); }
            public ControlAuthority authorityOf(String id) { return ControlAuthority.AI_AUTONOMOUS; }
        };

        ActionResult result = new ActionIntentValidator().validate(intent, world, System.currentTimeMillis());

        assertEquals(ActionResult.Status.REJECTED, result.status());
        assertEquals("STALE_WORLD_VERSION", result.code());
    }

    private ActionWorldView world(ControlAuthority authority) {
        return new ActionWorldView() {
            public long worldVersion() { return 10; }
            public boolean entityExists(String id) { return id.equals("player") || id.equals("door"); }
            public ControlAuthority authorityOf(String id) { return authority; }
        };
    }
}
