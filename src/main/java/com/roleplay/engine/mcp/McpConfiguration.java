package com.roleplay.engine.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for MCP (Model Context Protocol) servers.
 *
 * <p>Reads from {@code application.yml} under the {@code roleplay.mcp} prefix.
 * Supports stdio-type MCP servers that are launched as subprocesses.
 *
 * <p>Example YAML:
 * <pre>{@code
 * roleplay:
 *   mcp:
 *     servers:
 *       web-search:
 *         type: stdio
 *         command: npx
 *         args: ["@modelcontextprotocol/server-web-search"]
 *       weather:
 *         type: stdio
 *         command: npx
 *         args: ["@modelcontextprotocol/server-weather"]
 *     timeout-seconds: 30
 *     approval-timeout-seconds: 60
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "roleplay.mcp")
public class McpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpConfiguration.class);

    /** Map of server name → server config. */
    private Map<String, McpServerConfig> servers = new LinkedHashMap<>();

    /** Default timeout for MCP operations (seconds). */
    private int timeoutSeconds = 30;

    /** Default timeout for approval operations (seconds). */
    private int approvalTimeoutSeconds = 60;

    public Map<String, McpServerConfig> getServers() {
        return servers;
    }

    public void setServers(Map<String, McpServerConfig> servers) {
        this.servers = servers;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getApprovalTimeoutSeconds() {
        return approvalTimeoutSeconds;
    }

    public void setApprovalTimeoutSeconds(int approvalTimeoutSeconds) {
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
    }

    /**
     * Configuration for a single MCP server.
     */
    public static class McpServerConfig {
        /** Server type: "stdio" is currently supported. */
        private String type = "stdio";

        /** The executable command to launch. */
        private String command = "";

        /** Arguments to pass to the command. */
        private List<String> args = new ArrayList<>();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
    }
}
