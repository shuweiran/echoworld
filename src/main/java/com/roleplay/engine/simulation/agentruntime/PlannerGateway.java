package com.roleplay.engine.simulation.agentruntime;

import java.util.Optional;

/** Optional LLM/semantic boundary. Implementations must not mutate world state. */
@FunctionalInterface
public interface PlannerGateway {
    PlannerGateway NONE = request -> Optional.empty();

    Optional<PlannerProposal> propose(PlanningRequest request);
}
