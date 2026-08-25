package com.roleplay.engine.service.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.roleplay.engine.service.world.InputMailbox.OfferStatus;
import com.roleplay.engine.service.world.InputMailbox.OverflowPolicy;
import com.roleplay.engine.service.world.InputMailbox.Priority;

import static org.junit.jupiter.api.Assertions.*;

class InputMailboxTest {

    @Test
    @DisplayName("session 隔离且 drain 有上限，peek 不消费")
    void sessionIsolationPeekAndBoundedDrain() {
        InputMailbox mailbox = mailbox(4, 4);
        mailbox.offer("s1", "1", "甲", Priority.NORMAL, Instant.ofEpochMilli(1));
        mailbox.offer("s1", "2", "乙", Priority.NORMAL, Instant.ofEpochMilli(2));
        mailbox.offer("s2", "3", "丙", Priority.NORMAL, Instant.ofEpochMilli(3));

        assertEquals("甲", mailbox.peek("s1").orElseThrow().content());
        assertEquals(2, mailbox.pendingCount("s1"));
        assertEquals(List.of("甲"), mailbox.drain("s1", 1).stream().map(InputMailbox.MailboxInput::content).toList());
        assertEquals(List.of("丙"), mailbox.drain("s2", 10).stream().map(InputMailbox.MailboxInput::content).toList());
        assertEquals("乙", mailbox.peek("s1").orElseThrow().content());
    }

    @Test
    @DisplayName("按优先级、时间戳、到达序号稳定消费")
    void priorityTimestampAndSequenceOrder() {
        InputMailbox mailbox = mailbox(8, 8);
        Instant sameTime = Instant.ofEpochMilli(100);
        mailbox.offer("s", "low", "低", Priority.LOW, Instant.ofEpochMilli(1));
        mailbox.offer("s", "normal-late", "普通晚", Priority.NORMAL, Instant.ofEpochMilli(200));
        mailbox.offer("s", "high-1", "高一", Priority.HIGH, sameTime);
        mailbox.offer("s", "high-2", "高二", Priority.HIGH, sameTime);
        mailbox.offer("s", "critical", "紧急", Priority.CRITICAL, Instant.ofEpochMilli(999));

        assertEquals(List.of("紧急", "高一", "高二", "普通晚", "低"),
                mailbox.drain("s", 8).stream().map(InputMailbox.MailboxInput::content).toList());
    }

    @Test
    @DisplayName("inputId 在 drain 后仍幂等，且不同 session 可复用同一 ID")
    void idempotencySurvivesDrainAndIsSessionScoped() {
        InputMailbox mailbox = mailbox(2, 3);
        assertTrue(mailbox.offer("s1", "same", "首次", Priority.NORMAL, Instant.now()).accepted());
        mailbox.drain("s1", 1);
        assertEquals(OfferStatus.DUPLICATE,
                mailbox.offer("s1", "same", "重试", Priority.HIGH, Instant.now()).status());
        assertTrue(mailbox.offer("s2", "same", "另一会话", Priority.NORMAL, Instant.now()).accepted());
        assertEquals(1, mailbox.metrics().duplicates());
    }

    @Test
    @DisplayName("满载时高优先级淘汰最低优先级最老输入，淘汰可观测")
    void evictionPolicyIsObservable() {
        InputMailbox mailbox = mailbox(2, 3);
        mailbox.offer("s", "low-old", "旧低", Priority.LOW, Instant.ofEpochMilli(1));
        mailbox.offer("s", "low-new", "新低", Priority.LOW, Instant.ofEpochMilli(2));

        InputMailbox.OfferResult result = mailbox.offer(
                "s", "high", "高", Priority.HIGH, Instant.ofEpochMilli(3));

        assertEquals(OfferStatus.ACCEPTED_WITH_EVICTION, result.status());
        assertEquals("low-old", result.evictedInput().inputId());
        assertEquals(List.of("高", "新低"),
                mailbox.drain("s", 5).stream().map(InputMailbox.MailboxInput::content).toList());
        assertEquals(1, mailbox.metrics().evicted());
        assertEquals(1, mailbox.sessionMetrics("s").orElseThrow().evicted());
    }

