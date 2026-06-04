package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.apply.MigrationApplyAuditEntry;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.List;

/**
 * Append-only audit trail for the apply workflow.  Every state
 * transition writes one entry — never modified, never deleted.
 *
 * <p>Implementations MUST swallow persistence errors with a logged
 * warning rather than propagating: audit failure must never block a
 * user-initiated apply or cancellation.
 */
public interface MigrationApplyAuditLogPort {

    void save(MigrationApplyAuditEntry entry);

    /** Returns the audit timeline for a session, oldest first. */
    List<MigrationApplyAuditEntry> findBySessionId(WorkflowSessionId sessionId);
}
