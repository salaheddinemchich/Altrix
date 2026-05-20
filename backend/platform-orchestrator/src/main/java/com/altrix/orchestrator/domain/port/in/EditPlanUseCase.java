package com.altrix.orchestrator.domain.port.in;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Primary port — lets a reviewer override the AI-proposed plan at the
 * AWAITING_APPROVAL gate (#10 follow-up).
 *
 * <p>The override does not change session status — Approve / Reject still
 * happens via {@link HandleApprovalUseCase}.  The next call to approve
 * picks up the edited plan automatically because the resume pipeline
 * reads it from the session.
 */
public interface EditPlanUseCase {

    /**
     * @param sessionId   session currently at the approval gate
     * @param editedPlan  the plan the reviewer wants to migrate against —
     *                    replaces the AI's proposal in full (steps,
     *                    targetFiles, summary, …)
     * @return the saved session, plan field reflecting the new content
     */
    WorkflowSession editPlan(WorkflowSessionId sessionId, MigrationPlan editedPlan);
}
