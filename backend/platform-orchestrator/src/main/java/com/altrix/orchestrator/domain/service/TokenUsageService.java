package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.port.in.GetTokenUsageUseCase;
import com.altrix.orchestrator.domain.port.in.TokenUsageSummary;
import com.altrix.orchestrator.domain.port.out.TokenPricingPort;
import com.altrix.orchestrator.domain.port.out.TokenUsagePort;

import java.util.List;

public class TokenUsageService implements GetTokenUsageUseCase {

    private final TokenUsagePort tokenUsagePort;
    private final TokenPricingPort pricingPort;

    public TokenUsageService(TokenUsagePort tokenUsagePort, TokenPricingPort pricingPort) {
        this.tokenUsagePort = tokenUsagePort;
        this.pricingPort = pricingPort;
    }

    @Override
    public List<TokenUsageSummary> getSummary() {
        return tokenUsagePort.getSummary().stream()
                .map(this::enrichWithCost)
                .toList();
    }

    private TokenUsageSummary enrichWithCost(TokenUsageSummary s) {
        double cost = pricingPort.estimateCostUsd(s.providerId(), s.inputTokens(), s.outputTokens());
        if (cost == 0.0) return s;
        return new TokenUsageSummary(
                s.providerId(), s.tier(),
                s.inputTokens(), s.outputTokens(), s.totalTokens(),
                s.callCount(), cost);
    }
}
