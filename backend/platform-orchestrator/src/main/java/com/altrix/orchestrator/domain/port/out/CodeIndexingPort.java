package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.ProjectContext;
import com.altrix.orchestrator.domain.model.rag.RagIndexManifest;

/**
 * Driven port — indexes project source files into the vector store for RAG retrieval.
 *
 * <p>Returns a {@link RagIndexManifest} describing what made it into the
 * vector store so the caller can persist it for later UI display.  An
 * empty manifest is returned when the embedding model is disabled (and
 * indexing was skipped) — callers may persist it or skip persistence at
 * their discretion.
 */
public interface CodeIndexingPort {
    RagIndexManifest index(ProjectContext context);
}
