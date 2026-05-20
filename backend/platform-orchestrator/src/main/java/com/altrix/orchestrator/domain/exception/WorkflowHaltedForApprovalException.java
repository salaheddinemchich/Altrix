package com.altrix.orchestrator.domain.exception;

/**
 * Signal — NOT a real error — that the migration workflow voluntarily halted
 * because the configured policy ({@code workflow.require-approval.enabled})
 * requires a human reviewer to approve the plan before it can be applied (#10).
 *
 * <p>{@code OrchestratorService} catches this specifically and leaves the
 * {@code WorkflowSession} in {@code AWAITING_APPROVAL} without marking the job
 * FAILED.  Treating this as a {@link RuntimeException} is the cleanest way to
 * abort the LangGraph4j graph mid-flight without restructuring the entire
 * node/edge topology.
 *
 * <p>Carries the session id so the caller knows where the plan landed for
 * review.
 */
public class WorkflowHaltedForApprovalException extends RuntimeException {

    private final String sessionId;

    public WorkflowHaltedForApprovalException(String sessionId) {
        super("Workflow halted at approval gate for session " + sessionId);
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
