package com.roleplay.engine.service.world;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一般模式的异步输入邮箱。
 *
 * <p>邮箱按 session 隔离；每个 session 都是有界优先级队列，并保留一个同样有界的
 * inputId 幂等窗口。组件不创建线程、定时器或执行器，生产者只负责投递，主控在自己的
 * 调度节拍中调用 {@link #drain(String, int)} 消费，因此不会产生后台任务泄漏。</p>
 *
 * <p>默认满载策略为 {@link OverflowPolicy#EVICT_LOWEST_PRIORITY_OLDEST}：高优先级输入可
 * 淘汰最低优先级中最早的一条；同优先级新输入淘汰该优先级最早的一条；低优先级输入
 * 不会挤掉更高优先级输入。所有拒绝、重复和淘汰均同时体现在返回值与指标快照中。</p>
 */
@Service
public class InputMailbox {

    public static final int DEFAULT_SESSION_CAPACITY = 128;
    public static final int DEFAULT_MAX_SESSIONS = 1024;
    public static final int DEFAULT_IDEMPOTENCY_WINDOW = 512;

    private static final Comparator<QueuedInput> DELIVERY_ORDER =
            Comparator.comparingInt((QueuedInput item) -> item.input().priority().rank())
                    .thenComparing(item -> item.input().createdAt())
                    .thenComparingLong(QueuedInput::sequence);

    private final int sessionCapacity;
    private final int maxSessions;
    private final int idempotencyWindow;
    private final OverflowPolicy overflowPolicy;
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Object sessionCreationLock = new Object();
    private final AtomicLong sequence = new AtomicLong();

    private final LongAdder accepted = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder rejectedCapacity = new LongAdder();
    private final LongAdder rejectedSessionLimit = new LongAdder();
    private final LongAdder evicted = new LongAdder();
    private final LongAdder drained = new LongAdder();

    /** Spring 配置入口；未配置时使用安全的有界默认值。 */
    @Autowired
    public InputMailbox(
            @Value("${roleplay.world.input-mailbox.session-capacity:128}") int sessionCapacity,
            @Value("${roleplay.world.input-mailbox.max-sessions:1024}") int maxSessions,
            @Value("${roleplay.world.input-mailbox.idempotency-window:512}") int idempotencyWindow,
            @Value("${roleplay.world.input-mailbox.overflow-policy:EVICT_LOWEST_PRIORITY_OLDEST}")
            String overflowPolicy) {
        this(sessionCapacity, maxSessions, idempotencyWindow, OverflowPolicy.parse(overflowPolicy));
    }

    /** 便于非 Spring 场景与轻量单元测试直接使用。 */
    public InputMailbox() {
        this(DEFAULT_SESSION_CAPACITY, DEFAULT_MAX_SESSIONS, DEFAULT_IDEMPOTENCY_WINDOW,
                OverflowPolicy.EVICT_LOWEST_PRIORITY_OLDEST);
    }

    public InputMailbox(int sessionCapacity, int maxSessions, int idempotencyWindow,
                        OverflowPolicy overflowPolicy) {
        if (sessionCapacity <= 0) throw new IllegalArgumentException("sessionCapacity must be > 0");
        if (maxSessions <= 0) throw new IllegalArgumentException("maxSessions must be > 0");
        if (idempotencyWindow < sessionCapacity) {
            throw new IllegalArgumentException("idempotencyWindow must be >= sessionCapacity");
        }
        this.sessionCapacity = sessionCapacity;
        this.maxSessions = maxSessions;
        this.idempotencyWindow = idempotencyWindow;
        this.overflowPolicy = overflowPolicy == null
                ? OverflowPolicy.EVICT_LOWEST_PRIORITY_OLDEST : overflowPolicy;
    }

    public OfferResult offer(String sessionId, String inputId, String content,
                             Priority priority, Instant createdAt) {
        return offer(new MailboxInput(sessionId, inputId, content, priority, createdAt, Map.of()));
    }

    public OfferResult offer(MailboxInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        String sessionId = normalizeRequired(input.sessionId(), "sessionId");
        MailboxInput normalized = input.withSessionId(sessionId);

        while (true) {
            SessionState state = getOrCreateSession(sessionId);
            if (state == null) {
                rejectedSessionLimit.increment();
                return new OfferResult(OfferStatus.REJECTED_SESSION_LIMIT, normalized, null);
            }
            synchronized (state) {
                // removeSession 可能恰好发生在取引用之后；重试可保证输入不会落入孤儿状态。
                if (sessions.get(sessionId) != state) continue;
                state.touch();
                if (state.seenInputIds.containsKey(normalized.inputId())) {
                    state.duplicates++;
                    duplicates.increment();
                    return new OfferResult(OfferStatus.DUPLICATE, normalized, null);
                }

                QueuedInput displaced = null;
                if (state.queue.size() >= sessionCapacity) {
                    displaced = chooseDisplaced(state, normalized);
                    if (displaced == null) {
                        state.rejectedCapacity++;
                        rejectedCapacity.increment();
                        return new OfferResult(OfferStatus.REJECTED_CAPACITY, normalized, null);
                    }
                    state.queue.remove(displaced);
                    state.evicted++;
                    evicted.increment();
                }

                rememberInputId(state, normalized.inputId());
                state.queue.offer(new QueuedInput(normalized, sequence.incrementAndGet()));
                state.accepted++;
                accepted.increment();
                return new OfferResult(displaced == null ? OfferStatus.ACCEPTED : OfferStatus.ACCEPTED_WITH_EVICTION,
                        normalized, displaced == null ? null : displaced.input());
            }
        }
    }

    /** 查看下一条但不消费；顺序为优先级、时间戳、到达序号。 */
    public Optional<MailboxInput> peek(String sessionId) {
        SessionState state = sessions.get(normalizeRequired(sessionId, "sessionId"));
        if (state == null) return Optional.empty();
        synchronized (state) {
            QueuedInput item = state.queue.peek();
            state.touch();
            return item == null ? Optional.empty() : Optional.of(item.input());
        }
    }

    /** 最多消费 maxItems 条；不同 session 之间不会互相可见。 */
    public List<MailboxInput> drain(String sessionId, int maxItems) {
        if (maxItems <= 0) throw new IllegalArgumentException("maxItems must be > 0");
        SessionState state = sessions.get(normalizeRequired(sessionId, "sessionId"));
        if (state == null) return List.of();
        synchronized (state) {
            List<MailboxInput> result = new ArrayList<>(Math.min(maxItems, state.queue.size()));
            while (result.size() < maxItems) {
                QueuedInput item = state.queue.poll();
                if (item == null) break;
                result.add(item.input());
            }
            state.drained += result.size();
            drained.add(result.size());
            state.touch();
            return List.copyOf(result);
        }
    }

    /**
     * 消费器瞬时失败后的补偿入口。与普通 offer 不同，它允许同一 inputId 回到队列，
     * 但仍受 session 容量约束；调用方负责限制重试次数，避免毒消息永久占槽。
     */
    public boolean requeue(MailboxInput input) {
        if (input == null) return false;
        String sessionId = normalizeRequired(input.sessionId(), "sessionId");
        SessionState state = sessions.get(sessionId);
        if (state == null) return false;
        synchronized (state) {
            if (sessions.get(sessionId) != state || state.queue.size() >= sessionCapacity) return false;
            state.queue.offer(new QueuedInput(input.withSessionId(sessionId), sequence.incrementAndGet()));
            state.touch();
            return true;
        }
    }

    public int pendingCount(String sessionId) {
        SessionState state = sessions.get(normalizeRequired(sessionId, "sessionId"));
        if (state == null) return 0;
        synchronized (state) {
            return state.queue.size();
        }
    }

    /**
     * 显式结束 session。清除待处理输入及幂等窗口；之后相同 sessionId 可作为新会话重新建立。
     *
     * @return 被清除的待处理输入数
     */
    public int removeSession(String sessionId) {
        String normalized = normalizeRequired(sessionId, "sessionId");
        SessionState state = sessions.get(normalized);
        if (state == null) return 0;
        synchronized (state) {
            if (!sessions.remove(normalized, state)) return 0;
            int pending = state.queue.size();
            state.queue.clear();
            state.seenInputIds.clear();
            return pending;
        }
    }

    public Metrics metrics() {
        long pending = 0;
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                pending += state.queue.size();
            }
        }
        return new Metrics(sessions.size(), pending, accepted.sum(), duplicates.sum(),
                rejectedCapacity.sum(), rejectedSessionLimit.sum(), evicted.sum(), drained.sum());
    }

    public Optional<SessionMetrics> sessionMetrics(String sessionId) {
        SessionState state = sessions.get(normalizeRequired(sessionId, "sessionId"));
        if (state == null) return Optional.empty();
        synchronized (state) {
            return Optional.of(new SessionMetrics(state.queue.size(), state.accepted, state.duplicates,
                    state.rejectedCapacity, state.evicted, state.drained, state.lastAccessNanos));
        }
    }

    private SessionState getOrCreateSession(String sessionId) {
        SessionState existing = sessions.get(sessionId);
        if (existing != null) return existing;
        synchronized (sessionCreationLock) {
            existing = sessions.get(sessionId);
            if (existing != null) return existing;
            if (sessions.size() >= maxSessions) evictOneEmptySession();
            if (sessions.size() >= maxSessions) return null;
            SessionState created = new SessionState();
            sessions.put(sessionId, created);
            return created;
        }
    }

    /** 会话上限到达时只回收空邮箱，绝不静默丢弃仍待消费的其他会话输入。 */
    private void evictOneEmptySession() {
        Map.Entry<String, SessionState> oldest = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState state = entry.getValue();
            synchronized (state) {
                if (!state.queue.isEmpty()) continue;
                if (oldest == null || state.lastAccessNanos < oldest.getValue().lastAccessNanos) {
                    oldest = entry;
                }
            }
        }
        if (oldest != null) {
            SessionState state = oldest.getValue();
            synchronized (state) {
                if (state.queue.isEmpty()) sessions.remove(oldest.getKey(), state);
            }
        }
    }

    private QueuedInput chooseDisplaced(SessionState state, MailboxInput incoming) {
        if (overflowPolicy == OverflowPolicy.REJECT_NEW) return null;
        QueuedInput candidate = null;
        for (QueuedInput item : state.queue) {
            if (candidate == null
                    || item.input().priority().rank() > candidate.input().priority().rank()
                    || (item.input().priority() == candidate.input().priority()
                        && item.sequence() < candidate.sequence())) {
                candidate = item;
            }
        }
        // 低优先级输入不允许挤掉更高优先级输入；同级采用“最老出、新者入”。
        return candidate != null && incoming.priority().rank() <= candidate.input().priority().rank()
                ? candidate : null;
    }

    private void rememberInputId(SessionState state, String inputId) {
        state.seenInputIds.put(inputId, Boolean.TRUE);
        while (state.seenInputIds.size() > idempotencyWindow) {
            String eldest = state.seenInputIds.keySet().iterator().next();
            state.seenInputIds.remove(eldest);
        }
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum Priority {
        CRITICAL(0), HIGH(1), NORMAL(2), LOW(3);

        private final int rank;
        Priority(int rank) { this.rank = rank; }
        int rank() { return rank; }
    }

    public enum OverflowPolicy {
        REJECT_NEW,
        EVICT_LOWEST_PRIORITY_OLDEST;

        static OverflowPolicy parse(String value) {
            if (value == null || value.isBlank()) return EVICT_LOWEST_PRIORITY_OLDEST;
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return EVICT_LOWEST_PRIORITY_OLDEST;
            }
        }
    }

    public enum OfferStatus {
        ACCEPTED,
        ACCEPTED_WITH_EVICTION,
        DUPLICATE,
        REJECTED_CAPACITY,
        REJECTED_SESSION_LIMIT
    }

    public record MailboxInput(String sessionId, String inputId, String content, Priority priority,
                               Instant createdAt, Map<String, Object> attributes) {
        public MailboxInput {
            sessionId = normalizeRequired(sessionId, "sessionId");
            inputId = normalizeRequired(inputId, "inputId");
            if (content == null) throw new IllegalArgumentException("content must not be null");
            priority = priority == null ? Priority.NORMAL : priority;
            createdAt = createdAt == null ? Instant.now() : createdAt;
            attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        }

        private MailboxInput withSessionId(String normalizedSessionId) {
            return sessionId.equals(normalizedSessionId) ? this
                    : new MailboxInput(normalizedSessionId, inputId, content, priority, createdAt, attributes);
        }
    }

    public record OfferResult(OfferStatus status, MailboxInput input, MailboxInput evictedInput) {
        public boolean accepted() {
            return status == OfferStatus.ACCEPTED || status == OfferStatus.ACCEPTED_WITH_EVICTION;
        }
    }

    public record Metrics(int sessions, long pending, long accepted, long duplicates,
                          long rejectedCapacity, long rejectedSessionLimit, long evicted, long drained) {}

    public record SessionMetrics(int pending, long accepted, long duplicates, long rejectedCapacity,
                                 long evicted, long drained, long lastAccessNanos) {}

    private record QueuedInput(MailboxInput input, long sequence) {}

    private static final class SessionState {
        private final PriorityQueue<QueuedInput> queue = new PriorityQueue<>(DELIVERY_ORDER);
        private final LinkedHashMap<String, Boolean> seenInputIds = new LinkedHashMap<>();
        private long accepted;
        private long duplicates;
        private long rejectedCapacity;
        private long evicted;
        private long drained;
        private long lastAccessNanos = System.nanoTime();

        private void touch() {
            lastAccessNanos = System.nanoTime();
        }
    }
}
