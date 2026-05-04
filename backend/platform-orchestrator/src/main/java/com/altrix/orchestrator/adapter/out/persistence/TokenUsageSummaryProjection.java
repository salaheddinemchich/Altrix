package com.altrix.orchestrator.adapter.out.persistence;

/** Spring Data closed-projection for the aggregated token-usage summary query. */
public interface TokenUsageSummaryProjection {
    String getProviderId();
    String getTier();
    long getInputTokens();
    long getOutputTokens();
    long getTotalTokens();
    long getCallCount();
}
