package com.altrix.orchestrator.domain.model.session.event;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;

/**
 * Fired when the MigrationPlannerAgent produces a non-empty plan.
 */
public record PlanReady(WorkflowSessionId sessionId, String jobId, MigrationPlan plan, Instant occurredAt) {

    public static PlanReady of(WorkflowSessionId id, String jobId, MigrationPlan plan) {
        return new PlanReady(id, jobId, plan, Instant.now());
    }
}
