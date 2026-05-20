package com.altrix.orchestrator.domain.port.in;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Primary port — kicks off the migrator → validator → reporter half of the
 * pipeline after a reviewer approves the plan at the AWAITING_APPROVAL
 * gate (#10).
 *
 * <p>Always executed asynchronously so the HTTP approve call returns under
 * 100 ms; progress is streamed back to the client over the existing STOMP
 * WebSocket topic {@code /topic/jobs/{jobId}}.
 */
public interface ResumeMigrationUseCase {

    /**
     * Loads the approved plan stored on the session, then runs the rewriting,
     * validation and reporting agents sequentially.  Marks the session
     * complete and the job DONE on success, FAILED on any agent failure.
     *
     * @param sessionId the session that was just transitioned out of
     *                  AWAITING_APPROVAL → MIGRATING by
     *                  {@link HandleApprovalUseCase#approve(WorkflowSessionId)}
     */
    void resume(WorkflowSessionId sessionId);
}
