package com.altrix.orchestrator.infra.ai.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.altrix.orchestrator.infrastructure.config.McpConfig;
import com.altrix.orchestrator.infra.ai.tools.McpToolsPort;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP (Model Context Protocol) client — Streamable HTTP transport.
 *
 * <p>Protocol handshake per JSON-RPC 2.0 over HTTP:
 * <ol>
 *   <li>POST {@code initialize} → server confirms supported capabilities.</li>
 *   <li>POST {@code notifications/initialized} (fire-and-forget notification).</li>
 *   <li>POST {@code tools/list} → tool specs are cached in memory.</li>
 *   <li>On demand: POST {@code tools/call} → returns tool output as text.</li>
 * </ol>
 *
 * <p>This bean is only created when {@code ai.mcp.enabled=true}. It is injected
 * as {@code Optional<McpToolsPort>} into {@link com.altrix.orchestrator.infra.ai.ProviderRouter},
 * so disabling MCP requires no code changes — the agentic loop is simply skipped.
 *
 * <p>Security notes:
 * <ul>
 *   <li>No sensitive data (API keys, source code) is forwarded to MCP servers
 *       unless explicitly passed in tool arguments by the AI model.</li>
 *   <li>Tool names are validated against the cached list; unknown names are
 *       rejected without a network call.</li>
 *   <li>HTTP errors and JSON-RPC errors are wrapped and logged; raw server
 *       responses never reach callers directly.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.mcp", name = "enabled", havingValue = "true")
public class McpClientAdapter implements McpToolsPort {

    private static final String JSONRPC       = "2.0";
    private static final String PROTO_VERSION = "2024-11-05";
    private static final String CLIENT_NAME   = "platform-orchestrator";
    private static final String CLIENT_VER    = "1.0";

    private final McpConfig    config;
    private final ObjectMapper objectMapper;
    private final HttpClient   http;

    /** tool name → owning server name (used to route {@code tools/call} requests) */
    private final Map<String, String>                  toolToServer = new ConcurrentHashMap<>();
    /** server name → its discovered tool specs */
    private final Map<String, List<ToolSpecification>> serverTools  = new ConcurrentHashMap<>();
    /** monotonically increasing JSON-RPC request id */
    private final AtomicInteger idSeq = new AtomicInteger(0);

