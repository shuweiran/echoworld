package com.roleplay.engine.mcp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service that manages the lifecycle of MCP client connections.
 *
 * <p>On startup, reads {@link McpConfiguration} and establishes connections
 * to all configured MCP servers. Provides a unified API for discovering
 * tools and invoking them across all connected servers.
 *
 * <p>On shutdown, gracefully disconnects all MCP clients.
 */
@Service
public class McpService {

    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private final McpConfiguration config;

    /** Map of server name → client instance. */
    private final Map<String, StdioMcpClient> clients = new ConcurrentHashMap<>();

    /** Has the initial connection been attempted? */
    private boolean initialized = false;

    public McpService(McpConfiguration config) {
        this.config = config;
    }

    /**
     * Initialize all configured MCP servers.
     * Runs on application startup.
     */
    @PostConstruct
    public synchronized void init() {
        if (initialized) return;
        initialized = true;

        Map<String, McpConfiguration.McpServerConfig> servers = config.getServers();
        if (servers == null || servers.isEmpty()) {
            log.info("No MCP servers configured");
            return;
        }

        for (Map.Entry<String, McpConfiguration.McpServerConfig> entry : servers.entrySet()) {
            String name = entry.getKey();
            McpConfiguration.McpServerConfig serverConfig = entry.getValue();

            try {
                StdioMcpClient client = new StdioMcpClient(
                    name,
                    serverConfig.getCommand(),
                    serverConfig.getArgs()
                );
                client.connect();
                clients.put(name, client);
                log.info("MCP server '{}' connected", name);
            } catch (Exception e) {
                log.warn("Failed to connect MCP server '{}': {}. It will need manual reconnection.", name, e.getMessage());
            }
        }
    }

    /**
     * Gracefully shut down all MCP clients.
     */
    @PreDestroy
    public synchronized void shutdown() {
        log.info("Shutting down {} MCP client(s)", clients.size());
        for (Map.Entry<String, StdioMcpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error closing MCP client '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
    }

    // ════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════

    /**
     * Get all connected MCP clients.
     */
    public Map<String, StdioMcpClient> getClients() {
        return Map.copyOf(clients);
    }

    /**
     * Get a specific MCP client by server name.
     */
    public Optional<StdioMcpClient> getClient(String serverName) {
        return Optional.ofNullable(clients.get(serverName));
    }

    /**
     * Call a tool on a specific MCP server.
     *
     * @param serverName the logical server name (e.g., "web-search")
     * @param toolName   the tool name to invoke
     * @param args       arguments to pass to the tool
     * @return the tool result, or empty if the call failed
     */
    public Map<String, Object> callTool(String serverName, String toolName, Map<String, Object> args) {
        StdioMcpClient client = clients.get(serverName);
        if (client == null || !client.isConnected()) {
            log.warn("MCP server '{}' is not available (connected={})", serverName,
                client != null && client.isConnected());
            return null;
        }
        return client.callTool(toolName, args);
    }

    /**
     * Get a list of all connected servers with their available tools.
     *
     * @return list of server-info maps with name and tools fields
     */
    public List<Map<String, Object>> getServerList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, StdioMcpClient> entry : clients.entrySet()) {
            if (entry.getValue().isConnected()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", entry.getKey());
                info.put("connected", true);
                info.put("tools", entry.getValue().getTools());
                result.add(info);
            } else {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", entry.getKey());
                info.put("connected", false);
                info.put("tools", List.of());
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Check if a specific server is connected.
     */
    public boolean isConnected(String serverName) {
        StdioMcpClient client = clients.get(serverName);
        return client != null && client.isConnected();
    }

    /**
     * Reconnect a specific MCP server.
     */
    public boolean reconnect(String serverName) {
        StdioMcpClient old = clients.remove(serverName);
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {}
        }

        McpConfiguration.McpServerConfig serverConfig = config.getServers().get(serverName);
        if (serverConfig == null) {
            log.warn("No config found for MCP server '{}'", serverName);
            return false;
        }

        try {
            StdioMcpClient client = new StdioMcpClient(
                serverName,
                serverConfig.getCommand(),
                serverConfig.getArgs()
            );
            client.connect();
            clients.put(serverName, client);
            log.info("Reconnected MCP server '{}'", serverName);
            return true;
        } catch (Exception e) {
            log.warn("Failed to reconnect MCP server '{}': {}", serverName, e.getMessage());
            return false;
        }
    }
}
