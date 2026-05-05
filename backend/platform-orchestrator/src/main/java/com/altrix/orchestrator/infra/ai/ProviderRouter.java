package com.altrix.orchestrator.infra.ai;

import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.exception.TokenBudgetExceededException;
import com.altrix.orchestrator.domain.model.AiCallLedgerEntry;
import com.altrix.orchestrator.domain.model.TokenUsageRecord;
import com.altrix.orchestrator.domain.port.in.GetResilienceMetricsUseCase;
import com.altrix.orchestrator.domain.port.in.ProviderResilienceStatus;
import com.altrix.orchestrator.domain.port.out.AiCallLedgerPort;
import com.altrix.orchestrator.domain.port.out.TokenUsagePort;
import com.altrix.orchestrator.infrastructure.config.AiRoutingConfig;
import com.altrix.orchestrator.infrastructure.config.AiRoutingConfig.RoutingStrategy;
import com.altrix.orchestrator.infrastructure.config.McpConfig;
import com.altrix.orchestrator.infra.ai.exception.ProviderCallException;
import com.altrix.orchestrator.infra.ai.provider.ProviderTier;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infra.ai.routing.ExplicitOrderStrategy;
import com.altrix.orchestrator.infra.ai.routing.ProviderSelectionStrategy;
import com.altrix.orchestrator.infra.ai.routing.TierPreferenceStrategy;
import com.altrix.orchestrator.infra.ai.tools.McpToolsPort;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
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
 * <h2>Resilience stack (per call)</h2>
 * <pre>
 *   Tier Bulkhead  — limits total concurrent ANALYSIS / MIGRATION calls
 *     └─ per-provider Retry    — retries transient errors with exponential backoff
 *          └─ per-provider CB  — short-circuits broken providers
 *               └─ actual model.generate()
 * </pre>
 *
 * <h2>Agentic mode (MCP tools)</h2>
 * <p>When {@code ai.mcp.enabled=true} and at least one MCP server is
 * reachable, {@link #chatAgentic} runs a tool-augmented generation loop.
 * Regular {@link #chat} calls are unaffected.
 *
 * <p>Security notes:
 * <ul>
 *   <li>Provider exceptions are wrapped in {@link ProviderCallException} so raw
 *       HTTP error bodies never reach callers.</li>
 *   <li>Circuit breakers prevent hammering a broken provider.</li>
 *   <li>The agentic loop is bounded by {@code ai.mcp.max-tool-iterations}.</li>
 * </ul>
 */
@Slf4j
@Component
public class ProviderRouter implements GetResilienceMetricsUseCase {

    private final ProviderRegistry          registry;
    private final ProviderSelectionStrategy strategy;
    private final CircuitBreakerRegistry    cbRegistry;
    private final Retry                     retry;
    private final Bulkhead                  analysisBulkhead;
    private final Bulkhead                  migrationBulkhead;
    private final TokenUsagePort            tokenUsagePort;
    private final AiCallLedgerPort          aiCallLedgerPort;
    private final long                      monthlyTokenLimit; // 0 = unlimited
    private final McpToolsPort              mcpTools;          // null when MCP is disabled
    private final int                       maxToolIter;

    public ProviderRouter(
            ProviderRegistry         registry,
            AiRoutingConfig          routingCfg,
            McpConfig                mcpConfig,
            Optional<McpToolsPort>   mcpTools,
            TokenUsagePort           tokenUsagePort,
            AiCallLedgerPort         aiCallLedgerPort) {

        this.registry         = registry;
        this.strategy         = buildStrategy(routingCfg);
        this.cbRegistry       = buildCbRegistry(routingCfg.circuitBreaker());
        this.retry            = buildRetry(routingCfg.retry());
        this.analysisBulkhead = buildBulkhead("analysis",  routingCfg.bulkhead().analysisConcurrency(),  routingCfg.bulkhead().maxWaitMs());
        this.migrationBulkhead= buildBulkhead("migration", routingCfg.bulkhead().migrationConcurrency(), routingCfg.bulkhead().maxWaitMs());
        this.tokenUsagePort    = tokenUsagePort;
        this.aiCallLedgerPort  = aiCallLedgerPort;
        this.monthlyTokenLimit = routingCfg.monthlyTokenLimit();
        this.mcpTools          = mcpTools.orElse(null);
        this.maxToolIter       = mcpConfig.maxToolIterations();

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
     * @throws BulkheadFullException if the per-tier concurrency limit is exhausted
     */
    public String chat(ProviderTier tier, String systemPrompt, String userContent) {
        enforceBudget();
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
     * @throws AllProvidersUnavailableException if every provider fails
     * @throws BulkheadFullException if the per-tier concurrency limit is exhausted
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

    @Override
    public ProviderResilienceStatus getResilienceStatus() {
        Map<String, String> cbStates = new java.util.LinkedHashMap<>();
        registry.all().forEach(p ->
                cbStates.put(p.id(), cbRegistry.circuitBreaker(p.id()).getState().name()));

        Map<String, ProviderResilienceStatus.BulkheadSnapshot> bulkheads = Map.of(
                "ANALYSIS",  snapshot(analysisBulkhead),
                "MIGRATION", snapshot(migrationBulkhead)
        );
        return new ProviderResilienceStatus(Map.copyOf(cbStates), bulkheads);
    }

    private static ProviderResilienceStatus.BulkheadSnapshot snapshot(Bulkhead b) {
        return new ProviderResilienceStatus.BulkheadSnapshot(
                b.getBulkheadConfig().getMaxConcurrentCalls(),
                b.getMetrics().getAvailableConcurrentCalls());
    }

    // ── provider routing ──────────────────────────────────────────────────────

    /**
     * Wraps the full provider-fallback loop in the tier's bulkhead.
     * {@link BulkheadFullException} propagates immediately — the caller is at
     * the concurrency ceiling for this tier and all providers share it.
     */
    private String routeToProvider(ProviderTier tier, List<ChatMessage> messages,
                                   List<ToolSpecification> tools) {

        Bulkhead bulkhead = tier == ProviderTier.ANALYSIS ? analysisBulkhead : migrationBulkhead;

        Supplier<String> providerLoop = () -> {
            List<RegisteredProvider> ordered = strategy.order(registry.all(), tier);

            for (RegisteredProvider provider : ordered) {
                CircuitBreaker cb = cbRegistry.circuitBreaker(provider.id());
                if (cb.getState() == CircuitBreaker.State.OPEN) {
                    log.debug("Skipping [{}] — circuit OPEN", provider.id());
                    continue;
                }

                try {
                    String result = tools == null
                            ? callWithRetryAndCb(provider, tier, messages, cb)
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
        };

        return Bulkhead.decorateSupplier(bulkhead, providerLoop).get();
    }

    // ── plain generate with retry + CB ────────────────────────────────────────

    private String callWithRetryAndCb(RegisteredProvider provider, ProviderTier tier,
                                      List<ChatMessage> messages, CircuitBreaker cb) {
        // Retry wraps CB so each attempt counts independently against the CB
        return Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(cb, () -> {
                    try {
                        Response<AiMessage> response = provider.modelFor(tier).generate(messages);
                        recordTokenUsage(provider.id(), tier, response.tokenUsage());
                        return response.content().text();
                    } catch (Exception e) {
                        log.debug("Provider [{}] detail: {}", provider.id(), e.getMessage());
                        throw new ProviderCallException(provider.id(), tier, e);
                    }
                })
        ).get();
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
                recordTokenUsage(provider.id(), tier, response.tokenUsage());
                return aiMsg.text();
            }

            for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                String result = mcpTools.executeTool(req.name(), req.arguments());
                log.debug("MCP tool [{}] returned {} char(s)", req.name(), result.length());
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }

        throw new ProviderCallException(provider.id(), tier,
                new IllegalStateException("Agentic loop hit max " + maxToolIter + " iterations"));
    }

    // ── monthly budget enforcement ────────────────────────────────────────────

    private void enforceBudget() {
        if (monthlyTokenLimit <= 0) return;
        Instant startOfMonth = Instant.now()
                .atZone(java.time.ZoneOffset.UTC)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                .toInstant();
        long used = tokenUsagePort.getTotalTokensSince(startOfMonth);
        if (used >= monthlyTokenLimit) {
            throw new TokenBudgetExceededException(monthlyTokenLimit, used);
        }
    }

    // ── token usage ───────────────────────────────────────────────────────────

    private void recordTokenUsage(String providerId, ProviderTier tier, TokenUsage usage) {
        if (usage == null) return;
        Instant now = Instant.now();
        try {
            tokenUsagePort.save(new TokenUsageRecord(
                    providerId,
                    tier.name(),
                    usage.inputTokenCount(),
                    usage.outputTokenCount(),
                    usage.totalTokenCount(),
                    now));
        } catch (Exception e) {
            log.warn("Failed to record token usage for [{}]: {}", providerId, e.getMessage());
        }
        // Best-effort ledger entry — cost is 0 until pricing is configured per provider/model
        aiCallLedgerPort.record(new AiCallLedgerEntry(
                null,
                null,
                providerId,
                null,
                tier.name(),
                usage.inputTokenCount()  != null ? usage.inputTokenCount()  : 0L,
                usage.outputTokenCount() != null ? usage.outputTokenCount() : 0L,
                0.0,
                false,
                now));
    }

    // ── transient-error predicate ─────────────────────────────────────────────

    private static boolean isTransient(Throwable e) {
        if (!(e instanceof ProviderCallException)) return false;
        Throwable cause = e.getCause();
        if (cause == null) return false;
        if (cause instanceof IOException || cause instanceof SocketTimeoutException) return true;
        String msg = cause.getMessage();
        return msg != null && (msg.contains("429") || msg.contains("500")
                || msg.contains("502") || msg.contains("503") || msg.contains("504"));
    }

    // ── strategy / resilience factory ────────────────────────────────────────

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

    private Retry buildRetry(AiRoutingConfig.RetrySettings s) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(s.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        s.waitDurationMillis(), 1.5))
                .retryOnException(ProviderRouter::isTransient)
                .ignoreExceptions(CallNotPermittedException.class)
                .build();
        return Retry.of("provider-retry", config);
    }

    private static Bulkhead buildBulkhead(String name, int maxConcurrent, long maxWaitMs) {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ofMillis(maxWaitMs))
                .build();
        return Bulkhead.of(name, config);
    }

    // ── exceptions ────────────────────────────────────────────────────────────

    public static final class AllProvidersUnavailableException extends AiProviderUnavailableException {
        public AllProvidersUnavailableException(ProviderTier tier, List<RegisteredProvider> tried) {
            super("All providers unavailable for tier " + tier
                    + ". Tried: " + tried.stream().map(RegisteredProvider::id).toList());
        }
    }
}
