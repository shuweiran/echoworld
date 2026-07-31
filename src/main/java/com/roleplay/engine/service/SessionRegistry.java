package com.roleplay.engine.service;

import com.roleplay.engine.agent.AgentExecutor;
import com.roleplay.engine.controller.HistoryController;
import com.roleplay.engine.controller.SSEController;
import com.roleplay.engine.interrupt.InterruptManager;
import com.roleplay.engine.interrupt.WorldEventBus;
import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>向后兼容：未传 session_id 或 session_id 未知时，{@link #get(String)} 回退到
 * 默认单例 router（Spring bean），旧客户端（前端当前不传 session_id）行为完全不变。
 *
 * <p>注：HistoryController 注入点使用 {@link Lazy}，打破
 * SessionRegistry → HistoryController → RouterService 的构造依赖环。
 */
@Component
public class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, RouterService> sessions = new ConcurrentHashMap<>();
    /** 默认单例 router —— 未传 session_id 时的向后兼容目标。 */
    private final RouterService defaultRouter;

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

    public SessionRegistry(@Lazy RouterService defaultRouter, ArbiterService arbiter,
                           AgentExecutor executor, Compressor compressor, Monitor monitor,
                           GeneratorService generator, TrackRequestService trackRequestService,
                           LLMClient llmClient, @Lazy HistoryController historyController,
                           LorebookService lorebookService, InterruptManager interruptManager,
                           WorldEventBus eventBus, SSEController sse) {
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
    }

    /**
     * 读路由：按 session_id 取会话实例；未传 / 未知 session_id → 默认单例（向后兼容）。
     */
    public RouterService get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return defaultRouter;
        return sessions.getOrDefault(sessionId, defaultRouter);
    }

    /**
     * 写路由：会话的独立实例，不存在则创建（/api/init、历史加载首次触达时调用）。
     */
    public RouterService getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return defaultRouter;
        return sessions.computeIfAbsent(sessionId, this::createRouter);
    }

    /** 当前注册的独立会话数（监控/调试用）。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 创建会话专属 router 实例：独立 MemoryStore，共享无状态服务。 */
    private RouterService createRouter(String sessionId) {
        RouterService r = new RouterService(arbiter, executor, new MemoryStore(), compressor,
            monitor, generator, trackRequestService, llmClient, historyController,
            lorebookService, interruptManager, eventBus, sse);
        log.info("D11: created isolated RouterService for session {}", sessionId);
        return r;
    }
}
