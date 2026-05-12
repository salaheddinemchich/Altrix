package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration for MCP (Model Context Protocol) server connections.
 *
 * <p>Set {@code ai.mcp.enabled=true} and list one or more MCP servers under
 * {@code ai.mcp.servers} to activate agentic tool calls from the AI pipeline.
 *
 * <p>Each entry requires a {@code name} (used for log messages and collision
 * detection) and a {@code base-url} pointing at the server's HTTP endpoint.
 * The server must support the <em>Streamable HTTP</em> transport
 * (MCP spec 2024-11-05 / 2025-03-26): a single POST endpoint that accepts
 * JSON-RPC 2.0 bodies and returns {@code application/json} or
 * {@code text/event-stream} responses.
 *
 * <pre>{@code
 * ai:
 *   mcp:
 *     enabled: true
 *     max-tool-iterations: 5
 *     servers:
 *       - name: filesystem
 *         base-url: http://localhost:3001/mcp
 *         timeout-seconds: 30
 *       - name: git
 *         base-url: http://localhost:3002/mcp
 *         timeout-seconds: 15
 * }</pre>
 */
@ConfigurationProperties(prefix = "ai.mcp")
public record McpConfig(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") int maxToolIterations,
        List<McpServer> servers
) {

    /**
     * A single MCP server connection entry.
     *
     * @param name           logical name — used for logging and tool-to-server routing
     * @param baseUrl        HTTP endpoint that accepts JSON-RPC 2.0 POST requests
     * @param timeoutSeconds per-request timeout in seconds
     */
    public record McpServer(
            String name,
            String baseUrl,
            @DefaultValue("30") long timeoutSeconds
    ) {
    }
}
