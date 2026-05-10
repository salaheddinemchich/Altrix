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
}
