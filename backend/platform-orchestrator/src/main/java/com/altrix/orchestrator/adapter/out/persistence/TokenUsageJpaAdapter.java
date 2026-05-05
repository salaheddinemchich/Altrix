package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.TokenUsageRecord;
import com.altrix.orchestrator.domain.port.in.TokenUsageSummary;
import com.altrix.orchestrator.domain.port.out.TokenUsagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TokenUsageJpaAdapter implements TokenUsagePort {

    private final TokenUsageJpaRepository repository;

    @Override
    public void save(TokenUsageRecord usage) {
        repository.save(TokenUsageEntity.builder()
                .providerId(usage.providerId())
                .tier(usage.tier())
                .inputTokens(usage.inputTokens())
                .outputTokens(usage.outputTokens())
                .totalTokens(usage.totalTokens())
                .recordedAt(usage.recordedAt())
                .build());
    }

    @Override
    public List<TokenUsageSummary> getSummary() {
        return repository.findSummary().stream()
                .map(p -> new TokenUsageSummary(
                        p.getProviderId(),
                        p.getTier(),
                        p.getInputTokens(),
                        p.getOutputTokens(),
                        p.getTotalTokens(),
                        p.getCallCount()))
                .toList();
    }

    @Override
    public long getTotalTokensSince(Instant since) {
        return repository.sumTotalTokensSince(since);
    }
}
