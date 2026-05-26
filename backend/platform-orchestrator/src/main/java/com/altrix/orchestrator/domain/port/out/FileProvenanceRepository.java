package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.rag.FileProvenance;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.Optional;

/**
 * Secondary port — persists the per-file RAG provenance trace for a
 * migration session (#1).  One row per session; a re-run upserts.
 * Best-effort: persistence failure must never block the migration —
 * the trace is observability, not part of the migration result.
 */
public interface FileProvenanceRepository {

    void save(WorkflowSessionId sessionId, FileProvenance provenance);

    Optional<FileProvenance> findBySessionId(WorkflowSessionId sessionId);
}
