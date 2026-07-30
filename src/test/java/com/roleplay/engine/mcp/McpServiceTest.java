package com.roleplay.engine.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MCP configuration loading and service logic.
 *
 * <p>These tests do NOT require a real MCP server — they verify:
 * <ul>
 *   <li>Configuration parsing from YAML-style maps</li>
 *   <li>Service initialization logic with mock config</li>
 *   <li>Server list reporting</li>
 *   <li>Graceful handling of unavailable servers</li>
 * </ul>
 */
class McpServiceTest {

    private McpConfiguration config;
    private McpService service;

    @BeforeEach
    void setUp() {
        config = new McpConfiguration();
    }

    @Test
    @DisplayName("Empty config results in no servers")
    void testEmptyConfig() {
        config.setServers(new LinkedHashMap<>());
        service = new McpService(config);
        // init() would try to connect — servers map is empty so it's a no-op
        assertTrue(service.getClients().isEmpty());
        assertTrue(service.getServerList().isEmpty());
    }

    @Test
    @DisplayName("Config is parsed correctly from property map")
    void testConfigParsing() {
        Map<String, McpConfiguration.McpServerConfig> servers = new LinkedHashMap<>();

        McpConfiguration.McpServerConfig searchConfig = new McpConfiguration.McpServerConfig();
        searchConfig.setType("stdio");
        searchConfig.setCommand("npx");
        searchConfig.setArgs(List.of("@modelcontextprotocol/server-web-search"));
        servers.put("web-search", searchConfig);

        McpConfiguration.McpServerConfig weatherConfig = new McpConfiguration.McpServerConfig();
        weatherConfig.setType("stdio");
        weatherConfig.setCommand("npx");
        weatherConfig.setArgs(List.of("@modelcontextprotocol/server-weather"));
        servers.put("weather", weatherConfig);

        config.setServers(servers);
        config.setTimeoutSeconds(30);
        config.setApprovalTimeoutSeconds(60);

        assertEquals(2, config.getServers().size());
        assertTrue(config.getServers().containsKey("web-search"));
        assertTrue(config.getServers().containsKey("weather"));

        McpConfiguration.McpServerConfig search = config.getServers().get("web-search");
        assertEquals("stdio", search.getType());
        assertEquals("npx", search.getCommand());
        assertEquals(1, search.getArgs().size());
        assertEquals("@modelcontextprotocol/server-web-search", search.getArgs().getFirst());

        assertEquals(30, config.getTimeoutSeconds());
        assertEquals(60, config.getApprovalTimeoutSeconds());
    }

    @Test
    @DisplayName("McpService reports correct server list even without connections")
    void testServerListReporting() {
        // Create config with a server entry but service won't connect (no real process)
        Map<String, McpConfiguration.McpServerConfig> servers = new LinkedHashMap<>();
        McpConfiguration.McpServerConfig cfg = new McpConfiguration.McpServerConfig();
        cfg.setType("stdio");
        cfg.setCommand("nonexistent-command");
        cfg.setArgs(List.of());
        servers.put("test-server", cfg);
        config.setServers(servers);

        service = new McpService(config);

        // init() will try to connect to "nonexistent-command" which will fail — gracefully
        service.init();

        // After init failure, the server should not be in the clients map
        assertTrue(service.getClients().isEmpty() || !service.getClients().containsKey("test-server"));

        // getServerList should still be empty since no real connections
        List<Map<String, Object>> serverList = service.getServerList();
        assertNotNull(serverList);
    }

    @Test
    @DisplayName("Calling a tool on unavailable server returns null")
    void testCallToolOnUnavailableServer() {
        service = new McpService(config);
        Map<String, Object> result = service.callTool("nonexistent", "search", Map.of("query", "test"));
        assertNull(result);
    }

    @Test
    @DisplayName("isConnected returns false for unknown servers")
    void testIsConnectedUnknown() {
        service = new McpService(config);
        assertFalse(service.isConnected("unknown-server"));
    }

    @Test
    @DisplayName("getClient returns empty for unknown servers")
    void testGetClientUnknown() {
        service = new McpService(config);
        assertTrue(service.getClient("unknown-server").isEmpty());
    }

    @Test
    @DisplayName("Reconnect on unknown server returns false")
    void testReconnectUnknown() {
        service = new McpService(config);
        assertFalse(service.reconnect("unknown-server"));
    }

    @Test
    @DisplayName("StdioMcpClient stores server info correctly")
    void testStdioMcpClientInfo() {
        // Verify the client holds config correctly without connecting
        StdioMcpClient client = new StdioMcpClient("test", "echo", List.of("hello"));
        assertEquals("test", client.getServerName());
        assertFalse(client.isConnected());
        assertTrue(client.getTools().isEmpty());
    }

    @Test
    @DisplayName("Multiple server configs are independently accessible")
    void testMultipleServerConfigs() {
        Map<String, McpConfiguration.McpServerConfig> servers = new LinkedHashMap<>();

        McpConfiguration.McpServerConfig s1 = new McpConfiguration.McpServerConfig();
        s1.setCommand("cmd1");
        s1.setArgs(List.of("arg1"));
        servers.put("server-a", s1);

        McpConfiguration.McpServerConfig s2 = new McpConfiguration.McpServerConfig();
        s2.setCommand("cmd2");
        s2.setArgs(List.of("arg2a", "arg2b"));
        servers.put("server-b", s2);

        config.setServers(servers);

        assertEquals(2, config.getServers().size());
        assertEquals("cmd1", config.getServers().get("server-a").getCommand());
        assertEquals(List.of("arg2a", "arg2b"), config.getServers().get("server-b").getArgs());
    }

    @Test
    @DisplayName("McpServerConfig has sensible defaults")
    void testServerConfigDefaults() {
        McpConfiguration.McpServerConfig cfg = new McpConfiguration.McpServerConfig();
        assertEquals("stdio", cfg.getType());
        assertEquals("", cfg.getCommand());
        assertTrue(cfg.getArgs().isEmpty());
    }
}
