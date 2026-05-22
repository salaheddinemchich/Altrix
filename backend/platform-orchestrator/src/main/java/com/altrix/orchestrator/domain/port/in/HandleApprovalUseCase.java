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
     * @param sessionId  the session awaiting approval
     * @param decidedBy  identity of the reviewer who approved (JWT sub /
     *                   GitHub login).  Stored on the session for audit
     *                   (#125) and surfaced by the approval-history endpoint
     *                   (#126).  May be {@code "system"} when invoked from a
     *                   non-user path (e.g. tests, schedulers).
     * @return the saved session in {@code MIGRATING} status
     */
    WorkflowSession approve(WorkflowSessionId sessionId, String decidedBy);

    /**
     * Rejects the plan and transitions the session to {@code FAILED}.
     *
     * @param sessionId  the session awaiting approval
     * @param reason     human-readable rejection reason
     * @param decidedBy  identity of the reviewer who rejected — see {@link #approve}
     * @return the saved session in {@code FAILED} status
     */
    WorkflowSession reject(WorkflowSessionId sessionId, String reason, String decidedBy);
}
