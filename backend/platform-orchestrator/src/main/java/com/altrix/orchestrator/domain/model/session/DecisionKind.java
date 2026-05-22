package com.altrix.orchestrator.domain.model.session;

/**
 * Outcome of the human approval gate (#125 / #126).
 *
 * <p>Stored on {@code WorkflowSession.decisionKind} once the reviewer has
 * answered the AWAITING_APPROVAL gate.  Used to render the approval-history
 * timeline without having to infer the decision from the terminal status.
 */
public enum DecisionKind {
    APPROVED,
    REJECTED
}
