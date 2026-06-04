package com.altrix.orchestrator.domain.port.in;

import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

/**
 * The user cancelled at the confirmation gate.  Per spec: do NOT
 * touch the repository, do NOT consume the confirmation token (so the
 * user can change their mind, edit settings, and re-confirm), and
 * preserve the migration result in the session.  Writes a CANCELLED
 * audit entry.
 */
public interface CancelMigrationApplyUseCase {

    MigrationApplyResult cancel(WorkflowSessionId sessionId, String actorUserId);
}
