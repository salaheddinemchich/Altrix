package com.migrator.orchestrator.infra.ai;

import com.migrator.orchestrator.infra.ai.provider.ProviderTier;
import com.migrator.orchestrator.infra.ai.provider.RegisteredProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes an AI call to the best available provider for the requested tier.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Iterate providers in registration order (paid first).</li>
 *   <li>Skip any whose circuit breaker is OPEN.</li>
 *   <li>Attempt the call — on failure the CB records it and we try next provider.</li>
 *   <li>If all providers fail, throw {@link AllProvidersUnavailableException}.</li>
 * </ol>
 */
@Slf4j
@Component
public class ProviderRouter {

    private final List<RegisteredProvider>      providers;
    private final Map<String, CircuitBreaker>   circuitBreakers;

    private static final CircuitBreakerConfig CB_CONFIG = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    public ProviderRouter(ProviderRegistry registry) {
        this.providers      = registry.all();
        this.circuitBreakers = new ConcurrentHashMap<>();
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(CB_CONFIG);
        for (RegisteredProvider p : providers) {
            circuitBreakers.put(p.id(), cbRegistry.circuitBreaker(p.id()));
        }
    }

    /**
     * Execute a two-turn chat (system + user) using the best available provider
     * for the given tier. Falls back through the provider list if a provider fails.
     *
     * @return the assistant text response
     * @throws AllProvidersUnavailableException if every provider fails or is open
     */
    public String chat(ProviderTier tier, String systemPrompt, String userContent) {
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userContent)
        );

        for (RegisteredProvider provider : providers) {
            CircuitBreaker cb = circuitBreakers.get(provider.id());
            if (!cb.tryAcquirePermission()) {
                log.debug("Provider [{}] circuit is OPEN — skipping", provider.id());
                continue;
            }
            long start = System.currentTimeMillis();
            try {
                Response<AiMessage> response = provider.modelFor(tier).generate(messages);
                cb.onSuccess(System.currentTimeMillis() - start, java.util.concurrent.TimeUnit.MILLISECONDS);
                String text = response.content().text();
                log.info("Provider [{}] tier={} tokens={}", provider.id(), tier,
                        response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : "?");
                return text;
            } catch (Exception e) {
                cb.onError(System.currentTimeMillis() - start, java.util.concurrent.TimeUnit.MILLISECONDS, e);
                log.warn("Provider [{}] failed (tier={}): {} — trying next", provider.id(), tier, e.getMessage());
            }
        }
        throw new AllProvidersUnavailableException(tier);
    }

    /** Returns the circuit-breaker state for monitoring/actuator exposure. */
    public Map<String, CircuitBreaker.State> circuitBreakerStates() {
        Map<String, CircuitBreaker.State> states = new ConcurrentHashMap<>();
        circuitBreakers.forEach((id, cb) -> states.put(id, cb.getState()));
        return states;
    }

    public static final class AllProvidersUnavailableException extends RuntimeException {
        public AllProvidersUnavailableException(ProviderTier tier) {
            super("All AI providers are unavailable for tier: " + tier);
        }
    }
}
