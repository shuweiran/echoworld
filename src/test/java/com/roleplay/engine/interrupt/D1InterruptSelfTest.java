package com.roleplay.engine.interrupt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D1 中断系统逻辑自测（独立运行，不经 Spring/mvn）。
 * 覆盖：三种停止类型、状态机、协作式检查点、事件发布、TrackChange 事件驱动取消、线程中断。
 */
public class D1InterruptSelfTest {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        testHardCancel();
        testSoftCancelSavesPartial();
        testStateInvalid();
        testTrackChangeEvent();
        testEventBusTypedPublish();
        testCooperativeCheckpointInLoop();
        System.out.println("==============================================");
        System.out.println("D1 self-test result: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  [PASS] " + name); }
        else { failed++; System.out.println("  [FAIL] " + name); }
    }

    /** HARD 硬停止：token 置位 + 状态 CANCELLED + 线程中断 + TASK_CANCELLED 事件。 */
    static void testHardCancel() throws Exception {
        System.out.println("== testHardCancel ==");
        WorldEventBus bus = new WorldEventBus();
        InterruptManager im = new InterruptManager(bus);
        im.init();
        AgentTaskManager atm = new AgentTaskManager(im);

        AtomicReference<String> eventType = new AtomicReference<>();
        AtomicReference<String> eventTaskId = new AtomicReference<>();
        bus.subscribe(GameEvent.TYPE_TASK_CANCELLED, e -> {
            eventType.set(e.getType());
            eventTaskId.set(String.valueOf(e.getPayload().get("task_id")));
        });

        AgentTask task = atm.createTask("小明", TaskType.DIALOGUE, Map.of("trackId", "main"));
        check("task created IDLE", task.getStatus() == AgentTaskStatus.IDLE);
        atm.startTask(task);
        check("task RUNNING", task.getStatus() == AgentTaskStatus.RUNNING);

        // 模拟生成线程：阻塞在可中断操作（等价于 LLM HTTP 调用），并挂接 Future。
        // 注意用 executor.submit（FutureTask）而非 CompletableFuture —— CF.cancel 无法中断运行中线程。
        CompletableFuture<Boolean> threadInterrupted = new CompletableFuture<>();
        CompletableFuture<Boolean> generationAborted = new CompletableFuture<>();
        AgentTask finalTask = task;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> workerFuture = pool.submit(() -> {
            try {
                Thread.sleep(60_000);            // 模拟进行中的 LLM 调用（可中断阻塞）
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                threadInterrupted.complete(true);
            }
            try {
                finalTask.getCancelToken().checkpoint();   // 中断后检查点 → 取消信号
                generationAborted.complete(false);
            } catch (TaskCancelledException e) {
                generationAborted.complete(true);
            }
        });
        im.attachFuture(task.getId(), workerFuture);
        Thread.sleep(100);

        // 另一线程发起硬停止 → token 置位 + future.cancel(true) 中断工作线程
        Thread canceller = new Thread(() -> im.cancel(finalTask.getId(), StopType.HARD, "NPC 死亡"));
        canceller.start();
        canceller.join();
        pool.shutdown();

        check("HARD token cancelled", finalTask.getCancelToken().isCancelled());
        check("HARD status CANCELLED", finalTask.getStatus() == AgentTaskStatus.CANCELLED);
        check("HARD stopType recorded", finalTask.getStopType() == StopType.HARD);
        check("HARD worker thread interrupted", threadInterrupted.get(1, TimeUnit.SECONDS));
        check("HARD generation aborted at checkpoint", generationAborted.get(1, TimeUnit.SECONDS));
        check("TASK_CANCELLED event published", "TASK_CANCELLED".equals(eventType.get()));
        check("event carries task id", finalTask.getId().equals(eventTaskId.get()));
        check("getTask still queryable", im.getTask(finalTask.getId()) != null);
        check("active count 0 after cancel", im.activeTaskCount() == 0);
    }

    /** SOFT 软停止：不中断线程，检查点抛异常，partial 保存未完成内容。 */
    static void testSoftCancelSavesPartial() throws Exception {
        System.out.println("== testSoftCancelSavesPartial ==");
        WorldEventBus bus = new WorldEventBus();
        InterruptManager im = new InterruptManager(bus);
        im.init();
        AgentTaskManager atm = new AgentTaskManager(im);

        AgentTask task = atm.createTask("小红", TaskType.DIALOGUE, Map.of());
        atm.startTask(task);
        // 模拟软停止发生时刻：LLM 调用已完成，内容未提交
        String partial = "其实我认为凶手是...";
        task.saveUnfinished(partial);

        im.cancel(task.getId(), StopType.SOFT, "玩家打断");

        check("SOFT token cancelled", task.getCancelToken().isCancelled());
        check("SOFT status CANCELLED", task.getStatus() == AgentTaskStatus.CANCELLED);
        check("SOFT stopType SOFT", task.getStopType() == StopType.SOFT);
        check("SOFT unfinished content saved", partial.equals(task.getUnfinishedContent()));
        // 软停止后检查点抛 TaskCancelledException（带 stopType）
        try {
            task.getCancelToken().checkpoint();
            check("SOFT checkpoint throws", false);
        } catch (TaskCancelledException e) {
            check("SOFT checkpoint throws", e.getStopType() == StopType.SOFT);
        }
    }

    /** STATE_INVALID 状态停止：状态落到 INTERRUPTED。 */
    static void testStateInvalid() throws Exception {
        System.out.println("== testStateInvalid ==");
        WorldEventBus bus = new WorldEventBus();
        InterruptManager im = new InterruptManager(bus);
        im.init();
        AgentTaskManager atm = new AgentTaskManager(im);

        AgentTask task = atm.createTask("阿杰", TaskType.MOVE, Map.of("trackId", "t2"));
        atm.startTask(task);
        im.cancel(task.getId(), StopType.STATE_INVALID, "目标已取消（玩家离开）");

        check("STATE status INTERRUPTED", task.getStatus() == AgentTaskStatus.INTERRUPTED);
        check("STATE stopType STATE_INVALID", task.getStopType() == StopType.STATE_INVALID);
        check("STATE token cancelled", task.getCancelToken().isCancelled());
        check("STATE isTerminal", task.getStatus().isTerminal());
    }

    /** TrackChangeEvent：不在新轨道集合的任务被 STATE_INVALID 取消，仍属新轨道的保留。 */
    static void testTrackChangeEvent() throws Exception {
        System.out.println("== testTrackChangeEvent ==");
        WorldEventBus bus = new WorldEventBus();
        InterruptManager im = new InterruptManager(bus);
        im.init();
        AgentTaskManager atm = new AgentTaskManager(im);

        // 两个任务：A 在 track1，B 在 track2（task B 模拟已在进行）
        AgentTask taskA = atm.createTask("A", TaskType.DIALOGUE, Map.of("trackId", "track1"));
        AgentTask taskB = atm.createTask("B", TaskType.DIALOGUE, Map.of("trackId", "track2"));
        atm.startTask(taskA);
        atm.startTask(taskB);

        // 轨道变化：track2 被移除，只剩 track1（A 保留）
        bus.publish(new TrackChangeEvent("test",
                List.of("track1"), Map.of("track1", List.of("A")),
                List.of("track2"), List.of("A", "B")));

        check("A 仍属新轨道 → 保留 RUNNING", taskA.getStatus() == AgentTaskStatus.RUNNING);
        check("B 所在轨道已移除 → INTERRUPTED", taskB.getStatus() == AgentTaskStatus.INTERRUPTED);
        check("B reason 记录轨道变更", taskB.getStopReason().contains("轨道"));
    }

    /** 事件总线：类型过滤订阅 + 载荷。 */
    static void testEventBusTypedPublish() throws Exception {
        System.out.println("== testEventBusTypedPublish ==");
        WorldEventBus bus = new WorldEventBus();
        AtomicReference<GameEvent> got = new AtomicReference<>();
        bus.subscribe("MY_EVENT", got::set);
        bus.publish(new GameEvent("MY_EVENT", "t", Map.of("k", "v")));
        check("typed subscribe receives event", got.get() != null && "MY_EVENT".equals(got.get().getType()));
        check("payload preserved", "v".equals(got.get().getPayload().get("k")));
        check("listener count", bus.typedListenerCount() == 1);
    }

    /** 协作式循环：模拟需求文档 §五 while(stream.hasNext()) 检查点退出。 */
    static void testCooperativeCheckpointInLoop() {
        System.out.println("== testCooperativeCheckpointInLoop ==");
        CancellationToken token = new CancellationToken();
        StringBuilder stream = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            if (token.isCancelled()) break;            // 需求文档 §五：检查点
            stream.append("token").append(i).append(" ");
            if (i == 3) token.cancel(StopType.SOFT, "流式输出中途打断");
        }
        check("loop stopped at checkpoint", !stream.toString().contains("token4"));
        check("partial content kept", stream.toString().contains("token3"));
        // 取消幂等：重复置位不覆盖首次 stopType
        token.cancel(StopType.HARD, "again");
        check("cancel is idempotent (first stopType kept)", token.getStopType() == StopType.SOFT);
    }
}
