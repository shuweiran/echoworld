package com.roleplay.engine.simulation.observability;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("echoworld.ActionCommit")
@Label("EchoWorld Action Commit")
@Category({"EchoWorld", "Action"})
public final class ActionCommitJfrEvent extends Event {
    @Label("Intent") public String intentId;
    @Label("Action") public String action;
    @Label("Phase") public String phase;
    @Label("Code") public String code;
    @Label("World Version") public long worldVersion;
}
