package com.roleplay.engine.controller.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.service.replication.WorldReplicationService;
import com.roleplay.engine.simulation.replication.ClientReplicationBuffer;
import com.roleplay.engine.simulation.replication.InterestContext;
import com.roleplay.engine.simulation.replication.ReplicationProtocol;
import com.roleplay.engine.simulation.replication.SpatialCell;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** JSON migration adapter for Unity; SSE remains available for the React debug client. */
@Component
public class WorldReplicationWebSocketHandler extends TextWebSocketHandler {
    private final WorldReplicationService replication;
    private final ObjectMapper mapper;
    private final Map<String, String> clientBySession = new ConcurrentHashMap<>();

    public WorldReplicationWebSocketHandler(WorldReplicationService replication, ObjectMapper mapper) {
        this.replication = replication;
        this.mapper = mapper;
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = mapper.readTree(message.getPayload());
        String type = root.path("type").asText("");
        switch (type) {
            case "hello" -> hello(session, root.path("payload"));
            case "interest" -> updateInterest(session, root.path("payload"));
            case "ack" -> acknowledge(session, root.path("payload"));
            case "replay" -> replay(session, root.path("payload"));
            default -> send(session, Map.of("type", "error", "code", "UNKNOWN_MESSAGE"));
        }
    }

    private void hello(WebSocketSession session, JsonNode payload) {
        String clientId = required(payload, "clientId");
        int protocol = payload.path("protocolVersion").asInt(ReplicationProtocol.CURRENT_VERSION);
        if (protocol != ReplicationProtocol.CURRENT_VERSION) throw new IllegalArgumentException("protocol mismatch");
        InterestContext interest = interest(clientId, payload);
        clientBySession.put(session.getId(), clientId);
        replication.connect(clientId, interest, value -> send(session, value));
    }

    private void updateInterest(WebSocketSession session, JsonNode payload) {
        String clientId = client(session);
        replication.updateInterest(clientId, interest(clientId, payload));
    }

    private void acknowledge(WebSocketSession session, JsonNode payload) {
        String clientId = client(session);
        long sequence = payload.path("sequence").asLong(-1);
        var result = replication.acknowledge(new ClientReplicationBuffer.ClientAck(clientId,
                ReplicationProtocol.CURRENT_VERSION, sequence));
        send(session, new WorldReplicationService.Envelope("ack_result", result));
    }

    private void replay(WebSocketSession session, JsonNode payload) {
        String clientId = client(session);
        long sequence = payload.path("sequence").asLong(-1);
        replication.replayAfter(new ClientReplicationBuffer.ClientAck(clientId,
                ReplicationProtocol.CURRENT_VERSION, sequence));
    }

    private InterestContext interest(String clientId, JsonNode payload) {
        JsonNode focus = payload.path("focusCell");
        SpatialCell cell = focus.isObject() ? new SpatialCell(focus.path("zoneId").asText("world"),
                focus.path("floorId").asText("ground"), focus.path("x").asInt(), focus.path("z").asInt()) : null;
        Set<String> tags = new java.util.LinkedHashSet<>();
        payload.path("narrativeSubscriptions").forEach(node -> { if (node.isTextual()) tags.add(node.asText()); });
        return new InterestContext(clientId, cell, Math.max(0, payload.path("radiusCells").asInt(2)), tags);
    }

    private String client(WebSocketSession session) {
        String clientId = clientBySession.get(session.getId());
        if (clientId == null) throw new IllegalStateException("hello required");
        return clientId;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value;
    }

    private void send(WebSocketSession session, Object payload) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) { session.sendMessage(new TextMessage(mapper.writeValueAsString(payload))); }
        } catch (IOException e) {
            throw new IllegalStateException("websocket send failed", e);
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String clientId = clientBySession.remove(session.getId());
        if (clientId != null) replication.disconnect(clientId);
    }
}
