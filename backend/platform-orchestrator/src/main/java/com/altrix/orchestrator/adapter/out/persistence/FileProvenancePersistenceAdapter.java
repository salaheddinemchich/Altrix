package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.rag.FileProvenance;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.FileProvenanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Implements {@link FileProvenanceRepository} via Spring Data JPA.
 * Upsert by primary key (sessionId) — re-run replaces the existing trace.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileProvenancePersistenceAdapter implements FileProvenanceRepository {

    private final FileProvenanceJpaRepository repository;

    @Override
    @Transactional
    public void save(WorkflowSessionId sessionId, FileProvenance provenance) {
        FileProvenanceJpaEntity entity = FileProvenanceJpaEntity.builder()
                .sessionId(sessionId.value())
                .perFile(provenance.perFile())
                .generatedAt(provenance.generatedAt() != null ? provenance.generatedAt() : Instant.now())
                .build();
        repository.save(entity);
        log.debug("Saved file provenance for session '{}' ({} file(s))",
                sessionId, provenance.perFile().size());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileProvenance> findBySessionId(WorkflowSessionId sessionId) {
        return repository.findById(sessionId.value())
                .map(e -> new FileProvenance(
                        sessionId.value().toString(),
                        e.getPerFile(),
                        e.getGeneratedAt()
                ));
    }
}
