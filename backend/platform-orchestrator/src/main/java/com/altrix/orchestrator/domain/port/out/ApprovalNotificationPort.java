package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Secondary port — notifies a reviewer when a migration plan is waiting for approval (#66).
 */
public interface ApprovalNotificationPort {

    /**
     * Sends a notification (email or in-app) to the configured reviewer address.
     *
     * @param sessionId the session awaiting approval
     * @param jobId     the associated job identifier
     */
    void notifyApprovalRequired(WorkflowSessionId sessionId, String jobId);
}
