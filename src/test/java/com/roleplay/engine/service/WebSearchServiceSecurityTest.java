package com.roleplay.engine.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchServiceSecurityTest {

    @Test
    void rejectsNonHttpAndCredentialedUrls() {
        assertThrows(IllegalArgumentException.class,
            () -> WebSearchService.validatePublicHttpUri("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
            () -> WebSearchService.validatePublicHttpUri("https://user:pass@93.184.216.34/"));
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalAndSharedAddresses() {
        for (String url : new String[] {
            "http://127.0.0.1/",
            "http://10.0.0.1/",
            "http://172.16.0.1/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/",
            "http://[::1]/",
            "http://[fc00::1]/"
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> WebSearchService.validatePublicHttpUri(url), url);
        }
    }

    @Test
    void acceptsPublicHttpAddressWithoutNetworkRequest() {
        assertEquals("https", WebSearchService
            .validatePublicHttpUri("https://93.184.216.34/page")
            .getScheme());
    }

    @Test
    void fetchIsDefaultDenyAndUsesExactTrustedHostAllowlist() {
        WebSearchService defaultDeny = new WebSearchService();
        assertThrows(IllegalArgumentException.class,
            () -> defaultDeny.validateAllowedFetchUri("https://93.184.216.34/page"));

        WebSearchService trusted = new WebSearchService("93.184.216.34");
        assertEquals("93.184.216.34",
            trusted.validateAllowedFetchUri("https://93.184.216.34/page").getHost());
        assertThrows(IllegalArgumentException.class,
            () -> trusted.validateAllowedFetchUri("https://93.184.216.35/page"));
    }

    @Test
    void cleansHtmlBeforeApplyingCharacterLimit() {
        assertEquals("Hello world", WebSearchService.cleanAndTruncate(
            "<html><body><p>Hello <b>world</b></p></body></html>"));

        String cleaned = WebSearchService.cleanAndTruncate("<p>" + "x".repeat(3_000) + "</p>");
        assertEquals(2_000, cleaned.length());
        assertTrue(cleaned.chars().allMatch(ch -> ch == 'x'));
    }
}
