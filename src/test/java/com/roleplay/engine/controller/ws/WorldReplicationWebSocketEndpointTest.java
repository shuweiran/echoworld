package com.roleplay.engine.controller.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorldReplicationWebSocketEndpointTest {
    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void realEndpointCompletesHelloSnapshotAckAndUnknownMessageFlow() throws Exception {
        var listener = new CollectingListener();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/world"), listener)
                .get(5, TimeUnit.SECONDS);

        try {
            socket.sendText("""
                    {"type":"hello","payload":{"clientId":"java-endpoint-test","protocolVersion":1,
                    "focusCell":{"zoneId":"world","floorId":"ground","x":0,"z":0},
                    "radiusCells":2,"narrativeSubscriptions":[]}}
                    """, true).get(5, TimeUnit.SECONDS);

            JsonNode snapshot = listener.takeJson(mapper, "full_snapshot");
            assertEquals(1, snapshot.path("payload").path("protocolVersion").asInt());
            assertEquals(0, snapshot.path("payload").path("sequence").asLong());
            assertTrue(snapshot.path("payload").path("entities").isArray());
            assertTrue(snapshot.path("payload").path("events").isArray());

            socket.sendText("{\"type\":\"ack\",\"payload\":{\"sequence\":0}}", true)
                    .get(5, TimeUnit.SECONDS);
            JsonNode ack = listener.takeJson(mapper, "ack_result");
            assertEquals("ACKNOWLEDGED", ack.path("payload").path("status").asText());
            assertEquals(0, ack.path("payload").path("highestAcknowledgedSequence").asLong());
            assertEquals(0, ack.path("payload").path("latestSequence").asLong());

            socket.sendText("{\"type\":\"unsupported\",\"payload\":{}}", true)
                    .get(5, TimeUnit.SECONDS);
            JsonNode error = listener.takeJson(mapper, "error");
            assertEquals("UNKNOWN_MESSAGE", error.path("code").asText());
            assertNull(listener.failure.get(), () -> "WebSocket listener failed: " + listener.failure.get());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
        }
    }

    private static final class CollectingListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final StringBuilder current = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                messages.add(current.toString());
                current.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failure.compareAndSet(null, error);
        }

        private JsonNode takeJson(ObjectMapper mapper, String expectedType) throws Exception {
            String message = messages.poll(5, TimeUnit.SECONDS);
            if (message == null) {
                fail("Timed out waiting for WebSocket message type " + expectedType
                        + "; listener failure=" + failure.get());
            }
            JsonNode json = mapper.readTree(message);
            assertEquals(expectedType, json.path("type").asText(), message);
            return json;
        }
    }
}
