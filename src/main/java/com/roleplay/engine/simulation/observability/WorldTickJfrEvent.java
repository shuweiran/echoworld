package com.roleplay.engine.simulation.observability;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("echoworld.WorldTick")
@Label("EchoWorld World Tick")
@Category({"EchoWorld", "Simulation"})
public final class WorldTickJfrEvent extends Event {
    @Label("World Version") public long worldVersion;
    @Label("Tick") public long tick;
    @Label("Agent Count") public int agentCount;
    @Label("Pending Actions") public int pendingActions;
}
