package com.roleplay.engine.hooks;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.service.MemoryStore;
import com.roleplay.engine.service.WebSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A RoundHook that injects web-search results into agent context when
 * the recent conversation contains factual queries or search triggers.
 *
 * <p>This replaces the hardcoded {@code SEARCH_TRIGGER} logic that was
 * previously embedded in {@code RouterService.buildAgentContext()}. By
 * moving it to a hook, consumers can opt in/out, stack multiple search
 * providers, or replace the search trigger pattern without touching
 * the core RouterService.
 *
 * <p>Registration:
 * <pre>{@code
 * routerService.addHook(new WebSearchHook(webSearchService, memoryStore));
 * }</pre>
 */
public class WebSearchHook implements RoundHook {

    private static final Logger log = LoggerFactory.getLogger(WebSearchHook.class);

    /**
     * Pattern that matches Chinese search intent phrases.
     * Same as the original {@code RouterService.SEARCH_TRIGGER}.
     */
    private static final Pattern SEARCH_TRIGGER = Pattern.compile(
        "(?:搜一?下|查一?下|找一?找|搜索|查查|查资料|什么(?:是|叫|意思)|" +
        "(?:最新|最近|今天|现在|目前).{0,10}(?:新闻|消息|情况|价格|天气|汇率|股票|赛事|比分)|" +
        "(?:百度|Google|谷歌|维基|百科|知乎|微博|热搜)|网址|链接|看看)");

    private final WebSearchService webSearch;
    private final MemoryStore memory;

    /** Maximum visible messages to scan for triggers. */
    private int scanDepth = 4;

    /** Maximum search results to inject per agent context. */
    private int maxResults = 3;

    /** Maximum length of the extracted query (characters). */
    private int maxQueryLength = 100;

    public WebSearchHook(WebSearchService webSearch, MemoryStore memory) {
        this.webSearch = webSearch;
        this.memory = memory;
    }

    /** Configure how many recent messages to scan for search intent. */
    public WebSearchHook withScanDepth(int depth) {
        this.scanDepth = depth;
        return this;
    }

    /** Configure max search results per context injection. */
    public WebSearchHook withMaxResults(int max) {
        this.maxResults = max;
        return this;
    }

    /** Configure max query length. */
    public WebSearchHook withMaxQueryLength(int max) {
        this.maxQueryLength = max;
        return this;
    }

    @Override
    public void beforeAgentContext(String agentName, String trackMode, List<String> contextParts) {
        if (webSearch == null || !memory.hasSession()) return;

        List<Message> visible = memory.getAgentContext(agentName, 30);
        if (visible.isEmpty()) return;

        // Scan recent messages for search intent
        String recentText = visible.subList(
            Math.max(0, visible.size() - scanDepth), visible.size())
            .stream()
            .map(Message::getContent)
            .collect(java.util.stream.Collectors.joining(" "));

        if (!SEARCH_TRIGGER.matcher(recentText).find()) return;

        // Extract the most recent user message as query
        String query = "";
        for (int i = visible.size() - 1; i >= 0; i--) {
            Message m = visible.get(i);
            if (m.getRole() == Message.Role.USER) {
                query = m.getContent();
                if (query.length() > maxQueryLength) {
                    query = query.substring(0, maxQueryLength);
                }
                break;
            }
        }

        if (query.isEmpty()) return;

        try {
            List<Map<String, String>> results = webSearch.search(query, maxResults);
            if (results != null && !results.isEmpty()) {
                StringBuilder webInfo = new StringBuilder("【互联网检索】\n");
                for (Map<String, String> r : results) {
                    webInfo.append("\u2022 ")
                           .append(r.getOrDefault("title", ""))
                           .append(": ")
                           .append(r.getOrDefault("snippet", ""))
                           .append("\n");
                }
                webInfo.append("（以上信息来自互联网，可引用但不能编造）");
                contextParts.add(webInfo.toString());
                log.debug("Injected web search results for agent '{}', query='{}' ({} results)",
                    agentName, query, results.size());
            }
        } catch (Exception e) {
            log.warn("WebSearchHook failed for agent '{}': {}", agentName, e.getMessage());
        }
    }
}
