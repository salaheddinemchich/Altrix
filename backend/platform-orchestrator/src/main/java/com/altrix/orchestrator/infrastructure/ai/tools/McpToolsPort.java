package com.altrix.orchestrator.infrastructure.ai.tools;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * Infrastructure-level port for MCP (Model Context Protocol) tool support.
 *
 * <p>Implementations connect to one or more MCP servers, discover their tools,
 * and relay tool-call requests from the AI model back to those servers.
 *
 * <p>This port is intentionally <em>not</em> in the domain layer because it
 * depends on LangChain4j types ({@link ToolSpecification}). It is an
 * infrastructure-to-infrastructure contract used only by
 * {@link com.altrix.orchestrator.infrastructure.ai.ProviderRouter}.
 *
 * <p>The bean is optional — it is only registered when
 * {@code ai.mcp.enabled=true}. Callers should inject it as
 * {@code Optional<McpToolsPort>} so the feature degrades gracefully when
 * MCP is not configured.
 */
public interface McpToolsPort {

    /**
     * Returns the combined list of tool specifications discovered from all
     * configured MCP servers. Empty when no servers are available.
     */
    List<ToolSpecification> listTools();

    /**
     * Executes a tool on the MCP server that owns it.
     *
     * @param toolName      the tool name exactly as returned by {@link #listTools()}
     * @param argumentsJson the raw JSON arguments string from the model's
     *                      {@code ToolExecutionRequest.arguments()}
     * @return the tool's text output (or an error description on failure —
     * never throws, so the agentic loop can continue)
     */
    String executeTool(String toolName, String argumentsJson);
}
