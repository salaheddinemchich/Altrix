package com.migrator.orchestrator.infra.ai;

import com.migrator.orchestrator.infrastructure.config.AiRoutingConfig;
import com.migrator.orchestrator.infrastructure.config.AiRoutingConfig.RoutingStrategy;
import com.migrator.orchestrator.infrastructure.config.McpConfig;
import com.migrator.orchestrator.infra.ai.exception.ProviderCallException;
import com.migrator.orchestrator.infra.ai.provider.ProviderTier;
import com.migrator.orchestrator.infra.ai.provider.RegisteredProvider;
import com.migrator.orchestrator.infra.ai.routing.ExplicitOrderStrategy;
import com.migrator.orchestrator.infra.ai.routing.ProviderSelectionStrategy;
import com.migrator.orchestrator.infra.ai.routing.TierPreferenceStrategy;
import com.migrator.orchestrator.infra.ai.tools.McpToolsPort;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Routes an AI chat call to the best available provider using the configured
 * {@link ProviderSelectionStrategy}, falling back through the chain on failure.
 *
 * <p>Reads the live provider list from {@link ProviderRegistry} on every call,
 * so a config refresh takes effect immediately without restarting the router.
 *
 * <p>Circuit breakers are initialised lazily per provider ID via
 * {@link CircuitBreakerRegistry}, so new providers added via runtime refresh
 * automatically receive a CB with the shared configured policy.
 *
 * <h2>Agentic mode (MCP tools)</h2>
 * <p>When {@code ai.mcp.enabled=true} and at least one MCP server is
 * reachable, {@link #chatAgentic} runs a tool-augmented generation loop:
 * the model may call tools via the Model Context Protocol before producing
 * its final text response. Regular {@link #chat} calls are unaffected.
 *
 * <p>Security notes:
 * <ul>
 *   <li>Provider exceptions are wrapped in {@link ProviderCallException} so raw
 *       HTTP error bodies never reach callers.</li>
 *   <li>Circuit breakers prevent hammering a broken provider, limiting token
 *       waste and reducing exposure during any ongoing credential brute-force.</li>
 *   <li>The agentic loop is bounded by {@code ai.mcp.max-tool-iterations} to
 *       prevent runaway tool-call chains.</li>
 * </ul>
 */
@Slf4j
@Component
public class ProviderRouter {

    private final ProviderRegistry          registry;
    private final ProviderSelectionStrategy strategy;
    private final CircuitBreakerRegistry    cbRegistry;
    private final McpToolsPort              mcpTools;       // null when MCP is disabled
    private final int                       maxToolIter;

    public ProviderRouter(
            ProviderRegistry         registry,
            AiRoutingConfig          routingCfg,
            McpConfig                mcpConfig,
            Optional<McpToolsPort>   mcpTools) {

        this.registry    = registry;
        this.strategy    = buildStrategy(routingCfg);
        this.cbRegistry  = buildCbRegistry(routingCfg.circuitBreaker());
        this.mcpTools    = mcpTools.orElse(null);
        this.maxToolIter = mcpConfig.maxToolIterations();

        log.info("ProviderRouter ready — strategy={} tierPreference={} mcp={}",
                routingCfg.strategy(), routingCfg.tierPreference(),
                this.mcpTools != null ? "enabled" : "disabled");
    }

    // ── standard chat ─────────────────────────────────────────────────────────

    /**
     * Executes a two-turn chat (system + user) through the ordered provider chain.
     * No tool calls — use {@link #chatAgentic} for MCP tool support.
     *
     * @throws AllProvidersUnavailableException if every provider fails or has an open CB
     */
    public String chat(ProviderTier tier, String systemPrompt, String userContent) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userContent)
        );
        return routeToProvider(tier, messages, null);
    }

    // ── agentic chat (MCP tools) ──────────────────────────────────────────────

    /**
     * Executes a tool-augmented chat loop.
     *
     * <p>If MCP is not configured or no tools are available, falls back to
     * {@link #chat} transparently — callers need not check.
     *
     * <p>Loop contract:
     * <ol>
     *   <li>Send messages + tool specs to the model.</li>
     *   <li>If the model returns tool calls, execute each via MCP and append results.</li>
     *   <li>Repeat until the model returns a plain text response, or until
     *       {@code max-tool-iterations} is reached.</li>
     * </ol>
     *
     * @throws AllProvidersUnavailableException if every provider fails
     */
    public String chatAgentic(ProviderTier tier, String systemPrompt, String userContent) {
        if (mcpTools == null) {
            return chat(tier, systemPrompt, userContent);
        }
        List<ToolSpecification> tools = mcpTools.listTools();
        if (tools.isEmpty()) {
            log.debug("chatAgentic — no MCP tools available, falling back to regular chat");
            return chat(tier, systemPrompt, userContent);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userContent));

        return routeToProvider(tier, messages, tools);
    }

    // ── circuit breaker state (for monitoring) ────────────────────────────────

    /** Returns CB states for health endpoints without leaking internal types. */
    public Map<String, CircuitBreaker.State> circuitBreakerStates() {
        Map<String, CircuitBreaker.State> states = new ConcurrentHashMap<>();
        registry.all().forEach(p ->
                states.put(p.id(), cbRegistry.circuitBreaker(p.id()).getState()));
        return Map.copyOf(states);
    }

    // ── provider routing ──────────────────────────────────────────────────────

    /**
     * Tries each provider in order. If {@code tools} is non-null, runs the
     * agentic loop; otherwise executes a plain generate call.
     */
    private String routeToProvider(ProviderTier tier, List<ChatMessage> messages,
                                   List<ToolSpecification> tools) {

        List<RegisteredProvider> ordered = strategy.order(registry.all(), tier);

        for (RegisteredProvider provider : ordered) {
            CircuitBreaker cb = cbRegistry.circuitBreaker(provider.id());
            if (cb.getState() == CircuitBreaker.State.OPEN) {
                log.debug("Skipping [{}] — circuit OPEN", provider.id());
                continue;
            }

            try {
                String result = tools == null
                        ? callWithCb(cb, provider, tier, messages)
                        : agenticLoopWithCb(cb, provider, tier, messages, tools);
                log.info("Provider [{}] succeeded (tier={} agentic={})",
                        provider.id(), tier, tools != null);
                return result;
            } catch (CallNotPermittedException e) {
                log.debug("Provider [{}] CB rejected — trying next", provider.id());
            } catch (ProviderCallException e) {
                log.warn("Provider [{}] failed (tier={}) — trying next: {}",
                        provider.id(), tier, e.getMessage());
            }
        }

        throw new AllProvidersUnavailableException(tier, ordered);
    }

    // ── plain generate ────────────────────────────────────────────────────────

    private String callWithCb(CircuitBreaker cb, RegisteredProvider provider,
                               ProviderTier tier, List<ChatMessage> messages) {
        Supplier<String> call = CircuitBreaker.decorateSupplier(cb, () -> {
            try {
                Response<AiMessage> response = provider.modelFor(tier).generate(messages);
                return response.content().text();
            } catch (Exception e) {
                log.debug("Provider [{}] detail: {}", provider.id(), e.getMessage());
                throw new ProviderCallException(provider.id(), tier, e);
            }
        });
        return call.get();
    }

    // ── agentic loop ──────────────────────────────────────────────────────────

    private String agenticLoopWithCb(CircuitBreaker cb, RegisteredProvider provider,
                                     ProviderTier tier, List<ChatMessage> seedMessages,
                                     List<ToolSpecification> tools) {
        Supplier<String> call = CircuitBreaker.decorateSupplier(cb, () -> {
            try {
                return runAgenticLoop(provider, tier, seedMessages, tools);
            } catch (ProviderCallException e) {
                throw e;
            } catch (Exception e) {
                throw new ProviderCallException(provider.id(), tier, e);
            }
        });
        return call.get();
    }

    private String runAgenticLoop(RegisteredProvider provider, ProviderTier tier,
                                  List<ChatMessage> seedMessages,
                                  List<ToolSpecification> tools) {

        List<ChatMessage> messages = new ArrayList<>(seedMessages);

        for (int i = 0; i < maxToolIter; i++) {
            Response<AiMessage> response;
            try {
                response = provider.modelFor(tier).generate(messages, tools);
            } catch (Exception e) {
                log.debug("Provider [{}] agentic generate failed: {}", provider.id(), e.getMessage());
                throw new ProviderCallException(provider.id(), tier, e);
            }

            AiMessage aiMsg = response.content();
            messages.add(aiMsg);

            if (!aiMsg.hasToolExecutionRequests()) {
                log.debug("Agentic loop completed in {} iteration(s) via [{}]", i + 1, provider.id());
                return aiMsg.text();
            }

            // Execute each requested tool and append results
            for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                String result = mcpTools.executeTool(req.name(), req.arguments());
                log.debug("MCP tool [{}] returned {} char(s)", req.name(), result.length());
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }

        throw new ProviderCallException(provider.id(), tier,
                new IllegalStateException("Agentic loop hit max " + maxToolIter + " iterations"));
    }

    // ── strategy / CB factory ─────────────────────────────────────────────────

    private ProviderSelectionStrategy buildStrategy(AiRoutingConfig cfg) {
        if (cfg.strategy() == RoutingStrategy.EXPLICIT_ORDER && !cfg.explicitOrder().isEmpty()) {
            return new ExplicitOrderStrategy(cfg.explicitOrder());
        }
        return new TierPreferenceStrategy(cfg.tierPreference());
    }

    private CircuitBreakerRegistry buildCbRegistry(AiRoutingConfig.CircuitBreakerSettings s) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(s.slidingWindowSize())
                .failureRateThreshold(s.failureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(s.waitDurationOpenSeconds()))
                .permittedNumberOfCallsInHalfOpenState(s.permittedCallsHalfOpen())
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    // ── exceptions ────────────────────────────────────────────────────────────

    public static final class AllProvidersUnavailableException extends RuntimeException {
        public AllProvidersUnavailableException(ProviderTier tier, List<RegisteredProvider> tried) {
            super("All providers unavailable for tier " + tier
                    + ". Tried: " + tried.stream().map(RegisteredProvider::id).toList());
        }
    }
}
