package com.altrix.orchestrator.adapter.out.rag;

import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import com.altrix.orchestrator.infra.ai.tools.McpToolsPort;
import com.altrix.orchestrator.infrastructure.rag.DomainAllowListValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * MCP-backed implementation of {@link DocumentationFetchPort}.
 *
 * <p>Routes every documentation fetch through a configured MCP server's
 * {@code fetch}-style tool (Anthropic's {@code modelcontextprotocol/server-fetch}
 * uses the name {@code fetch}; other servers may differ — the actual tool
 * name is discovered from {@link McpToolsPort#listTools()} at first use and
 * cached).  When MCP is configured this bean wins over the plain HTTP
 * fallback via {@code @ConditionalOnProperty} + the
 * {@code @ConditionalOnMissingBean(name = "mcpDocumentationFetchAdapter")}
 * annotation on the HTTP adapter.
 *
 * <p>Allow-listing is enforced BEFORE the tool call so a misconfigured
 * YAML can't punch out to arbitrary hosts via MCP either.
 *
 * <p>Failure modes — all log + return empty string (matches the HTTP
 * fallback's contract so {@code DocumentationIngestionService} keeps
 * skipping bad pages instead of crashing the startup hook):
 * <ul>
 *   <li>MCP server didn't expose a usable fetch tool</li>
 *   <li>MCP tool returned an error</li>
 *   <li>Allow-list rejected the URL</li>
 *   <li>Argument JSON serialisation blew up</li>
 * </ul>
 */
@Slf4j
@Component("mcpDocumentationFetchAdapter")
@ConditionalOnProperty(prefix = "ai.mcp", name = "enabled", havingValue = "true")
public class McpDocumentationFetchAdapter implements DocumentationFetchPort {

    private final McpToolsPort mcpTools;
    private final ObjectMapper objectMapper;
    private final DomainAllowListValidator allowList;

    /**
     * Comma-separated list of tool names to try, in priority order.
     * Default covers the canonical Anthropic server ({@code fetch}) and a
     * couple of community alternatives.  Override with
     * {@code documentation.mcp.fetch-tool-names=foo,bar} if your MCP server
     * names its web-fetch tool something else.
     */
    @Value("${documentation.mcp.fetch-tool-names:fetch,web_fetch,http_fetch,url_fetch}")
    private String fetchToolCandidates;

    public McpDocumentationFetchAdapter(McpToolsPort mcpTools,
                                        ObjectMapper objectMapper,
                                        DomainAllowListValidator allowList) {
        this.mcpTools = mcpTools;
        this.objectMapper = objectMapper;
        this.allowList = allowList;
    }

    @Override
    public String fetchPage(String url) {
        if (!allowList.isAllowed(url)) return "";

        Optional<String> resolvedTool = resolveFetchTool();
        if (resolvedTool.isEmpty()) {
            log.warn("No MCP fetch tool available — none of [{}] is registered by any MCP server. "
                    + "Page will be skipped: {}", fetchToolCandidates, url);
            return "";
        }
        String toolName = resolvedTool.get();

        String argumentsJson;
        try {
            argumentsJson = objectMapper.writeValueAsString(Map.of("url", url));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise MCP fetch arguments for {}: {}", url, e.getMessage());
            return "";
        }

        log.debug("Fetching documentation via MCP tool [{}]: {}", toolName, url);
        String body = mcpTools.executeTool(toolName, argumentsJson);
        if (body == null || body.isBlank()) {
            log.warn("MCP fetch tool [{}] returned empty body for {}", toolName, url);
            return "";
        }
        return stripHtml(body);
    }

    /**
     * Inspect the cached tool list and return the first candidate name
     * that an MCP server actually exposes.  Walked left-to-right so the
     * priority follows the YAML order.
     */
    private Optional<String> resolveFetchTool() {
        java.util.List<ToolSpecification> available = mcpTools.listTools();
        if (available == null || available.isEmpty()) return Optional.empty();
        for (String candidate : fetchToolCandidates.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) continue;
            boolean match = available.stream()
                    .anyMatch(t -> trimmed.equalsIgnoreCase(t.name()));
            if (match) return Optional.of(trimmed);
        }
        return Optional.empty();
    }

    /**
     * Reuses the same HTML-stripping rules as the HTTP fallback so the
     * embeddings produced through either path are byte-for-byte comparable
     * when the page is identical.  Removes script/style blocks, then all
     * tags, then normalises whitespace + the most common HTML entities.
     */
    private static String stripHtml(String html) {
        return html
                .replaceAll("(?s)<(script|style)[^>]*>.*?</(script|style)>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
