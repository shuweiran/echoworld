package com.roleplay.engine.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 逻辑链追踪条目（阶段② P-0809-B，调研报告 docs/ui-api-survey.md §5 方案 C）。
 *
 * <p>一条 REST 请求链路的完整记录：请求元信息 + 耗时 + 请求体摘要 +
 * 关联的 LLM 子调用（{@link TraceContext#recordLlm} 打点）与 SSE 事件
 * （{@link TraceContext#recordSse} 打点，SSEController.broadcast* 内触发）。
 *
 * <p>纯调试横切数据，不进 DB，不参与任何业务逻辑。
 */
public class TraceEntry {

    /** 链路 ID：客户端 X-Request-Id 或服务端生成的 UUID。 */
    public final String requestId;
    /** 请求开始时间（epoch millis）。 */
    public final long ts;
    public final String method;
    public final String path;
    /** 查询参数（原始 query string，可为空）。 */
    public String query = "";
    /** 关联对局会话（query 或 JSON body 提取，尽力而为）。 */
    public String sessionId = "";
    /** 来源：frontend=客户端带 X-Request-Id；auto=服务端自动生成。 */
    public String source = "auto";
    /** HTTP 状态码（0=未知/未完成）。 */
    public int status = 0;
    /** 总耗时（毫秒）。 */
    public long ms = 0;
    /** 请求体摘要（UTF-8 截断，防爆）。 */
    public String bodySummary = "";
    /** 异常信息（请求处理抛异常时）。 */
    public String error = "";
    /** SSE 事件（事件名/时间/会话）。 */
    public final List<Map<String, Object>> sseEvents = new ArrayList<>();
    /** LLM 子调用（模型/耗时/时间）。 */
    public final List<Map<String, Object>> llmCalls = new ArrayList<>();

    public TraceEntry(String requestId, long ts, String method, String path) {
        this.requestId = requestId;
        this.ts = ts;
        this.method = method;
        this.path = path;
    }

    public void addSseEvent(String eventType, String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_type", eventType);
        m.put("ts", System.currentTimeMillis());
        if (sessionId != null && !sessionId.isBlank()) {
            m.put("session_id", sessionId);
        }
        sseEvents.add(m);
    }

    public void addLlmCall(String model, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("ms", ms);
        m.put("ts", System.currentTimeMillis());
        llmCalls.add(m);
    }

    /**
     * 序列化为前端 JSON。
     *
     * @param detail true=详情视图（含 body_summary / sse_events / llm_calls）；false=列表视图（仅计数徽标）。
     */
    public Map<String, Object> toMap(boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request_id", requestId);
        m.put("ts", ts);
        m.put("method", method);
        m.put("path", path);
        if (!query.isBlank()) m.put("query", query);
        if (!sessionId.isBlank()) m.put("session_id", sessionId);
        m.put("source", source);
        m.put("status", status);
        m.put("ms", ms);
        if (!error.isBlank()) m.put("error", error);
        m.put("sse_count", sseEvents.size());
        m.put("llm_count", llmCalls.size());
        if (detail) {
            if (!bodySummary.isBlank()) m.put("body_summary", bodySummary);
            if (!sseEvents.isEmpty()) m.put("sse_events", sseEvents);
            if (!llmCalls.isEmpty()) m.put("llm_calls", llmCalls);
        }
        return m;
    }
}
