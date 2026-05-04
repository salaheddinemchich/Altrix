package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.TokenUsageRecord;
import com.altrix.orchestrator.domain.port.in.TokenUsageSummary;

import java.util.List;

/** Driven port — persists and queries per-call token-usage data. */
public interface TokenUsagePort {
    void record(TokenUsageRecord usage);
    List<TokenUsageSummary> getSummary();
}
