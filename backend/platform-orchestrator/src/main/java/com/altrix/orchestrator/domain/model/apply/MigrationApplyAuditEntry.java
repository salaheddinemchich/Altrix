package com.altrix.orchestrator.domain.model.apply;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;
import java.util.Map;

/**
 * Single audit-log row.  Every state transition through the apply
 * workflow writes one (strategy chosen, confirmation submitted, branch
 * created, PR opened, cancellation, failure) so we can answer
 * "who triggered what, when, with which parameters".  No secrets ever
 * land in {@code payload}.
 */
public record MigrationApplyAuditEntry(
        WorkflowSessionId sessionId,
        String actorUserId,
        EventType eventType,
        Map<String, String> payload,
        Instant occurredAt
) {
    public MigrationApplyAuditEntry {
        if (sessionId == null)   throw new IllegalArgumentException("sessionId is required");
        if (actorUserId == null) throw new IllegalArgumentException("actorUserId is required");
        if (eventType == null)   throw new IllegalArgumentException("eventType is required");
        if (occurredAt == null)  occurredAt = Instant.now();
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }

    public enum EventType {
        STRATEGY_CHOSEN,
        APPLY_CONFIRMED,
        BRANCH_CREATED,
        PR_CREATED,
        MERGED,
        CANCELLED,
        FAILED
    }
}
