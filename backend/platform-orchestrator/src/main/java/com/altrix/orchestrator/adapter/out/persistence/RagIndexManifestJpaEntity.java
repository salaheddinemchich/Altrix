package com.altrix.orchestrator.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping for {@code rag_index_manifests} — one row per session, the
 * list of source files that made it into the vector store at the end of
 * Agent 0 (RAG indexer).
 */
@Entity
@Table(name = "rag_index_manifests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagIndexManifestJpaEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "file_paths", nullable = false, columnDefinition = "jsonb")
    private List<String> filePaths;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;
}
