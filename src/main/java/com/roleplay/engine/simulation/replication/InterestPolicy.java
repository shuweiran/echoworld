package com.roleplay.engine.simulation.replication;

public interface InterestPolicy {
    Decision evaluate(ReplicaEntity entity, InterestContext context);

    Decision evaluate(ReplicationEvent event, InterestContext context);

    default boolean shouldReplicate(ReplicaEntity entity, InterestContext context) {
        return evaluate(entity, context).included();
    }

    default boolean shouldReplicate(ReplicationEvent event, InterestContext context) {
        return evaluate(event, context).included();
    }

    enum Reason {
        DENIED_BY_PERCEPTION,
        OWNERSHIP,
        SPATIAL_CELL,
        NARRATIVE_RELEVANCE,
        GLOBAL_EVENT,
        OUT_OF_INTEREST
    }

    record Decision(boolean included, Reason reason) {
        public static Decision include(Reason reason) { return new Decision(true, reason); }
        public static Decision exclude(Reason reason) { return new Decision(false, reason); }
    }
}
