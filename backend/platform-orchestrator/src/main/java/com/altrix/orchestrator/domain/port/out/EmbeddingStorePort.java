package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.DocumentChunk;

import java.util.List;

/**
 * Driven port — persists and retrieves embedded document chunks from the vector store.
 */
public interface EmbeddingStorePort {

    /**
     * Upsert chunks; skips any chunk whose content_hash already exists in the store.
     */
    void upsert(List<DocumentChunk> chunks);

    /**
     * Semantic search: returns the top-k chunks most similar to the query,
     * scoped to the given projectId and documentTypes.
     */
    List<DocumentChunk> findRelevant(String query, String projectId,
                                     List<com.altrix.common.domain.enums.DocumentType> types,
                                     int topK);

    /**
     * Returns true if documentation chunks for the given logicalPath already exist.
     */
    boolean documentationExists(String logicalPath);

    /**
     * Removes every DOCUMENTATION-type row whose {@code file_path} is NOT in
     * {@code keepLogicalPaths}.  Used by the documentation-ingestion startup
     * hook to drop pages that have been removed from the corpus YAML —
     * otherwise stale embeddings (e.g. the removed
     * {@code migration/kafka-to-pubsub} page) would keep surfacing in
     * similarity search.
     *
     * <p>Implementations must be a no-op when {@code keepLogicalPaths} is
     * null or empty (defensive — never wipe the entire DOCUMENTATION corpus
     * on a misconfiguration).
     *
     * @return the number of rows deleted.
     */
    int deleteDocumentationNotIn(java.util.Collection<String> keepLogicalPaths);
}
