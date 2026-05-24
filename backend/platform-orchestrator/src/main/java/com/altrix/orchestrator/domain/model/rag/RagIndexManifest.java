package com.altrix.orchestrator.domain.model.rag;

import java.time.Instant;
import java.util.List;

/**
 * What got indexed for RAG during a single session.
 *
 * <p>Framework-free.  Persisted once at the end of indexing by
 * {@code CodeIndexingAgent} and exposed via
 * {@code GET /api/v1/sessions/{id}/rag-index} so the JobDetail timeline
 * can show the reviewer which source files made it into the vector store.
 *
 * @param projectId  project the files belong to.
 * @param filePaths  repository-relative paths that survived the indexable
 *                   filter ({@link
 *                   com.altrix.orchestrator.adapter.out.rag.CodeIndexingAgent}
 *                   {@code INDEXABLE_EXTENSIONS}).  Sorted alphabetically
 *                   for stable UI rendering.
 * @param fileCount  redundant with {@code filePaths.size()} but stored
 *                   denormalised so list endpoints can return counts
 *                   without serialising the full path list.
 * @param chunkCount number of vector-store chunks produced from those files.
 * @param indexedAt  when the upsert finished.
 */
public record RagIndexManifest(
        String projectId,
        List<String> filePaths,
        int fileCount,
        int chunkCount,
        Instant indexedAt
) {
    public RagIndexManifest {
        filePaths = filePaths != null ? List.copyOf(filePaths) : List.of();
    }
}
