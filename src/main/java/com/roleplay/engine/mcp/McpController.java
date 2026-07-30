package com.roleplay.engine.mcp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for MCP (Model Context Protocol) tool management.
 *
 * <p>Provides endpoints to discover connected MCP servers and invoke
 * their tools. This allows DM users and other services to call external
 * tools (web search, weather, etc.) through the standardized MCP interface.
 *
 * <h3>Curl examples:</h3>
 * <pre>{@code
 * # List all connected MCP servers and their tools
 * curl http://localhost:8000/api/mcp/servers
 *
 * # Call a tool on a specific server
 * curl -X POST http://localhost:8000/api/mcp/call \
 *   -H "Content-Type: application/json" \
 *   -d '{"server":"web-search","tool":"search","args":{"query":"今天的新闻"}}'
 * }</pre>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpService mcpService;

    public McpController(McpService mcpService) {
        this.mcpService = mcpService;
    }

    /**
     * List all connected MCP servers and their available tools.
     *
     * <p>Response format:
     * <pre>{@code
     * [
     *   {
     *     "name": "web-search",
     *     "connected": true,
     *     "tools": [
     *       {"name": "search", "description": "...", "inputSchema": {...}}
     *     ]
     *   }
     * ]
     * }</pre>
     */
    @GetMapping("/servers")
    public ResponseEntity<List<Map<String, Object>>> listServers() {
        return ResponseEntity.ok(mcpService.getServerList());
    }

    /**
     * Call a tool on a specific MCP server.
     *
     * <p>Request body:
     * <pre>{@code
     * {
     *   "server": "web-search",
     *   "tool": "search",
     *   "args": {"query": "今天天气怎么样"}
     * }
     * }</pre>
     *
     * <p>Response: the raw tool result from the MCP server.
     */
    @PostMapping("/call")
    public ResponseEntity<Map<String, Object>> callTool(@RequestBody Map<String, Object> body) {
        String serverName = (String) body.getOrDefault("server", "");
        String toolName = (String) body.getOrDefault("tool", "");

        if (serverName.isBlank() || toolName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Both 'server' and 'tool' fields are required"
            ));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) body.getOrDefault("args", Map.of());

        Map<String, Object> result = mcpService.callTool(serverName, toolName, args);
        if (result == null) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "MCP server '" + serverName + "' is not available or tool call failed"
            ));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Check connectivity to a specific MCP server.
     */
    @GetMapping("/status/{serverName}")
    public ResponseEntity<Map<String, Object>> serverStatus(@PathVariable String serverName) {
        boolean connected = mcpService.isConnected(serverName);
        return ResponseEntity.ok(Map.of(
            "name", serverName,
            "connected", connected
        ));
    }

    /**
     * Reconnect a specific MCP server.
     *
     * <p>Curl example:
     * <pre>{@code
     * curl -X POST http://localhost:8000/api/mcp/reconnect/web-search
     * }</pre>
     */
    @PostMapping("/reconnect/{serverName}")
    public ResponseEntity<Map<String, Object>> reconnect(@PathVariable String serverName) {
        boolean result = mcpService.reconnect(serverName);
        if (result) {
            return ResponseEntity.ok(Map.of(
                "status", "reconnected",
                "name", serverName
            ));
        } else {
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to reconnect '" + serverName + "'"
            ));
        }
    }
}
