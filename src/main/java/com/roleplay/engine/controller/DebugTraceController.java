package com.roleplay.engine.controller;

import com.roleplay.engine.debug.TraceEntry;
import com.roleplay.engine.debug.TraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API 逻辑链追踪查询端点（阶段② P-0809-B，调研报告 docs/ui-api-survey.md §5 方案 C）。
 *
 * <ul>
 *   <li>{@code GET /api/debug/trace?limit=N} —— 最近请求链路列表（默认 50，上限 200；新→旧）；</li>
 *   <li>{@code GET /api/debug/trace/{requestId}} —— 单条详情（含 LLM 子调用时间线 / 请求体摘要 / SSE 事件标记）。</li>
 * </ul>
 *
 * <p>开关 {@code roleplay.debug.trace-enabled} 关闭（默认）时两个端点一律 404，
 * 不影响任何既有行为。端点自身请求也会被 TraceFilter 打点（调试面板轮询可见）。
 */
@RestController
@RequestMapping("/api/debug/trace")
public class DebugTraceController {

    private static final int LIST_LIMIT_MAX = 200;

    @Autowired
    private TraceService traceService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (!traceService.isEnabled()) {
            return ResponseEntity.status(404).body(Map.of(
                    "detail", "逻辑链追踪未开启（roleplay.debug.trace-enabled=false）"));
        }
        int n = Math.min(Math.max(limit, 1), LIST_LIMIT_MAX);
        List<Map<String, Object>> entries = traceService.list(n).stream()
                .map(e -> e.toMap(false))
                .collect(Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", true);
        resp.put("count", entries.size());
        resp.put("limit", n);
        resp.put("entries", entries);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<?> detail(@PathVariable String requestId) {
        if (!traceService.isEnabled()) {
            return ResponseEntity.status(404).body(Map.of(
                    "detail", "逻辑链追踪未开启（roleplay.debug.trace-enabled=false）"));
        }
        TraceEntry entry = traceService.get(requestId);
        if (entry == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "detail", "未找到该请求链路：" + requestId));
        }
        return ResponseEntity.ok(entry.toMap(true));
    }
}
