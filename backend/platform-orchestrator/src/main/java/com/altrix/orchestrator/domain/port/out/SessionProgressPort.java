package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Secondary port — pushes session lifecycle events to subscribed clients (#60).
 *
 * <p>Domain services call this port after a successful state transition.
 * The implementation (WebSocket adapter) must never be imported by the domain.
 */
public interface SessionProgressPort {

    /**
     * Broadcasts the new session status to all clients watching this session.
     *
     * @param sessionId the session that changed state
     * @param jobId     the corresponding migration job
     * @param status    the new status after the transition
     * @param message   optional detail (error message, plan summary, etc.)
     */
    void publishSessionUpdate(WorkflowSessionId sessionId, String jobId,
                              SessionStatus status, String message);
}
