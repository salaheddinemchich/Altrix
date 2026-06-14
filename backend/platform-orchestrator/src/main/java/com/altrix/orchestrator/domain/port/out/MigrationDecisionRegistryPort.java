package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.migration.MigrationDecision;
import com.altrix.orchestrator.domain.model.migration.MigrationDecisionRegistry;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.List;

/**
 * Persistence port for the session-scoped {@link MigrationDecisionRegistry}.
 *
 * <p>The migrator + SemanticValidator both read the registry to enforce
 * cross-file consistency, and the migrator appends to it as decisions are
 * made.  Implementations persist to {@code migration_decisions} (Flyway V24).
 */
public interface MigrationDecisionRegistryPort {

    /**
     * Returns the registry for {@code sessionId} — never null; an empty
     * registry is returned when the session has recorded nothing yet.
     */
    MigrationDecisionRegistry findForSession(WorkflowSessionId sessionId);

    /** Appends one decision (load → add → save). */
    void record(WorkflowSessionId sessionId, MigrationDecision decision);

    /** Appends several decisions in one save. */
    void recordAll(WorkflowSessionId sessionId, List<MigrationDecision> decisions);

    /** Removes all decisions for a session.  Used in tests + on full reset. */
    void clear(WorkflowSessionId sessionId);
}
