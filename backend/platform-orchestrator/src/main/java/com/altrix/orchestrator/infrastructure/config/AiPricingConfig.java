package com.altrix.orchestrator.infrastructure.config;

import com.altrix.orchestrator.domain.port.out.TokenPricingPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Per-model token pricing for cost estimates in the analytics API (#151).
 * Prices are in USD per million tokens. Leave empty to omit cost estimates.
 *
 * <pre>
 * ai.pricing.models:
 *   gpt-4o:                  { input-per-million: 5.0,  output-per-million: 15.0 }
 *   gpt-4o-mini:             { input-per-million: 0.15, output-per-million: 0.6  }
 *   claude-sonnet-4-6:       { input-per-million: 3.0,  output-per-million: 15.0 }
 *   llama-3.3-70b-versatile: { input-per-million: 0.0,  output-per-million: 0.0  }
 * </pre>
 */
@ConfigurationProperties(prefix = "ai.pricing")
public record AiPricingConfig(
        @DefaultValue Map<String, ModelPricing> models
) implements TokenPricingPort {

    public record ModelPricing(
            @DefaultValue("0.0") double inputPerMillion,
            @DefaultValue("0.0") double outputPerMillion
    ) {
    }

    @Override
    public double estimateCostUsd(String providerId, long inputTokens, long outputTokens) {
        ModelPricing pricing = models.get(providerId);
        if (pricing == null) return 0.0;
        return (inputTokens / 1_000_000.0) * pricing.inputPerMillion()
                + (outputTokens / 1_000_000.0) * pricing.outputPerMillion();
    }
}
