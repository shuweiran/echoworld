package com.roleplay.engine.simulation.observability;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("echoworld.ReplicationFrame")
@Label("EchoWorld Replication Frame")
@Category({"EchoWorld", "Replication"})
public final class ReplicationFrameJfrEvent extends Event {
    public String clientId;
    public long sequence;
    public long serverTick;
    public int creates;
    public int updates;
    public int removes;
    public int events;
    public int estimatedBytes;
}
