package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.TokenUsageRecord;
import com.altrix.orchestrator.domain.port.in.TokenUsageSummary;

import java.time.Instant;
import java.util.List;

/**
 * Driven port — persists and queries per-call token-usage data.
 */
public interface TokenUsagePort {
    void save(TokenUsageRecord usage);

    List<TokenUsageSummary> getSummary();

    /**
     * Total tokens consumed since {@code since} — used for monthly budget checks.
     */
    long getTotalTokensSince(Instant since);
}
