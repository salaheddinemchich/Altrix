package com.altrix.orchestrator.domain.model.session;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle states of a {@link WorkflowSession} aggregate.
 *
 * <p>Allowed transitions:
 * <pre>
 * PENDING ──────────────────────────────► CONTEXT_ANALYSED
 *   │                                          │
 *   │ (direct, Sprint 1 — no approval gate)    ▼
 *   │                                    PLAN_READY
 *   │                                     │     │
 *   │                                     │     ▼
 *   │                                     │  AWAITING_APPROVAL
 *   │                                     │     │
 *   └─────────────────────────────────────┴─────▼
 *                                          MIGRATING
 *                                              │
 *                                              ▼
 *                                         VALIDATING
 *                                              │
 *                                              ▼
 *                                            DONE  (terminal)
 *
 * Any non-terminal state ──► FAILED  (terminal)
 * Any non-terminal state ──► PAUSED  (resume in Sprint 2)
 * PAUSED ──► (any non-terminal it was paused from)
 * </pre>
 */
public enum SessionStatus {

    PENDING,
    CONTEXT_ANALYSED,
    PLAN_READY,
    AWAITING_APPROVAL,
    MIGRATING,
    VALIDATING,
    DONE,
    FAILED,
    PAUSED;

    private static final Map<SessionStatus, Set<SessionStatus>> ALLOWED = Map.of(
            PENDING,            EnumSet.of(CONTEXT_ANALYSED, MIGRATING, FAILED),
            CONTEXT_ANALYSED,   EnumSet.of(PLAN_READY, FAILED, PAUSED),
            PLAN_READY,         EnumSet.of(AWAITING_APPROVAL, MIGRATING, FAILED, PAUSED),
            AWAITING_APPROVAL,  EnumSet.of(MIGRATING, FAILED, PAUSED),
            MIGRATING,          EnumSet.of(VALIDATING, DONE, FAILED, PAUSED),
            VALIDATING,         EnumSet.of(DONE, FAILED, PAUSED),
            PAUSED,             EnumSet.of(CONTEXT_ANALYSED, PLAN_READY,
                                           AWAITING_APPROVAL, MIGRATING, VALIDATING, FAILED),
            DONE,               EnumSet.noneOf(SessionStatus.class),
            FAILED,             EnumSet.noneOf(SessionStatus.class)
    );

    public boolean canTransitionTo(SessionStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(SessionStatus.class)).contains(next);
    }

    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}
