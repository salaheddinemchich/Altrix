package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.rag.FileProvenance.DocReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPA mapping for {@code file_provenance} — one row per session capturing
 * the map of source-file path → list of doc-chunk references used as
 * RAG context when migrating that file (#1).
 */
@Entity
@Table(name = "file_provenance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileProvenanceJpaEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Convert(converter = FileProvenanceJsonConverter.class)
    @Column(name = "per_file", nullable = false, columnDefinition = "jsonb")
    private Map<String, List<DocReference>> perFile;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
