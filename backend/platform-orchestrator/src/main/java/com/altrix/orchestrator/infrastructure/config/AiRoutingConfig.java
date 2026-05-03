package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Externalises every routing policy value — nothing hardcoded in Java.
 *
 * <p>Change {@code ai.routing.strategy} to switch between modes:
 * <ul>
 *   <li>{@code TIER_PREFERENCE} + {@code tier-preference: PAID_FIRST}  — quality first</li>
 *   <li>{@code TIER_PREFERENCE} + {@code tier-preference: FREE_FIRST}  — cost first</li>
 *   <li>{@code EXPLICIT_ORDER} + {@code explicit-order: [openai,groq]} — full control</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ai.routing")
public record AiRoutingConfig(
        @DefaultValue("TIER_PREFERENCE") RoutingStrategy        strategy,
        @DefaultValue("PAID_FIRST")      TierPreference         tierPreference,
        @DefaultValue("")                List<String>           explicitOrder,
                                         CircuitBreakerSettings circuitBreaker,
                                         RetrySettings          retry
) {

    public enum RoutingStrategy { TIER_PREFERENCE, EXPLICIT_ORDER }
    public enum TierPreference  { PAID_FIRST, FREE_FIRST }

    public record CircuitBreakerSettings(
            @DefaultValue("10")   int   slidingWindowSize,
            @DefaultValue("50")   float failureRateThreshold,
            @DefaultValue("30")   long  waitDurationOpenSeconds,
            @DefaultValue("3")    int   permittedCallsHalfOpen
    ) {}

    public record RetrySettings(
            @DefaultValue("2")    int  maxAttempts,
            @DefaultValue("1000") long waitDurationMillis
    ) {}
}
