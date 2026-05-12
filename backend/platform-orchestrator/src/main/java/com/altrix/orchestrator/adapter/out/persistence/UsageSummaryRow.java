package com.altrix.orchestrator.adapter.out.persistence;

/**
 * JPQL constructor-expression projection for ai_call_ledger aggregate queries (#53).
 */
import java.math.BigDecimal;

record UsageSummaryRow(
        String agentName,
        String providerName,
        long totalInputTokens,
        long totalOutputTokens,
        BigDecimal totalCostUsd,
        long callCount
) {
}
