package com.roleplay.engine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Web search via DuckDuckGo.
 * Maps from Python services/web_search.py.
 */
@Service
public class WebSearchService {
    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final int MAX_FETCH_BYTES = 1_000_000;
    private static final int MAX_CONTENT_CHARS = 2_000;

    private final HttpClient client;
    private final Set<String> allowedFetchHosts;

    public WebSearchService() {
        this("");
    }

    @Autowired
    public WebSearchService(@Value("${roleplay.web.fetch-allowed-hosts:}") String allowedHosts) {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.allowedFetchHosts = Arrays.stream((allowedHosts == null ? "" : allowedHosts).split(","))
            .map(WebSearchService::normalizeHost)
            .filter(host -> !host.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Search DuckDuckGo and return text snippets. */
    public List<Map<String, String>> search(String query, int maxResults) {
        List<Map<String, String>> results = new ArrayList<>();
        try {
            String url = "https://html.duckduckgo.com/html/?q=" +
                java.net.URLEncoder.encode(query, "UTF-8");
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            // Parse result snippets (simplified)
            String body = resp.body();
            String[] snippets = body.split("<a rel=\"nofollow\" class=\"result__a\"");
            for (int i = 1; i < Math.min(snippets.length, maxResults + 1); i++) {
                String s = snippets[i];
                String title = s.replaceAll(".*?>", "").replaceAll("<.*", "").trim();
                String snippet = "";
                var m = java.util.regex.Pattern.compile("class=\"result__snippet\">(.*?)</a>")
                    .matcher(s);
                if (m.find()) snippet = m.group(1).replaceAll("<[^>]+>", "").trim();
                if (!title.isEmpty()) {
                    results.add(Map.of("title", title, "snippet", snippet));
                }
            }
        } catch (Exception e) {
            log.warn("Search failed: {}", e.getMessage());
        }
        return results;
    }

    /** Fetch a URL's text content. */
    public String fetchContent(String url) {
        URI uri = validateAllowedFetchUri(url);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            HttpResponse<java.io.InputStream> resp = client.send(
                req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                resp.body().close();
                log.warn("Fetch rejected HTTP status {} for {}", resp.statusCode(), uri.getHost());
                return "";
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(Locale.ROOT);
            if (!contentType.isEmpty()
                && !contentType.startsWith("text/")
                && !contentType.contains("json")
                && !contentType.contains("xml")) {
                resp.body().close();
                log.warn("Fetch rejected non-text content type {} for {}", contentType, uri.getHost());
                return "";
            }
            byte[] bytes;
            try (var input = resp.body()) {
                bytes = input.readNBytes(MAX_FETCH_BYTES + 1);
            }
            if (bytes.length > MAX_FETCH_BYTES) {
                log.warn("Fetch response exceeded {} bytes for {}", MAX_FETCH_BYTES, uri.getHost());
                return "";
            }
            return cleanAndTruncate(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Fetch failed: {}", e.getMessage());
            return "";
        }
    }

    URI validateAllowedFetchUri(String rawUrl) {
        URI uri = validatePublicHttpUri(rawUrl);
        String host = normalizeHost(uri.getHost());
        // 任意域名即使预检为公网仍可通过 DNS rebinding 在真正连接时解析到内网。
        // 因此抓取端点默认关闭，只允许管理员明确配置的可信主机；白名单为精确匹配，不接受通配域。
        if (!allowedFetchHosts.contains(host)) {
            throw new IllegalArgumentException(
                "该主机不在可信抓取白名单中；请配置 roleplay.web.fetch-allowed-hosts");
        }
        return uri;
    }

    static URI validatePublicHttpUri(String rawUrl) {
        final URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式无效", e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null
            || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("仅允许访问 HTTP/HTTPS 公网地址");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL 不允许包含用户凭据");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address)) {
                    throw new IllegalArgumentException("禁止访问本机、内网或保留地址");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("URL 主机无法解析", e);
        }
        return uri;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // RFC 4193 unique-local addresses (fc00::/7).
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        }
        // RFC 6598 shared address space (100.64.0.0/10) is not public internet.
        return bytes.length == 4
            && (bytes[0] & 0xff) == 100
            && ((bytes[1] & 0xc0) == 64);
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String cleanAndTruncate(String body) {
        String cleaned = (body == null ? "" : body)
            .replaceAll("<[^>]+>", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return cleaned.substring(0, Math.min(MAX_CONTENT_CHARS, cleaned.length()));
    }
}
