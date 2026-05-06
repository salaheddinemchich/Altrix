package com.altrix.orchestrator.domain.port.in;

import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Primary port — handles human approval or rejection of a migration plan (#64 #65).
 *
 * <p>Called by the REST controller after a reviewer approves or rejects
 * the plan that was generated for a session in {@code AWAITING_APPROVAL}.
 */
public interface HandleApprovalUseCase {

    /**
     * Approves the plan and transitions the session to {@code MIGRATING}.
     *
     * @param sessionId the session awaiting approval
     * @return the saved session in {@code MIGRATING} status
     */
    WorkflowSession approve(WorkflowSessionId sessionId);

    /**
     * Rejects the plan and transitions the session to {@code FAILED}.
     *
     * @param sessionId the session awaiting approval
     * @param reason    human-readable rejection reason
     * @return the saved session in {@code FAILED} status
     */
    WorkflowSession reject(WorkflowSessionId sessionId, String reason);
}
