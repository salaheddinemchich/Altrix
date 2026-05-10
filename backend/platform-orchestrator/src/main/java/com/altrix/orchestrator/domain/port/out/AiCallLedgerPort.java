package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.AiCallLedgerEntry;
import com.altrix.orchestrator.domain.model.AiCallUsageSummary;

import java.time.Instant;
import java.util.List;

/**
 * Secondary port — persists per-call AI usage records and aggregates them for billing (#53).
 */
public interface AiCallLedgerPort {

    /**
     * Persists one ledger entry. Best-effort — never throw on the caller.
     */
    void record(AiCallLedgerEntry entry);

    /**
     * Returns usage aggregated by agent and provider for the given time window.
     * Results are ordered by total cost descending.
     */
    List<AiCallUsageSummary> queryUsage(Instant from, Instant to);
}
