package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.AiCallLedgerEntry;
import com.altrix.orchestrator.domain.model.AiCallUsageSummary;
import com.altrix.orchestrator.domain.port.out.AiCallLedgerPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallLedgerPersistenceAdapter implements AiCallLedgerPort {

    private final AiCallLedgerJpaRepository repository;

    @Override
    public void record(AiCallLedgerEntry entry) {
        try {
            repository.save(AiCallLedgerJpaEntity.builder()
                    .jobId(entry.jobId())
                    .agentName(entry.agentName())
                    .providerName(entry.providerName())
                    .modelName(entry.modelName())
                    .tier(entry.tier())
                    .inputTokens(entry.inputTokens())
                    .outputTokens(entry.outputTokens())
                    .costUsd(entry.costUsd())
                    .cacheHit(entry.cacheHit())
                    .createdAt(entry.createdAt())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to record AI call ledger entry for provider '{}': {}",
                    entry.providerName(), e.getMessage());
        }
    }

    @Override
    public List<AiCallUsageSummary> queryUsage(Instant from, Instant to) {
        return repository.aggregateUsage(from, to).stream()
                .map(r -> new AiCallUsageSummary(
                        r.agentName(),
                        r.providerName(),
                        r.totalInputTokens(),
                        r.totalOutputTokens(),
                        r.totalCostUsd(),
                        r.callCount()))
                .toList();
    }
}
