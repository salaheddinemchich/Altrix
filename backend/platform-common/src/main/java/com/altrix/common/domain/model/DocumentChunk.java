package com.altrix.common.domain.model;

import com.altrix.common.domain.enums.DocumentType;

import java.io.Serializable;

/**
 * A piece of text ready to be embedded and stored in the vector store.
 *
 * <p>For SOURCE_CODE chunks: projectId is the migration job's project ID,
 * filePath is the relative path inside the uploaded ZIP, sourceUrl is null.
 *
 * <p>For DOCUMENTATION chunks: projectId is null (shared across all jobs),
 * filePath is a logical identifier (e.g. "kafka/producer-api"), sourceUrl
 * is the canonical URL the content was fetched from.
 */
public record DocumentChunk(
        String projectId,
        DocumentType documentType,
        String filePath,
        int chunkIndex,
        String text,
        String contentHash,
        String sourceUrl
) implements Serializable {
    public static DocumentChunk sourceCode(String projectId, String filePath,
                                           int chunkIndex, String text, String hash) {
        return new DocumentChunk(projectId, DocumentType.SOURCE_CODE,
                filePath, chunkIndex, text, hash, null);
    }

    public static DocumentChunk documentation(String logicalPath, int chunkIndex,
                                              String text, String hash, String sourceUrl) {
        return new DocumentChunk(null, DocumentType.DOCUMENTATION,
                logicalPath, chunkIndex, text, hash, sourceUrl);
    }
}
