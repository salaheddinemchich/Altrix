package com.altrix.orchestrator.domain.exception;

import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Thrown when a {@link com.altrix.orchestrator.domain.model.session.WorkflowSession} cannot be found.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(WorkflowSessionId id) {
        super("WorkflowSession not found: " + id.value());
    }
}
