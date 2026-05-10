package com.altrix.orchestrator.domain.port.in;

import java.util.List;

/**
 * Driving port — returns aggregated token-usage analytics.
 */
public interface GetTokenUsageUseCase {
    List<TokenUsageSummary> getSummary();
}