    @Test
    @DisplayName("低优先级不能挤掉高优先级；REJECT_NEW 策略恒拒绝新输入")
    void capacityRejectionIsObservable() {
        InputMailbox mailbox = mailbox(1, 2);
        mailbox.offer("s", "high", "高", Priority.HIGH, Instant.now());
        assertEquals(OfferStatus.REJECTED_CAPACITY,
                mailbox.offer("s", "low", "低", Priority.LOW, Instant.now()).status());

        InputMailbox rejectNew = new InputMailbox(1, 2, 2, OverflowPolicy.REJECT_NEW);
        rejectNew.offer("s", "low", "低", Priority.LOW, Instant.now());
        assertEquals(OfferStatus.REJECTED_CAPACITY,
                rejectNew.offer("s", "critical", "紧急", Priority.CRITICAL, Instant.now()).status());
        assertEquals(1, rejectNew.metrics().rejectedCapacity());
    }

    @Test
    @DisplayName("会话总数有界：只自动回收空会话，不丢有待处理输入的会话")
    void sessionLimitIsBoundedAndSafe() {
        InputMailbox mailbox = new InputMailbox(2, 2, 2, OverflowPolicy.REJECT_NEW);
        mailbox.offer("s1", "1", "甲", Priority.NORMAL, Instant.now());
        mailbox.offer("s2", "2", "乙", Priority.NORMAL, Instant.now());

        assertEquals(OfferStatus.REJECTED_SESSION_LIMIT,
                mailbox.offer("s3", "3", "丙", Priority.NORMAL, Instant.now()).status());
        mailbox.drain("s1", 2); // 空会话可在下一次创建时回收
        assertTrue(mailbox.offer("s3", "3", "丙", Priority.NORMAL, Instant.now()).accepted());
        assertEquals(2, mailbox.metrics().sessions());
        assertEquals(1, mailbox.metrics().rejectedSessionLimit());
        assertEquals("乙", mailbox.peek("s2").orElseThrow().content());
    }

    @Test
    @DisplayName("removeSession 显式释放待处理输入和幂等状态")
    void explicitSessionRemoval() {
        InputMailbox mailbox = mailbox(2, 2);
        mailbox.offer("s", "id", "旧", Priority.NORMAL, Instant.now());
        assertEquals(1, mailbox.removeSession("s"));
        assertEquals(0, mailbox.pendingCount("s"));
        assertTrue(mailbox.offer("s", "id", "新会话", Priority.NORMAL, Instant.now()).accepted());
    }

    @Test
    @DisplayName("并发生产与消费不丢不重，队列容量始终受限")
    void concurrentOfferAndDrainAreThreadSafe() throws Exception {
        int producers = 8;
        int each = 100;
        int total = producers * each;
        InputMailbox mailbox = new InputMailbox(total, 2, total, OverflowPolicy.REJECT_NEW);
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int producer = p;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    String id = producer + "-" + i;
                    assertTrue(mailbox.offer("s", id, id, Priority.NORMAL, Instant.now()).accepted());
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        List<InputMailbox.MailboxInput> all = mailbox.drain("s", total);
        Set<String> ids = new HashSet<>(all.stream().map(InputMailbox.MailboxInput::inputId).toList());
        assertEquals(total, all.size());
        assertEquals(total, ids.size());
        assertEquals(0, mailbox.pendingCount("s"));
        assertEquals(total, mailbox.metrics().accepted());
        assertEquals(total, mailbox.metrics().drained());
    }

    @Test
    @DisplayName("参数与输入校验拒绝无界或不可识别状态")
    void validation() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputMailbox(0, 1, 1, OverflowPolicy.REJECT_NEW));
        assertThrows(IllegalArgumentException.class,
                () -> new InputMailbox(2, 1, 1, OverflowPolicy.REJECT_NEW));
        InputMailbox mailbox = mailbox(2, 2);
        assertThrows(IllegalArgumentException.class,
                () -> mailbox.offer(" ", "id", "x", Priority.NORMAL, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> mailbox.drain("s", 0));
    }

    private InputMailbox mailbox(int capacity, int idempotencyWindow) {
        return new InputMailbox(capacity, 8, idempotencyWindow,
                OverflowPolicy.EVICT_LOWEST_PRIORITY_OLDEST);
    }
}