    public McpClientAdapter(McpConfig config, ObjectMapper objectMapper) {
        this.config       = config;
        this.objectMapper = objectMapper;
        this.http         = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostConstruct
    void init() {
        for (McpConfig.McpServer server : config.servers()) {
            try {
                initServer(server);
            } catch (Exception e) {
                log.warn("MCP server [{}] unavailable at startup: {}", server.name(), e.getMessage());
            }
        }
        int total = (int) serverTools.values().stream().mapToLong(Collection::size).sum();
        log.info("MCP ready — {} configured server(s), {} tool(s) registered", serverTools.size(), total);
    }

    @Override
    public List<ToolSpecification> listTools() {
        return serverTools.values().stream()
                .flatMap(Collection::stream)
                .toList();
    }

    @Override
    public String executeTool(String toolName, String argumentsJson) {
        String serverName = toolToServer.get(toolName);
        if (serverName == null) {
            log.warn("Received call for unknown MCP tool: [{}]", toolName);
            return "Error: tool [" + toolName + "] is not registered.";
        }
        McpConfig.McpServer server = findServer(serverName);
        try {
            return callTool(server, toolName, argumentsJson);
        } catch (Exception e) {
            log.error("MCP tool [{}] on server [{}] failed: {}", toolName, serverName, e.getMessage());
            return "Error executing tool [" + toolName + "]: " + e.getMessage();
        }
    }

    // ── initialisation ────────────────────────────────────────────────────────

    private void initServer(McpConfig.McpServer server) throws Exception {
        sendInitialize(server);
        sendNotificationInitialized(server);
        List<ToolSpecification> tools = fetchTools(server);
        serverTools.put(server.name(), tools);
        tools.forEach(t -> toolToServer.merge(t.name(), server.name(), (existing, incoming) -> {
            log.warn("Tool name collision: [{}] registered by [{}] will be overridden by [{}]",
                    t.name(), existing, incoming);
            return incoming;
        }));
        log.info("MCP server [{}] initialised — {} tool(s): {}",
                server.name(), tools.size(),
                tools.stream().map(ToolSpecification::name).toList());
    }

    private void sendInitialize(McpConfig.McpServer server) throws Exception {
        Map<String, Object> params = Map.of(
                "protocolVersion", PROTO_VERSION,
                "capabilities",    Map.of("tools", Map.of()),
                "clientInfo",      Map.of("name", CLIENT_NAME, "version", CLIENT_VER)
        );
        postRpc(server, "initialize", params);
        log.debug("initialize handshake complete for MCP server [{}]", server.name());
    }

    /** Fire-and-forget notification (no {@code id} field, response may be empty). */
    private void sendNotificationInitialized(McpConfig.McpServer server) throws Exception {
        Map<String, Object> notification = Map.of(
                "jsonrpc", JSONRPC,
                "method",  "notifications/initialized"
        );
        String body = objectMapper.writeValueAsString(notification);
        HttpRequest req = buildRequest(server, body);
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private List<ToolSpecification> fetchTools(McpConfig.McpServer server) throws Exception {
        Map<String, Object> result = postRpc(server, "tools/list", Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawTools =
                (List<Map<String, Object>>) result.getOrDefault("tools", List.of());

        List<ToolSpecification> specs = new ArrayList<>();
        for (Map<String, Object> raw : rawTools) {
            try {
                specs.add(toToolSpec(raw));
            } catch (Exception e) {
                log.warn("Skipping malformed tool from [{}]: {}", server.name(), e.getMessage());
            }
        }
        return List.copyOf(specs);
    }

    // ── tool execution ────────────────────────────────────────────────────────

    private String callTool(McpConfig.McpServer server, String toolName, String argumentsJson)
            throws Exception {

        Map<String, Object> args = argumentsJson != null && !argumentsJson.isBlank()
                ? objectMapper.readValue(argumentsJson, new TypeReference<>() {})
                : Map.of();

        Map<String, Object> params = Map.of("name", toolName, "arguments", args);
        Map<String, Object> result = postRpc(server, "tools/call", params);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) result.getOrDefault("content", List.of());

        String textResult = content.stream()
                .filter(c -> "text".equals(c.get("type")))
                .map(c -> String.valueOf(c.get("text")))
                .findFirst()
                .orElse(null);

        if (textResult != null) {
            return textResult;
        }
        return objectMapper.writeValueAsString(result);
    }

    // ── JSON-RPC transport ────────────────────────────────────────────────────

    /**
     * Sends a JSON-RPC 2.0 request and returns the unwrapped {@code result} object.
     * Handles both plain JSON responses and SSE-framed responses ({@code data: {...}}).
     *
     * @throws McpException on HTTP error, JSON-RPC error response, or parse failure
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postRpc(McpConfig.McpServer server, String method,
                                        Map<String, Object> params) throws Exception {
        int id = idSeq.incrementAndGet();
        Map<String, Object> rpc = Map.of(
                "jsonrpc", JSONRPC,
                "method",  method,
                "params",  params,
                "id",      id
        );

        String body = objectMapper.writeValueAsString(rpc);
        HttpResponse<String> response = http.send(
                buildRequest(server, body),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpException("HTTP " + response.statusCode() + " from MCP server [" + server.name() + "]");
        }

        String responseBody = unwrapSse(response.body());
        if (responseBody.isBlank()) {
            return Map.of();
        }

        Map<String, Object> json = objectMapper.readValue(responseBody, new TypeReference<>() {});

        if (json.containsKey("error")) {
            Map<String, Object> err = (Map<String, Object>) json.get("error");
            throw new McpException("JSON-RPC error from [" + server.name() + "]: " + err);
        }

        Object resultObj = json.get("result");
        if (resultObj instanceof Map<?, ?> resultMap) {
            return (Map<String, Object>) resultMap;
        }
        return Map.of();
    }

    /** Extracts the JSON payload from an SSE-framed body if necessary. */
    private String unwrapSse(String body) {
        if (body == null || !body.startsWith("data:")) {
            return body != null ? body : "";
        }
        return body.lines()
                .filter(l -> l.startsWith("data:"))
                .map(l -> l.substring(5).strip())
                .filter(l -> !l.isEmpty() && !l.equals("[DONE]"))
                .findFirst()
                .orElse("");
    }

    private HttpRequest buildRequest(McpConfig.McpServer server, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl()))
                .timeout(Duration.ofSeconds(server.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    // ── LangChain4j conversion ────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "deprecation", "removal"})
    private ToolSpecification toToolSpec(Map<String, Object> raw) {
        String name = (String) raw.get("name");
        String desc = (String) raw.getOrDefault("description", "");

        Map<String, Object> schema =
                (Map<String, Object>) raw.getOrDefault("inputSchema", Map.of());
        Map<String, Map<String, Object>> props =
                (Map<String, Map<String, Object>>) schema.getOrDefault("properties", Map.of());
        List<String> required =
                (List<String>) schema.getOrDefault("required", List.of());

        return ToolSpecification.builder()
                .name(name)
                .description(desc)
                .parameters(ToolParameters.builder()
                        .properties(props)
                        .required(required)
                        .build())
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private McpConfig.McpServer findServer(String name) {
        return config.servers().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new McpException("No server config found for: " + name));
    }

    // ── inner exception ───────────────────────────────────────────────────────

    public static final class McpException extends RuntimeException {
        public McpException(String message) { super(message); }
    }
}
