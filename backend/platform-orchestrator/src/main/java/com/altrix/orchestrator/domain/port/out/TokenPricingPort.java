package com.altrix.orchestrator.domain.port.out;

/** Driven port — provides per-model token pricing for cost estimation. */
public interface TokenPricingPort {

    /**
     * Returns estimated USD cost for the given token counts.
     *
     * @param providerId the provider ID key used to look up pricing
     * @param inputTokens  number of input (prompt) tokens consumed
     * @param outputTokens number of output (completion) tokens consumed
     * @return estimated cost in USD, or 0.0 if pricing is not configured
     */
    double estimateCostUsd(String providerId, long inputTokens, long outputTokens);
}
