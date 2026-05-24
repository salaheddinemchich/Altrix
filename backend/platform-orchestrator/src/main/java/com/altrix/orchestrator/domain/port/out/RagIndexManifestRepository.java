package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.rag.RagIndexManifest;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.util.Optional;

/**
 * Secondary port — persists which source files were indexed for RAG in
 * a session, and lets the UI fetch them after the indexer has run.
 *
 * <p>One row per session.  A re-run for the same session overwrites
 * (upsert).  Best-effort: persistence failure must never block the
 * pipeline — the embeddings are already in the vector store.
 */
public interface RagIndexManifestRepository {

    /** Upserts the manifest for the session. */
    void save(WorkflowSessionId sessionId, RagIndexManifest manifest);

    /** Returns the persisted manifest, or empty when none exists. */
    Optional<RagIndexManifest> findBySessionId(WorkflowSessionId sessionId);
}
