package com.altrix.common.domain.model;

import java.io.Serializable;

/**
 * Outcome of a single {@link com.altrix.common.domain.port.MigrationAgent}
 * execution — either a successful value or a structured failure.
 *
 * <p>Sealed: only the two declared variants are permitted, so the orchestrator
 * can pattern-match exhaustively on the result.
 *
 * @param <T> the agent's output type on success
 */
public sealed interface AgentResult<T> extends Serializable
        permits AgentResult.Success, AgentResult.Failure {

    boolean isSuccess();

    /**
     * Successful execution carrying the agent's typed output.
     */
    record Success<T>(T value) implements AgentResult<T> {
        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * Non-recoverable failure with a human-readable reason.
     */
    record Failure<T>(String reason) implements AgentResult<T> {
        @Override
        public boolean isSuccess() {
            return false;
        }
    }

    static <T> Success<T> success(T value) {
        return new Success<>(value);
    }

    static <T> Failure<T> failure(String reason) {
        return new Failure<>(reason);
    }
}
