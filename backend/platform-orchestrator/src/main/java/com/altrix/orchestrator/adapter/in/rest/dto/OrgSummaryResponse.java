package com.altrix.orchestrator.adapter.in.rest.dto;

import java.util.Map;

/**
 * Cross-session aggregate metrics for the dashboard (#131).
 *
 * <p>Returned by {@code GET /api/v1/reports/summary}.  All counters are
 * guaranteed non-null and zero-safe — an empty database produces an
 * all-zeros response, never 404.
 *
 * @param totalSessions          number of WorkflowSession rows ever created.
 * @param successRate            DONE / totalSessions, range [0.0, 1.0].
 *                               0.0 when totalSessions is 0.
 * @param averageDurationSeconds mean (updatedAt - createdAt) of DONE sessions.
 *                               0.0 when no DONE sessions exist yet.
 * @param countsByStatus         per-status row counts.  Keys are the
 *                               {@code SessionStatus} enum names; missing keys
 *                               mean zero.
 */
public record OrgSummaryResponse(
        long totalSessions,
        double successRate,
        double averageDurationSeconds,
        Map<String, Long> countsByStatus
) {}
