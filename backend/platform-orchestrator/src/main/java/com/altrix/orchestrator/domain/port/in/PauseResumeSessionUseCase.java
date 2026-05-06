package com.altrix.orchestrator.domain.port.in;

import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * Primary port — pauses or resumes an in-progress session (#69 #70).
 *
 * <p>Pause is allowed from any non-terminal, non-paused state.
 * Resume restores the session to the state it was in before pausing.
 */
public interface PauseResumeSessionUseCase {

    WorkflowSession pause(WorkflowSessionId sessionId);

    WorkflowSession resume(WorkflowSessionId sessionId);
}
