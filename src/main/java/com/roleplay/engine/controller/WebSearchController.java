package com.roleplay.engine.controller;

import com.roleplay.engine.service.WebSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Web search endpoint — characters & DM can search the internet.
 * Maps from Python search functionality.
 */
@RestController
@RequestMapping("/api/search")
public class WebSearchController {

    private final WebSearchService webSearch;

    public WebSearchController(WebSearchService webSearch) {
        this.webSearch = webSearch;
    }

    @PostMapping
    public ResponseEntity<List<Map<String, String>>> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.getOrDefault("query", body.getOrDefault("q", ""));
        int max = body.containsKey("max") ? ((Number) body.get("max")).intValue() : 3;
        if (query.isEmpty()) {
            return ResponseEntity.badRequest().body(List.of());
        }
        List<Map<String, String>> results = webSearch.search(query, Math.min(max, 10));
        return ResponseEntity.ok(results);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, String>>> searchGet(@RequestParam String q,
                                                                @RequestParam(defaultValue = "3") int max) {
        List<Map<String, String>> results = webSearch.search(q, Math.min(max, 10));
        return ResponseEntity.ok(results);
    }

    @PostMapping("/fetch")
    public ResponseEntity<Map<String, String>> fetch(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "");
        if (url.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("content", ""));
        }
        try {
            String content = webSearch.fetchContent(url);
            return ResponseEntity.ok(Map.of("url", url, "content", content));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "url", url,
                "content", "",
                "error", e.getMessage()
            ));
        }
    }
}
