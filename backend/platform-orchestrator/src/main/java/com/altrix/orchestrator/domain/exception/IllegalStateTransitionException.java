package com.altrix.orchestrator.domain.exception;

import com.altrix.orchestrator.domain.model.session.SessionStatus;

/**
 * Thrown when a {@link com.altrix.orchestrator.domain.model.session.WorkflowSession}
 * is asked to transition to a state that is not reachable from its current state.
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(SessionStatus from, SessionStatus to) {
        super("Cannot transition WorkflowSession from %s to %s".formatted(from, to));
    }
}
