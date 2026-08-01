package com.roleplay.engine.broadcast;

import com.roleplay.engine.config.AppConfig;
import com.roleplay.engine.interrupt.GameEvent;
import com.roleplay.engine.interrupt.WorldEventBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 公告/广播服务 —— 演讲与广播合并地基的中枢（调研报告 §3.3）。
 *
 * <p>统一消息管线：任何发布方（AI 演讲 / 玩家广播 / 系统阶段）→ {@link #enqueue}
 * → 优先级队列（SYSTEM &gt; EVENT &gt; PLAYER &gt; NPC）→ 滑动窗口节流 + 同 key 合并
 * → 每 100ms flush → 复用 {@link WorldEventBus} 进程内分发（TYPE_ANNOUNCEMENT）
 * + {@link SseBroadcaster} 推前端（事件名 announcement）。
 *
 * <p>节流参数（窗口/上限/队列上限/环形缓冲大小）来自 {@code roleplay.broadcast.*} 配置
 * （AppConfig.BroadcastConfig，对齐 D-004「阈值勿 hardcode」决策风格），未配置时用默认值。
 *
 * <p>线程安全：enqueue/flush/recentSince 用同一把锁串行化（消息量级为「每轮几条」，
 * 不需要无锁队列；flush 消费不阻塞模拟 tick——它由独立调度线程驱动）。
 */
@Component
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);
    /** 推送事件名（前端 useSSE 监听表注册）。 */
    public static final String SSE_EVENT = "announcement";
    private static final long FLUSH_INTERVAL_MS = 100;

    private final long windowMs;
    private final int maxPerWindow;
    private final int maxPending;
    private final int recentRingSize;
    private final SseBroadcaster sse;
    private final WorldEventBus eventBus;

    /**
     * 演讲广播模式（来源 {@code roleplay.broadcast.speech-mode}，默认 merged=正式版）。
     * merged=正式版（回调 → HearingSystem 声学判定 → 可配置兜底）；
     * auto=方案A 旧行为（回调 → wouldOthersListen 硬编码判定，无听众恒全局公告）；
     * split=方案B 旧行为（SpeechStrategy.processResults 内联区域广播）。
     * 各路径均实时读此值 gate，同一运行实例可运行时切换（setSpeechMode / POST /api/announcements/mode），
     * merged/auto 走回调、split 走内联，互斥保证不重复推送。
     */
    private volatile String speechMode;
    /** 无听众兜底（正式版 merged 生效）：true=无听众→自动升级全局公告（默认）；false=不升级，仅区域演讲。 */
    private final boolean fallbackToGlobal;
    /** 剧本杀阶段切换 SYSTEM 广播总开关（默认 true=启用，进正式版）。 */
    private final boolean scriptPhaseBroadcast;

    /** 优先级队列：level.prio 升序（SYSTEM 先），同级按时间先进先出。 */
    private final PriorityQueue<BroadcastMessage> queue = new PriorityQueue<>(
            Comparator.comparingInt((BroadcastMessage m) -> m.level().prio())
                      .thenComparingLong(m -> m.timestamp()));
    /** 滑动窗口计数：channel → 窗口内命中时间戳。 */
    private final Map<String, Deque<Long>> windowHits = new ConcurrentHashMap<>();
    /** 同 key 合并计数：coalesceKey → 累计条数（首条已入队，后续只计数）。 */
    private final Map<String, Integer> coalesced = new ConcurrentHashMap<>();
    /** 断线补发环形缓冲：最近 N 条已推送消息。 */
    private final Deque<BroadcastMessage> recent = new ArrayDeque<>();
    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "announcement-flush");
        t.setDaemon(true);
        return t;
    });

    public AnnouncementService(SseBroadcaster sse, WorldEventBus eventBus, AppConfig appConfig) {
        this.sse = sse;
        this.eventBus = eventBus;
        AppConfig.BroadcastConfig cfg = appConfig.getBroadcast();
        this.windowMs = cfg.getWindowMs();
        this.maxPerWindow = cfg.getMaxPerWindow();
        this.maxPending = cfg.getMaxPending();
        this.recentRingSize = cfg.getRecentRingSize();
        this.speechMode = cfg.getSpeechMode() == null || cfg.getSpeechMode().isBlank()
                ? "merged" : cfg.getSpeechMode();
        this.fallbackToGlobal = cfg.isFallbackToGlobal();
        this.scriptPhaseBroadcast = cfg.isScriptPhaseBroadcast();
    }

    /** 当前演讲广播模式（merged=正式版 / auto=方案A / split=方案B）。 */
    public String getSpeechMode() { return speechMode; }

    /** 无听众兜底（merged 生效）：true=升级全局公告 / false=仅区域演讲。 */
    public boolean isFallbackToGlobal() { return fallbackToGlobal; }

    /** 剧本杀阶段切换 SYSTEM 广播总开关。 */
    public boolean isScriptPhaseBroadcast() { return scriptPhaseBroadcast; }

    /**
     * 运行时切换演讲广播模式（同一运行实例演示用）。
     * 合法值：merged（正式版）｜auto（方案A 旧行为）｜split（方案B 旧行为）；非法值忽略。
     */
    public void setSpeechMode(String mode) {
        if (mode == null) return;
        String m = mode.trim().toLowerCase();
        if ("merged".equals(m) || "auto".equals(m) || "split".equals(m)) {
            this.speechMode = m;
            log.info("Speech broadcast mode → {}", m);
        }
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /**
     * AI 发言自动选择入口：有听众（近处可听、未入群角色 ≥2）→ 演讲（area + 半径），
     * 否则 → 全局广播。调用方（SimulationService / 演示端点）用
     * {@code ModeClassifier.wouldOthersListen}（复用现有听众判定）计算 hasAudience。
     *
     * @return 入队成功的消息；被节流/合并/队列满丢弃时返回 null
     */
    public BroadcastMessage enqueueAutoSpeech(String speaker, String text,
                                              double x, double y, double radius,
                                              boolean hasAudience) {
        // 兼容重载：不传兜底开关 → 恒升级全局公告（方案A 旧行为，供 auto 回退对比）
        return enqueueAutoSpeech(speaker, text, x, y, radius, hasAudience, true);
    }

    /**
     * AI 发言自动选择入口（正式版 merged）：有听众 → 演讲（area + 半径）；
     * 无听众时按 {@code fallbackToGlobal} 决定——true=升级全局公告（信息不哑火），
     * false=不升级，仅区域演讲（纯空间语义，半径外自然无人展示）。
     *
     * @return 入队成功的消息；被节流/合并/队列满丢弃时返回 null
     */
    public BroadcastMessage enqueueAutoSpeech(String speaker, String text,
                                              double x, double y, double radius,
                                              boolean hasAudience, boolean fallbackToGlobal) {
        if (hasAudience || !fallbackToGlobal) {
            // 有听众（或关闭兜底）= 区域演讲：channel=area，半径取说话人听觉范围，形态标记 speech
            return enqueue(BroadcastMessage.of(BroadcastMessage.Level.NPC, "area", speaker, text,
                    x, y, radius, BroadcastMessage.MODE_SPEECH));
        }
        // 无听众 + 兜底开 → 全局公告（无范围），形态标记 announcement
        return enqueue(BroadcastMessage.of(BroadcastMessage.Level.NPC, "global", speaker, text,
                -1, -1, 0, BroadcastMessage.MODE_ANNOUNCEMENT));
    }

    /**
     * 消息入队：滑动窗口节流 → 同 key 合并 → 优先级队列（超限丢弃）。
     *
     * @return 入队成功的消息；被节流/合并（只计数不重复入队）/队列满丢弃时返回 null
     */
    public BroadcastMessage enqueue(BroadcastMessage m) {
        if (m == null) return null;
        synchronized (lock) {
            if (!throttled(m.channel())) {
                log.debug("Announcement throttled: {} (channel={})", m.coalesceKey(), m.channel());
                return null;
            }
            String key = m.coalesceKey();
            if (key != null && coalesced.containsKey(key)) {
                // 同 key 已有首条在队列 → 合并：只累计条数，不再入队（防刷屏）
                coalesced.merge(key, 1, Integer::sum);
                log.debug("Announcement coalesced: {} → ×{}", key, coalesced.get(key) + 1);
                return null;
            }
            if (queue.size() >= maxPending) {
                log.warn("Announcement queue full ({}), dropping: {}", maxPending, m.speaker());
                return null;
            }
            if (key != null) coalesced.put(key, 1);
            queue.offer(m);
            return m;
        }
    }

    /** 滑动窗口限速：同 channel 每 windowMs 内 ≤ maxPerWindow 条。 */
    private boolean throttled(String channel) {
        long now = System.currentTimeMillis();
        Deque<Long> hits = windowHits.computeIfAbsent(channel, k -> new ArrayDeque<>());
        while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) hits.pollFirst();
        if (hits.size() >= maxPerWindow) return false;
        hits.addLast(now);
        return true;
    }

    /**
     * 消费队列（100ms 节拍 + 测试可手动调用）：合并计数拼进文本（×N）→
     * WorldEventBus 进程内分发（TYPE_ANNOUNCEMENT）→ SSE 推前端（announcement）→
     * 写入断线补发环形缓冲。
     */
    public void flush() {
        List<BroadcastMessage> batch = new ArrayList<>();
        synchronized (lock) {
            BroadcastMessage m;
            while ((m = queue.poll()) != null) batch.add(m);
        }
        for (BroadcastMessage m : batch) {
            String text = m.text();
            String key = m.coalesceKey();
            if (key != null) {
                Integer total = coalesced.remove(key);
                if (total != null && total > 1) {
                    text = text + "（×" + total + "）";
                }
            }
            push(m, text);
            synchronized (lock) {
                recent.addLast(m);
                if (recent.size() > recentRingSize) recent.removeFirst();
            }
        }
        if (!batch.isEmpty()) {
            log.info("Announcement flushed: {} message(s)", batch.size());
        }
    }

    /** 进程内分发（WorldEventBus）+ 进程外推送（SSE）双通道。 */
    private void push(BroadcastMessage m, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", m.id());
        payload.put("level", m.level().name());
        payload.put("channel", m.channel());
        payload.put("speaker", m.speaker());
        payload.put("text", text);
        payload.put("x", m.x());
        payload.put("y", m.y());
        payload.put("radius", m.radius());
        payload.put("mode", m.mode());
        payload.put("timestamp", m.timestamp());

        if (eventBus != null) {
            eventBus.publish(new GameEvent(GameEvent.TYPE_ANNOUNCEMENT, "announcement", payload));
        }
        if (sse != null) {
            try {
                sse.broadcast(SSE_EVENT, payload);
            } catch (Exception e) {
                log.warn("SSE broadcast failed: {}", e.getMessage());
            }
        }
    }

    /** 断线补发：返回 sinceTs 之后推送过的消息（前端重连后拉取，P3 轻量版）。 */
    public List<BroadcastMessage> recentSince(long sinceTs) {
        synchronized (lock) {
            List<BroadcastMessage> out = new ArrayList<>();
            for (BroadcastMessage m : recent) {
                if (m.timestamp() > sinceTs) out.add(m);
            }
            return out;
        }
    }

    /** 当前队列深度（测试/监控用）。 */
    public int pendingCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** 最近一次 flush 时实际推送的消息快照（测试用，避免依赖内部 recent）。 */
    public List<Map<String, Object>> recentPushed(int limit) {
        synchronized (lock) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (BroadcastMessage m : recent) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", m.id());
                p.put("level", m.level().name());
                p.put("channel", m.channel());
                p.put("speaker", m.speaker());
                p.put("text", m.text());
                p.put("mode", m.mode());
                out.add(p);
                if (out.size() >= limit) break;
            }
            return out;
        }
    }
}
