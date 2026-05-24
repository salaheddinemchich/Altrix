package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.sandbox.SandboxLog;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.List;
import java.util.Optional;

/**
 * Secondary port — persists captured sandbox-runner output for
 * post-execution review (#105).
 *
 * <p>Save is upsert on (sessionId, runnerId).  Best-effort: a DB hiccup
 * on save must never fail the validation pipeline — the findings are
 * still returned.
 */
public interface SandboxLogRepository {

    /** Stores or replaces the runner's captured output for a session. */
    void save(SandboxLog log);

    /** All persisted logs for a session, ordered by runner id. */
    List<SandboxLog> findBySessionId(WorkflowSessionId sessionId);

    /** Lookup by (session, runner) — feeds the log-viewer fetch. */
    Optional<SandboxLog> findBySessionIdAndRunnerId(WorkflowSessionId sessionId, String runnerId);
}
