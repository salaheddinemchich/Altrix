package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.port.in.GetTokenUsageUseCase;
import com.altrix.orchestrator.domain.port.in.TokenUsageSummary;
import com.altrix.orchestrator.domain.port.out.TokenUsagePort;

import java.util.List;

public class TokenUsageService implements GetTokenUsageUseCase {

    private final TokenUsagePort tokenUsagePort;

    public TokenUsageService(TokenUsagePort tokenUsagePort) {
        this.tokenUsagePort = tokenUsagePort;
    }

    @Override
    public List<TokenUsageSummary> getSummary() {
        return tokenUsagePort.getSummary();
    }
}
