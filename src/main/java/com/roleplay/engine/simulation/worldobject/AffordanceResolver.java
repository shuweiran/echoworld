package com.roleplay.engine.simulation.worldobject;

import com.roleplay.engine.simulation.action.ActionIntent;
import com.roleplay.engine.simulation.spatial.Transform3D;

/** Resolves whether a requested physical interaction is currently grounded. */
public final class AffordanceResolver {
    public Result resolve(ActionIntent intent, Transform3D actor, WorldObject object) {
        if (intent == null || actor == null || object == null) return new Result(false, "MISSING_CONTEXT", null);
        AffordanceDefinition definition = object.affordances().get(intent.action());
        if (definition == null) return new Result(false, "ACTION_NOT_SUPPORTED", null);
        double distance = actor.position().groundDistance(object.transform().position());
        if (distance > definition.requiredDistance()) return new Result(false, "TOO_FAR", definition);
        return new Result(true, "AVAILABLE", definition);
    }

    public record Result(boolean available, String code, AffordanceDefinition definition) { }
}
