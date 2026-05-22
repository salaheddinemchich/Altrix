package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigrationReport;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Implements {@link MigrationReportRepository} via Spring Data JPA (#129).
 *
 * <p>Save is upsert — re-running the migration on the same session replaces
 * the previous report (one row per session by primary-key constraint).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationReportPersistenceAdapter implements MigrationReportRepository {

    private final MigrationReportJpaRepository repository;

    @Override
    @Transactional
    public void save(WorkflowSessionId sessionId, MigrationReport report) {
        MigrationReportJpaEntity entity = MigrationReportJpaEntity.builder()
                .sessionId(sessionId.value())
                .projectId(report.projectId())
                .content(report.content())
                .generatedAt(report.generatedAt() != null ? report.generatedAt() : Instant.now())
                .build();
        repository.save(entity);
        log.debug("Saved migration report for session '{}' ({} chars)", sessionId, report.content().length());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MigrationReport> findBySessionId(WorkflowSessionId sessionId) {
        return repository.findById(sessionId.value())
                .map(e -> new MigrationReport(e.getProjectId(), e.getContent(), e.getGeneratedAt()));
    }
}
