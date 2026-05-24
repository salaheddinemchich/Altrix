package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.rag.RagIndexManifest;

import java.time.Instant;
import java.util.List;

/**
 * REST shape for the RAG index manifest — what files the indexer pulled
 * into the vector store for a session.  Drives the "Index" step's
 * expandable file list in the JobDetail timeline.
 *
 * @param chunkCount 0 when the embedding model was disabled at index
 *                   time (filePaths still populated with what WOULD
 *                   have been indexed).
 */
public record RagIndexManifestResponse(
        String projectId,
        List<String> filePaths,
        int fileCount,
        int chunkCount,
        Instant indexedAt
) {
    public static RagIndexManifestResponse from(RagIndexManifest m) {
        return new RagIndexManifestResponse(
                m.projectId(),
                m.filePaths(),
                m.fileCount(),
                m.chunkCount(),
                m.indexedAt()
        );
    }
}
