package com.roleplay.engine.service.world;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorldCommandProtocolTest {

    @Test
    void parserAcceptsFenceActionsAliasesAndInlinePayload() {
        String raw = """
                主控建议如下：
                ```json
                {"actions":[
                  {"id":"c1","type":"spawn-extra","session_id":"s1",
                   "archetype":"卖花姑娘","ttl_seconds":300,"created_at":"2026-08-24T01:02:03Z"},
                  {"command_id":"c2","command_type":"ASSIGN_TRACK","payload":{"roleId":"r1","track":"WEAK"},
                   "preconditions":[{"path":"role.r1.status","op":"eq","value":"ACTIVE"}]},
                  {"type":"NOT_A_REAL_COMMAND"}
                ]}
                ```
                """;

        List<WorldCommand> commands = new WorldCommandParser().parse(raw, "fallback");

        assertEquals(2, commands.size());
        assertEquals(WorldCommandType.SPAWN_EXTRA, commands.get(0).type());
        assertEquals("s1", commands.get(0).sessionId());
        assertEquals("卖花姑娘", commands.get(0).payload().get("archetype"));
        assertEquals(Instant.parse("2026-08-24T01:02:03Z"), commands.get(0).createdAt());
        assertEquals("fallback", commands.get(1).sessionId());
        assertEquals("EQ", commands.get(1).preconditions().getFirst().operator());
    }

    @Test
    void parserFailsClosedForTextAndMissingSession() {
        WorldCommandParser parser = new WorldCommandParser();
        assertTrue(parser.parse("请生成一个路人，但这不是 JSON", "s1").isEmpty());
        assertTrue(parser.parse("{\"type\":\"SPAWN_EXTRA\"}", null).isEmpty());
    }

    @Test
    void busIsSessionIsolatedBoundedAndIdempotent() {
        WorldCommandBus bus = new WorldCommandBus(2, 4);
        WorldCommand first = command("same", "s1");
        assertTrue(bus.offer(first));
        assertFalse(bus.offer(first));
        assertTrue(bus.offer(command("second", "s1")));
        assertFalse(bus.offer(command("third", "s1")));
        assertTrue(bus.offer(command("same", "s2")));

        assertEquals(Set.of("s1", "s2"), bus.sessions());
        assertEquals(List.of("same", "second"), bus.drain("s1", 10).stream().map(WorldCommand::id).toList());
        assertEquals("same", bus.poll("s2").orElseThrow().id());
    }

    @Test
    void busHandlesConcurrentProducersWithoutDuplicateIds() throws Exception {
        WorldCommandBus bus = new WorldCommandBus(128, 256);
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return bus.offer(command("one-id", "s1"));
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, results.stream().filter(future -> {
                try { return future.get(); } catch (Exception e) { throw new AssertionError(e); }
            }).count());
        }
        assertEquals(1, bus.size("s1"));
    }

    private static WorldCommand command(String id, String session) {
        return new WorldCommand(id, WorldCommandType.SPAWN_EXTRA, session, Map.of(), List.of(), "test", Instant.EPOCH);
    }
}
