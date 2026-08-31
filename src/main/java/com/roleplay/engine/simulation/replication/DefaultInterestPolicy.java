package com.roleplay.engine.simulation.replication;

import java.util.Objects;
import java.util.Set;

/** Perception boundary first, then spatial cell, ownership, and narrative relevance. */
public final class DefaultInterestPolicy implements InterestPolicy {
    @Override
    public Decision evaluate(ReplicaEntity entity, InterestContext context) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(context, "context");
        if (!entity.isPerceivableBy(context.clientId())) {
            return Decision.exclude(Reason.DENIED_BY_PERCEPTION);
        }
        if (context.clientId().equals(entity.ownerClientId())) {
            return Decision.include(Reason.OWNERSHIP);
        }
        if (isSpatiallyRelevant(entity.cell(), context)) {
            return Decision.include(Reason.SPATIAL_CELL);
        }
        if (intersects(entity.narrativeTags(), context.narrativeSubscriptions())) {
            return Decision.include(Reason.NARRATIVE_RELEVANCE);
        }
        return Decision.exclude(Reason.OUT_OF_INTEREST);
    }

    @Override
    public Decision evaluate(ReplicationEvent event, InterestContext context) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(context, "context");
        if (!event.perceptionScope().allows(context.clientId())) {
            return Decision.exclude(Reason.DENIED_BY_PERCEPTION);
        }
        if (event.globalInterest()) return Decision.include(Reason.GLOBAL_EVENT);
        if (event.cell() != null && isSpatiallyRelevant(event.cell(), context)) {
            return Decision.include(Reason.SPATIAL_CELL);
        }
        if (intersects(event.narrativeTags(), context.narrativeSubscriptions())) {
            return Decision.include(Reason.NARRATIVE_RELEVANCE);
        }
        return Decision.exclude(Reason.OUT_OF_INTEREST);
    }

    private boolean isSpatiallyRelevant(SpatialCell cell, InterestContext context) {
        return context.focusCell() != null && cell.isWithin(context.focusCell(), context.spatialRadiusCells());
    }

    private boolean intersects(Set<String> left, Set<String> right) {
        for (String value : left) if (right.contains(value)) return true;
        return false;
    }
}
