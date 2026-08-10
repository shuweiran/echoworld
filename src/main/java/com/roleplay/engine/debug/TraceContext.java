package com.roleplay.engine.debug;

/**
 * 逻辑链追踪的静态门面（阶段② P-0809-B，调研报告 ui-api-survey.md §5 方案 C）。
 *
 * <p>ThreadLocal 承载「当前请求上下文」{@link TraceEntry}：TraceFilter 在请求进入时
 * {@link #setCurrent(TraceEntry)}、finally 中 {@link #clear()}，请求处理线程内任何
 * 打点（LLM 调用 / SSE 广播）都能关联到当前链路。
 *
 * <p>{@link TraceService} 在 Spring 启动时经 {@link #register} 注册为全局单例
 * （static volatile），LLMClient / SSEController 等不便改构造器的点直接调用静态方法
 * 上报，避免侵入既有构造链；测试可自行 register/unregister。
 *
 * <p>零开销保证：开关关闭（TraceService.isEnabled()=false）或线程无请求上下文时
 * recordLlm/recordSse 立即返回，不做任何分配。
 */
public final class TraceContext {

    private static volatile TraceService registry;
    private static final ThreadLocal<TraceEntry> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 注册全局服务（TraceService @PostConstruct 调用；测试可手动注册/注销）。 */
    public static void register(TraceService service) {
        registry = service;
    }

    /** 注销全局服务（测试隔离用）。 */
    public static void unregister() {
        registry = null;
    }

    /** 绑定当前请求条目（TraceFilter 调用）。 */
    public static void setCurrent(TraceEntry entry) {
        CURRENT.set(entry);
    }

    /** 当前线程的请求条目（无请求上下文时为 null）。 */
    public static TraceEntry current() {
        return CURRENT.get();
    }

    /** 解除绑定（TraceFilter finally 调用，防 ThreadLocal 泄漏）。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * LLM 调用打点：LLMClient.callSync / callStream 墙钟耗时 + 模型，
     * 关联到当前请求链路。开关关闭/无请求上下文 → 无操作。
     */
    public static void recordLlm(String model, long ms) {
        TraceService svc = registry;
        TraceEntry entry = CURRENT.get();
        if (svc == null || entry == null || !svc.isEnabled()) return;
        entry.addLlmCall(model, ms);
    }

    /**
     * SSE 事件打点：SSEController.broadcast / broadcastToSession 内触发，
     * 关联到当前请求链路（大多数 SSE 事件在 REST 请求线程内同步触发，可关联；
     * 后台虚拟线程——讨论引擎/autoPlay——触发的 SSE 无请求上下文，无法关联，属已知限制）。
     */
    public static void recordSse(String eventType, String sessionId) {
        TraceService svc = registry;
        TraceEntry entry = CURRENT.get();
        if (svc == null || entry == null || !svc.isEnabled()) return;
        entry.addSseEvent(eventType, sessionId);
    }
}
