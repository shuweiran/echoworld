package com.roleplay.engine.service.replication;

/** Transport-neutral sink; WebSocket is an adapter, not part of the replication domain. */
@FunctionalInterface
public interface ReplicationClientSink {
    void send(Object message);
}
