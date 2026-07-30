package com.roleplay.engine.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A lightweight MCP (Model Context Protocol) client that communicates with
 * MCP servers over standard I/O (stdio) using JSON-RPC 2.0.
 *
 * <p>This implements a minimal but functional subset of the MCP protocol:
 * <ul>
 *   <li>{@code initialize} — capability negotiation</li>
 *   <li>{@code tools/list} — discover available tools</li>
 *   <li>{@code tools/call} — invoke a tool with arguments</li>
 * </ul>
 *
 * <p>The protocol uses newline-delimited JSON (NDJSON) over the subprocess's
 * stdin/stdout. Each request carries a unique integer ID, and responses are
 * matched to requests via that ID.
 *
 * <p>Reference: <a href="https://github.com/modelcontextprotocol/specification">MCP Specification</a>
 */
public class StdioMcpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(1);

    private final String serverName;
    private final String command;
    private final List<String> args;

    private Process process;
    private BufferedReader stdoutReader;
    private BufferedWriter stdinWriter;
    private Thread readerThread;

    /** Map of pending request ID → CompletableFuture for the response. */
    private final ConcurrentHashMap<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    /** Cached list of tools discovered during initialization. */
    private final List<Map<String, Object>> tools = new CopyOnWriteArrayList<>();

    private volatile boolean connected = false;
    private volatile boolean shuttingDown = false;

    /**
     * @param serverName logical name for this server (e.g., "web-search")
     * @param command    the executable to launch
     * @param args       arguments to pass to the executable
     */
    public StdioMcpClient(String serverName, String command, List<String> args) {
        this.serverName = serverName;
        this.command = command;
        this.args = args != null ? args : List.of();
    }

    // ════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════

    /**
     * Connect to the MCP server by launching the subprocess, performing
     * the initialize handshake, and discovering available tools.
     *
     * @throws IOException          if the subprocess cannot be started
     * @throws InterruptedException if the thread is interrupted during connect
     */
    public synchronized void connect() throws IOException, InterruptedException {
        if (connected) {
            log.debug("MCP client '{}' already connected", serverName);
            return;
        }

        log.info("Connecting to MCP server '{}': {} {}", serverName, command, String.join(" ", args));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (!args.isEmpty()) {
            pb.command(command);
            List<String> cmdList = new ArrayList<>();
            cmdList.add(command);
            cmdList.addAll(args);
            pb.command(cmdList);
        }
        pb.redirectErrorStream(true);

        this.process = pb.start();
        this.stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // Start reader thread to process incoming JSON-RPC messages
        this.readerThread = new Thread(this::readLoop, "mcp-reader-" + serverName);
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        // Perform initialize handshake
        Map<String, Object> initResult = sendRequest("initialize", Map.of(
            "protocolVersion", "2025-03-26",
            "capabilities", Map.of(),
            "clientInfo", Map.of(
                "name", "roleplay-engine",
                "version", "1.0.0"
            )
        ));

        if (initResult != null) {
            connected = true;
            log.info("MCP server '{}' initialized: protocol={}", serverName,
                initResult.getOrDefault("protocolVersion", "unknown"));

            // Discover available tools
            discoverTools();
        } else {
            throw new IOException("MCP initialize failed for '" + serverName + "'");
        }
    }

    /**
     * Discover tools from the server and cache them.
     */
    @SuppressWarnings("unchecked")
    private void discoverTools() {
        try {
            Map<String, Object> result = sendRequest("tools/list", Map.of());
            if (result != null) {
                List<Map<String, Object>> rawTools = (List<Map<String, Object>>) result.getOrDefault("tools", List.of());
                tools.clear();
                for (Map<String, Object> t : rawTools) {
                    Map<String, Object> clean = new LinkedHashMap<>();
                    clean.put("name", t.getOrDefault("name", "unknown"));
                    clean.put("description", t.getOrDefault("description", ""));
                    clean.put("inputSchema", t.getOrDefault("inputSchema", t.getOrDefault("parameters", Map.of())));
                    tools.add(clean);
                }
                log.info("MCP server '{}' offers {} tools: {}", serverName, tools.size(),
                    tools.stream().map(t -> (String) t.get("name")).toList());
            }
        } catch (Exception e) {
            log.warn("Failed to discover tools from '{}': {}", serverName, e.getMessage());
        }
    }

    /**
     * Gracefully shut down the MCP client — send a close notification
     * and destroy the subprocess.
     */
    @Override
    public synchronized void close() {
        if (!connected && process == null) return;
        shuttingDown = true;
        connected = false;

        // Fail all pending requests
        for (CompletableFuture<Map<String, Object>> future : pending.values()) {
            future.completeExceptionally(new RuntimeException("MCP client '" + serverName + "' is shutting down"));
        }
        pending.clear();

        try {
            if (stdinWriter != null) {
                try {
                    String goodbye = mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "method", "notifications/closed",
                        "params", Map.of()
                    )) + "\n";
                    stdinWriter.write(goodbye);
                    stdinWriter.flush();
                } catch (Exception ignored) {}
                stdinWriter.close();
            }
        } catch (Exception ignored) {}

        if (readerThread != null) {
            readerThread.interrupt();
        }

        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        tools.clear();
        log.info("MCP client '{}' shut down", serverName);
    }

    // ════════════════════════════════════════════════════════════
    //  Tool invocation
    // ════════════════════════════════════════════════════════════

    /**
     * Call a tool on the MCP server.
     *
     * @param toolName the name of the tool to invoke
     * @param arguments the arguments to pass (map of string → any)
     * @return the tool result, or null on failure
     */
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        if (!connected) {
            log.warn("MCP client '{}' not connected, cannot call tool '{}'", serverName, toolName);
            return null;
        }

        try {
            Map<String, Object> result = sendRequest("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
            ));
            return result;
        } catch (Exception e) {
            log.error("MCP tool call failed on '{}': {} -> {}", serverName, toolName, e.getMessage());
            return null;
        }
    }

    /**
     * Get the list of tools discovered from this server.
     */
    public List<Map<String, Object>> getTools() {
        return List.copyOf(tools);
    }

    /**
     * Check if this client is connected and ready.
     */
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    /**
     * Get the server name.
     */
    public String getServerName() {
        return serverName;
    }

    // ════════════════════════════════════════════════════════════
    //  JSON-RPC messaging
    // ════════════════════════════════════════════════════════════

    /**
     * Send a JSON-RPC request and wait for the response.
     */
    private Map<String, Object> sendRequest(String method, Map<String, Object> params)
            throws IOException, InterruptedException {

        if (shuttingDown) return null;

        int id = requestId.getAndIncrement();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        String json = mapper.writeValueAsString(request) + "\n";
        synchronized (stdinWriter) {
            stdinWriter.write(json);
            stdinWriter.flush();
        }

        log.debug("MCP -> '{}': {}", serverName, method);

        try {
            return future.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("MCP request timed out: " + method);
        } catch (ExecutionException e) {
            pending.remove(id);
            throw new IOException("MCP request failed: " + method + " -> " + e.getCause().getMessage());
        }
    }

    /**
     * Background thread that reads JSON-RPC responses from the MCP
     * server's stdout and dispatches them to the corresponding pending futures.
     */
    @SuppressWarnings("unchecked")
    private void readLoop() {
        try {
            String line;
            while (!shuttingDown && (line = stdoutReader.readLine()) != null) {
                if (line.isBlank()) continue;

                try {
                    Map<String, Object> msg = mapper.readValue(line, Map.class);
                    if (msg.containsKey("id")) {
                        // This is a response to a request
                        int id = ((Number) msg.get("id")).intValue();
                        CompletableFuture<Map<String, Object>> future = pending.remove(id);
                        if (future != null) {
                            if (msg.containsKey("error")) {
                                Map<String, Object> error = (Map<String, Object>) msg.get("error");
                                String errMsg = error != null ? (String) error.getOrDefault("message", "unknown error") : "unknown error";
                                future.completeExceptionally(new RuntimeException(errMsg));
                            } else {
                                Map<String, Object> result = (Map<String, Object>) msg.getOrDefault("result", Map.of());
                                future.complete(result);
                            }
                        }
                    } else if (msg.containsKey("method")) {
                        // This is a notification from the server — log and ignore
                        String method = (String) msg.get("method");
                        log.debug("MCP notification from '{}': {}", serverName, method);
                    }
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse MCP message from '{}': {}", serverName, e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!shuttingDown) {
                log.error("MCP read loop for '{}' failed: {}", serverName, e.getMessage());
            }
        }

        // Process died — fail all pending
        if (!shuttingDown) {
            connected = false;
            for (Map.Entry<Integer, CompletableFuture<Map<String, Object>>> entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(new RuntimeException("MCP server '" + serverName + "' disconnected"));
            }
            pending.clear();
            log.warn("MCP server '{}' process terminated", serverName);
        }
    }
}
