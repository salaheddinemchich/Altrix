package com.altrix.orchestrator.domain.model.session;

import java.util.Map;

/**
 * Read-side aggregate over the workflow_sessions table for the org-summary
 * dashboard (#131).  Pure data, framework-free — the repository port
 * computes the numbers in a single round-trip and hands this back to the
 * REST adapter for serialisation.
 *
 * @param totalSessions          total row count across all statuses.
 * @param doneCount              rows in {@link SessionStatus#DONE} — used for
 *                               success rate.
 * @param averageDurationSeconds mean (updatedAt − createdAt) of DONE sessions;
 *                               0.0 when none exist yet.
 * @param countsByStatus         per-status row counts.  Every {@link SessionStatus}
 *                               is present; missing values are 0.
 */
public record SessionAggregate(
        long totalSessions,
        long doneCount,
        double averageDurationSeconds,
        Map<SessionStatus, Long> countsByStatus
) {
    public double successRate() {
        return totalSessions == 0 ? 0.0 : (double) doneCount / totalSessions;
    }
}
