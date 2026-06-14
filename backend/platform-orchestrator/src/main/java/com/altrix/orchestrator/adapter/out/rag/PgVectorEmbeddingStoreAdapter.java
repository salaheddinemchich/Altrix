package com.altrix.orchestrator.adapter.out.rag;

import com.altrix.common.domain.enums.DocumentType;
import com.altrix.common.domain.model.DocumentChunk;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgVectorEmbeddingStoreAdapter implements EmbeddingStorePort {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    @Override
    public void upsert(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return;
        int skipped = 0;
        int stored = 0;

        for (DocumentChunk chunk : chunks) {
            // Skip if this exact chunk (same project + path + index) hasn't changed
            if (chunkExists(chunk)) {
                skipped++;
                continue;
            }

            float[] vector = embed(chunk.text());
            jdbc.update("""
                            INSERT INTO code_embeddings
                                (project_id, document_type, file_path, chunk_index,
                                 chunk_text, content_hash, embedding, source_url)
                            VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?)
                            ON CONFLICT (project_id, file_path, chunk_index)
                                WHERE project_id IS NOT NULL
                            DO UPDATE SET
                                chunk_text   = EXCLUDED.chunk_text,
                                content_hash = EXCLUDED.content_hash,
                                embedding    = EXCLUDED.embedding,
                                created_at   = NOW()
                            """,
                    chunk.projectId(),
                    chunk.documentType().name(),
                    chunk.filePath(),
                    chunk.chunkIndex(),
                    chunk.text(),
                    chunk.contentHash(),
                    toVectorLiteral(vector),
                    chunk.sourceUrl()
            );
            stored++;
        }
        log.info("Embedding upsert: {} stored, {} skipped (content unchanged)", stored, skipped);
    }

    @Override
    public List<DocumentChunk> findRelevant(String query, String projectId,
                                            List<DocumentType> types, int topK) {
        float[] queryVector;
        try {
            queryVector = embed(query);
        } catch (IllegalStateException disabled) {
            // RAG disabled — agents will run without semantic search results.
            log.debug("RAG findRelevant skipped: {}", disabled.getMessage());
            return List.of();
        }
        String typeList = types.stream()
                .map(Enum::name)
                .map(t -> "'" + t + "'")
                .reduce((a, b) -> a + "," + b)
                .orElse("''");

        String sql = """
                SELECT project_id, document_type, file_path, chunk_index,
                       chunk_text, content_hash, source_url
                FROM code_embeddings
                WHERE document_type IN (%s)
                  AND (project_id = ? OR project_id IS NULL)
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(typeList);

        return jdbc.query(sql,
                (rs, rowNum) -> new DocumentChunk(
                        rs.getString("project_id"),
                        DocumentType.valueOf(rs.getString("document_type")),
                        rs.getString("file_path"),
                        rs.getInt("chunk_index"),
                        rs.getString("chunk_text"),
                        rs.getString("content_hash"),
                        rs.getString("source_url")
                ),
                projectId, toVectorLiteral(queryVector), topK
        );
    }

    @Override
    public boolean documentationExists(String logicalPath) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_embeddings WHERE file_path = ? AND project_id IS NULL",
                Integer.class, logicalPath);
        return count != null && count > 0;
    }

    @Override
    public int deleteDocumentationNotIn(java.util.Collection<String> keepLogicalPaths) {
        // Defensive: never wipe the corpus on a misconfiguration.  An empty
        // or null "keep" set means "we have no idea what to keep" — better
        // to leave the table alone than to delete everything.
        if (keepLogicalPaths == null || keepLogicalPaths.isEmpty()) {
            log.warn("deleteDocumentationNotIn called with empty keep-set — skipping (would have wiped corpus)");
            return 0;
        }
        // Pass the keep-set as a varchar[] so we can use the array NOT IN
        // (= ALL) form regardless of size.  PostgreSQL-specific but
        // pgvector already pins us to PG, so this is fine.
        String[] keep = keepLogicalPaths.toArray(new String[0]);
        int deleted = jdbc.update(
                "DELETE FROM code_embeddings "
                        + "WHERE document_type = 'DOCUMENTATION' "
                        + "  AND project_id IS NULL "
                        + "  AND file_path <> ALL (?)",
                (Object) keep);
        if (deleted > 0) {
            log.info("Pruned {} stale DOCUMENTATION row(s) no longer in the configured corpus", deleted);
        }
        return deleted;
    }

    private boolean chunkExists(DocumentChunk chunk) {
        if (chunk.projectId() == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_embeddings WHERE project_id = ? AND file_path = ? AND chunk_index = ? AND content_hash = ?",
                Integer.class,
                chunk.projectId(), chunk.filePath(), chunk.chunkIndex(), chunk.contentHash());
        return count != null && count > 0;
    }

    private float[] embed(String text) {
        Embedding embedding = embeddingModel.embed(text).content();
        return embedding.vector();
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }
}
