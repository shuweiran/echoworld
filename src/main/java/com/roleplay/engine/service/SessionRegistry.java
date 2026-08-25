package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.HistoryController;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * D11: 按 session_id 的 RouterService 实例管理（多会话隔离）。
 *
 * <p>缺陷背景：SessionController 原有 {@code Map<String, RouterService> sessions}
 * 只写不读，/api/send 始终走单例 router —— 两个会话先后 /api/init 后互相覆盖
 * 状态（角色、场景、模式、消息互相串扰）。
 *
 * <p>本注册表修复：每个 session_id 持有独立的 {@link RouterService} 实例，
 * 且每个实例配独立 {@link MemoryStore}（消息 / 摘要 / 压缩块 / 轮次计数完全隔离）。
 * 共享的依赖（Arbiter / AgentExecutor / Compressor / Monitor / LLMClient 等）均为
 * 无会话状态的服务，可安全复用同一批 Spring 单例 bean。
 *
 * <p>向后兼容：未传 session_id 时，{@link #get(String)} 回退到默认单例 router（Spring bean）。
 * 非空但未知的 session_id 必须明确返回 404，不能静默读写默认会话造成跨会话串线。
 *
 * <p>注：HistoryController 注入点使用 {@link Lazy}，打破
 * SessionRegistry → HistoryController → RouterService 的构造依赖环。
 */
@Component
public class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, RouterService> sessions = new ConcurrentHashMap<>();
    /** 最近访问时间；只跟踪独立会话，默认单例不参与淘汰。 */
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    /** create/get/remove/sweep 的生命周期锁，避免刚访问的会话被并发清扫。 */
    private final Object lifecycleLock = new Object();
    /** 独立会话释放通知；自治子系统据此清理自己的 session 资源。 */
    private final CopyOnWriteArrayList<RemovalListener> removalListeners = new CopyOnWriteArrayList<>();
    /** 默认单例 router —— 未传 session_id 时的向后兼容目标。 */
    private final RouterService defaultRouter;

    /** 独立会话空闲 TTL；0=禁用。 */
    @Value("${roleplay.session.ttl-ms:43200000}")
    private long sessionTtlMs = 43_200_000L;
    /** 独立会话容量上限；0=不限制。达到上限时淘汰最久未访问会话。 */
    @Value("${roleplay.session.max-sessions:128}")
    private int maxSessions = 128;

    // RouterService 构造依赖链（与 Spring 单例 bean 共用同一批无会话状态服务）
    private final ArbiterService arbiter;
    private final AgentExecutor executor;
    private final Compressor compressor;
    private final Monitor monitor;
    private final GeneratorService generator;
    private final TrackRequestService trackRequestService;
    private final LLMClient llmClient;
    private final HistoryController historyController;
    private final LorebookService lorebookService;
    private final InterruptManager interruptManager;
    private final WorldEventBus eventBus;
    /** D8: SSE 广播器 —— 每个会话 router 实例共享同一批 SSE 连接。 */
    private final SSEController sse;
    /** P-0802-P2：玩家身份解析器（Phase 2 判定链路解析式，透传给会话 router 实例）。 */
    private final PlayerIdentityService identityService;
    /** P-0810-09：场景目标服务（一般模式目标生成/判定，透传给会话 router 实例；null=未启用）。 */
    private final SceneGoalService sceneGoalService;
    /** P-0813-A：自动续轮延时（roleplay.round.auto-continue-ms，毫秒；0=禁用）——
     *  透传给会话专属 router 实例。@Value 只对 Spring bean 生效，createRouter 是 new 出来的，
     *  必须显式注入（同 serialRound 的既有限制）。 */
    @Value("${roleplay.round.auto-continue-ms:3000}")
    private long autoContinueMs = 0;
    /** P-0814-A：点击驱动对话模式开关（roleplay.round.playback-driven）——透传给会话专属 router 实例
     *  （同 autoContinueMs 的 @Value 限制；测试 yml false=旧定时行为，生产 yml true=点击驱动）。 */
    @Value("${roleplay.round.playback-driven:false}")
    private boolean playbackDriven = false;
    /** P-0813-B：校准轮间隔（roleplay.round.calibrate-every；0=禁用；默认 6）——
     *  透传给会话专属 router 实例（同 autoContinueMs 的 @Value 限制）。 */
    @Value("${roleplay.round.calibrate-every:6}")
    private int calibrateEvery = 6;
    /** P-0814-C：一般模式串行调度开关（roleplay.round.serial；D-024/D-027）——透传给会话专属
     *  router 实例。此前缺失此透传 → 会话 router 恒走并行路径（@Value 仅对 Spring bean 生效），
     *  而并行路径 context 未传给 LLM（已另修 AgentExecutor），双重缺陷叠加致 AI 看不到上下文。 */
    @Value("${roleplay.round.serial:false}")
    private boolean serialRound = false;

    public SessionRegistry(@Lazy RouterService defaultRouter, ArbiterService arbiter,
                           AgentExecutor executor, Compressor compressor, Monitor monitor,
                           GeneratorService generator, TrackRequestService trackRequestService,
                           LLMClient llmClient, @Lazy HistoryController historyController,
                           LorebookService lorebookService, InterruptManager interruptManager,
                           WorldEventBus eventBus, SSEController sse,
                           PlayerIdentityService playerIdentityService,
                           SceneGoalService sceneGoalService) {
        this.defaultRouter = defaultRouter;
        this.arbiter = arbiter;
        this.executor = executor;
        this.compressor = compressor;
        this.monitor = monitor;
        this.generator = generator;
        this.trackRequestService = trackRequestService;
        this.llmClient = llmClient;
        this.historyController = historyController;
        this.lorebookService = lorebookService;
        this.interruptManager = interruptManager;
        this.eventBus = eventBus;
        this.sse = sse;
        this.identityService = playerIdentityService;
        this.sceneGoalService = sceneGoalService;
    }

    /** 读路由：未传 session_id → 默认单例；非空未知 session_id → 404。 */
    public RouterService get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return defaultRouter;
        String normalized = sessionId.trim();
        synchronized (lifecycleLock) {
            RouterService router = sessions.get(normalized);
            if (router == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "会话不存在或已过期: " + normalized);
            }
            lastAccess.put(normalized, System.currentTimeMillis());
            return router;
        }
    }

    /**
     * 写路由：会话的独立实例，不存在则创建（/api/init、历史加载首次触达时调用）。
     */
    public RouterService getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return defaultRouter;
        String normalized = sessionId.trim();
        synchronized (lifecycleLock) {
            RouterService existing = sessions.get(normalized);
            if (existing != null) {
                lastAccess.put(normalized, System.currentTimeMillis());
                return existing;
            }
            evictOldestIfAtCapacity();
            RouterService created = createRouter(normalized);
            sessions.put(normalized, created);
            lastAccess.put(normalized, System.currentTimeMillis());
            return created;
        }
    }

    /**
     * 显式关闭并移除独立会话。使用 session-scoped close 取消本 Router 的自动续轮与等待状态，
     * 不调用共享 InterruptManager.cancelAll，避免误停其他会话。
     * 空 session_id 指向兼容默认单例，不能通过本方法删除。
     */
    public boolean remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        RouterService removed;
        String normalized = sessionId.trim();
        synchronized (lifecycleLock) {
            removed = sessions.remove(normalized);
            lastAccess.remove(normalized);
        }
        releaseQuietly(normalized, removed, "explicit close");
        return removed != null;
    }

    /** Spring 定时清扫入口；由统一的 @EnableScheduling 激活，不再依赖第 N 次业务访问。 */
    @Scheduled(fixedDelayString = "${roleplay.session.sweep-interval-ms:60000}")
    public void sweepExpired() {
        if (sessionTtlMs <= 0) return;
        long deadline = System.currentTimeMillis() - sessionTtlMs;
        Map<String, RouterService> expired = new java.util.LinkedHashMap<>();
        synchronized (lifecycleLock) {
            for (Map.Entry<String, Long> entry : lastAccess.entrySet()) {
                Long accessedAt = entry.getValue();
                if (accessedAt == null || accessedAt >= deadline) continue;
                String sessionId = entry.getKey();
                RouterService removed = sessions.remove(sessionId);
                lastAccess.remove(sessionId);
                if (removed != null) expired.put(sessionId, removed);
            }
        }
        expired.forEach((id, router) -> releaseQuietly(id, router, "idle TTL"));
    }

    /** 测试/运维钩子：运行时调整 TTL，0=禁用。 */
    public void setSessionTtlMs(long ttlMs) {
        this.sessionTtlMs = Math.max(0, ttlMs);
    }

    /** 测试/运维钩子：运行时调整容量，0=不限制。 */
    public void setMaxSessions(int maxSessions) {
        this.maxSessions = Math.max(0, maxSessions);
    }

    /** 当前注册的独立会话数（监控/调试用）。 */
    public int sessionCount() {
        return sessions.size();
    }

    public void addRemovalListener(RemovalListener listener) {
        if (listener != null) removalListeners.addIfAbsent(listener);
    }

    public void removeRemovalListener(RemovalListener listener) {
        if (listener != null) removalListeners.remove(listener);
    }

    private void evictOldestIfAtCapacity() {
        if (maxSessions <= 0) return;
        while (sessions.size() >= maxSessions) {
            String oldest = lastAccess.entrySet().stream()
                    .filter(e -> sessions.containsKey(e.getKey()))
                    .min(Comparator.comparingLong(e -> e.getValue() == null ? Long.MIN_VALUE : e.getValue()))
                    .map(Map.Entry::getKey)
                    .orElseGet(() -> sessions.keySet().stream().findFirst().orElse(null));
            if (oldest == null) return;
            RouterService removed = sessions.remove(oldest);
            lastAccess.remove(oldest);
            releaseQuietly(oldest, removed, "capacity limit");
        }
    }

    private void releaseQuietly(String sessionId, RouterService router, String reason) {
        if (router == null) return;
        try {
            router.closeSessionResources();
        } catch (RuntimeException e) {
            log.warn("Failed to release session {} during {}: {}", sessionId, reason, e.getMessage());
        }
        for (RemovalListener listener : removalListeners) {
            try { listener.onRemoved(sessionId, router); }
            catch (RuntimeException e) {
                log.warn("Session removal listener failed for {}: {}", sessionId, e.getMessage());
            }
        }
        log.info("Removed isolated RouterService for session {} ({})", sessionId, reason);
    }

    @FunctionalInterface
    public interface RemovalListener {
        void onRemoved(String sessionId, RouterService removedRouter);
    }

    /** 创建会话专属 router 实例：独立 MemoryStore，共享无状态服务。 */
    private RouterService createRouter(String sessionId) {
        RouterService r = new RouterService(arbiter, executor, new MemoryStore(), compressor,
            monitor, generator, trackRequestService, llmClient, historyController,
            lorebookService, interruptManager, eventBus, sse, identityService);
        // P-0810-09：注入场景目标服务（一般模式 init 生成 / 每轮判定）
        r.setSceneGoalService(sceneGoalService);
        // P-0813-A：注入自动续轮延时（@Value 仅对 Spring bean 生效，此处显式透传）
        r.setAutoContinueMs(autoContinueMs);
        // P-0814-A：注入点击驱动开关（同上，显式透传）
        r.setPlaybackDriven(playbackDriven);
        // P-0814-C：注入串行调度开关（同上，显式透传；缺失时会话 router 恒并行）
        r.setSerialRound(serialRound);
        // P-0813-B：注入校准轮间隔（同上，显式透传）
        r.setCalibrateEvery(calibrateEvery);
        log.info("D11: created isolated RouterService for session {}", sessionId);
        return r;
    }
}
