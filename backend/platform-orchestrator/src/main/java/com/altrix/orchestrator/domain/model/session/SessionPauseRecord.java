package com.altrix.orchestrator.domain.model.session;

import java.time.Instant;

/**
 * Immutable value object representing one pause entry in the session's audit log (#72).
 *
 * <p>{@code resumedAt} is {@code null} for an open (not yet resumed) pause entry.
 */
public record SessionPauseRecord(
        Long id,
        WorkflowSessionId sessionId,
        SessionStatus pausedFrom,
        Instant pausedAt,
        Instant resumedAt
) {
}
