package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.model.session.SessionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Spring Data projection for paginated session lists.
 *
 * <p>Excludes the heavy {@code migrated_files} JSONB column (each row carries
 * the full migrated source — tens of KB) but DOES include {@code plan}, which
 * the sessions UI needs to render the plan-preview row at the AWAITING_APPROVAL
 * gate (#10).  Plans are typically &lt; 4 KB so leaving them in keeps the list
 * endpoint cheap enough.
 */
public interface SessionSummaryProjection {
    UUID getId();
    String getJobId();
    String getProjectId();
    SessionStatus getStatus();
    SessionStatus getPausedFrom();
    int getConsecutiveAgentErrors();
    MigrationPlan getPlan();
    Instant getCreatedAt();
    Instant getUpdatedAt();
}
