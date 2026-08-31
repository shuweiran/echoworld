package com.roleplay.engine.simulation.worldobject;

import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.action.ActionSource;
import com.roleplay.engine.simulation.action.ActionType;
import com.roleplay.engine.simulation.spatial.Transform3D;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffordanceResolverTest {

    @Test
    void interactionMustExistAndBeWithinRequiredDistance() {
        AffordanceDefinition sit = new AffordanceDefinition(ActionType.SIT, 80, 1000, 1, Map.of());
        WorldObject chair = new WorldObject("chair", "CHAIR", Transform3D.ground(100, 100),
                Map.of(ActionType.SIT, sit), Set.of("furniture"));
        ActionIntent intent = new ActionIntent("sit-1", "agent", ActionSource.AI_PLANNER,
                ActionType.SIT, "chair", 1, 0, Map.of());

        AffordanceResolver resolver = new AffordanceResolver();
        assertTrue(resolver.resolve(intent, Transform3D.ground(130, 100), chair).available());
        assertEquals("TOO_FAR", resolver.resolve(intent, Transform3D.ground(300, 100), chair).code());
    }
}
