package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.rag.RagIndexManifest;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.RagIndexManifestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Implements {@link RagIndexManifestRepository} via Spring Data JPA.
 *
 * <p>Save is upsert — the JPA save() with the same primary key replaces
 * the existing row, so a re-run for the same session refreshes the
 * manifest in place rather than appending.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagIndexManifestPersistenceAdapter implements RagIndexManifestRepository {

    private final RagIndexManifestJpaRepository repository;

    @Override
    @Transactional
    public void save(WorkflowSessionId sessionId, RagIndexManifest manifest) {
        RagIndexManifestJpaEntity entity = RagIndexManifestJpaEntity.builder()
                .sessionId(sessionId.value())
                .projectId(manifest.projectId())
                .filePaths(manifest.filePaths())
                .fileCount(manifest.fileCount())
                .chunkCount(manifest.chunkCount())
                .indexedAt(manifest.indexedAt() != null ? manifest.indexedAt() : Instant.now())
                .build();
        repository.save(entity);
        log.debug("Saved RAG index manifest for session '{}' ({} files, {} chunks)",
                sessionId, manifest.fileCount(), manifest.chunkCount());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RagIndexManifest> findBySessionId(WorkflowSessionId sessionId) {
        return repository.findById(sessionId.value())
                .map(e -> new RagIndexManifest(
                        e.getProjectId(),
                        e.getFilePaths(),
                        e.getFileCount(),
                        e.getChunkCount(),
                        e.getIndexedAt()
                ));
    }
}
