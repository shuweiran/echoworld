package com.roleplay.engine.simulation.observability;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("echoworld.PathPlan")
@Label("EchoWorld Path Plan")
@Category({"EchoWorld", "Navigation"})
public final class PathPlanJfrEvent extends Event {
    public String actorId;
    public String backend;
    public String status;
    public int steps;
    public long worldVersion;
}
