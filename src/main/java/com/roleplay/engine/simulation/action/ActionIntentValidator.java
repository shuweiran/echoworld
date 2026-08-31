package com.roleplay.engine.simulation.action;

import com.roleplay.engine.simulation.spatial.ControlAuthority;

/** Revalidates an intent against current world state immediately before execution. */
public final class ActionIntentValidator {
    public ActionResult validate(ActionIntent intent, ActionWorldView world, long nowMillis) {
        if (!world.entityExists(intent.actorId())) {
            return ActionResult.rejected(intent, "ACTOR_MISSING", "actor no longer exists", world.worldVersion());
        }
        if (intent.expiresAtMillis() > 0 && nowMillis > intent.expiresAtMillis()) {
            return ActionResult.rejected(intent, "INTENT_EXPIRED", "intent expired before execution", world.worldVersion());
        }
        if (intent.basedOnWorldVersion() > world.worldVersion()) {
            return ActionResult.rejected(intent, "INVALID_WORLD_VERSION", "intent references a future world", world.worldVersion());
        }
        if (intent.basedOnWorldVersion() < world.minimumAcceptedWorldVersion()) {
            return ActionResult.rejected(intent, "STALE_WORLD_VERSION",
                    "intent was planned against an expired world snapshot", world.worldVersion());
        }
        if (intent.source() == ActionSource.AI_PLANNER
                && world.authorityOf(intent.actorId()) == ControlAuthority.PLAYER_INPUT) {
            return ActionResult.rejected(intent, "PLAYER_AUTHORITY",
                    "AI planners cannot issue actions for a player-controlled entity", world.worldVersion());
        }
        if (!intent.targetId().isBlank() && !world.entityExists(intent.targetId())) {
            return ActionResult.rejected(intent, "TARGET_MISSING", "target no longer exists", world.worldVersion());
        }
        if (!intent.targetId().isBlank() && !world.affordanceAvailable(intent.actorId(), intent.targetId(), intent.action())) {
            return ActionResult.rejected(intent, "AFFORDANCE_UNAVAILABLE",
                    "target does not currently permit this action", world.worldVersion());
        }
        return new ActionResult(intent.intentId(), ActionResult.Status.ACCEPTED,
                "VALID", "intent accepted for execution", world.worldVersion(), java.util.List.of());
    }
}
