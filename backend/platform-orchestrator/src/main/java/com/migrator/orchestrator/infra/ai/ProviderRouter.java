package com.migrator.orchestrator.infra.ai;

import com.migrator.orchestrator.infrastructure.config.AiRoutingConfig;
import com.migrator.orchestrator.infrastructure.config.AiRoutingConfig.RoutingStrategy;
import com.migrator.orchestrator.infra.ai.exception.ProviderCallException;
import com.migrator.orchestrator.infra.ai.provider.ProviderTier;
import com.migrator.orchestrator.infra.ai.provider.RegisteredProvider;
import com.migrator.orchestrator.infra.ai.routing.ExplicitOrderStrategy;
import com.migrator.orchestrator.infra.ai.routing.ProviderSelectionStrategy;
import com.migrator.orchestrator.infra.ai.routing.TierPreferenceStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Routes an AI chat call to the best available provider using the configured
 * {@link ProviderSelectionStrategy}, falling back through the chain on failure.
 *
 * <p>Reads the live provider list from {@link ProviderRegistry} on every call,
 * so a config refresh takes effect immediately without restarting the router.
 *
 * <p>Circuit breakers are initialised lazily per provider ID, so new providers
 * added via refresh automatically receive a CB with the configured policy.
 *
 * <p>Security notes:
 * <ul>
 *   <li>Provider exceptions are wrapped in {@link ProviderCallException} so raw
 *       HTTP error bodies never reach callers.</li>
 *   <li>Circuit breakers prevent hammering a broken provider, limiting token waste
 *       and reducing exposure during any ongoing credential brute-force.</li>
 * </ul>
 */
@Slf4j
@Component
public class ProviderRouter {

    private final ProviderRegistry           registry;
    private final ProviderSelectionStrategy  strategy;
    private final CircuitBreakerRegistry     cbRegistry;

    public ProviderRouter(ProviderRegistry registry, AiRoutingConfig routingCfg) {
        this.registry   = registry;
        this.strategy   = buildStrategy(routingCfg);
        this.cbRegistry = buildCbRegistry(routingCfg.circuitBreaker());
        log.info("ProviderRouter ready — strategy={} tierPreference={}",
                routingCfg.strategy(), routingCfg.tierPreference());
    }

    /**
     * Executes a two-turn chat (system + user) through the ordered provider chain.
     *
     * @throws AllProvidersUnavailableException if every provider fails or has an open CB
     */
    public String chat(ProviderTier tier, String systemPrompt, String userContent) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userContent)
        );

        List<RegisteredProvider> ordered = strategy.order(registry.all(), tier);

        for (RegisteredProvider provider : ordered) {
            CircuitBreaker cb = cbRegistry.circuitBreaker(provider.id());

            if (cb.getState() == CircuitBreaker.State.OPEN) {
                log.debug("Skipping provider [{}] — circuit is OPEN", provider.id());
                continue;
            }

            try {
                String result = callWithCircuitBreaker(cb, provider, tier, messages);
                log.info("Provider [{}] succeeded (tier={})", provider.id(), tier);
                return result;
            } catch (CallNotPermittedException e) {
                log.debug("Provider [{}] CB rejected call — trying next", provider.id());
            } catch (ProviderCallException e) {
                log.warn("Provider [{}] failed (tier={}) — trying next", provider.id(), tier);
            }
        }

        throw new AllProvidersUnavailableException(tier, ordered);
    }

    /** Exposes CB states for monitoring without leaking internal implementation. */
    public Map<String, CircuitBreaker.State> circuitBreakerStates() {
        Map<String, CircuitBreaker.State> states = new ConcurrentHashMap<>();
        registry.all().forEach(p ->
                states.put(p.id(), cbRegistry.circuitBreaker(p.id()).getState()));
        return Map.copyOf(states);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String callWithCircuitBreaker(
            CircuitBreaker cb,
            RegisteredProvider provider,
            ProviderTier tier,
            List<ChatMessage> messages) {

        Supplier<String> call = CircuitBreaker.decorateSupplier(cb, () -> {
            try {
                Response<AiMessage> response = provider.modelFor(tier).generate(messages);
                return response.content().text();
            } catch (Exception e) {
                log.debug("Provider [{}] call detail: {}", provider.id(), e.getMessage());
                throw new ProviderCallException(provider.id(), tier, e);
            }
        });

        return call.get();
    }

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
