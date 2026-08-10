package com.roleplay.engine.debug;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * API 逻辑链追踪服务（阶段② P-0809-B，调研报告 docs/ui-api-survey.md §5 方案 C）。
 *
 * <p>内存环形缓冲（{@link ConcurrentLinkedQueue} + 容量上限，默认 1000、硬上限 2000），
 * 不进 DB。开关 {@code roleplay.debug.trace-enabled}（默认 false）关闭时：
 * TraceFilter 直通零打点、/api/debug/trace 端点返回 404，对既有行为零影响。
 *
 * <p>线程安全：入队/清理由调用方（TraceFilter 请求线程）串行执行；
 * {@link #list}/{@link #get} 为弱一致读（调试用途可接受）。
 */
@Service
public class TraceService {

    private static final int LIST_LIMIT_MAX = 200;

    private final int capacity;
    private final ConcurrentLinkedQueue<TraceEntry> buffer = new ConcurrentLinkedQueue<>();
    private volatile boolean enabled;

    public TraceService(
            @Value("${roleplay.debug.trace-enabled:false}") boolean traceEnabled,
            @Value("${roleplay.debug.trace-size:1000}") int traceSize) {
        this.capacity = Math.min(Math.max(traceSize, 1), 2000);
        this.enabled = traceEnabled;
    }

    @PostConstruct
    void register() {
        TraceContext.register(this);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 运行时开关（测试钩子；生产由 yml 决定）。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCapacity() {
        return capacity;
    }

    /** 开启一条请求链路并绑定当前线程（TraceFilter 调用）。 */
    public TraceEntry start(String requestId, String method, String path, String query, String sessionId, String source) {
        TraceEntry entry = new TraceEntry(requestId, System.currentTimeMillis(), method, path);
        entry.query = query == null ? "" : query;
        entry.sessionId = sessionId == null ? "" : sessionId;
        entry.source = source;
        TraceContext.setCurrent(entry);
        return entry;
    }

    /** 收尾填充（状态码/耗时/请求体摘要/异常），仍由 TraceFilter 调用。 */
    public void finish(TraceEntry entry, int status, long ms, String bodySummary, String error) {
        entry.status = status;
        entry.ms = ms;
        if (bodySummary != null && !bodySummary.isBlank()) entry.bodySummary = bodySummary;
        if (error != null && !error.isBlank()) entry.error = error;
    }

    /** 入队（容量超限时淘汰最旧）。 */
    public void enqueue(TraceEntry entry) {
        buffer.add(entry);
        trim();
    }

    private void trim() {
        while (buffer.size() > capacity) {
            buffer.poll();
        }
    }

    /** 最近 N 条（新→旧）；N 收拢到 [1, 200]。 */
    public List<TraceEntry> list(int limit) {
        int n = Math.min(Math.max(limit, 1), LIST_LIMIT_MAX);
        List<TraceEntry> all = new ArrayList<>(buffer);
        int size = all.size();
        List<TraceEntry> out = new ArrayList<>(Math.min(n, size));
        for (int i = size - 1; i >= 0 && out.size() < n; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    /** 按链路 ID 查详情（无 → null）。 */
    public TraceEntry get(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        for (TraceEntry e : buffer) {
            if (requestId.equals(e.requestId)) return e;
        }
        return null;
    }

    /** 缓冲内条目数（测试/观测用）。 */
    public int count() {
        return buffer.size();
    }

    /** 清空缓冲（测试钩子）。 */
    public void clear() {
        buffer.clear();
    }
}
