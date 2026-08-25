package com.roleplay.engine.service.world;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 后台地图任务生命周期管理器。
 *
 * <p>任务和幂等键均按 session 隔离；生成结果必须通过注入的验证器，且只有 READY
 * 状态能经显式 publish 发布。服务持有异步执行器，使用完毕后必须 close。</p>
 */
public final class MapLifecycleService implements AutoCloseable {

    private static final String CLOSED_ERROR = "map lifecycle service closed";

    private final Object lock = new Object();
    private final Map<String, MutableJob> jobsById = new LinkedHashMap<>();
    private final Map<SessionKey, String> jobsByIdempotencyKey = new LinkedHashMap<>();
    private final MapGenerator generator;
    private final MapValidator validator;
    private final ExecutorService executor;
    private final int capacity;
    private final Duration readyTtl;
    private final Clock clock;
    private boolean closed;

    public MapLifecycleService(MapGenerator generator,
                               MapValidator validator,
                               int capacity,
                               int workerCount,
                               Duration readyTtl) {
        this(generator, validator, capacity, readyTtl, Clock.systemUTC(),
                Executors.newFixedThreadPool(requirePositive(workerCount, "workerCount"),
                        Thread.ofVirtual().name("map-lifecycle-", 0).factory()));
    }

    MapLifecycleService(MapGenerator generator,
                        MapValidator validator,
                        int capacity,
                        Duration readyTtl,
                        Clock clock,
                        ExecutorService executor) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.capacity = requirePositive(capacity, "capacity");
        this.readyTtl = requirePositive(readyTtl, "readyTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** 提交任务；同一 session 内重复幂等键始终返回第一次任务。 */
    public MapJob submit(String sessionId, MapGenerationRequest request) {
        String safeSessionId = requireText(sessionId, "sessionId");
        Objects.requireNonNull(request, "request");
        MutableJob job;
        synchronized (lock) {
            ensureOpen();
            expireDueJobsLocked();
            SessionKey key = new SessionKey(safeSessionId, request.idempotencyKey());
            String existingId = jobsByIdempotencyKey.get(key);
            if (existingId != null) {
                MutableJob existing = jobsById.get(existingId);
                if (existing != null) {
                    return existing.snapshot();
                }
                jobsByIdempotencyKey.remove(key);
            }
            makeRoomLocked();
            Instant now = clock.instant();
            job = new MutableJob(UUID.randomUUID().toString(), safeSessionId,
                    request.idempotencyKey(), request, now);
            jobsById.put(job.jobId, job);
            jobsByIdempotencyKey.put(key, job.jobId);
        }
        try {
            executor.execute(() -> generateAndValidate(job.jobId));
        } catch (RejectedExecutionException ex) {
            fail(job.jobId, "map generation executor rejected task");
        }
        return getRequired(safeSessionId, job.jobId);
    }

    public Optional<MapJob> find(String sessionId, String jobId) {
        String safeSessionId = requireText(sessionId, "sessionId");
        String safeJobId = requireText(jobId, "jobId");
        synchronized (lock) {
            expireDueJobsLocked();
            MutableJob job = jobsById.get(safeJobId);
            return job != null && job.sessionId.equals(safeSessionId)
                    ? Optional.of(job.snapshot())
                    : Optional.empty();
        }
    }

    public Optional<MapJob> findByIdempotencyKey(String sessionId, String idempotencyKey) {
        String safeSessionId = requireText(sessionId, "sessionId");
        String safeKey = requireText(idempotencyKey, "idempotencyKey");
        synchronized (lock) {
            expireDueJobsLocked();
            String jobId = jobsByIdempotencyKey.get(new SessionKey(safeSessionId, safeKey));
            MutableJob job = jobId == null ? null : jobsById.get(jobId);
            return job == null ? Optional.empty() : Optional.of(job.snapshot());
        }
    }

    public List<MapJob> list(String sessionId) {
        String safeSessionId = requireText(sessionId, "sessionId");
        synchronized (lock) {
            expireDueJobsLocked();
            return jobsById.values().stream()
                    .filter(job -> job.sessionId.equals(safeSessionId))
                    .sorted(Comparator.comparing(job -> job.createdAt))
                    .map(MutableJob::snapshot)
                    .toList();
        }
    }

    /** 显式发布 READY 地图；重复发布幂等。 */
    public MapJob publish(String sessionId, String jobId) {
        synchronized (lock) {
            MutableJob job = requireOwnedJob(sessionId, jobId);
            expireJobIfDueLocked(job);
            if (job.status == MapJobStatus.PUBLISHED) {
                return job.snapshot();
            }
            if (job.status != MapJobStatus.READY || job.mapData == null) {
                throw new IllegalStateException("map job is not publishable: " + job.status);
            }
            Instant now = clock.instant();
            job.status = MapJobStatus.PUBLISHED;
            job.updatedAt = now;
            job.publishedAt = now;
            return job.snapshot();
        }
    }

    /** 立即让尚未发布的任务过期；正在生成的任务不能被回收。 */
    public MapJob expire(String sessionId, String jobId) {
        synchronized (lock) {
            MutableJob job = requireOwnedJob(sessionId, jobId);
            if (job.status == MapJobStatus.PUBLISHED) {
                throw new IllegalStateException("published map cannot expire");
            }
            if (job.status == MapJobStatus.QUEUED
                    || job.status == MapJobStatus.GENERATING
                    || job.status == MapJobStatus.VALIDATING) {
                throw new IllegalStateException("active map job cannot expire");
            }
            job.status = MapJobStatus.EXPIRED;
            job.mapData = null;
            job.updatedAt = clock.instant();
            return job.snapshot();
        }
    }

    public int expireDueJobs() {
        synchronized (lock) {
            return expireDueJobsLocked();
        }
    }

    /** 会话结束时移除其全部任务与幂等键；在途 worker 看到任务已不存在会自行停止回写。 */
    public int removeSession(String sessionId) {
        String safeSessionId = requireText(sessionId, "sessionId");
        synchronized (lock) {
            List<MutableJob> owned = jobsById.values().stream()
                    .filter(job -> job.sessionId.equals(safeSessionId)).toList();
            owned.forEach(this::removeLocked);
            return owned.size();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            Instant now = clock.instant();
            for (MutableJob job : jobsById.values()) {
                if (isActive(job.status)) {
                    job.status = MapJobStatus.FAILED;
                    job.error = CLOSED_ERROR;
                    job.mapData = null;
                    job.updatedAt = now;
                }
            }
        }
        executor.shutdownNow();
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void generateAndValidate(String jobId) {
        MapGenerationRequest request;
        String sessionId;
        synchronized (lock) {
            MutableJob job = jobsById.get(jobId);
            if (job == null || job.status != MapJobStatus.QUEUED || closed) {
                return;
            }
            job.status = MapJobStatus.GENERATING;
            job.updatedAt = clock.instant();
            request = job.request;
            sessionId = job.sessionId;
        }
        try {
            Map<String, Object> generated = generator.generate(sessionId, request);
            if (generated == null) {
                fail(jobId, "map generator returned null");
                return;
            }
            Map<String, Object> frozen = Collections.unmodifiableMap(new LinkedHashMap<>(generated));
            synchronized (lock) {
                MutableJob job = jobsById.get(jobId);
                if (job == null || job.status != MapJobStatus.GENERATING || closed) {
                    return;
                }
                job.status = MapJobStatus.VALIDATING;
                job.updatedAt = clock.instant();
            }
            MapValidationResult result = validator.validate(frozen);
            synchronized (lock) {
                MutableJob job = jobsById.get(jobId);
                if (job == null || job.status != MapJobStatus.VALIDATING || closed) {
                    return;
                }
                if (result == null || !result.valid()) {
                    job.status = MapJobStatus.FAILED;
                    job.error = result == null ? "map validator returned null" : result.error();
                    job.mapData = null;
                } else {
                    job.status = MapJobStatus.READY;
                    job.error = null;
                    job.mapData = frozen;
                }
                job.updatedAt = clock.instant();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(jobId, CLOSED_ERROR);
        } catch (Exception ex) {
            fail(jobId, safeError(ex));
        }
    }

    private void fail(String jobId, String error) {
        synchronized (lock) {
            MutableJob job = jobsById.get(jobId);
            if (job == null || !isActive(job.status)) {
                return;
            }
            job.status = MapJobStatus.FAILED;
            job.error = error;
            job.mapData = null;
            job.updatedAt = clock.instant();
        }
    }

    private MapJob getRequired(String sessionId, String jobId) {
        return find(sessionId, jobId)
                .orElseThrow(() -> new NoSuchElementException("map job not found"));
    }

    private MutableJob requireOwnedJob(String sessionId, String jobId) {
        String safeSessionId = requireText(sessionId, "sessionId");
        String safeJobId = requireText(jobId, "jobId");
        MutableJob job = jobsById.get(safeJobId);
        if (job == null || !job.sessionId.equals(safeSessionId)) {
            throw new NoSuchElementException("map job not found");
        }
        return job;
    }

    private void makeRoomLocked() {
        if (jobsById.size() < capacity) {
            return;
        }
        List<MutableJob> removable = new ArrayList<>(jobsById.values());
        removable.removeIf(job -> isActive(job.status));
        removable.sort(Comparator.comparing(job -> job.updatedAt));
        if (removable.isEmpty()) {
            throw new RejectedExecutionException("map job capacity reached");
        }
        removeLocked(removable.getFirst());
    }

    private int expireDueJobsLocked() {
        int expired = 0;
        for (MutableJob job : jobsById.values()) {
            if (expireJobIfDueLocked(job)) {
                expired++;
            }
        }
        return expired;
    }

    private boolean expireJobIfDueLocked(MutableJob job) {
        if ((job.status == MapJobStatus.READY || job.status == MapJobStatus.FAILED)
                && !clock.instant().isBefore(job.updatedAt.plus(readyTtl))) {
            job.status = MapJobStatus.EXPIRED;
            job.mapData = null;
            job.updatedAt = clock.instant();
            return true;
        }
        return false;
    }

    private void removeLocked(MutableJob job) {
        jobsById.remove(job.jobId);
        jobsByIdempotencyKey.remove(new SessionKey(job.sessionId, job.idempotencyKey));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(CLOSED_ERROR);
        }
    }

    private static boolean isActive(MapJobStatus status) {
        return status == MapJobStatus.QUEUED
                || status == MapJobStatus.GENERATING
                || status == MapJobStatus.VALIDATING;
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record SessionKey(String sessionId, String idempotencyKey) {
    }

    private static final class MutableJob {
        private final String jobId;
        private final String sessionId;
        private final String idempotencyKey;
        private final MapGenerationRequest request;
        private final Instant createdAt;
        private MapJobStatus status = MapJobStatus.QUEUED;
        private Map<String, Object> mapData;
        private String error;
        private Instant updatedAt;
        private Instant publishedAt;

        private MutableJob(String jobId,
                           String sessionId,
                           String idempotencyKey,
                           MapGenerationRequest request,
                           Instant now) {
            this.jobId = jobId;
            this.sessionId = sessionId;
            this.idempotencyKey = idempotencyKey;
            this.request = request;
            this.createdAt = now;
            this.updatedAt = now;
        }

        private MapJob snapshot() {
            return new MapJob(jobId, sessionId, idempotencyKey, status, mapData, error,
                    createdAt, updatedAt, publishedAt);
        }
    }
}
