package com.altrix.orchestrator.domain.exception;

/**
 * Domain-level signal that every configured AI provider is unavailable (all circuit
 * breakers open, bulkhead full, or all providers failed). Kept in the domain so that
 * OrchestratorService can trigger graceful degradation without depending on infra types.
 */
public class AiProviderUnavailableException extends RuntimeException {
    public AiProviderUnavailableException(String message) {
        super(message);
    }
    public AiProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
