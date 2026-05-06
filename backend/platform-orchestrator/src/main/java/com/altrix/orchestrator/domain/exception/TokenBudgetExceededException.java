package com.altrix.orchestrator.domain.exception;

/**
 * Thrown before calling any AI provider when the monthly token budget is exhausted.
 * Extends {@link AiProviderUnavailableException} so OrchestratorService's graceful
 * degradation path handles it the same way as a provider outage.
 */
public class TokenBudgetExceededException extends AiProviderUnavailableException {
    private final long limit;
    private final long used;

    public TokenBudgetExceededException(long limit, long used) {
        super("Monthly token budget exceeded: " + used + " / " + limit + " tokens used");
        this.limit = limit;
        this.used = used;
    }

    public long limit() {
        return limit;
    }

    public long used() {
        return used;
    }
}
