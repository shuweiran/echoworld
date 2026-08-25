package com.roleplay.engine.service.world;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapLifecycleServiceTest {

    @Test
    void validMapNeedsExplicitPublish() throws Exception {
        try (MapLifecycleService service = service(
                (session, request) -> Map.of("name", request.attributes().get("theme")),
                map -> MapValidationResult.success(), 4)) {
            MapJob submitted = service.submit("session-a",
                    new MapGenerationRequest("map-1", Map.of("theme", "harbor")));

            MapJob ready = awaitStatus(service, "session-a", submitted.jobId(), MapJobStatus.READY);
            assertTrue(ready.publishable());
            assertEquals("harbor", ready.mapData().get("name"));
            assertEquals(MapJobStatus.READY, service.find("session-a", submitted.jobId()).orElseThrow().status());

            MapJob published = service.publish("session-a", submitted.jobId());
            assertEquals(MapJobStatus.PUBLISHED, published.status());
            assertEquals(published, service.publish("session-a", submitted.jobId()));
            assertTrue(published.publishedAt() != null);
        }
    }

    @Test
    void invalidMapFailsAndCanNeverBePublished() throws Exception {
        try (MapLifecycleService service = service(
                (session, request) -> Map.of("width", 0),
                map -> MapValidationResult.invalid("width must be positive"), 4)) {
            MapJob submitted = service.submit("session-a",
                    new MapGenerationRequest("bad-map", Map.of()));

            MapJob failed = awaitStatus(service, "session-a", submitted.jobId(), MapJobStatus.FAILED);
            assertEquals("width must be positive", failed.error());
            assertTrue(failed.mapData() == null);
            assertThrows(IllegalStateException.class,
                    () -> service.publish("session-a", submitted.jobId()));
        }
    }

    @Test
    void jobsAndIdempotencyKeysAreSessionIsolated() throws Exception {
        AtomicInteger generated = new AtomicInteger();
        try (MapLifecycleService service = service(
                (session, request) -> Map.of("sequence", generated.incrementAndGet()),
                map -> MapValidationResult.success(), 4)) {
            MapGenerationRequest request = new MapGenerationRequest("same-key", Map.of());
            MapJob first = service.submit("session-a", request);
            MapJob duplicate = service.submit("session-a", request);
            MapJob otherSession = service.submit("session-b", request);

            assertEquals(first.jobId(), duplicate.jobId());
            assertNotEquals(first.jobId(), otherSession.jobId());
            awaitStatus(service, "session-a", first.jobId(), MapJobStatus.READY);
            awaitStatus(service, "session-b", otherSession.jobId(), MapJobStatus.READY);
            assertEquals(2, generated.get());
            assertTrue(service.find("session-b", first.jobId()).isEmpty());
            assertThrows(NoSuchElementException.class,
                    () -> service.publish("session-b", first.jobId()));
        }
    }

    @Test
    void capacityRejectsWhenEveryRetainedJobIsActive() throws Exception {
        CountDownLatch enteredGenerator = new CountDownLatch(1);
        CountDownLatch releaseGenerator = new CountDownLatch(1);
        try (MapLifecycleService service = service((session, request) -> {
            enteredGenerator.countDown();
            releaseGenerator.await();
            return Map.of("ok", true);
        }, map -> MapValidationResult.success(), 1)) {
            MapJob first = service.submit("session-a",
                    new MapGenerationRequest("first", Map.of()));
            assertTrue(enteredGenerator.await(2, TimeUnit.SECONDS));
            assertEquals(MapJobStatus.GENERATING,
                    service.find("session-a", first.jobId()).orElseThrow().status());

            assertThrows(RejectedExecutionException.class, () -> service.submit("session-a",
                    new MapGenerationRequest("second", Map.of())));
            releaseGenerator.countDown();
            awaitStatus(service, "session-a", first.jobId(), MapJobStatus.READY);
        } finally {
            releaseGenerator.countDown();
        }
    }

    @Test
    void readyJobExpiresAndLosesPayload() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
        try (MapLifecycleService service = new MapLifecycleService(
                (session, request) -> Map.of("ok", true),
                map -> MapValidationResult.success(),
                2, Duration.ofMinutes(5), clock,
                Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()))) {
            MapJob submitted = service.submit("session-a",
                    new MapGenerationRequest("expiring", Map.of()));
            awaitStatus(service, "session-a", submitted.jobId(), MapJobStatus.READY);

            clock.advance(Duration.ofMinutes(5));
            MapJob expired = service.find("session-a", submitted.jobId()).orElseThrow();
            assertEquals(MapJobStatus.EXPIRED, expired.status());
            assertTrue(expired.mapData() == null);
            assertThrows(IllegalStateException.class,
                    () -> service.publish("session-a", submitted.jobId()));
        }
    }

    @Test
    void closeStopsExecutorAndFailsActiveJob() throws Exception {
        CountDownLatch enteredGenerator = new CountDownLatch(1);
        CountDownLatch neverReleasedNormally = new CountDownLatch(1);
        MapLifecycleService service = service((session, request) -> {
            enteredGenerator.countDown();
            neverReleasedNormally.await();
            return Map.of("ok", true);
        }, map -> MapValidationResult.success(), 2);
        MapJob submitted = service.submit("session-a",
                new MapGenerationRequest("closing", Map.of()));
        assertTrue(enteredGenerator.await(2, TimeUnit.SECONDS));

        service.close();

        assertTrue(service.isClosed());
        MapJob failed = service.find("session-a", submitted.jobId()).orElseThrow();
        assertEquals(MapJobStatus.FAILED, failed.status());
        assertEquals("map lifecycle service closed", failed.error());
        assertThrows(IllegalStateException.class, () -> service.submit("session-a",
                new MapGenerationRequest("after-close", Map.of())));
    }

    @Test
    void exposesAllContractStatuses() {
        assertEquals(7, MapJobStatus.values().length);
        assertEquals(MapJobStatus.QUEUED, MapJobStatus.valueOf("QUEUED"));
        assertEquals(MapJobStatus.GENERATING, MapJobStatus.valueOf("GENERATING"));
        assertEquals(MapJobStatus.VALIDATING, MapJobStatus.valueOf("VALIDATING"));
        assertEquals(MapJobStatus.READY, MapJobStatus.valueOf("READY"));
        assertEquals(MapJobStatus.PUBLISHED, MapJobStatus.valueOf("PUBLISHED"));
        assertEquals(MapJobStatus.FAILED, MapJobStatus.valueOf("FAILED"));
        assertEquals(MapJobStatus.EXPIRED, MapJobStatus.valueOf("EXPIRED"));
    }

    private static MapLifecycleService service(MapGenerator generator,
                                               MapValidator validator,
                                               int capacity) {
        return new MapLifecycleService(generator, validator, capacity, 1, Duration.ofMinutes(10));
    }

    private static MapJob awaitStatus(MapLifecycleService service,
                                      String sessionId,
                                      String jobId,
                                      MapJobStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        MapJob last = null;
        while (System.nanoTime() < deadline) {
            last = service.find(sessionId, jobId).orElseThrow();
            if (last.status() == expected) {
                return last;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("expected " + expected + " but was "
                + (last == null ? "missing" : last.status()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
