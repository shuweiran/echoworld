package com.roleplay.engine.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * API 逻辑链追踪过滤器（阶段② P-0809-B，调研报告 docs/ui-api-survey.md §5 方案 C）。
 *
 * <ul>
 *   <li>仅拦截 {@code /api/**}（SSE 长连接 {@code /api/events} 除外——其事件经
 *       SSEController.broadcast* 钩子附加到触发请求的链路，连接本身不做条目）；</li>
 *   <li>X-Request-Id：客户端携带则回显并标记来源 frontend；否则生成 UUID 标记 auto；</li>
 *   <li>请求体摘要：{@link ContentCachingRequestWrapper} 缓存（8KB 上限）→ UTF-8 截断
 *       （600 字符），防爆；JSON body 内 session_id 尽力提取用于对局分组；</li>
 *   <li>开关关闭（roleplay.debug.trace-enabled=false）→ 直通，零打点零影响。</li>
 * </ul>
 */
@Component
public class TraceFilter extends OncePerRequestFilter {

    private static final int BODY_CACHE_LIMIT = 8192;
    private static final int BODY_SUMMARY_MAX = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TraceService traceService;

    /** 仅 /api/** 需要打点（静态资源 / SPA 兜底不拦）。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /**
     * SSE 长连接（/api/events）不做请求条目打点：异步连接生命周期与普通请求不同
     * （SseEmitter 返回后请求即完成、连接继续挂起），其事件由 broadcast* 钩子承载。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        // 开关关闭 → 直通，零打点（不 wrap、不加头、不记录）
        if (!traceService.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/events")) {
            chain.doFilter(request, response);
            return;
        }

        // X-Request-Id：来源（前端带）或自动生成（auto），响应头回写
        String headerId = request.getHeader("X-Request-Id");
        String requestId;
        String source;
        if (headerId != null && !headerId.isBlank()) {
            requestId = headerId.trim();
            source = "frontend";
        } else {
            requestId = UUID.randomUUID().toString();
            source = "auto";
        }
        response.setHeader("X-Request-Id", requestId);

        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, BODY_CACHE_LIMIT);
        TraceEntry entry = traceService.start(requestId, request.getMethod(), uri,
                request.getQueryString(), request.getParameter("session_id"), source);
        long t0 = System.currentTimeMillis();
        Throwable failure = null;
        try {
            chain.doFilter(wrapped, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            long ms = System.currentTimeMillis() - t0;
            traceService.finish(entry, response.getStatus(), ms, bodySummary(wrapped, entry), failure == null ? null : failure.getMessage());
            TraceContext.clear();
            traceService.enqueue(entry);
        }
    }

    /** 请求体摘要（UTF-8 截断防爆）；并尽力从 JSON body 提取 session_id 做对局分组。 */
    private String bodySummary(ContentCachingRequestWrapper wrapped, TraceEntry entry) {
        byte[] body;
        try {
            body = wrapped.getContentAsByteArray();
        } catch (Exception e) {
            // 超缓存上限等：不缓存摘要，防止大 body 撑爆内存
            return "（请求体过大或不可读，未缓存摘要）";
        }
        if (body == null || body.length == 0) return "";
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.length() > BODY_SUMMARY_MAX) {
            text = text.substring(0, BODY_SUMMARY_MAX) + "…（截断）";
        }
        if (entry.sessionId.isBlank() && text.startsWith("{")) {
            try {
                JsonNode node = MAPPER.readTree(body);
                JsonNode sid = node.get("session_id");
                if (sid != null && sid.isTextual() && !sid.asText().isBlank()) {
                    entry.sessionId = sid.asText();
                }
            } catch (Exception ignored) {
                // 非 JSON / 解析失败：跳过（尽力而为）
            }
        }
        return text;
    }
}
